package com.openminis.app.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentPartTest {

    // Helper to create a JSON string with a given type field
    private fun createJsonWithType(type: String, extraFields: Map<String, String> = emptyMap()): String {
        val fields = extraFields.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        return """{"type":"$type"${if (fields.isNotEmpty()) ",$fields" else ""}}"""
    }

    // ==================== ContentPart.Subclass Serialization/Deserialization ====================

    @Test
    fun `serialize Text to JSON and back`() {
        val original = ContentPart.Text("hello world")
        val json = contentPartJson.encodeToString(original)
        val restored = contentPartJson.decodeFromString<ContentPart>(json)
        assertEquals(original, restored)
    }

    @Test
    fun `serialize Media to JSON and back`() {
        val mediaRef = MediaRef("id1", "/path/to/file", "image/png", "file.png")
        val original = ContentPart.Media(mediaRef)
        val json = contentPartJson.encodeToString(original)
        val restored = contentPartJson.decodeFromString<ContentPart>(json)
        assertEquals(original, restored)
    }

    @Test
    fun `serialize Tool to JSON and back`() {
        val toolUse = ToolUse("use1", "toolA", "input data", "description")
        val original = ContentPart.Tool(toolUse)
        val json = contentPartJson.encodeToString(original)
        val restored = contentPartJson.decodeFromString<ContentPart>(json)
        assertEquals(original, restored)
    }

    @Test
    fun `serialize Result to JSON and back`() {
        val mediaRef = MediaRef("id2", "/path/to/result", "application/pdf", "result.pdf")
        val toolResult = ToolResult("use2", "output data", true, mediaRef)
        val original = ContentPart.Result(toolResult)
        val json = contentPartJson.encodeToString(original)
        val restored = contentPartJson.decodeFromString<ContentPart>(json)
        assertEquals(original, restored)
    }

    @Test
    fun `deserialize unknown type falls back to Text`() {
        val json = """{"type":"unknown","value":"fallback text"}"""
        val result = contentPartJson.decodeFromString<ContentPart>(json)
        assertTrue(result is ContentPart.Text)
        assertEquals("fallback text", (result as ContentPart.Text).value)
    }

    // ==================== ContentPartSerializer.selectDeserializer (indirectly tested) ====================

    // The selectDeserializer is protected, but we can test its logic via decodeFromString.
    @Test
    fun `selectDeserializer returns correct serializer for each type`() {
        // Test with valid types
        val textJson = """{"type":"text","value":"a"}"""
        val mediaJson = """{"type":"mediaRef","value":{"id":"x","relativePath":"/p","mimeType":"t","originalFileName":"f"}}"""
        val toolJson = """{"type":"toolUse","value":{"toolUseId":"u","name":"n","input":"i","description":"d"}}"""
        val resultJson = """{"type":"toolResult","value":{"toolUseId":"r","output":"o","success":true,"mediaRef":null}}"""

        assertEquals(ContentPart.Text::class, contentPartJson.decodeFromString<ContentPart>(textJson)::class)
        assertEquals(ContentPart.Media::class, contentPartJson.decodeFromString<ContentPart>(mediaJson)::class)
        assertEquals(ContentPart.Tool::class, contentPartJson.decodeFromString<ContentPart>(toolJson)::class)
        assertEquals(ContentPart.Result::class, contentPartJson.decodeFromString<ContentPart>(resultJson)::class)
    }

    // ==================== Data class functions (copy, toString, equals, hashCode) ====================

    @Test
    fun `ContentPart_Text copy works`() {
        val original = ContentPart.Text("original")
        val copy = original.copy(value = "changed")
        assertEquals("changed", copy.value)
        assertNotEquals(original, copy)
    }

    @Test
    fun `ContentPart_Media copy works`() {
        val ref = MediaRef("id", "path", "mime", "name")
        val original = ContentPart.Media(ref)
        val newRef = ref.copy(relativePath = "newPath")
        val copy = original.copy(value = newRef)
        assertEquals(newRef, copy.value)
        assertNotEquals(original, copy)
    }

    @Test
    fun `ContentPart_Tool copy works`() {
        val use = ToolUse("uid", "name", "input", "desc")
        val original = ContentPart.Tool(use)
        val copy = original.copy(value = use.copy(name = "newName"))
        assertEquals("newName", copy.value.name)
    }

    @Test
    fun `ContentPart_Result copy works`() {
        val res = ToolResult("uid", "out", true, null)
        val original = ContentPart.Result(res)
        val copy = original.copy(value = res.copy(output = "newOut"))
        assertEquals("newOut", copy.value.output)
    }

    @Test
    fun `ContentPart_toString contains class name`() {
        assertTrue(ContentPart.Text("test").toString().contains("Text"))
        assertTrue(ContentPart.Media(MediaRef("i","p","m")).toString().contains("Media"))
        assertTrue(ContentPart.Tool(ToolUse("u","n","i")).toString().contains("Tool"))
        assertTrue(ContentPart.Result(ToolResult("u","o",true)).toString().contains("Result"))
    }

    @Test
    fun `ContentPart_equals and hashCode consistency`() {
        val a = ContentPart.Text("hello")
        val b = ContentPart.Text("hello")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val c = ContentPart.Text("different")
        assertNotEquals(a, c)
    }

    // ==================== MediaRef / ToolUse / ToolResult data class functions ====================

    @Test
    fun `MediaRef default originalFileName is null`() {
        val ref = MediaRef("id", "path", "mime")
        assertEquals(null, ref.originalFileName)
    }

    @Test
    fun `MediaRef copy and toString`() {
        val ref = MediaRef("id", "path", "mime", "name")
        val copy = ref.copy(originalFileName = "newName")
        assertEquals("newName", copy.originalFileName)
        assertTrue(ref.toString().contains("MediaRef"))
    }

    @Test
    fun `ToolUse description default is null`() {
        val use = ToolUse("uid", "name", "input")
        assertEquals(null, use.description)
    }

    @Test
    fun `ToolUse copy and toString`() {
        val use = ToolUse("uid", "name", "input", "desc")
        val copy = use.copy(name = "newName")
        assertEquals("newName", copy.name)
        assertTrue(use.toString().contains("ToolUse"))
    }

    @Test
    fun `ToolResult mediaRef default is null`() {
        val res = ToolResult("uid", "out", true)
        assertEquals(null, res.mediaRef)
    }

    @Test
    fun `ToolResult copy and toString`() {
        val res = ToolResult("uid", "out", true, MediaRef("i","p","m"))
        val copy = res.copy(output = "newOut")
        assertEquals("newOut", copy.output)
        assertTrue(res.toString().contains("ToolResult"))
    }

    // ==================== Json configuration (contentPartJson) ====================

    @Test
    fun `contentPartJson has ignoreUnknownKeys true`() {
        // Should not throw when unknown keys are present
        val json = """{"type":"text","value":"ok","unknownKey":"ignore"}"""
        val result = contentPartJson.decodeFromString<ContentPart>(json)
        assertEquals(ContentPart.Text("ok"), result)
    }

    @Test
    fun `contentPartJson has encodeDefaults true`() {
        // originalFileName is nullable with default null, should be serialized as null
        val ref = MediaRef("id", "path", "mime") // originalFileName = null
        val json = contentPartJson.encodeToString(ref)
        assertTrue(json.contains("\"originalFileName\":null"))
    }

    // ==================== Serialization of standalone MediaRef, ToolUse, ToolResult ====================

    @Test
    fun `serialize MediaRef standalone`() {
        val ref = MediaRef("id", "path", "mime", "name")
        val json = contentPartJson.encodeToString(ref)
        val restored = contentPartJson.decodeFromString<MediaRef>(json)
        assertEquals(ref, restored)
    }

    @Test
    fun `serialize ToolUse standalone`() {
        val use = ToolUse("uid", "name", "input", "desc")
        val json = contentPartJson.encodeToString(use)
        val restored = contentPartJson.decodeFromString<ToolUse>(json)
        assertEquals(use, restored)
    }

    @Test
    fun `serialize ToolResult standalone`() {
        val res = ToolResult("uid", "out", true, null)
        val json = contentPartJson.encodeToString(res)
        val restored = contentPartJson.decodeFromString<ToolResult>(json)
        assertEquals(res, restored)
    }
}