package com.openminis.app.gateway

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-local-llm-gateway] Inbound decoding contracts. Each assertion pins a wire
 * detail a real SDK emits; a regression here means a client silently loses the
 * system prompt or the tool call.
 */
class GatewayRequestCodecTest {

    // ── OpenAI Chat Completions ──

    @Test
    fun `system and developer turns are hoisted out of the message list`() {
        val body = JSONObject(
            """
            {"model":"gpt-4o","messages":[
              {"role":"system","content":"be terse"},
              {"role":"developer","content":"answer in French"},
              {"role":"user","content":"hi"}]}
            """,
        )
        val req = GatewayRequestCodec.parseOpenAIChat(body)
        assertEquals("be terse\n\nanswer in French", req.systemPrompt)
        assertEquals(1, req.messages.size)
        assertEquals("user", req.messages[0].role)
        assertEquals(GatewayProtocol.openai, req.protocol)
    }

    @Test
    fun `assistant tool_calls keep id and arguments`() {
        val body = JSONObject(
            """
            {"model":"m","messages":[
              {"role":"assistant","content":null,"tool_calls":[
                {"id":"call_9","type":"function","function":{"name":"shell_execute","arguments":"{\"command\":\"ls\"}"}}]}]}
            """,
        )
        val req = GatewayRequestCodec.parseOpenAIChat(body)
        val call = req.messages.single().toolCalls.single()
        assertEquals("call_9", call.id)
        assertEquals("shell_execute", call.name)
        assertEquals("""{"command":"ls"}""", call.argsJson)
    }

    @Test
    fun `tool result turns carry their call id`() {
        val body = JSONObject(
            """
            {"model":"m","messages":[
              {"role":"user","content":"run it"},
              {"role":"tool","tool_call_id":"call_9","content":"ok"}]}
            """,
        )
        val req = GatewayRequestCodec.parseOpenAIChat(body)
        val tool = req.messages.first { it.role == "tool" }
        assertEquals("call_9", tool.toolCallId)
        assertEquals("ok", tool.text)
    }

    @Test
    fun `data url images unwrap to base64 plus mime, http urls are skipped`() {
        val body = JSONObject(
            """
            {"model":"m","messages":[{"role":"user","content":[
              {"type":"text","text":"what is this"},
              {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAAA"}},
              {"type":"image_url","image_url":{"url":"https://x/y.png"}}]}]}
            """,
        )
        val req = GatewayRequestCodec.parseOpenAIChat(body)
        val msg = req.messages.single()
        assertEquals("what is this", msg.text)
        assertEquals(1, msg.images.size)
        assertEquals("image/jpeg", msg.images[0].mimeType)
        assertEquals("AAAA", msg.images[0].base64)
    }

    @Test
    fun `max_completion_tokens wins over max_tokens and streaming flag reads`() {
        val body = JSONObject(
            """{"model":"m","messages":[{"role":"user","content":"x"}],
               "max_tokens":100,"max_completion_tokens":2048,"stream":true}""",
        )
        val req = GatewayRequestCodec.parseOpenAIChat(body)
        assertEquals(2048, req.maxTokens)
        assertTrue(req.stream)
    }

