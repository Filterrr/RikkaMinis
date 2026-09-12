package com.openminis.app.gateway

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-local-llm-gateway] Outbound encoding contracts, asserted field by field
 * the way each SDK's response model reads them.
 */
class GatewayResponseCodecTest {

    private fun result(
        text: String = "hello",
        reasoning: String = "",
        calls: List<GatewayToolCall> = emptyList(),
        stop: String = "end_turn",
    ) = GatewayResult(
        text = text,
        reasoning = reasoning,
        toolCalls = calls,
        stopReason = stop,
        inputTokens = 11,
        outputTokens = 22,
        model = "m1",
    )

    private fun req(protocol: GatewayProtocol, stream: Boolean = false) = GatewayChatRequest(
        model = "m1",
        messages = listOf(GatewayMessage(role = "user", text = "hi")),
        systemPrompt = null,
        maxTokens = 100,
        temperature = null,
        topP = null,
        tools = emptyList(),
        toolChoice = null,
        stream = stream,
        stop = emptyList(),
        thinkingRequested = false,
        requestId = "id_1",
        protocol = protocol,
    )

    @Test
    fun `openai buffered reply has choices message usage and finish reason`() {
        val json = JSONObject(GatewayResponseCodec.completion(req(GatewayProtocol.openai), result()))
        assertEquals("chat.completion", json.getString("object"))
        val choice = json.getJSONArray("choices").getJSONObject(0)
        assertEquals("stop", choice.getString("finish_reason"))
        assertEquals("hello", choice.getJSONObject("message").getString("content"))
        assertEquals(11, json.getJSONObject("usage").getInt("prompt_tokens"))
        assertEquals(33, json.getJSONObject("usage").getInt("total_tokens"))
        val message = choice.getJSONObject("message")
        assertFalse("tools absent when none were called", message.has("tool_calls"))
    }

    @Test
    fun `openai tool call turn reports null content and a string arguments field`() {
        val r = result(text = "", calls = listOf(GatewayToolCall("call_1", "f", """{"a":1}""")), stop = "tool_use")
        val message = JSONObject(GatewayResponseCodec.completion(req(GatewayProtocol.openai), r))
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        assertTrue("content must be null, not empty", message.isNull("content"))
        val call = message.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("function", call.getString("type"))
        assertTrue(call.getJSONObject("function").get("arguments") is String)
        val completion = JSONObject(GatewayResponseCodec.completion(req(GatewayProtocol.openai), r))
        assertEquals("tool_calls", completion.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
    }

    @Test
    fun `anthropic message nests blocks and reports input and output tokens`() {
        val json = JSONObject(GatewayResponseCodec.completion(req(GatewayProtocol.anthropic), result(reasoning = "think")))
        assertEquals("message", json.getString("type"))
        assertEquals("end_turn", json.getString("stop_reason"))
        val blocks = json.getJSONArray("content")
        assertEquals("thinking", blocks.getJSONObject(0).getString("type"))
        assertEquals("text", blocks.getJSONObject(1).getString("type"))
        assertEquals(11, json.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(22, json.getJSONObject("usage").getInt("output_tokens"))
    }

    @Test
    fun `anthropic tool_use block carries an object input and never an empty content array`() {
        val r = result(text = "", calls = listOf(GatewayToolCall("tu_1", "f", """{"a":1}""")), stop = "tool_use")
        val json = JSONObject(GatewayResponseCodec.completion(req(GatewayProtocol.anthropic), r))
        val block = json.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_use", block.getString("type"))
        assertTrue(block.get("input") is JSONObject)
        assertEquals("tool_use", json.getString("stop_reason"))
        // An empty assistant turn must still be a valid message shape.
        // An empty, call-free assistant turn stays valid but emits no empty text block.
        val empty = JSONObject(GatewayResponseCodec.completion(req(GatewayProtocol.anthropic), result(text = "")))
        assertEquals("text", empty.getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("", empty.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun `gemini reply uses model role and usageMetadata`() {
        val json = JSONObject(GatewayResponseCodec.completion(req(GatewayProtocol.gemini), result()))
        val candidate = json.getJSONArray("candidates").getJSONObject(0)
        val content = candidate.getJSONObject("content")
        assertEquals("model", content.getString("role"))
        // parts live INSIDE content — the Gemini candidate has no parts sibling.
        assertEquals("hello", content.getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("STOP", candidate.getString("finishReason"))
        assertEquals(11, json.getJSONObject("usageMetadata").getInt("promptTokenCount"))
    }

    @Test
    fun `gemini function call part uses args object and thought flag marks reasoning`() {
        val r = result(text = "", reasoning = "because", calls = listOf(GatewayToolCall("c", "f", """{"a":1}""")))
        val parts = JSONObject(GatewayResponseCodec.completion(req(GatewayProtocol.gemini), r))
            .getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        assertTrue(parts.getJSONObject(0).getBoolean("thought"))
        val fn = parts.getJSONObject(1).getJSONObject("functionCall")
        assertEquals("f", fn.getString("name"))
        assertEquals(1, fn.getJSONObject("args").getInt("a"))
    }

    @Test
    fun `ollama chat exposes reasoning on the top level thinking field`() {
        val json = JSONObject(
            GatewayResponseCodec.completion(req(GatewayProtocol.ollama), result(reasoning = "step 1")),
        )
        assertEquals("step 1", json.getString("thinking"))
        assertEquals("hello", json.getJSONObject("message").getString("content"))
    }

    @Test
    fun `ollama chat reply uses message object while generate uses response`() {
        val chat = JSONObject(GatewayResponseCodec.completion(req(GatewayProtocol.ollama), result()))
        assertTrue(chat.getBoolean("done"))
        assertEquals("stop", chat.getString("done_reason"))
        assertEquals("hello", chat.getJSONObject("message").getString("content"))
        assertEquals(22, chat.getInt("eval_count"))
        val generate = JSONObject(GatewayResponseCodec.ollamaGenerate(req(GatewayProtocol.ollama), result()))
        assertEquals("hello", generate.getString("response"))
        assertFalse(generate.has("message"))
    }

    @Test
    fun `ollama tool call arguments are an object not a string`() {
        val r = result(text = "", calls = listOf(GatewayToolCall("c", "f", """{"a":1}""")), stop = "tool_use")
        val call = JSONObject(GatewayResponseCodec.completion(req(GatewayProtocol.ollama), r))
            .getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0)
            .getJSONObject("function")
        assertTrue(call.get("arguments") is JSONObject)
    }

    @Test
    fun `responses api reply wraps output items and text channel`() {
        val json = JSONObject(GatewayResponseCodec.completion(req(GatewayProtocol.openaiResponses), result()))
        assertEquals("response", json.getString("object"))
        assertEquals("completed", json.getString("status"))
        val item = json.getJSONArray("output").getJSONObject(0)
        assertEquals("message", item.getString("type"))
        assertEquals("hello", item.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("hello", json.getString("output_text"))
    }

    @Test
    fun `responses api truncation reports incomplete status`() {
        val json = JSONObject(
            GatewayResponseCodec.completion(req(GatewayProtocol.openaiResponses), result(stop = "max_tokens")),
        )
        assertEquals("incomplete", json.getString("status"))
    }
}
