package com.openminis.app.gateway

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-local-llm-gateway] The normalised-request → app-provider mapping.
 *
 * This is where a gateway request becomes the shape the providers consume, so
 * every assertion here is a corruption that a client would otherwise see only as
 * a silently wrong answer: a lost image, an orphaned tool_use (the pairing
 * sanitizer drops it and the model never learns its tool ran), or tool results
 * split across turns (which some backends reject outright).
 */
class GatewayMessageMapperTest {

    private fun req(vararg messages: GatewayMessage) = GatewayChatRequest(
        model = "m",
        messages = messages.toList(),
        systemPrompt = null,
        maxTokens = 100,
        temperature = null,
        topP = null,
        tools = emptyList(),
        toolChoice = null,
        stream = false,
        stop = emptyList(),
        thinkingRequested = false,
        requestId = "r",
        protocol = GatewayProtocol.openai,
    )

    private fun pngBase64(): String =
        java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3))

    @Test
    fun `plain user and assistant turns keep the legacy shape`() {
        val out = GatewayMessageMapper.toAppMessages(
            req(
                GatewayMessage(role = "user", text = "hi"),
                GatewayMessage(role = "assistant", text = "hello"),
            ),
        )
        assertEquals(LLMMessage.Role.USER, out[0].role)
        assertEquals("hi", out[0].content)
        assertTrue("no parts needed for plain text", out[0].contentParts.isEmpty())
        assertEquals(LLMMessage.Role.ASSISTANT, out[1].role)
    }

    @Test
    fun `assistant tool calls become ToolUse parts`() {
        val out = GatewayMessageMapper.toAppMessages(
            req(
                GatewayMessage(
                    role = "assistant",
                    text = "let me look",
                    toolCalls = listOf(GatewayToolCall("call_1", "shell_execute", """{"command":"ls"}""")),
                ),
            ),
        )
        val parts = out.single().contentParts
        assertTrue(parts[0] is AgentContentPart.Text)
        val use = parts[1] as AgentContentPart.ToolUse
        assertEquals("call_1", use.id)
        assertEquals("shell_execute", use.name)
        assertEquals("ls", use.input.getString("command"))
    }

    @Test
    fun `unparsable tool arguments degrade to an empty object`() {
        val out = GatewayMessageMapper.toAppMessages(
            req(
                GatewayMessage(
                    role = "assistant", text = "",
                    toolCalls = listOf(GatewayToolCall("c", "f", "{\"broken\":")),
                ),
            ),
        )
        assertEquals(0, (out.single().contentParts[0] as AgentContentPart.ToolUse).input.length())
    }

    @Test
    fun `consecutive tool results group into one user message`() {
        val out = GatewayMessageMapper.toAppMessages(
            req(
                GatewayMessage(
                    role = "assistant", text = "",
                    toolCalls = listOf(
                        GatewayToolCall("a", "f1", "{}"),
                        GatewayToolCall("b", "f2", "{}"),
                    ),
                ),
                GatewayMessage(role = "tool", text = "first", toolCallId = "a", toolName = "f1"),
                GatewayMessage(role = "tool", text = "second", toolCallId = "b", toolName = "f2", toolIsError = true),
            ),
        )
        assertEquals("the two answers share one user turn", 2, out.size)
        val results = out[1].contentParts.filterIsInstance<AgentContentPart.ToolResult>()
        assertEquals(2, results.size)
        assertEquals("a", results[0].id)
        assertEquals("first", results[0].content)
        assertEquals("b", results[1].id)
        assertTrue("is_error survives", results[1].isError)
    }

    @Test
    fun `a tool result followed by a user turn flushes before the new turn`() {
        val out = GatewayMessageMapper.toAppMessages(
            req(
                GatewayMessage(role = "tool", text = "result", toolCallId = "a"),
                GatewayMessage(role = "user", text = "and now?"),
            ),
        )
        assertEquals(2, out.size)
        assertEquals(
            "result",
            out[0].contentParts.filterIsInstance<AgentContentPart.ToolResult>().single().content,
        )
        assertEquals("and now?", out[1].content)
    }

    @Test
    fun `images land in both imageParts and ImageData parts`() {
        val b64 = pngBase64()
        val out = GatewayMessageMapper.toAppMessages(
            req(GatewayMessage(role = "user", text = "look", images = listOf(GatewayImage(b64, "image/png")))),
        )
        val msg = out.single()
        // The providers take the structured branch as soon as contentParts is
        // non-empty, so the bytes must exist there or the image is dropped.
        assertEquals(1, msg.imageParts.size)
        assertTrue(msg.imageParts[0].data.isNotEmpty())
        val image = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>().single()
        assertEquals("image/png", image.mimeType)
        assertTrue(image.data.isNotEmpty())
        assertEquals("the text caption stays adjacent to the image", "look", msg.content)
    }

    @Test
    fun `undecodable image payloads are skipped without failing the turn`() {
        val out = GatewayMessageMapper.toAppMessages(
            req(
                GatewayMessage(
                    role = "user", text = "x",
                    images = listOf(GatewayImage("!!!not base64!!!", "image/png"), GatewayImage(pngBase64(), "image/png")),
                ),
            ),
        )
        assertEquals(1, out.single().imageParts.size)
        assertEquals("user", out.single().role.value)
    }

    @Test
    fun `assistant reasoning is echoed only on assistant turns`() {
        val out = GatewayMessageMapper.toAppMessages(
            req(
                GatewayMessage(role = "assistant", text = "a", reasoning = "because"),
                GatewayMessage(role = "user", text = "u", reasoning = "stray"),
            ),
        )
        assertEquals("because", out[0].reasoningContent)
        assertNull(out[1].reasoningContent)
    }

    // ── tool schema mapping ──

    @Test
    fun `tool schema keeps property order and required list`() {
        val tool = GatewayTool(
            name = "f",
            description = "d",
            schemaJson = """{"type":"object","properties":{"z":{"type":"string","description":"zz"},
                "a":{"type":"integer","description":"aa"},"e":{"type":"string","enum":["x","y"]}},
                "required":["a","z"]}""",
        )
        val def = GatewayMessageMapper.toAgentTool(tool)!!
        // No explicit `propertyOrdering` was sent, so required keys are hoisted
        // ahead of the optional ones — deterministic regardless of the JSON
        // iteration order, which differs between the Android and JVM org.json
        // builds (a real hazard this test would otherwise be prone to).
        assertEquals(listOf("a", "z", "e"), def.propertyOrdering)
        assertEquals(listOf("a", "z"), def.required)
        assertEquals("integer", def.parameters["a"]!!.type)
        assertEquals(listOf("x", "y"), def.parameters["e"]!!.enumValues)
    }

    @Test
    fun `client declared propertyOrdering wins over required hoisting`() {
        val def = GatewayMessageMapper.toAgentTool(
            GatewayTool(
                name = "f", description = "",
                schemaJson = """{"properties":{"a":{"type":"string"},"b":{"type":"string"},"c":{"type":"string"}},
                    "required":["c"],"propertyOrdering":["b","c","a"]}""",
            ),
        )!!
        assertEquals(listOf("b", "c", "a"), def.propertyOrdering)
        // An ordering that names an unknown property must not invent one.
        val rogue = GatewayMessageMapper.toAgentTool(
            GatewayTool(
                name = "f", description = "",
                schemaJson = """{"properties":{"a":{"type":"string"}},"propertyOrdering":["a","ghost"]}""",
            ),
        )!!
        assertEquals(listOf("a"), rogue.propertyOrdering)
    }

    @Test
    fun `nested array items survive the round trip to the dialect schema`() {
        val def = GatewayMessageMapper.toAgentTool(
            GatewayTool(
                name = "todos", description = "",
                schemaJson = """{"type":"object","properties":{"todos":{"type":"array","description":"t",
                    "items":{"type":"object","properties":{"content":{"type":"string"}},"required":["content"]}}}}""",
            ),
        )!!
        val items = def.parameters["todos"]!!.items
        val schema = items ?: return assertEquals("nested items schema must survive", 1, 0)
        assertEquals("object", schema.type)
        // The provider layer emits `items` for Gemini/Anthropic/OpenAI alike.
        assertTrue(schema.toJson().getJSONObject("properties").has("content"))
    }

    @Test
    fun `malformed schema and blank names are handled without throwing`() {
        assertEquals(0, GatewayMessageMapper.toAgentTool(GatewayTool("f", "d", "not json"))!!.parameters.size)
        assertNull(GatewayMessageMapper.toAgentTool(GatewayTool("  ", "d", "{}")))
        // Property order is null (not an empty list) when there are no properties.
        assertNull(GatewayMessageMapper.toAgentTool(GatewayTool("f", "d", "{}"))!!.propertyOrdering)
    }

    @Test
    fun `mapped tool definition serialises into every dialect shape`() {
        val def = GatewayMessageMapper.toAgentTool(
            GatewayTool("f", "desc", """{"properties":{"a":{"type":"string","description":"aa"}},"required":["a"]}"""),
        )!!
        val anthropic = def.toAnthropicJson()
        assertEquals("f", anthropic.getString("name"))
        assertEquals("aa", anthropic.getJSONObject("input_schema").getJSONObject("properties")
            .getJSONObject("a").getString("description"))
        // Gemini wants uppercased types.
        assertEquals("STRING", def.toGeminiJson().getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("a").getString("type"))
        assertEquals("function", def.toOpenAIJson().getString("type"))
        def.toOpenAIJson().getJSONObject("function").getJSONObject("parameters").also {
            assertEquals("a", it.getJSONArray("required").getString(0))
            assertEquals("aa", it.getJSONObject("properties").getJSONObject("a").getString("description"))
        }
    }
}
