package com.openminis.app.provider.antigravity

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix-antigravity-thought-signature] Real-device 400:
 * `Function call is missing a thought_signature in functionCall parts`.
 *
 * Antigravity v1internal (Gemini family) attaches an opaque part-level
 * `thoughtSignature` to functionCall parts and requires it back verbatim on
 * the next request's history. The provider used to drop it at parse time and
 * rebuild history without it.
 */
class AntigravityThoughtSignatureTest {

    private fun provider(modelId: String = "gemini-3-flash") = AntigravityProvider(
        accessToken = "test-token",
        model = LLMModel(modelId, modelId, "Antigravity"),
    )

    private fun responseChunk(signature: String?) = JSONObject().apply {
        put("candidates", JSONArray().put(JSONObject().apply {
            put("content", JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("functionCall", JSONObject().apply {
                        put("name", "todo_write")
                        put("args", JSONObject().put("tool_title", "x"))
                    })
                    if (signature != null) put("thoughtSignature", signature)
                }))
            })
        }))
    }

    @Test
    fun `camelCase signature is extracted`() {
        val calls = provider().let { p ->
            // extractFunctionCalls is private; exercise it via the request
            // builder seam instead — here we assert through reflection-free
            // duplication: build the response and verify via the provider's
            // public parse path is out of scope, so call the private helper
            // through the test-visible copy below.
            callsFrom(p, responseChunk("SIG123"))
        }
        assertEquals(1, calls.size)
        assertEquals("SIG123", calls[0].third)
    }

    @Test
    fun `snake_case legacy signature is tolerated like upstream normalizePart`() {
        val json = responseChunk(null)
        json.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .put("thought_signature", "LEGACY_SIG")
        val calls = callsFrom(provider(), json)
        assertEquals("LEGACY_SIG", calls[0].third)
    }

    @Test
    fun `missing signature parses to null without breaking extraction`() {
        val calls = callsFrom(provider(), responseChunk(null))
        assertEquals(1, calls.size)
        assertNull(calls[0].third)
    }

    @Test
    fun `history rebuild re-emits the signature at PART level`() {
        val part = AgentContentPart.ToolUse(
            id = "antigravity_1",
            name = "todo_write",
            input = JSONObject().put("tool_title", "x"),
            thoughtSignature = "SIG123",
        )
        val provider = provider()
        val body = buildRequestWithHistory(provider, listOf(part))
        val contents = body.getJSONArray("contents")
        // Find the model-role content carrying the functionCall.
        var emittedPart: JSONObject? = null
        for (i in 0 until contents.length()) {
            val content = contents.getJSONObject(i)
            if (content.optString("role") != "model") continue
            val parts = content.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                if (parts.getJSONObject(j).optJSONObject("functionCall")
                        ?.optString("name") == "todo_write") {
                    emittedPart = parts.getJSONObject(j)
                }
            }
        }
        assertNotNull("functionCall part missing from rebuilt history", emittedPart)
        // PART level, sibling of functionCall — inside functionCall Google
        // proto-rejects it ("Unknown name thoughtSignature").
        assertEquals("SIG123", emittedPart!!.optString("thoughtSignature"))
        assertFalse(emittedPart.getJSONObject("functionCall").has("thoughtSignature"))
    }

    @Test
    fun `claude targets emit paired ids on functionCall and functionResponse`() {
        val provider = provider("claude-opus-4-6-thinking")
        val toolUse = AgentContentPart.ToolUse(
            id = "antigravity_7",
            name = "todo_write",
            input = JSONObject().put("tool_title", "x"),
        )
        val toolResult = AgentContentPart.ToolResult(
            id = "antigravity_7",
            name = "todo_write",
            content = "done",
        )
        val body = buildRequestWithHistory(provider, listOf(toolUse, toolResult))
        val contents = body.getJSONArray("contents")
        var callId: String? = null
        var responseId: String? = null
        for (i in 0 until contents.length()) {
            val parts = contents.getJSONObject(i).optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                part.optJSONObject("functionCall")?.let { callId = it.optString("id") }
                part.optJSONObject("functionResponse")?.let { responseId = it.optString("id") }
            }
        }
        assertEquals("antigravity_7", callId)
        assertEquals("antigravity_7", responseId)
    }

    @Test
    fun `gemini targets keep functionCall id-free`() {
        val part = AgentContentPart.ToolUse(
            id = "antigravity_8",
            name = "todo_write",
            input = JSONObject().put("tool_title", "y"),
        )
        val body = buildRequestWithHistory(provider("gemini-3-flash"), listOf(part))
        val contents = body.getJSONArray("contents")
        for (i in 0 until contents.length()) {
            val parts = contents.getJSONObject(i).optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val fc = parts.getJSONObject(j).optJSONObject("functionCall") ?: continue
                assertFalse(fc.has("id"))
            }
        }
    }

    @Test
    fun `history without signature stays byte-compatible`() {
        val part = AgentContentPart.ToolUse(
            id = "antigravity_2",
            name = "todo_write",
            input = JSONObject().put("tool_title", "y"),
        )
        val body = buildRequestWithHistory(provider(), listOf(part))
        val contents = body.getJSONArray("contents")
        for (i in 0 until contents.length()) {
            val parts = contents.getJSONObject(i).optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val partJson = parts.getJSONObject(j)
                val fc = partJson.optJSONObject("functionCall") ?: continue
                assertFalse(fc.has("thoughtSignature"))
            }
        }
    }

    // ── test seams (mirror the private helpers 1:1) ──

    private fun callsFrom(provider: AntigravityProvider, json: JSONObject) =
        provider.extractFunctionCallsForTest(json)

    private fun buildRequestWithHistory(
        provider: AntigravityProvider,
        toolParts: List<AgentContentPart>,
    ): JSONObject {
        // sanitizeToolPairing strips assistant tool_use blocks that are not
        // answered by the immediately-following user message, so pair every
        // tool_use with a matching tool_result to keep them in the payload.
        val toolResults = toolParts.filterIsInstance<AgentContentPart.ToolUse>().map {
            AgentContentPart.ToolResult(id = it.id, name = it.name, content = "ok")
        }
        val messages = listOf(
            com.openminis.app.data.model.LLMMessage(
                role = com.openminis.app.data.model.LLMMessage.Role.USER,
                content = "hi",
            ),
            com.openminis.app.data.model.LLMMessage(
                role = com.openminis.app.data.model.LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = toolParts,
            ),
            com.openminis.app.data.model.LLMMessage(
                role = com.openminis.app.data.model.LLMMessage.Role.USER,
                content = "",
                contentParts = toolResults,
            ),
        )
        return provider.buildRequestForTest(messages)
    }
}
