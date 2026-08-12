package com.openminis.app.sandbox.offload

import com.openminis.app.MinisApp
import com.openminis.app.browser.BrowserActionResult
import com.openminis.app.sandbox.NativeOffloadRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BrowserUseOffloadHandlerTest {

    private lateinit var app: MinisApp
    private lateinit var handler: BrowserUseOffloadHandler

    @BeforeEach
    fun setUp() {
        app = mockk(relaxed = true)
        handler = BrowserUseOffloadHandler(app)
        mockkObject(com.openminis.app.service.SessionActivityTracker)
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(com.openminis.app.service.SessionActivityTracker)
    }

    private fun mockSuccessResult(resultText: String = "Success"): BrowserActionResult {
        return mockk {
            every { success } returns true
            every { text } returns resultText
            every { pageURL } returns "https://example.com"
            every { base64Image } returns null
            every { imageFilePath } returns null
            every { fetchedFileName } returns null
            every { fetchedFileData } returns null
        }
    }

    @Test
    fun `handle with no args returns help`() {
        val request = NativeOffloadRequest(listOf("minis-browser-use"))
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("minis-browser-use - Drive the in-app WebView from the shell"))
    }

    @Test
    fun `handle with help flag returns help`() {
        val request = NativeOffloadRequest(listOf("minis-browser-use", "--help"))
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("USAGE:"))
    }

    @Test
    fun `handle with invalid json returns error`() {
        val request = NativeOffloadRequest(listOf("minis-browser-use", "--json", "invalid_json"))
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("invalid_args"))
    }

    @Test
    fun `handle with missing action returns error`() {
        val request = NativeOffloadRequest(listOf("minis-browser-use", "--url", "https://example.com"))
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("invalid_args"))
    }

    @Test
    fun `handle with valid action executes and returns success`() {
        coEvery { app.sharedBrowserTabPool.execute(any(), any()) } returns mockSuccessResult()

        val request = NativeOffloadRequest(listOf("minis-browser-use", "navigate", "--url", "https://example.com"))
        val result = handler.handle(request)

        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output.trim())
        assertTrue(json.getBoolean("ok"))
        assertEquals("navigate", json.getString("action"))
        assertEquals("Success", json.getJSONObject("data").getString("text"))
    }

    @Test
    fun `handle with failed action returns error`() {
        val actionResult = mockk<BrowserActionResult> {
            every { success } returns false
            every { text } returns "Failed to navigate"
            every { pageURL } returns null
            every { base64Image } returns null
            every { imageFilePath } returns null
            every { fetchedFileName } returns null
            every { fetchedFileData } returns null
        }
        coEvery { app.sharedBrowserTabPool.execute(any(), any()) } returns actionResult

        val request = NativeOffloadRequest(listOf("minis-browser-use", "navigate", "--url", "https://example.com"))
        val result = handler.handle(request)

        assertEquals(1, result.exitCode)
        val json = JSONObject(result.output.trim())
        assertEquals(false, json.getBoolean("ok"))
        assertEquals("internal_error", json.getJSONObject("error").getString("code"))
    }

    @Test
    fun `handle with quiet flag returns only data`() {
        coEvery { app.sharedBrowserTabPool.execute(any(), any()) } returns mockSuccessResult("Quiet Success")

        val request = NativeOffloadRequest(listOf("minis-browser-use", "navigate", "--url", "https://example.com", "-q"))
        val result = handler.handle(request)

        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output.trim())
        assertEquals("Quiet Success", json.getString("text"))
    }
}