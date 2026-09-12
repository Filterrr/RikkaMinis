package com.openminis.app.gateway

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-local-llm-gateway] Streaming frame contracts.
 *
 * These are the shapes SDK clients assemble a message from, and the ones most
 * likely to silently break: Anthropic's block state machine must never open two
 * blocks at once or forget a `content_block_stop`; OpenAI needs the `[DONE]`
 * sentinel plus `finish_reason`; Ollama needs NDJSON rather than SSE.
 */
class GatewayStreamWriterTest {

    private fun req(
        protocol: GatewayProtocol,
        generate: Boolean = false,
        id: String = "id_s",
    ) = GatewayChatRequest(
        model = "m",
        messages = listOf(GatewayMessage(role = "user", text = "hi")),
        systemPrompt = null,
        maxTokens = 64,
        temperature = null,
        topP = null,
        tools = emptyList(),
        toolChoice = null,
        stream = true,
        stop = emptyList(),
        thinkingRequested = false,
        requestId = id,
        protocol = protocol,
        isGenerateApi = generate,
    )

    private fun payload(frame: GatewayStreamWriter.SseFrame) = JSONObject(frame.data)

    @Test
    fun `openai stream opens with a role delta and closes with finish reason plus DONE`() {
        val writer = GatewayStreamWriter(req(GatewayProtocol.openai), "m")
        val open = writer.open().single()
        val openJson = payload(open)
        assertEquals("chat.completion.chunk", openJson.getString("object"))
        val firstDelta = openJson.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
        assertEquals("assistant", firstDelta.getString("role"))

        val text = writer.onText("Hel").single()
        assertEquals("Hel", payload(text).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta").getString("content"))

        val close = writer.close("end_turn", truncated = false).single()
        assertEquals("stop", payload(close).getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        assertEquals("[DONE]", writer.doneSentinel()?.data)
    }

    @Test
    fun `openai reasoning streams in the reasoning_content delta channel`() {
        val writer = GatewayStreamWriter(req(GatewayProtocol.openai), "m")
        val frame = writer.onReasoning("thinking hard").single()
        assertEquals(
            "thinking hard",
            payload(frame).getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
                .getString("reasoning_content"),
        )
    }

    @Test
    fun `openai tool stream opens a call then appends argument fragments`() {
        val writer = GatewayStreamWriter(req(GatewayProtocol.openai), "m")
        val start = writer.onToolStart("call_7", "shell_execute").single()
        val call = payload(start).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals(0, call.getInt("index"))
        assertEquals("call_7", call.getString("id"))
        assertEquals("shell_execute", call.getJSONObject("function").getString("name"))

        val args = writer.onToolArgs("call_7", """{"com""").single()
        val argDelta = payload(args).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("""{"com""", argDelta.getJSONObject("function").getString("arguments"))
        assertEquals(0, argDelta.getInt("index"))

        // A second call must land on ordinal 1, not overwrite the first.
        val second = writer.onToolStart("call_8", "read").single()
        val secondCall = payload(second).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals(1, secondCall.getInt("index"))
        assertEquals("call_8", secondCall.getString("id"))
    }

    @Test
    fun `openai completes a call that never streamed argument deltas`() {
        val writer = GatewayStreamWriter(req(GatewayProtocol.openai), "m")
        val frames = writer.onToolComplete("call_1", "f", JSONObject("""{"a":1}"""))
        assertEquals("backends that skip deltas still reach the client", 1, frames.size)
        val call = payload(frames.single()).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("""{"a":1}""", call.getJSONObject("function").getString("arguments"))
        assertEquals("call_1", call.getString("id"))
    }

    @Test
    fun `anthropic stream pairs every block start with a stop and ends with message_stop`() {
        val writer = GatewayStreamWriter(req(GatewayProtocol.anthropic), "m")
        val events = writer.open().mapNotNull { it.event }.toMutableList()
        assertEquals(listOf("message_start", "ping"), events)

        val textA = writer.onText("a")
        val textB = writer.onText("b")
        events += textA.mapNotNull { it.event }
        events += textB.mapNotNull { it.event }
        events += writer.onReasoning("think").mapNotNull { it.event }
        events += writer.close("end_turn", truncated = false).mapNotNull { it.event }

        // Consecutive deltas on the SAME channel must not reopen a block: only
        // the first text piece opens, then thinking opens a second one.
        assertEquals("same-channel delta must not reopen a block",
            0, textB.count { it.event == "content_block_start" })
        assertEquals(2, events.count { it == "content_block_start" })
        assertEquals("every block opened must be closed",
            events.count { it == "content_block_start" }, events.count { it == "content_block_stop" })
        assertEquals("message_delta", events[events.size - 2])
        assertEquals("message_stop", events.last())
    }

    @Test
    fun `anthropic reasoning opens a thinking block before text and indexes separately`() {
        val writer = GatewayStreamWriter(req(GatewayProtocol.anthropic), "m")
        // Opening a channel emits [start, delta] — no preceding stop, since
        // nothing was open yet.
        val thinking = writer.onReasoning("deep")
        assertEquals(2, thinking.size)
        val started = thinking.first { it.event == "content_block_start" }
        assertEquals("thinking", payload(started).getJSONObject("content_block").getString("type"))
        assertEquals(0, payload(started).getInt("index"))

        // Switching channels closes block 0 and opens block 1 for the text.
        val answer = writer.onText("answer")
        val stopped = answer.first { it.event == "content_block_stop" }
        assertEquals(0, payload(stopped).getInt("index"))
        val reopened = answer.first { it.event == "content_block_start" }
        assertEquals(1, payload(reopened).getInt("index"))
        assertEquals("text", payload(reopened).getJSONObject("content_block").getString("type"))
    }

    @Test
    fun `anthropic tool blocks carry id name and input json deltas`() {
        val writer = GatewayStreamWriter(req(GatewayProtocol.anthropic), "m")
        val start = writer.onToolStart("tu_9", "lookup").last()
        val block = payload(start).getJSONObject("content_block")
        assertEquals("tool_use", block.getString("type"))
        assertEquals("tu_9", block.getString("id"))
        val args = writer.onToolArgs("tu_9", """{"q":""").single()
        assertEquals("input_json_delta", payload(args).getJSONObject("delta").getString("type"))
        val stop = writer.close("tool_use", truncated = false)
        assertEquals("tool_use", payload(stop.first { it.event == "message_delta" })
            .getJSONObject("delta").getString("stop_reason"))
    }

    @Test
    fun `gemini frames carry a full candidate chunk per delta`() {
        val writer = GatewayStreamWriter(req(GatewayProtocol.gemini), "m")
        val frame = payload(writer.onText("tok").single())
        val parts = frame.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        assertEquals("tok", parts.getJSONObject(0).getString("text"))
        val done = payload(writer.close("end_turn", truncated = false).single())
        assertEquals("STOP", done.getJSONArray("candidates").getJSONObject(0).getString("finishReason"))
        assertTrue("Gemini has no [DONE] sentinel", writer.doneSentinel() == null)
    }

    @Test
    fun `ollama streams ndjson objects and a terminal done frame`() {
        val base = req(GatewayProtocol.ollama)
        val writer = GatewayStreamWriter(base, "m")
        assertTrue(writer.rawNdjson)
        // onText can emit a block-open frame in front of the delta (Anthropic);
        // the last frame is always the content one.
        val frame = payload(writer.onText("chunk").last())
        assertEquals("chunk", frame.getJSONObject("message").getString("content"))
        assertFalse(frame.getBoolean("done"))
        val last = payload(writer.close("max_tokens", truncated = false).single())
        assertTrue(last.getBoolean("done"))
        assertEquals("length", last.getString("done_reason"))
    }

    @Test
    fun `ollama generate api answers in the response field`() {
        val writer = GatewayStreamWriter(req(GatewayProtocol.ollama, generate = true), "m")
        assertEquals("hi", payload(writer.onText("hi").single()).getString("response"))
    }

    @Test
    fun `responses api emits typed text and function call events`() {
        val writer = GatewayStreamWriter(req(GatewayProtocol.openaiResponses), "m")
        val open = writer.open().map { it.event }
        assertTrue(open.contains("response.created"))
        assertTrue(open.contains("response.in_progress"))
        val added = writer.onText("Hello").mapNotNull { it.event }
        assertTrue(added.contains("response.output_item.added"))
        assertTrue(added.contains("response.content_part.added"))
        assertTrue(added.contains("response.output_text.delta"))
        val close = writer.onText("!") + writer.close("end_turn", truncated = false)
        val events = close.mapNotNull { it.event }
        assertTrue(events.contains("response.output_text.done"))
        assertEquals("response.completed", events.last())
        val done = payload(close.last())
        assertEquals("completed", done.getJSONObject("response").getString("status"))
        assertEquals("Hello!", done.getJSONObject("response").getString("output_text"))
    }

    @Test
    fun `reasoning replay is suppressed once deltas already streamed`() {
        val writer = GatewayStreamWriter(req(GatewayProtocol.openai), "m")
        writer.onReasoning("live")
        assertTrue("accumulated replay must not duplicate", writer.onReasoningIfAbsent("live").isEmpty())
        val fresh = GatewayStreamWriter(req(GatewayProtocol.openai), "m")
        assertEquals(1, fresh.onReasoningIfAbsent("only-blob").size)
    }

    @Test
    fun `snapshot folds streamed content for the terminal bookkeeping`() {
        val writer = GatewayStreamWriter(req(GatewayProtocol.anthropic), "m")
        writer.onText("ab")
        writer.onText("cd")
        writer.onUsage(5, 9)
        val snap = writer.snapshot()
        assertEquals("abcd", snap.text)
        assertEquals(5, snap.inputTokens)
        assertEquals(9, snap.outputTokens)
        assertEquals("m", snap.model)
    }
}
