package com.openminis.app.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BrowserActionInputTest {

    @Test
    fun `parse returns null for invalid JSON`() {
        val result = BrowserActionInput.parse("invalid json")
        assertNull(result)
    }

    @Test
    fun `parse returns null for missing action`() {
        val json = JSONObject().toString()
        val result = BrowserActionInput.parse(json)
        assertNull(result)
    }

    @Test
    fun `parse returns null for unknown action`() {
        val json = JSONObject().put("action", "UNKNOWN_ACTION").toString()
        val result = BrowserActionInput.parse(json)
        assertNull(result)
    }

    @Test
    fun `parse correctly parses all fields`() {
        val cookieObj = JSONObject().put("name", "test").put("value", 123)
        val cookiesArray = JSONArray().put(cookieObj)
        
        val json = JSONObject()
            .put("action", "NAVIGATE")
            .put("url", "https://example.com")
            .put("selector", "#id")
            .put("text", "hello")
            .put("coordinate_x", 10)
            .put("coordinate_y", 20)
            .put("direction", "UP")
            .put("amount", 5)
            .put("script", "console.log('test')")
            .put("user_agent", "MOBILE")
            .put("max_depth", 3)
            .put("tab_id", 1)
            .put("viewport_width", 800)
            .put("viewport_height", 600)
            .put("reset", true)
            .put("keywords", JSONArray().put("a").put("b"))
            .put("fuzzy", true)
            .put("item_selector", ".item")
            .put("scroll_count", 2)
            .put("timeout", 5000)
            .put("full_page", true)
            .put("cookies", cookiesArray)
            .toString()

        val result = BrowserActionInput.parse(json)
        assertNotNull(result)
        result!!
        assertEquals("https://example.com", result.url)
        assertEquals("#id", result.selector)
        assertEquals("hello", result.text)
        assertEquals(10, result.coordinateX)
        assertEquals(20, result.coordinateY)
        assertNotNull(result.direction)
        assertEquals(5, result.amount)
        assertEquals("console.log('test')", result.script)
        assertNotNull(result.userAgent)
        assertEquals(3, result.maxDepth)
        assertEquals(1, result.tabId)
        assertEquals(800, result.viewportWidth)
        assertEquals(600, result.viewportHeight)
        assertTrue(result.reset)
        assertEquals(listOf("a", "b"), result.keywords)
        assertTrue(result.fuzzy)
        assertEquals(".item", result.itemSelector)
        assertEquals(2, result.scrollCount)
        assertEquals(5000, result.timeoutMs)
        assertTrue(result.fullPage)
        assertNotNull(result.cookies)
        assertEquals(1, result.cookies!!.size)
        assertEquals("test", result.cookies!![0]["name"])
        assertEquals(123, result.cookies!![0]["value"])
    }

    @Test
    fun `parse handles cookies as string`() {
        val cookieObj = JSONObject().put("name", "test").put("value", 123)
        val cookiesArray = JSONArray().put(cookieObj)
        
        val json = JSONObject()
            .put("action", "NAVIGATE")
            .put("cookies", cookiesArray.toString())
            .toString()

        val result = BrowserActionInput.parse(json)
        assertNotNull(result)
        result!!
        assertNotNull(result.cookies)
        assertEquals(1, result.cookies!!.size)
        assertEquals("test", result.cookies!![0]["name"])
        assertEquals(123, result.cookies!![0]["value"])
    }

    @Test
    fun `parse handles empty strings as null for optional string fields`() {
        val json = JSONObject()
            .put("action", "NAVIGATE")
            .put("url", "")
            .put("selector", "")
            .put("text", "")
            .put("script", "")
            .put("item_selector", "")
            .toString()

        val result = BrowserActionInput.parse(json)
        assertNotNull(result)
        result!!
        assertNull(result.url)
        assertNull(result.selector)
        assertNull(result.text)
        assertNull(result.script)
        assertNull(result.itemSelector)
    }

    @Test
    fun `parse handles empty keywords array as null`() {
        val json = JSONObject()
            .put("action", "NAVIGATE")
            .put("keywords", JSONArray())
            .toString()

        val result = BrowserActionInput.parse(json)
        assertNotNull(result)
        result!!
        assertNull(result.keywords)
    }
    
    @Test
    fun `parse handles empty cookies array as null`() {
        val json = JSONObject()
            .put("action", "NAVIGATE")
            .put("cookies", JSONArray())
            .toString()

        val result = BrowserActionInput.parse(json)
        assertNotNull(result)
        result!!
        assertNull(result.cookies)
    }
}