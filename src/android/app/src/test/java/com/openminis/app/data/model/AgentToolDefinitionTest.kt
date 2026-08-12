package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentToolDefinitionTest {

    @Test
    fun `toAnthropicJson returns correct JSON structure`() {
        val param = AgentToolParam("string", "A test parameter", listOf("val1", "val2"))
        val definition = AgentToolDefinition(
            name = "test_tool",
            description = "A test tool",
            parameters = mapOf("param1" to param),
            required = listOf("param1")
        )

        val result = definition.toAnthropicJson()

        assertEquals("test_tool", result.getString("name"))
        assertEquals("A test tool", result.getString("description"))
        val inputSchema = result.getJSONObject("input_schema")
        assertEquals("object", inputSchema.getString("type"))
        val properties = inputSchema.getJSONObject("properties")
        assertTrue(properties.has("param1"))
        val requiredArray = inputSchema.getJSONArray("required")
        assertEquals(1, requiredArray.length())
        assertEquals("param1", requiredArray.getString(0))
    }

    @Test
    fun `toAnthropicJson without required returns empty required`() {
        val param = AgentToolParam("integer", "Count")
        val definition = AgentToolDefinition(
            name = "counter",
            description = "Counts things",
            parameters = mapOf("count" to param)
        )

        val result = definition.toAnthropicJson()

        val inputSchema = result.getJSONObject("input_schema")
        assertEquals(false, inputSchema.has("required"))
    }

    @Test
    fun `toGeminiJson returns correct JSON structure`() {
        val param = AgentToolParam("number", "A numeric value", listOf("1.0", "2.0"))
        val definition = AgentToolDefinition(
            name = "calc",
            description = "Calculator",
            parameters = mapOf("value" to param),
            required = listOf("value"),
            propertyOrdering = listOf("value")
        )

        val result = definition.toGeminiJson()

        assertEquals("calc", result.getString("name"))
        assertEquals("Calculator", result.getString("description"))
        val params = result.getJSONObject("parameters")
        assertEquals("OBJECT", params.getString("type"))
        val properties = params.getJSONObject("properties")
        assertTrue(properties.has("value"))
        val requiredArray = params.getJSONArray("required")
        assertEquals("value", requiredArray.getString(0))
        val orderingArray = params.getJSONArray("propertyOrdering")
        assertEquals("value", orderingArray.getString(0))
    }

    @Test
    fun `toGeminiJson without propertyOrdering and required`() {
        val param = AgentToolParam("boolean", "Flag")
        val definition = AgentToolDefinition(
            name = "flag_tool",
            description = "Sets a flag",
            parameters = mapOf("flag" to param)
        )

        val result = definition.toGeminiJson()

        val params = result.getJSONObject("parameters")
        assertEquals(false, params.has("required"))
        assertEquals(false, params.has("propertyOrdering"))
    }

    @Test
    fun `toOpenAIJson returns correct JSON structure`() {
        val param = AgentToolParam("string", "Name", listOf("Alice", "Bob"))
        val definition = AgentToolDefinition(
            name = "greet",
            description = "Greets a person",
            parameters = mapOf("name" to param),
            required = listOf("name")
        )

        val result = definition.toOpenAIJson()

        assertEquals("function", result.getString("type"))
        val function = result.getJSONObject("function")
        assertEquals("greet", function.getString("name"))
        assertEquals("Greets a person", function.getString("description"))
        val params = function.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        val properties = params.getJSONObject("properties")
        assertTrue(properties.has("name"))
        val requiredArray = params.getJSONArray("required")
        assertEquals("name", requiredArray.getString(0))
    }

    @Test
    fun `toOpenAIJson without required`() {
        val param = AgentToolParam("integer", "Age")
        val definition = AgentToolDefinition(
            name = "age_tool",
            description = "Gets age",
            parameters = mapOf("age" to param)
        )

        val result = definition.toOpenAIJson()

        val function = result.getJSONObject("function")
        val params = function.getJSONObject("parameters")
        assertEquals(false, params.has("required"))
    }

    @Test
    fun `toAnthropicJson handles empty parameters`() {
        val definition = AgentToolDefinition(
            name = "empty_tool",
            description = "Tool with no parameters",
            parameters = emptyMap()
        )

        val result = definition.toAnthropicJson()

        val inputSchema = result.getJSONObject("input_schema")
        val properties = inputSchema.getJSONObject("properties")
        assertEquals(0, properties.length())
    }

    @Test
    fun `toGeminiJson handles empty parameters`() {
        val definition = AgentToolDefinition(
            name = "empty_tool",
            description = "Tool with no parameters",
            parameters = emptyMap()
        )

        val result = definition.toGeminiJson()

        val params = result.getJSONObject("parameters")
        val properties = params.getJSONObject("properties")
        assertEquals(0, properties.length())
    }

    @Test
    fun `toOpenAIJson handles empty parameters`() {
        val definition = AgentToolDefinition(
            name = "empty_tool",
            description = "Tool with no parameters",
            parameters = emptyMap()
        )

        val result = definition.toOpenAIJson()

        val function = result.getJSONObject("function")
        val params = function.getJSONObject("parameters")
        val properties = params.getJSONObject("properties")
        assertEquals(0, properties.length())
    }

    @Test
    fun `toAnthropicJson param with enum values`() {
        val param = AgentToolParam("string", "Color", listOf("red", "green", "blue"))
        val definition = AgentToolDefinition(
            name = "color_tool",
            description = "Selects a color",
            parameters = mapOf("color" to param)
        )

        val result = definition.toAnthropicJson()
        val paramJson = result.getJSONObject("input_schema")
            .getJSONObject("properties")
            .getJSONObject("color")

        assertEquals("string", paramJson.getString("type"))
        assertEquals("Color", paramJson.getString("description"))
        val enumArray = paramJson.getJSONArray("enum")
        assertEquals("red", enumArray.getString(0))
        assertEquals("green", enumArray.getString(1))
        assertEquals("blue", enumArray.getString(2))
    }

    @Test
    fun `toGeminiJson param with enum values`() {
        val param = AgentToolParam("string", "Size", listOf("S", "M", "L"))
        val definition = AgentToolDefinition(
            name = "size_tool",
            description = "Selects a size",
            parameters = mapOf("size" to param)
        )

        val result = definition.toGeminiJson()
        val paramJson = result.getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("size")

        assertEquals("STRING", paramJson.getString("type"))
        assertEquals("Size", paramJson.getString("description"))
        val enumArray = paramJson.getJSONArray("enum")
        assertEquals("S", enumArray.getString(0))
        assertEquals("M", enumArray.getString(1))
        assertEquals("L", enumArray.getString(2))
    }

    @Test
    fun `toOpenAIJson param with enum values`() {
        val param = AgentToolParam("string", "Option", listOf("A", "B"))
        val definition = AgentToolDefinition(
            name = "option_tool",
            description = "Selects an option",
            parameters = mapOf("option" to param)
        )

        val result = definition.toOpenAIJson()
        val paramJson = result.getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("option")

        assertEquals("string", paramJson.getString("type"))
        assertEquals("Option", paramJson.getString("description"))
        val enumArray = paramJson.getJSONArray("enum")
        assertEquals("A", enumArray.getString(0))
        assertEquals("B", enumArray.getString(1))
    }

    @Test
    fun `toAnthropicJson multiple parameters`() {
        val param1 = AgentToolParam("string", "First name")
        val param2 = AgentToolParam("integer", "Age")
        val definition = AgentToolDefinition(
            name = "multi_tool",
            description = "Tool with multiple params",
            parameters = mapOf("name" to param1, "age" to param2),
            required = listOf("name")
        )

        val result = definition.toAnthropicJson()
        val properties = result.getJSONObject("input_schema").getJSONObject("properties")
        assertEquals(2, properties.length())
        assertTrue(properties.has("name"))
        assertTrue(properties.has("age"))
    }

    @Test
    fun `toGeminiJson multiple parameters with propertyOrdering`() {
        val param1 = AgentToolParam("string", "City")
        val param2 = AgentToolParam("number", "Latitude")
        val definition = AgentToolDefinition(
            name = "geo_tool",
            description = "Geographic tool",
            parameters = mapOf("city" to param1, "lat" to param2),
            propertyOrdering = listOf("city", "lat")
        )

        val result = definition.toGeminiJson()
        val params = result.getJSONObject("parameters")
        val properties = params.getJSONObject("properties")
        assertEquals(2, properties.length())
        val ordering = params.getJSONArray("propertyOrdering")
        assertEquals("city", ordering.getString(0))
        assertEquals("lat", ordering.getString(1))
    }

    @Test
    fun `toOpenAIJson multiple parameters`() {
        val param1 = AgentToolParam("boolean", "Active")
        val param2 = AgentToolParam("string", "Status")
        val definition = AgentToolDefinition(
            name = "status_tool",
            description = "Status checker",
            parameters = mapOf("active" to param1, "status" to param2)
        )

        val result = definition.toOpenAIJson()
        val properties = result.getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")
        assertEquals(2, properties.length())
        assertTrue(properties.has("active"))
        assertTrue(properties.has("status"))
    }
}