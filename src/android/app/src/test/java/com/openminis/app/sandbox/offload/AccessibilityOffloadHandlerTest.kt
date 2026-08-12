package com.openminis.app.sandbox.offload

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.accessibility.NodeRegistry
import com.openminis.app.logging.AppLogger
import com.openminis.app.macro.MacroSystem
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertContains

@ExtendWith(MockKExtension::class)
class AccessibilityOffloadHandlerTest {

    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockMacroSystem: MacroSystem

    @MockK
    private lateinit var mockMacroStorage: MacroSystem.Storage

    @MockK
    private lateinit var mockGuard: MacroSystem.Guard

    @MockK
    private lateinit var mockAccessibilityService: MinisAccessibilityService

    @MockK
    private lateinit var mockNodeRegistry: NodeRegistry

    @MockK
    private lateinit var mockNodeInfo: AccessibilityNodeInfo

    @MockK
    private lateinit var mockBitmap: Bitmap

    @TempDir
    lateinit var tempDir: Path

    private lateinit var handler: AccessibilityOffloadHandler

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        
        mockkStatic(MinisAccessibilityService::class)
        mockkStatic(com.openminis.app.service.SessionActivityTracker::class)
        mockkStatic(OffloadGate::class)
        mockkStatic(OffloadOutput::class)
        mockkStatic(AppLogger::class)
        mockkObject(MacroSystem.Companion)

        every { MinisAccessibilityService.getInstance() } returns mockAccessibilityService
        every { mockContext.getExternalFilesDir(any()) } returns tempDir.toFile()
        every { mockContext.cacheDir } returns tempDir.toFile()
        every { mockMacroSystem.storage } returns mockMacroStorage
        every { mockMacroSystem.guard } returns mockGuard
        every { mockAccessibilityService.nodeRegistry } returns mockNodeRegistry
        every { mockNodeRegistry.put(any()) } returns "node-1"
        every { mockNodeRegistry.get(any()) } returns mockNodeInfo
        
        // Mock MacroSystem constructor
        every { MacroSystem(any()) } returns mockMacroSystem

