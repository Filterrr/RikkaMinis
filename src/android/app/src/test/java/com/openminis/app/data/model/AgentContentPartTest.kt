package com.openminis.app.data.model

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentContentPartTest {

    @Test
    fun testText() {
        val text1 = AgentContentPart.Text("hello")
        val text2 = AgentContentPart.Text("hello")
        val text3 = AgentContentPart.Text("world")

        assertEquals("hello", text1.text)
        assertEquals(text1, text2)
        assertEquals(text1.hashCode(), text2.hashCode())
        assertNotEquals(text1, text3)
    }

    @Test
    fun testToolUse() {
        val json1 = JSONObject().put("key", "value1")
        val json2 = JSONObject().put("key", "value1")
        val json3 = JSONObject().put("key", "value2")

        val toolUse1 = AgentContentPart.ToolUse("id1", "tool1", json1)
        val toolUse2 = AgentContentPart.ToolUse("id1", "tool1", json2)
        val toolUse3 = AgentContentPart.ToolUse("id2", "tool1", json1)

        assertEquals("id1", toolUse1.id)
        assertEquals("tool1", toolUse1.name)
        assertEquals(json1, toolUse1.input)

        assertEquals(toolUse1, toolUse2)
        assertEquals(toolUse1.hashCode(), toolUse2.hashCode())
        assertNotEquals(toolUse1, toolUse3)
    }

    @Test
    fun testToolResultEqualsAndHashCode() {
        val imageData = byteArrayOf(1, 2, 3)
        val imageDataSame = byteArrayOf(1, 2, 3)
        val imageDataDiff = byteArrayOf(4, 5, 6)

        val result1 = AgentContentPart.ToolResult(
            id = "id1",
            name = "tool1",
            content = "content1",
            isError = false,
            imageData = imageData,
            imageMimeType = "image/png",
            imageLinuxPath = "/path/to/image"
        )
        val result2 = AgentContentPart.ToolResult(
            id = "id1",
            name = "tool1",
            content = "content1",
            isError = false,
            imageData = imageDataSame,
            imageMimeType = "image/png",
            imageLinuxPath = "/path/to/image"
        )
        val result3 = AgentContentPart.ToolResult(
            id = "id2",
            name = "tool1",
            content = "content1",
            isError = false,
            imageData = imageDataSame,
            imageMimeType = "image/png",
            imageLinuxPath = "/path/to/image"
        )

        // Test properties
        assertEquals("id1", result1.id)
        assertEquals("tool1", result1.name)
        assertEquals("content1", result1.content)
        assertFalse(result1.isError)
        assertTrue(result1.imageData!!.contentEquals(imageData))
        assertEquals("image/png", result1.imageMimeType)
        assertEquals("/path/to/image", result1.imageLinuxPath)

        // Test equals
        assertEquals(result1, result1) // same instance
        assertEquals(result1, result2) // same properties
        assertNotEquals(result1, result3) // different id
        assertNotEquals(result1, Any()) // different type

        assertNotEquals(result1, result1.copy(name = "tool2"))
        assertNotEquals(result1, result1.copy(content = "content2"))
        assertNotEquals(result1, result1.copy(isError = true))
        assertNotEquals(result1, result1.copy(imageData = imageDataDiff))
        assertNotEquals(result1, result1.copy(imageMimeType = "image/jpeg"))
        assertNotEquals(result1, result1.copy(imageLinuxPath = "/new/path"))
        assertNotEquals(result1, result1.copy(imageData = null))

        // Test hashCode
        assertEquals(result1.hashCode(), result2.hashCode())
        assertNotEquals(result1.hashCode(), result3.hashCode())
    }

    @Test
    fun testToolResultDefaultValues() {
        val result = AgentContentPart.ToolResult(
            id = "id1",
            name = "tool1",
            content = "content1"
        )
        assertFalse(result.isError)
        assertNull(result.imageData)
        assertNull(result.imageMimeType)
        assertNull(result.imageLinuxPath)
    }

    @Test
    fun testImageDataEqualsAndHashCode() {
        val data1 = byteArrayOf(1, 2, 3)
        val data2 = byteArrayOf(1, 2, 3)
        val data3 = byteArrayOf(4, 5, 6)

        val image1 = AgentContentPart.ImageData(
            data = data1,
            mimeType = "image/png",
            linuxPath = "/path/to/image"
        )
        val image2 = AgentContentPart.ImageData(
            data = data2,
            mimeType = "image/png",
            linuxPath = "/path/to/image"
        )
        val image3 = AgentContentPart.ImageData(
            data = data3,
            mimeType = "image/png",
            linuxPath = "/path/to/image"
        )

        // Test properties
        assertTrue(image1.data.contentEquals(data1))
        assertEquals("image/png", image1.mimeType)
        assertEquals("/path/to/image", image1.linuxPath)

        // Test equals
        assertEquals(image1, image1) // same instance
        assertEquals(image1, image2) // same properties
        assertNotEquals(image1, image3) // different data
        assertNotEquals(image1, Any()) // different type

        assertNotEquals(image1, image1.copy(mimeType = "image/jpeg"))
        assertNotEquals(image1, image1.copy(linuxPath = "/new/path"))
        assertNotEquals(image1, image1.copy(linuxPath = null))

        // Test hashCode
        assertEquals(image1.hashCode(), image2.hashCode())
        assertNotEquals(image1.hashCode(), image3.hashCode())
    }

    @Test
    fun testImageDataDefaultValues() {
        val image = AgentContentPart.ImageData(
            data = byteArrayOf(1, 2),
            mimeType = "image/png"
        )
        assertNull(image.linuxPath)
    }
}