package com.openminis.app.sandbox.offload

import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfigOffloadHandlerTest {

    private val handler = ConfigOffloadHandler()

    private fun request(vararg argv: String): NativeOffloadRequest {
        return NativeOffloadRequest(listOf("minis-config", *argv))
    }

    @Test
    fun `handle help flag returns exit ok and help text`() {
        val result = handler.handle(request("--help"))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("USAGE:"))
    }

    @Test
    fun `handle -h flag returns exit ok and help text`() {
        val result = handler.handle(request("-h"))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("USAGE:"))
    }

    @Test
    fun `handle no subcommand calls list-topics`() {
        val result = handler.handle(request())
        assertNotNull(result)
    }

    @Test
    fun `handle list-topics subcommand`() {
        val result = handler.handle(request("list-topics"))
        assertNotNull(result)
    }

    @Test
    fun `handle topic-help without topic returns invalid args`() {
        val result = handler.handle(request("topic-help"))
        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("INVALID_ARGS"))
    }

    @Test
    fun `handle topic-help with topic`() {
        val result = handler.handle(request("topic-help", "someTopic"))
        assertNotNull(result)
    }

    @Test
    fun `handle get without path returns invalid args`() {
        val result = handler.handle(request("get"))
        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("INVALID_ARGS"))
    }

    @Test
    fun `handle get with path`() {
        val result = handler.handle(request("get", "some.path"))
        assertNotNull(result)
    }

    @Test
    fun `handle set without args returns invalid args`() {
        val result = handler.handle(request("set"))
        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("INVALID_ARGS"))
    }

    @Test
    fun `handle set with path and value`() {
        val result = handler.handle(request("set", "some.path", "\"value\""))
        assertNotNull(result)
    }

    @Test
    fun `handle add without args returns invalid args`() {
        val result = handler.handle(request("add"))
        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("INVALID_ARGS"))
    }

    @Test
    fun `handle add with topic and value`() {
        val result = handler.handle(request("add", "someTopic", "{}"))
        assertNotNull(result)
    }

    @Test
    fun `handle set-batch without args returns invalid args`() {
        val result = handler.handle(request("set-batch"))
        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("INVALID_ARGS"))
    }

    @Test
    fun `handle set-batch with invalid json returns invalid args`() {
        val result = handler.handle(request("set-batch", "invalid_json"))
        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("INVALID_ARGS"))
    }

    @Test
    fun `handle set-batch with empty array returns invalid args`() {
        val result = handler.handle(request("set-batch", "[]"))
        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("Empty batch"))
    }

    @Test
    fun `handle set-batch with valid array`() {
        val result = handler.handle(request("set-batch", "[{\"path\":\"a\",\"value_json\":\"1\"}]"))
        assertNotNull(result)
    }

    @Test
    fun `handle audit-list`() {
        val result = handler.handle(request("audit-list"))
        assertNotNull(result)
    }

    @Test
    fun `handle audit-get without id returns invalid args`() {
        val result = handler.handle(request("audit-get"))
        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("INVALID_ARGS"))
    }

    @Test
    fun `handle audit-get with id`() {
        val result = handler.handle(request("audit-get", "123"))
        assertNotNull(result)
    }

    @Test
    fun `handle audit-revert without id returns invalid args`() {
        val result = handler.handle(request("audit-revert"))
        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("INVALID_ARGS"))
    }

    @Test
    fun `handle audit-revert with id`() {
        val result = handler.handle(request("audit-revert", "123"))
        assertNotNull(result)
    }

    @Test
    fun `handle unknown subcommand returns invalid args`() {
        val result = handler.handle(request("unknown-command"))
        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("INVALID_ARGS"))
        assertTrue(result.stdout.contains("Unknown subcommand"))
    }
}