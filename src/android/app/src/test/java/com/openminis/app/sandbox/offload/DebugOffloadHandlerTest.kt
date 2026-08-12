package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.sandbox.NativeOffloadRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket

class DebugOffloadHandlerTest {

    @TempDir
    lateinit var tempDir: File

    private fun createHandler(context: Context = mockContext()): DebugOffloadHandler {
        return DebugOffloadHandler(context)
    }

    private fun mockContext(): Context {
        // Since Context is an Android class, we need to mock it or use a test double
        // For unit tests, we can use a simple mock
        return org.mockito.Mockito.mock(Context::class.java)
    }

    @Test
    fun `handle with no args returns exit code 2 and help`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug"))
        
        val result = handler.handle(request)
        
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("Usage:"))
    }

    @Test
    fun `handle with help flag returns exit code 0 and help`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "--help"))
        
        val result = handler.handle(request)
        
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("Usage:"))
    }

    @Test
    fun `handle with help flag and positional args returns exit code 0 and help`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "discover", "--help"))
        
        val result = handler.handle(request)
        
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("Usage:"))
    }

    @Test
    fun `handle with no subcommand returns exit code 2 and help`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "--compact"))
        
        val result = handler.handle(request)
        
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("Usage:"))
    }

    @Test
    fun `handle with unknown subcommand returns exit code 2 and error`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "unknownCmd"))
        
        val result = handler.handle(request)
        
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("unknown subcommand"))
    }

    @Test
    fun `handle with discover subcommand and no server returns unreachable error`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "discover"))
        
        val result = handler.handle(request)
        
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("debug_server_unreachable"))
    }

    @Test
    fun `handle with appInfo subcommand and no server returns unreachable error`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "appInfo"))
        
        val result = handler.handle(request)
        
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("debug_server_unreachable"))
    }

    @Test
    fun `handle with screenshot subcommand and no server returns unreachable error`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "screenshot", "--scale", "2.0"))
        
        val result = handler.handle(request)
        
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("debug_server_unreachable"))
    }

    @Test
    fun `handle with ls subcommand and no server returns unreachable error`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "ls", "/sdcard", "--recursive", "--maxDepth", "2"))
        
        val result = handler.handle(request)
        
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("debug_server_unreachable"))
    }

    @Test
    fun `handle with read subcommand missing path returns exit code 2`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "read"))
        
        val result = handler.handle(request)
        
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("missing <path>"))
    }

    @Test
    fun `handle with read subcommand and no server returns unreachable error`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "read", "/etc/passwd", "--offset", "0", "--limit", "100", "--base64"))
        
        val result = handler.handle(request)
        
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("debug_server_unreachable"))
    }

    @Test
    fun `handle with write subcommand missing path returns exit code 2`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "write"))
        
        val result = handler.handle(request)
        
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("missing <path>"))
    }

    @Test
    fun `handle with write subcommand missing content returns exit code 2`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "write", "/tmp/test.txt"))
        
        val result = handler.handle(request)
        
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("missing --content"))
    }

    @Test
    fun `handle with write subcommand and no server returns unreachable error`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "write", "/tmp/test.txt", "--content", "hello", "--encoding", "utf8"))
        
        val result = handler.handle(request)
        
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("debug_server_unreachable"))
    }

    @Test
    fun `handle with exec subcommand missing command returns exit code 2`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "exec"))
        
        val result = handler.handle(request)
        
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("missing <command...>"))
    }

    @Test
    fun `handle with exec subcommand and no server returns unreachable error`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "exec", "uname", "-a"))
        
        val result = handler.handle(request)
        
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("debug_server_unreachable"))
    }

    @Test
    fun `handle with shizuku subcommand and no server returns unreachable error`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "shizuku", "cmd", "arg1"))
        
        val result = handler.handle(request)
        
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("debug_server_unreachable"))
    }

    @Test
    fun `handle with model-use subcommand and no server returns unreachable error`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "model-use", "cmd", "arg1"))
        
        val result = handler.handle(request)
        
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("debug_server_unreachable"))
    }

    @Test
    fun `handle with call subcommand missing method returns exit code 2`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "call"))
        
        val result = handler.handle(request)
        
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("missing <method>"))
    }

    @Test
    fun `handle with call subcommand and invalid params returns exit code 2`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "call", "test.method", "--params", "invalid-json"))
        
        val result = handler.handle(request)
        
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("--params must be a JSON object"))
    }

    @Test
    fun `handle with call subcommand and no server returns unreachable error`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "call", "test.method", "--params", "{}"))
        
        val result = handler.handle(request)
        
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("debug_server_unreachable"))
    }

    @Test
    fun `handle with call subcommand and empty params returns unreachable error`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "call", "test.method"))
        
        val result = handler.handle(request)
        
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("debug_server_unreachable"))
    }

    @Test
    fun `handle with shizuku subcommand and invalid args returns exit code 2`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "shizuku", "--args", "not-a-json-array"))
        
        val result = handler.handle(request)
        
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("--args must be a JSON array"))
    }

    @Test
    fun `handle with model-use subcommand and invalid args returns exit code 2`() {
        val handler = createHandler()
        val request = NativeOffloadRequest(arrayOf("minis-debug", "model-use", "--args", "not-a-json-array"))
        
        val result = handler.handle(request)
        
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("--args must be a JSON array"))
    }

    @Test
    fun `handle with successful server response returns exit code 0`() {
        // Start a mock server
        val serverSocket = ServerSocket(5321)
        Thread {
            val socket = serverSocket.accept()
            val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"
            socket.getOutputStream().write(response.toByteArray())
            socket.close()
        }.start()

        try {
            val handler = createHandler()
            val request = NativeOffloadRequest(arrayOf("minis-debug", "discover"))
            
            val result = handler.handle(request)
            
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("{}"))
        } finally {
            serverSocket.close()
        }
    }

    @Test
    fun `handle with error response returns exit code 1`() {
        // Start a mock server
        val serverSocket = ServerSocket(5321)
        Thread {
            val socket = serverSocket.accept()
            val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 21\r\n\r\n{\"error\":\"test error\"}"
            socket.getOutputStream().write(response.toByteArray())
            socket.close()
        }.start()

        try {
            val handler = createHandler()
            val request = NativeOffloadRequest(arrayOf("minis-debug", "discover"))
            
            val result = handler.handle(request)
            
            assertEquals(1, result.exitCode)
            assertTrue(result.output.contains("test error"))
        } finally {
            serverSocket.close()
        }
    }

    @Test
    fun `handle with compact flag returns compact output`() {
        // Start a mock server
        val serverSocket = ServerSocket(5321)
        Thread {
            val socket = serverSocket.accept()
            val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"
            socket.getOutputStream().write(response.toByteArray())
            socket.close()
        }.start()

        try {
            val handler = createHandler()
            val request = NativeOffloadRequest(arrayOf("minis-debug", "discover", "--compact"))
            
            val result = handler.handle(request)
            
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("{}"))
        } finally {
            serverSocket.close()
        }
    }

    @Test
    fun `handle with quiet flag returns quiet output`() {
        // Start a mock server
        val serverSocket = ServerSocket(5321)
        Thread {
            val socket = serverSocket.accept()
            val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"
            socket.getOutputStream().write(response.toByteArray())
            socket.close()
        }.start()

        try {
            val handler = createHandler()
            val request = NativeOffloadRequest(arrayOf("minis-debug", "discover", "-q"))
            
            val result = handler.handle(request)
            
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("{}"))
        } finally {
            serverSocket.close()
        }
    }
}