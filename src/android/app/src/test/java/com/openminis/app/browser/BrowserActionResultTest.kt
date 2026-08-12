package com.openminis.app.browser

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BrowserActionResultTest {

    @Test
    fun `default constructor should set success true and all other fields null`() {
        val result = BrowserActionResult(text = "test")
        assertEquals("test", result.text)
        assertTrue(result.success)
        assertNull(result.base64Image)
        assertNull(result.imageFilePath)
        assertNull(result.fetchedFileData)
        assertNull(result.fetchedFileName)
        assertNull(result.pageURL)
        assertNull(result.tabId)
    }

    @Test
    fun `constructor with all parameters should assign correctly`() {
        val data = byteArrayOf(1, 2, 3)
        val result = BrowserActionResult(
            text = "hello",
            success = false,
            base64Image = "base64",
            imageFilePath = "/path",
            fetchedFileData = data,
            fetchedFileName = "file.txt",
            pageURL = "http://example.com",
            tabId = 42
        )
        assertEquals("hello", result.text)
        assertFalse(result.success)
        assertEquals("base64", result.base64Image)
        assertEquals("/path", result.imageFilePath)
        assertSame(data, result.fetchedFileData)
        assertEquals("file.txt", result.fetchedFileName)
        assertEquals("http://example.com", result.pageURL)
        assertEquals(42, result.tabId)
    }

    @Test
    fun `error companion object should create failed result with error message`() {
        val result = BrowserActionResult.error("Something went wrong")
        assertFalse(result.success)
        assertEquals("Error: Something went wrong", result.text)
        assertNull(result.base64Image)
        assertNull(result.imageFilePath)
        assertNull(result.fetchedFileData)
        assertNull(result.fetchedFileName)
        assertNull(result.pageURL)
        assertNull(result.tabId)
    }

    @Test
    fun `copy should create a new instance with modified fields`() {
        val original = BrowserActionResult(text = "original", success = true, tabId = 1)
        val copied = original.copy(text = "modified", success = false)
        assertEquals("modified", copied.text)
        assertFalse(copied.success)
        assertNull(copied.tabId)
        assertEquals("original", original.text)
        assertTrue(original.success)
        assertEquals(1, original.tabId)
    }

    @Test
    fun `componentN functions should return fields in order`() {
        val data = byteArrayOf(10, 20)
        val result = BrowserActionResult(
            "text",
            success = false,
            base64Image = "img",
            imageFilePath = "path",
            fetchedFileData = data,
            fetchedFileName = "name",
            pageURL = "url",
            tabId = 99
        )
        val (text, success, base64Image, imageFilePath, fetchedFileData, fetchedFileName, pageURL, tabId) = result
        assertEquals("text", text)
        assertFalse(success)
        assertEquals("img", base64Image)
        assertEquals("path", imageFilePath)
        assertSame(data, fetchedFileData)
        assertEquals("name", fetchedFileName)
        assertEquals("url", pageURL)
        assertEquals(99, tabId)
    }

    @Test
    fun `equals should return true for same field values`() {
        val data = byteArrayOf(1, 2)
        val a = BrowserActionResult(
            text = "a",
            success = false,
            base64Image = "b64",
            imageFilePath = "p",
            fetchedFileData = data,
            fetchedFileName = "f",
            pageURL = "u",
            tabId = 1
        )
        val b = BrowserActionResult(
            text = "a",
            success = false,
            base64Image = "b64",
            imageFilePath = "p",
            fetchedFileData = data,
            fetchedFileName = "f",
            pageURL = "u",
            tabId = 1
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals should return false for different ByteArray reference`() {
        val data1 = byteArrayOf(1, 2)
        val data2 = byteArrayOf(1, 2)
        val a = BrowserActionResult(fetchedFileData = data1)
        val b = BrowserActionResult(fetchedFileData = data2)
        assertNotEquals(a, b)
    }

    @Test
    fun `toString should contain all fields`() {
        val result = BrowserActionResult(
            text = "test",
            success = false,
            base64Image = "img",
            imageFilePath = "path",
            fetchedFileData = byteArrayOf(1, 2),
            fetchedFileName = "file",
            pageURL = "url",
            tabId = 10
        )
        val str = result.toString()
        assertTrue(str.contains("text=test"))
        assertTrue(str.contains("success=false"))
        assertTrue(str.contains("base64Image=img"))
        assertTrue(str.contains("imageFilePath=path"))
        assertTrue(str.contains("fetchedFileData="))
        assertTrue(str.contains("fetchedFileName=file"))
        assertTrue(str.contains("pageURL=url"))
        assertTrue(str.contains("tabId=10"))
    }
}