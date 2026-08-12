package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.sandbox.NativeOffloadRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.json.JSONObject
import org.json.JSONArray

class ShizukuOffloadHandlerTest {

    private fun createHandler(): ShizukuOffloadHandler {
        val context = mockContext()
        return ShizukuOffloadHandler(context)
    }

    private fun mockContext(): Context {
        return org.mockito.Mockito.mock(Context::class.java)
    }

    private fun createRequest(vararg argv: String): NativeOffloadRequest {
        return NativeOffloadRequest(argv.toList())
    }

    @Test
    fun `handle with empty argv returns help`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("Usage:"))
    }

    @Test
    fun `handle with help flag returns help`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "--help")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("Usage:"))
    }

    @Test
    fun `handle with version flag returns version`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "--version")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("android-shizuku-cli 1.0"))
    }

    @Test
    fun `handle with service status returns service info`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "service", "status")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("state"))
    }

    @Test
    fun `handle with service ping returns ping result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "service", "ping")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("OK") || result.output.contains("FAIL"))
    }

    @Test
    fun `handle with service help returns service help`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "service", "help")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("service"))
    }

    @Test
    fun `handle with unknown service subcommand returns error`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "service", "unknown")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
    }

    @Test
    fun `handle with package list returns package list`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "package", "list")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with package info returns package info`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "package", "info", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with package install returns install result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "package", "install", "/path/to.apk")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with package uninstall returns uninstall result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "package", "uninstall", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with package enable returns enable result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "package", "enable", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with package disable returns disable result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "package", "disable", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with package clear returns clear result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "package", "clear", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with package path returns path result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "package", "path", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with package help returns package help`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "package", "help")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("package"))
    }

    @Test
    fun `handle with unknown package subcommand returns error`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "package", "unknown")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
    }

    @Test
    fun `handle with permission list returns permission list`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "permission", "list", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with permission grant returns grant result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "permission", "grant", "com.example.app", "android.permission.CAMERA")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with permission revoke returns revoke result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "permission", "revoke", "com.example.app", "android.permission.CAMERA")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with permission appops returns appops result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "permission", "appops", "com.example.app", "CAMERA", "allow")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with permission help returns permission help`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "permission", "help")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("permission"))
    }

    @Test
    fun `handle with unknown permission subcommand returns error`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "permission", "unknown")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
    }

    @Test
    fun `handle with activity start returns start result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "activity", "start", "--package", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with activity force-stop returns force-stop result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "activity", "force-stop", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with activity kill returns kill result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "activity", "kill", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with activity broadcast returns broadcast result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "activity", "broadcast", "com.example.ACTION")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with activity top returns top result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "activity", "top")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with activity help returns activity help`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "activity", "help")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("activity"))
    }

    @Test
    fun `handle with unknown activity subcommand returns error`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "activity", "unknown")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
    }

    @Test
    fun `handle with display list returns display list`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "display", "list")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with display set returns set result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "display", "set", "--width", "1080", "--height", "1920")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with display reset returns reset result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "display", "reset")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with display help returns display help`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "display", "help")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("display"))
    }

    @Test
    fun `handle with unknown display subcommand returns error`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "display", "unknown")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
    }

    @Test
    fun `handle with settings get returns settings value`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "settings", "get", "global", "airplane_mode_on")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with settings set returns set result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "settings", "set", "global", "airplane_mode_on", "1")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with settings delete returns delete result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "settings", "delete", "global", "some_key")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with settings list returns list result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "settings", "list", "global")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with settings help returns settings help`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "settings", "help")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("settings"))
    }

    @Test
    fun `handle with unknown settings subcommand returns error`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "settings", "unknown")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
    }

    @Test
    fun `handle with user list returns user list`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "user", "list")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with user create returns create result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "user", "create", "TestUser")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with user remove returns remove result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "user", "remove", "10")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with user switch returns switch result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "user", "switch", "10")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with user start returns start result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "user", "start", "10")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with user stop returns stop result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "user", "stop", "10")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with user help returns user help`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "user", "help")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("user"))
    }

    @Test
    fun `handle with unknown user subcommand returns error`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "user", "unknown")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
    }

    @Test
    fun `handle with network restrict returns restrict result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "network", "restrict", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with network allow returns allow result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "network", "allow", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with network stats returns stats result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "network", "stats", "com.example.app")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with network help returns network help`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "network", "help")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("network"))
    }

    @Test
    fun `handle with unknown network subcommand returns error`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "network", "unknown")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
    }

    @Test
    fun `handle with input tap returns tap result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "input", "tap", "100", "200")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with input swipe returns swipe result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "input", "swipe", "0", "0", "100", "100")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle with input key returns key result`() {
        val handler = createHandler()
        val request = createRequest("android-shizuku-cli", "input", "key", "KEYCODE_HOME")
        val result = handler.handle(request)
        assertEquals(0,