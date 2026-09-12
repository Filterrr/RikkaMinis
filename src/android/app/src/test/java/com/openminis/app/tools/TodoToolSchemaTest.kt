package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.AgentToolSchema
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-antigravity-v1internal-items] Regression coverage for the Antigravity
 * 400 `INVALID_ARGUMENT: function_declarations[6].parameters.properties
 * [todos].items: missing field` — Gemini-family Schema (v1internal included)
 * requires every ARRAY node to carry an `items` schema, so a bare
 * `AgentToolParam("array", ...)` must never reach the wire.
 */
class TodoToolSchemaTest {

    private fun todoTool(): AgentToolDefinition =
        AgentTools.makeAgentTools(supportsImageInput = false, memoryEnabled = false)
            .first { it.name == "todo_write" }

    @Test
    fun `todos array carries an items schema in the gemini declaration`() {
        val decl = todoTool().toGeminiJson()
        val todos = decl
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("todos")
        assertEquals("ARRAY", todos.getString("type"))
        val items = todos.getJSONObject("items")
        assertEquals("OBJECT", items.getString("type"))
        val props = items.getJSONObject("properties")
        assertEquals("STRING", props.getJSONObject("content").getString("type"))
        assertEquals("STRING", props.getJSONObject("status").getString("type"))
        assertEquals(
            "pending",
            props.getJSONObject("status").getJSONArray("enum").getString(0),
        )
        assertEquals("content", items.getJSONArray("required").getString(0))
    }

    @Test
    fun `gemini declaration satisfies the v1internal schema walk`() {
        // Walk every function declaration exactly like the Gemini Schema
        // validator: ARRAY nodes must always define items.
        val decls = AgentTools.makeAgentTools(supportsImageInput = true, memoryEnabled = true)
        assertTrue(decls.size >= 7)
        for (tool in decls) {
            walkGeminiSchema(tool.toGeminiJson().getJSONObject("parameters"))
        }
    }

    private fun walkGeminiSchema(schema: JSONObject) {
        if (!schema.has("properties")) return
        val props = schema.getJSONObject("properties")
        val keys = props.keys()
        while (keys.hasNext()) {
            val node = props.getJSONObject(keys.next())
            val type = node.optString("type")
            if (type.equals("ARRAY", ignoreCase = true)) {
                assertTrue("ARRAY node missing items schema", node.has("items"))
                walkGeminiSchema(node.getJSONObject("items"))
            } else if (type.equals("OBJECT", ignoreCase = true)) {
                walkGeminiSchema(node)
            }
        }
    }

    @Test
    fun `non-gemini formats keep lowercase types and omit propertyOrdering`() {
        val decl = todoTool().toOpenAIJson()
        val params = decl.getJSONObject("function").getJSONObject("parameters")
        val todos = params.getJSONObject("properties").getJSONObject("todos")
        assertEquals("array", todos.getString("type"))
        assertNotNull(todos.getJSONObject("items").getJSONObject("properties"))
        assertFalse(params.has("propertyOrdering"))
    }

    @Test
    fun `nested item schema serialises recursively for gemini`() {
        val param = AgentToolParam(
            "array",
            "matrix",
            items = AgentToolSchema(
                type = "object",
                properties = mapOf(
                    "tags" to AgentToolParam(
                        "array",
                        "tags",
                        items = AgentToolSchema(type = "string"),
                    ),
                ),
                required = listOf("tags"),
            ),
        )
        val json = param.toGeminiJson()
        val nested = json.getJSONObject("items").getJSONObject("properties").getJSONObject("tags")
        assertEquals("ARRAY", nested.getString("type"))
        assertEquals("STRING", nested.getJSONObject("items").getString("type"))
    }
}