        handler = AccessibilityOffloadHandler(mockContext)
    }

    @Test
    fun `handle with empty args returns help`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertContains(result.output, "android-a11y-cli")
    }

    @Test
    fun `handle with help flag returns help`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "--help"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertContains(result.output, "android-a11y-cli")
    }

    @Test
    fun `handle with unknown command returns error`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "unknown"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertContains(result.output, "unknown subcommand")
    }

    @Test
    fun `handle with version returns version`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "--version"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertContains(result.output, "android-a11y-cli 0.1")
    }

    @Test
    fun `handle with permission denied returns error`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "ui", "dump"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns false
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        val result = handler.handle(request)
        assertEquals(1, result.exitCode)
        assertContains(result.output, "PERMISSION_DENIED")
    }

    @Test
    fun `handle service status returns running info`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "service", "status"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output.trim())
        assertTrue(json.getJSONObject("data").getBoolean("running"))
        assertEquals("com.openminis.app.accessibility.MinisAccessibilityService", 
            json.getJSONObject("data").getString("serviceName"))
    }

    @Test
    fun `handle service ping returns running`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "service", "ping"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertContains(result.output, "Accessibility service is running")
    }

    @Test
    fun `handle service ping returns not running`() {
        every { MinisAccessibilityService.getInstance() } returns null
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "service", "ping"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        
        val result = handler.handle(request)
        assertEquals(77, result.exitCode)
        assertContains(result.output, "not running")
    }

    @Test
    fun `handle service with unknown action returns help`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "service", "unknown"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertContains(result.output, "service status | ping")
    }

    @Test
    fun `handle ui dump returns nodes`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "ui", "dump"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        every { mockAccessibilityService.rootNodes() } returns listOf(mockNodeInfo)
        every { mockNodeInfo.childCount } returns 0
        every { mockNodeInfo.isVisibleToUser } returns true
        every { mockNodeInfo.text } returns "test"
        every { mockNodeInfo.contentDescription } returns null
        every { mockNodeInfo.className } returns "android.widget.TextView"
        every { mockNodeInfo.viewIdResourceName } returns null
        every { mockNodeInfo.packageName } returns "com.test"
        every { mockNodeInfo.isClickable } returns false
        every { mockNodeInfo.isLongClickable } returns false
        every { mockNodeInfo.isScrollable } returns false
        every { mockNodeInfo.isEditable } returns false
        every { mockNodeInfo.isCheckable } returns false
        every { mockNodeInfo.isChecked } returns false
        every { mockNodeInfo.isFocusable } returns false
        every { mockNodeInfo.isFocused } returns false
        every { mockNodeInfo.isSelected } returns false
        every { mockNodeInfo.isEnabled } returns true
        every { mockNodeInfo.getBoundsInScreen(any()) } answers {
            val rect = firstArg<Rect>()
            rect.left = 0; rect.top = 0; rect.right = 100; rect.bottom = 100
        }
        
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output.trim())
        assertTrue(json.getJSONObject("data").getInt("count") >= 0)
    }

    @Test
    fun `handle ui find with text returns matches`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "ui", "find", "--text", "test"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        every { mockAccessibilityService.rootNodes() } returns listOf(mockNodeInfo)
        every { mockNodeInfo.childCount } returns 0
        every { mockNodeInfo.text } returns "test"
        every { mockNodeInfo.contentDescription } returns null
        every { mockNodeInfo.className } returns "android.widget.TextView"
        every { mockNodeInfo.viewIdResourceName } returns null
        every { mockNodeInfo.packageName } returns "com.test"
        every { mockNodeInfo.isClickable } returns false
        every { mockNodeInfo.isLongClickable } returns false
        every { mockNodeInfo.isScrollable } returns false
        every { mockNodeInfo.isEditable } returns false
        every { mockNodeInfo.isCheckable } returns false
        every { mockNodeInfo.isChecked } returns false
        every { mockNodeInfo.isFocusable } returns false
        every { mockNodeInfo.isFocused } returns false
        every { mockNodeInfo.isSelected } returns false
        every { mockNodeInfo.isEnabled } returns true
        every { mockNodeInfo.getBoundsInScreen(any()) } answers {
            val rect = firstArg<Rect>()
            rect.left = 0; rect.top = 0; rect.right = 100; rect.bottom = 100
        }
        
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output.trim())
        assertTrue(json.getJSONObject("data").getInt("count") >= 0)
    }

    @Test
    fun `handle ui info returns package info`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "ui", "info"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        every { mockAccessibilityService.foregroundPackage() } returns ("com.test" to "MainActivity")
        every { mockAccessibilityService.windowInfos() } returns listOf(emptyMap())
        
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output.trim())
        assertEquals("com.test", json.getJSONObject("data").getString("packageName"))
        assertEquals("MainActivity", json.getJSONObject("data").getString("activityName"))
    }

    @Test
    fun `handle ui node with missing id returns error`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "ui", "node"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertContains(result.output, "missing <nodeId>")
    }

    @Test
    fun `handle ui node with invalid id returns error`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "ui", "node", "invalid-id"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        every { mockNodeRegistry.get(any()) } returns null
        
        val result = handler.handle(request)
        assertEquals(1, result.exitCode)
        assertContains(result.output, "NODE_NOT_FOUND")
    }

    @Test
    fun `handle ui screenshot on older android returns error`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "ui", "screenshot"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        mockkStatic(Build::class)
        every { Build.VERSION.SDK_INT } returns Build.VERSION_CODES.Q
        
        val result = handler.handle(request)
        assertEquals(1, result.exitCode)
        assertContains(result.output, "NOT_SUPPORTED")
    }

    @Test
    fun `handle tap node with valid node returns success`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "tap", "node", "node-1"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        every { mockNodeInfo.isClickable } returns true
        every { mockNodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output.trim())
        assertEquals("click", json.getJSONObject("data").getString("action"))
    }

    @Test
    fun `handle tap node with non-clickable node uses gesture`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "tap", "node", "node-1"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        every { mockNodeInfo.isClickable } returns false
        every { mockNodeInfo.getBoundsInScreen(any()) } answers {
            val rect = firstArg<Rect>()
            rect.left = 0; rect.top = 0; rect.right = 100; rect.bottom = 100
        }
        every { mockAccessibilityService.dispatchSimpleGesture(any(), any(), any()) } returns true
        
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output.trim())
        assertEquals("tap", json.getJSONObject("data").getString("action"))
    }

    @Test
    fun `handle tap xy returns success`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-a11y-cli", "tap", "xy", "100", "200"),
            env = emptyMap(),
            cwd = "",
            stdin = ""
        )
        every { OffloadGate.allow(any(), any(), any()) } returns true
        every { com.openminis.app.service.SessionActivityTracker.updateToolStatus(any()) } just Runs
        every { mockAccessibilityService.dispatchSimpleGesture(any(), any(), any()) } returns true
        
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output.trim())
        assertEquals(100, json.getJSONObject("data").getInt("x"))
        assertEquals(200, json.getJSONObject("data").getInt("y"))
    }

    @Test
    fun `handle tap text with match returns success`() {
        val request = NativeOffload