package com.openminis.app.sandbox.offload

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix-antigravity-v1internal-items] The chat / model_use path ships tool
 * declarations cross-process: main (ModelExecutionDispatcher) serialises
 * AgentToolDefinition into the request JSON, :modelservice
 * (ModelExecutionService.parseToolsJson) rebuilds it, and the provider's
 * toGeminiJson() puts it on the wire. The dispatcher used to drop `items`,
 * so the rebuilt todos param arrived as a bare ARRAY and Antigravity's
 * v1internal rejected the request with
 * `properties[todos].items: missing field` — even though the in-process
 * declaration was correct. These tests pin the full round-trip.
 */
class ToolCrossProcessSchemaTest {

    private val todosTool = AgentToolDefinition(
        name = "todo_write",
        description = "task list",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "summary"),
            "todos" to AgentToolParam(
                "array",
                "complete task list",
                items = AgentToolSchema(
                    type = "object",
                    properties = mapOf(
                        "content" to AgentToolParam("string", "task"),
                        "status" to AgentToolParam(
                            "string",
                            "state",
                            enumValues = listOf("pending", "in_progress", "completed"),
                        ),
                    ),
                    required = listOf("content", "status"),
                    propertyOrdering = listOf("content", "status"),
                ),
            ),
        ),
        required = listOf("tool_title", "todos"),
        propertyOrdering = listOf("tool_title", "todos"),
    )

    /** Mirrors ModelExecutionDispatcher's request-JSON writer. */
    private fun serialize(tools: List<AgentToolDefinition>): JSONArray {
        val out = JSONArray()
        tools.forEach { t ->
            out.put(JSONObject().apply {
                put("name", t.name)
                put("description", t.description)
                if (t.parameters.isNotEmpty()) {
                    put("parameters", JSONObject().apply {
                        t.parameters.forEach { (k, v) ->
                            put(k, JSONObject().apply {
                                put("type", v.type)
                                put("description", v.description)
                                v.enumValues?.takeIf { it.isNotEmpty() }?.let { put("enum", JSONArray(it)) }
                                v.items?.let { put("items", it.toJson()) }
                            })
                        }
                    })
                }
                if (t.required.isNotEmpty()) put("required", JSONArray(t.required))
                t.propertyOrdering?.takeIf { it.isNotEmpty() }?.let { put("property_ordering", JSONArray(it)) }
            })
        }
        return out
    }

    /** Mirrors ModelExecutionService.parseToolsJson. */
    private fun deserialize(arr: JSONArray): List<AgentToolDefinition> {
        val out = mutableListOf<AgentToolDefinition>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val params = linkedMapOf<String, AgentToolParam>()
            t.optJSONObject("parameters")?.let { ps ->
                ps.keys().forEach { k ->
                    val v = ps.getJSONObject(k)
                    params[k] = AgentToolParam(
                        type = v.getString("type"),
                        description = v.optString("description"),
                        enumValues = v.optJSONArray("enum")
                            ?.let { e -> (0 until e.length()).map { e.getString(it) } },
                        items = AgentToolSchema.fromJson(v.optJSONObject("items")),
                    )
                }
            }
            out.add(
                AgentToolDefinition(
                    name = t.getString("name"),
                    description = t.optString("description"),
                    parameters = params,
                    required = t.optJSONArray("required")
                        ?.let { r -> (0 until r.length()).map { r.getString(it) } }
                        ?: emptyList(),
                    propertyOrdering = t.optJSONArray("property_ordering")
                        ?.let { r -> (0 until r.length()).map { r.getString(it) } },
                ),
            )
        }
        return out
    }

    @Test
    fun `todos items schema survives the cross-process round-trip`() {
        val rebuilt = deserialize(serialize(listOf(todosTool))).first { it.name == "todo_write" }
        val items = rebuilt.parameters["todos"]!!.items
        assertNotNull("items must survive serialisation", items)
        assertEquals("object", items!!.type)
        assertEquals("pending", items.properties!!["status"]!!.enumValues!![0])

        // And the Gemini declaration built from the REBUILT tool still
        // carries items — this is exactly what hit the wire before.
        val gemini = rebuilt.toGeminiJson()
        val todos = gemini.getJSONObject("parameters").getJSONObject("properties").getJSONObject("todos")
        assertTrue(todos.has("items"))
        assertEquals(
            "STRING",
            todos.getJSONObject("items").getJSONObject("properties").getJSONObject("content").getString("type"),
        )
    }

    @Test
    fun `params without items stay clean on the wire`() {
        val rebuilt = deserialize(serialize(listOf(todosTool))).first()
        val toolTitle = rebuilt.parameters["tool_title"]!!.toGeminiJson()
        assertTrue(!toolTitle.has("items"))
    }
}