    @Test
    fun `tools are read from the function wrapper and tool_choice normalises`() {
        val body = JSONObject(
            """
            {"model":"m","messages":[{"role":"user","content":"x"}],
             "tool_choice":"required",
             "tools":[{"type":"function","function":{"name":"f","description":"d",
                "parameters":{"type":"object","properties":{"a":{"type":"string","description":"aa"}},"required":["a"]}}}]}
            """,
        )
        val req = GatewayRequestCodec.parseOpenAIChat(body)
        assertEquals("required", req.toolChoice)
        val tool = req.tools.single()
        assertEquals("f", tool.name)
        val schema = JSONObject(tool.schemaJson)
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("a").getString("type"))
        assertEquals("a", schema.getJSONArray("required").getString(0))
    }

    // ── Anthropic Messages ──

    @Test
    fun `anthropic system block array hoists and tool_result blocks flatten`() {
        val body = JSONObject(
            """
            {"model":"claude-sonnet-5","max_tokens":64,
             "system":[{"type":"text","text":"sys A"},{"type":"text","text":"sys B"}],
             "messages":[
               {"role":"user","content":[
                  {"type":"tool_result","tool_use_id":"tu_1","content":"result text"},
                  {"type":"text","text":"and a follow-up"}]},
               {"role":"assistant","content":[
                  {"type":"text","text":"thinking out loud"},
                  {"type":"tool_use","id":"tu_2","name":"read","input":{"path":"/tmp/x"}}]}]}
            """,
        )
        val req = GatewayRequestCodec.parseAnthropic(body)
        assertEquals("sys A\n\nsys B", req.systemPrompt)
        // tool_result becomes its own tool message; the sibling text stays a user turn.
        val tool = req.messages.first { it.role == "tool" }
        assertEquals("tu_1", tool.toolCallId)
        assertEquals("result text", tool.text)
        val assistant = req.messages.first { it.role == "assistant" }
        assertEquals("thinking out loud", assistant.text)
        val call = assistant.toolCalls.single()
        assertEquals("tu_2", call.id)
        assertEquals("/tmp/x", JSONObject(call.argsJson).getString("path"))
    }

    @Test
    fun `anthropic error tool results keep the is_error flag`() {
        val body = JSONObject(
            """
            {"model":"c","max_tokens":8,"messages":[{"role":"user","content":[
              {"type":"tool_result","tool_use_id":"t1","content":"boom","is_error":true}]}]}
            """,
        )
        val req = GatewayRequestCodec.parseAnthropic(body)
        assertTrue(req.messages.first { it.role == "tool" }.toolIsError)
    }

    @Test
    fun `anthropic tool_choice any maps to required`() {
        val body = JSONObject(
            """{"model":"c","max_tokens":8,"tool_choice":{"type":"any"},
               "messages":[{"role":"user","content":"x"}]}""",
        )
        assertEquals("required", GatewayRequestCodec.parseAnthropic(body).toolChoice)
    }

    // ── Gemini ──

    @Test
    fun `gemini model comes from the path and role model maps to assistant`() {
        val body = JSONObject(
            """
            {"systemInstruction":{"parts":[{"text":"be brief"}]},
             "contents":[
               {"role":"user","parts":[{"text":"hi"}]},
               {"role":"model","parts":[{"text":"hello"}]}],
             "generationConfig":{"maxOutputTokens":512,"temperature":0.2},
             "tools":[{"functionDeclarations":[{"name":"f","description":"d",
                 "parameters":{"type":"OBJECT","properties":{"a":{"type":"STRING","description":"aa"}}}}]}]}
            """,
        )
        val req = GatewayRequestCodec.parseGemini(body, "gemini-3-flash-preview", true)
        assertEquals("gemini-3-flash-preview", req.model)
        assertEquals("be brief", req.systemPrompt)
        assertEquals(512, req.maxTokens)
        assertEquals(0.2, req.temperature!!, 1e-9)
        assertTrue(req.stream)
        assertEquals("assistant", req.messages[1].role)
        assertEquals("f", req.tools.single().name)
    }

    @Test
    fun `gemini functionCall and functionResponse round-trip`() {
        val body = JSONObject(
            """
            {"contents":[
              {"role":"model","parts":[{"functionCall":{"name":"lookup","args":{"city":"Osaka"}}}]},
              {"role":"user","parts":[{"functionResponse":{"name":"lookup","response":{"result":"rain"}}}]}]}
            """,
        )
        val req = GatewayRequestCodec.parseGemini(body, "m", false)
        val call = req.messages.first { it.role == "assistant" }.toolCalls.single()
        assertEquals("lookup", call.name)
        assertEquals("Osaka", JSONObject(call.argsJson).getString("city"))
        val answer = req.messages.first { it.role == "tool" }
        assertEquals("rain", answer.text)
        assertEquals("call_lookup", answer.toolCallId)
    }

    // ── Ollama ──

    @Test
    fun `ollama options map onto the normalised request`() {
        val body = JSONObject(
            """
            {"model":"llama3","stream":false,"messages":[
               {"role":"system","content":"sys"},{"role":"user","content":"hi","images":["AAAA"]}],
             "options":{"num_predict":300,"temperature":0.7,"top_p":0.9,"stop":["/s"]},
             "tools":[{"type":"function","function":{"name":"f","description":"d","parameters":{"type":"object"}}}]}
            """,
        )
        val req = GatewayRequestCodec.parseOllamaChat(body)
        assertEquals("sys", req.systemPrompt)
        assertEquals(300, req.maxTokens)
        assertEquals(0.9, req.topP!!, 1e-9)
        assertEquals(listOf("/s"), req.stop)
        assertFalse(req.stream)
        assertEquals("AAAA", req.messages.single().images.single().base64)
        assertEquals("f", req.tools.single().name)
    }

    @Test
    fun `generate api is flagged and prompt becomes the last user turn`() {
        val body = JSONObject(
            """{"model":"llama3","prompt":"explain X","system":"s","stream":false,"messages":[
               {"role":"user","content":"prior"},{"role":"assistant","content":"answer"}]}""",
        )
        val req = GatewayRequestCodec.parseOllamaGenerate(body)
        assertTrue(req.isGenerateApi)
        assertEquals("s", req.systemPrompt)
        assertEquals(3, req.messages.size)
        assertEquals("explain X", req.messages.last().text)
        assertEquals("assistant", req.messages[1].role)
    }

    // ── Responses API + legacy completions ──

    @Test
    fun `responses input items decode instructions and function calls`() {
        val body = JSONObject(
            """
            {"model":"gpt-5.2","instructions":"be nice","stream":true,
             "max_output_tokens":700,
             "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
                      {"type":"function_call","call_id":"c1","name":"f","arguments":"{}"},
                      {"type":"function_call_output","call_id":"c1","output":"done"}],
             "tools":[{"type":"function","name":"f","description":"d","parameters":{"type":"object"}}],
             "reasoning":{"effort":"high"}}
            """,
        )
        val req = GatewayRequestCodec.parseOpenAIResponses(body)
        assertEquals("be nice", req.systemPrompt)
        assertEquals(700, req.maxTokens)
        assertTrue(req.thinkingRequested)
        assertEquals(GatewayProtocol.openaiResponses, req.protocol)
        assertEquals(3, req.messages.size)
        assertEquals("done", req.messages.last().text)
        assertEquals("c1", req.messages[1].toolCalls.single().id)
    }

    @Test
    fun `legacy completions prompt array joins into one user turn`() {
        val body = JSONObject("""{"model":"davinci","prompt":["a","b"],"max_tokens":16}""")
        val req = GatewayRequestCodec.parseOpenAICompletions(body)
        assertEquals(GatewayProtocol.openaiCompletions, req.protocol)
        assertEquals("a\nb", req.messages.single().text)
    }

    // ── stop-reason table ──

    @Test
    fun `stop reasons map per dialect and tool calls always win`() {
        assertEquals("tool_calls", GatewayErrors.stopReason("tool_use", GatewayProtocol.openai))
        assertEquals("length", GatewayErrors.stopReason("max_tokens", GatewayProtocol.openai))
        assertEquals("stop", GatewayErrors.stopReason("end_turn", GatewayProtocol.openai))
        // Anthropic spells a prose finish "end_turn" and an explicit stop
        // sequence "stop_sequence"; the two canonical values stay distinct.
        assertEquals("end_turn", GatewayErrors.stopReason("end_turn", GatewayProtocol.anthropic))
        assertEquals("stop_sequence", GatewayErrors.stopReason("stop", GatewayProtocol.anthropic))
        assertEquals("MAX_TOKENS", GatewayErrors.stopReason("max_tokens", GatewayProtocol.gemini))
        assertEquals("requires_action", GatewayErrors.stopReason("tool_use", GatewayProtocol.openaiResponses))
        // A provider that forgot to report tool_calls still must not look like prose.
        assertEquals("tool_use", GatewayErrors.canonicalStop("stop", hasToolCalls = true))
        assertEquals("max_tokens", GatewayErrors.canonicalStop("length", false))
        // No terminal event (EOF) is a finished turn, not a length wall.
        assertEquals("stop", GatewayErrors.canonicalStop(null, false))
        assertEquals("stop", GatewayErrors.canonicalStop("STOP", false))
        assertEquals("stop", GatewayErrors.canonicalStop("stop_sequence", false))
        assertEquals("end_turn", GatewayErrors.canonicalStop("end_turn", false))
    }

    @Test
    fun `worker error codes map to client-visible statuses`() {
        assertEquals(401, GatewayErrors.fromWorkerCode("missing_api_key", "x").status)
        assertEquals(429, GatewayErrors.fromWorkerCode("rate_limited", "x").status)
        assertEquals(404, GatewayErrors.fromWorkerCode("model_not_found", "x").status)
        assertEquals(504, GatewayErrors.fromWorkerCode("first_chunk_timeout", "x").status)
        assertEquals(502, GatewayErrors.fromWorkerCode("model_use_failed", "x").status)
    }

    @Test
    fun `provider exceptions map to the status a client retries on`() {
        // openai-python branches retry policy on the status, so an upstream 401
        // must not collapse into a generic 5xx.
        assertEquals(401, GatewayErrors.fromThrowable(com.openminis.app.data.model.LLMError.InvalidApiKey()).status)
        assertEquals(429, GatewayErrors.fromThrowable(com.openminis.app.data.model.LLMError.RateLimited()).status)
        assertEquals(503, GatewayErrors.fromThrowable(com.openminis.app.data.model.LLMError.TransientError("x")).status)
        assertEquals(
            502,
            GatewayErrors.fromThrowable(
                com.openminis.app.data.model.LLMError.NetworkError(java.io.IOException("reset")),
            ).status,
        )
        // A worker that died before delivering anything is retry-able.
        assertEquals(
            503,
            GatewayErrors.fromThrowable(
                com.openminis.app.sandbox.offload.ModelWorkerDiedException(hadChunks = false),
            ).status,
        )
        assertEquals(
            502,
            GatewayErrors.fromThrowable(
                com.openminis.app.sandbox.offload.ModelStreamErrorException("boom", hadChunks = true),
            ).status,
        )
        assertEquals(502, GatewayErrors.fromThrowable(RuntimeException("other")).status)
        assertEquals("authentication_error", GatewayErrors.fromThrowable(com.openminis.app.data.model.LLMError.InvalidApiKey()).type)
    }

    @Test
    fun `ollama errors answer with a flat error string, others nest it`() {
        val ex = GatewayException(401, "authentication_error", "nope", "invalid_api_key")
        val flat = JSONObject(GatewayErrors.body(ex, null, GatewayProtocol.ollama))
        assertEquals("nope", flat.getString("error"))
        val nested = JSONObject(GatewayErrors.body(ex, "req_1", GatewayProtocol.openai))
        assertEquals("nope", nested.getJSONObject("error").getString("message"))
        assertEquals("req_1", nested.getJSONObject("error").getString("request_id"))
    }

    // ── data-url helper edge cases ──

    @Test
    fun `parseDataUrl tolerates junk and keeps urlencoded payloads`() {
        assertNull(GatewayRequestCodec.parseDataUrl(null))
        assertNull(GatewayRequestCodec.parseDataUrl("https://not-a-data-url"))
        assertNull(GatewayRequestCodec.parseDataUrl("data:image/png"))
        val img = GatewayRequestCodec.parseDataUrl("data:;base64,QUJD")
        assertEquals("QUJD", img?.base64)
        assertEquals("image/png", GatewayRequestCodec.parseDataUrl("data:image/png;base64,AA")?.mimeType)
    }

    /** org.json under JVM tests is the real implementation (testImplementation org.json:json). */
    @Test
    fun `json helpers never throw on malformed shapes`() {
        assertNull(GatewayJson.obj("not json".toByteArray()))
        assertNull(GatewayJson.obj(ByteArray(0)))
        val o = JSONObject().put("n", "12").put("d", "1.5")
        o.put("z", JSONObject.NULL)
        assertEquals(12, GatewayJson.int(o, "n", 0))
        assertEquals(1.5, GatewayJson.dbl(o, "d")!!, 1e-9)
        assertEquals(7, GatewayJson.int(o, "missing", 7))
        assertNull(GatewayJson.dbl(o, "z"))
        // Multi-part content joins with a newline: two text parts are two blocks.
        assertEquals(
            "a\nb",
            GatewayJson.partsText(JSONArray().put(JSONObject().put("text", "a")).put(JSONObject().put("text", "b"))),
        )
    }

    @Test
    fun `arguments sanitizer rejects unparsable payloads`() {
        assertEquals("{}", GatewayResponseCodec.sanitizeArgs(""))
        assertEquals("{}", GatewayResponseCodec.sanitizeArgs("{\"truncated\":"))
        assertEquals("""{"a":1}""", GatewayResponseCodec.sanitizeArgs("""{"a":1}"""))
    }
}
