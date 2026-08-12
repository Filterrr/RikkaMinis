package com.openminis.app.sandbox.offload

import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.mockito.MockedStatic
import org.mockito.Mockito

class OffloadGateTest {

    private lateinit var permissionManagerMock: MockedStatic<OffloadPermissionManager>

    @BeforeEach
    fun setUp() {
        permissionManagerMock = Mockito.mockStatic(OffloadPermissionManager::class.java)
    }

    @Test
    fun `allow with sessionId returns true when permission granted`() {
        val toolName = "testTool"
        val displayName = "Test Tool"
        val sessionId = "testSession"

        permissionManagerMock.`when`<Boolean> {
            OffloadPermissionManager.checkPermission(toolName, displayName, sessionId)
        }.thenReturn(true)

        val result = OffloadGate.allow(toolName, displayName, sessionId)

        assertTrue(result)
    }

    @Test
    fun `allow with sessionId returns false when permission denied`() {
        val toolName = "testTool"
        val displayName = "Test Tool"
        val sessionId = "testSession"

        permissionManagerMock.`when`<Boolean> {
            OffloadPermissionManager.checkPermission(toolName, displayName, sessionId)
        }.thenReturn(false)

        val result = OffloadGate.allow(toolName, displayName, sessionId)

        assertFalse(result)
    }

    @Test
    fun `allow with null sessionId uses global session id`() {
        val toolName = "testTool"
        val displayName = "Test Tool"

        permissionManagerMock.`when`<Boolean> {
            OffloadPermissionManager.checkPermission(
                toolName,
                displayName,
                OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID
            )
        }.thenReturn(true)

        val result = OffloadGate.allow(toolName, displayName, null)

        assertTrue(result)
    }

    @Test
    fun `allow with NativeOffloadRequest delegates to allow with sessionId`() {
        val toolName = "testTool"
        val displayName = "Test Tool"
        val sessionId = "requestSession"
        val request = NativeOffloadRequest(sessionId = sessionId)

        permissionManagerMock.`when`<Boolean> {
            OffloadPermissionManager.checkPermission(toolName, displayName, sessionId)
        }.thenReturn(true)

        val result = OffloadGate.allow(toolName, displayName, request)

        assertTrue(result)
    }

    @Test
    fun `enforce returns null when permission granted`() {
        val toolName = "testTool"
        val displayName = "Test Tool"
        val args = OffloadArgs()
        val request = NativeOffloadRequest(sessionId = "session")

        permissionManagerMock.`when`<Boolean> {
            OffloadPermissionManager.checkPermission(toolName, displayName, "session")
        }.thenReturn(true)

        val result = OffloadGate.enforce(toolName, displayName, args, request)

        assertNull(result)
    }

    @Test
    fun `enforce returns NativeOffloadResult with error when permission denied`() {
        val toolName = "testTool"
        val displayName = "Test Tool"
        val args = OffloadArgs()
        val request = NativeOffloadRequest(sessionId = "session")

        permissionManagerMock.`when`<Boolean> {
            OffloadPermissionManager.checkPermission(toolName, displayName, "session")
        }.thenReturn(false)

        val result = OffloadGate.enforce(toolName, displayName, args, request)

        assertNotNull(result)
        assertEquals(126, result?.exitCode)
        assertTrue(result?.output?.contains("permission_denied") == true)
        assertTrue(result?.output?.contains("Agent is not allowed to use Test Tool") == true)
    }

    @Test
    fun `enforce with null request uses global session id`() {
        val toolName = "testTool"
        val displayName = "Test Tool"
        val args = OffloadArgs()

        permissionManagerMock.`when`<Boolean> {
            OffloadPermissionManager.checkPermission(
                toolName,
                displayName,
                OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID
            )
        }.thenReturn(false)

        val result = OffloadGate.enforce(toolName, displayName, args, null)

        assertNotNull(result)
        assertEquals(126, result?.exitCode)
    }

    @Test
    fun `enforce returns null when permission granted with null request`() {
        val toolName = "testTool"
        val displayName = "Test Tool"
        val args = OffloadArgs()

        permissionManagerMock.`when`<Boolean> {
            OffloadPermissionManager.checkPermission(
                toolName,
                displayName,
                OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID
            )
        }.thenReturn(true)

        val result = OffloadGate.enforce(toolName, displayName, args, null)

        assertNull(result)
    }
}