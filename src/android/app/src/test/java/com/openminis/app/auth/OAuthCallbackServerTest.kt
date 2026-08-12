package com.openminis.app.auth

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.jupiter.api.Test
import org.junit.Rule
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthCallbackServerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `server should start and bind to port`() {
        var receivedCode: String? = null
        var receivedState: String? = null
        
        val server = OAuthCallbackServer(
            port = 0,
            onCode = { code, state ->
                receivedCode = code
                receivedState = state
            }
        )
        
        server.start()
        Thread.sleep(100)
        
        assertTrue(server.boundPort > 0)
        assertTrue(server.boundPort in 0..65535)
        
        server.stop()
    }

    @Test
    fun `server should start with fallback ports`() {
        var receivedCode: String? = null
        
        val server = OAuthCallbackServer(
            port = 0,
            fallbackPorts = listOf(0, 0),
            onCode = { code, _ ->
                receivedCode = code
            }
        )
        
        server.start()
        Thread.sleep(100)
        
        assertTrue(server.boundPort > 0)
        
        server.stop()
    }

    @Test
    fun `server should handle external cancel callback`() {
        var cancelCalled = false
        
        val server = OAuthCallbackServer(
            port = 0,
            onCode = { _, _ -> }
        )
        
        server.start()
        Thread.sleep(100)
        
        server.onExternalCancel = { cancelCalled = true }
        server.stop()
        
        assertTrue(cancelCalled)
    }

    @Test
    fun `server should handle code and state parameters`() {
        var receivedCode: String? = null
        var receivedState: String? = null
        
        val server = OAuthCallbackServer(
            port = 0,
            onCode = { code, state ->
                receivedCode = code
                receivedState = state
            }
        )
        
        server.start()
        Thread.sleep(100)
        
        // Simulate a callback with code and state
        val thread = Thread {
            try {
                val socket = java.net.Socket("localhost", server.boundPort)
                val request = "GET /callback?code=test_code&state=test_state HTTP/1.1\r\nHost: localhost\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray())
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        thread.start()
        thread.join()
        
        Thread.sleep(500)
        
        assertEquals("test_code", receivedCode)
        assertEquals("test_state", receivedState)
        
        server.stop()
    }

    @Test
    fun `server should handle OPTIONS request`() {
        var receivedCode: String? = null
        
        val server = OAuthCallbackServer(
            port = 0,
            onCode = { code, _ ->
                receivedCode = code
            }
        )
        
        server.start()
        Thread.sleep(100)
        
        // Simulate OPTIONS request
        val thread = Thread {
            try {
                val socket = java.net.Socket("localhost", server.boundPort)
                val request = "OPTIONS /callback HTTP/1.1\r\nHost: localhost\r\nOrigin: https://auth.x.ai\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray())
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        thread.start()
        thread.join()
        
        Thread.sleep(500)
        
        assertNull(receivedCode)
        
        server.stop()
    }

    @Test
    fun `server should handle invalid request`() {
        var receivedCode: String? = null
        
        val server = OAuthCallbackServer(
            port = 0,
            onCode = { code, _ ->
                receivedCode = code
            }
        )
        
        server.start()
        Thread.sleep(100)
        
        // Simulate invalid request
        val thread = Thread {
            try {
                val socket = java.net.Socket("localhost", server.boundPort)
                val request = "INVALID REQUEST\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray())
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        thread.start()
        thread.join()
        
        Thread.sleep(500)
        
        assertNull(receivedCode)
        
        server.stop()
    }

    @Test
    fun `server should handle multiple requests`() {
        var receivedCodes = mutableListOf<String>()
        
        val server = OAuthCallbackServer(
            port = 0,
            onCode = { code, _ ->
                receivedCodes.add(code)
            }
        )
        
        server.start()
        Thread.sleep(100)
        
        // Send multiple requests
        for (i in 1..3) {
            val thread = Thread {
                try {
                    val socket = java.net.Socket("localhost", server.boundPort)
                    val request = "GET /callback?code=code$i HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    socket.getOutputStream().write(request.toByteArray())
                    socket.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
            thread.start()
            thread.join()
        }
        
        Thread.sleep(500)
        
        assertTrue(receivedCodes.size >= 1)
        
        server.stop()
    }

    @Test
    fun `server should handle port conflict gracefully`() {
        var receivedCode: String? = null
        
        // Create first server to occupy a port
        val firstServer = OAuthCallbackServer(
            port = 0,
            onCode = { _, _ -> }
        )
        firstServer.start()
        Thread.sleep(100)
        
        val occupiedPort = firstServer.boundPort
        
        // Try to create second server on same port
        val secondServer = OAuthCallbackServer(
            port = occupiedPort,
            fallbackPorts = listOf(0),
            onCode = { code, _ ->
                receivedCode = code
            }
        )
        
        secondServer.start()
        Thread.sleep(100)
        
        assertTrue(secondServer.boundPort != occupiedPort)
        
        firstServer.stop()
        secondServer.stop()
    }

    @Test
    fun `server should handle null state parameter`() {
        var receivedCode: String? = null
        var receivedState: String? = null
        
        val server = OAuthCallbackServer(
            port = 0,
            onCode = { code, state ->
                receivedCode = code
                receivedState = state
            }
        )
        
        server.start()
        Thread.sleep(100)
        
        // Simulate callback without state
        val thread = Thread {
            try {
                val socket = java.net.Socket("localhost", server.boundPort)
                val request = "GET /callback?code=test_code HTTP/1.1\r\nHost: localhost\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray())
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        thread.start()
        thread.join()
        
        Thread.sleep(500)
        
        assertEquals("test_code", receivedCode)
        assertNull(receivedState)
        
        server.stop()
    }

    @Test
    fun `server should handle URL encoded parameters`() {
        var receivedCode: String? = null
        var receivedState: String? = null
        
        val server = OAuthCallbackServer(
            port = 0,
            onCode = { code, state ->
                receivedCode = code
                receivedState = state
            }
        )
        
        server.start()
        Thread.sleep(100)
        
        // Simulate callback with URL encoded parameters
        val thread = Thread {
            try {
                val socket = java.net.Socket("localhost", server.boundPort)
                val request = "GET /callback?code=test%20code&state=test%20state HTTP/1.1\r\nHost: localhost\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray())
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        thread.start()
        thread.join()
        
        Thread.sleep(500)
        
        assertEquals("test code", receivedCode)
        assertEquals("test state", receivedState)
        
        server.stop()
    }
}