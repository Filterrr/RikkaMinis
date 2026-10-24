package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-tool-preflight] The preflight rule was extracted from
 * ChatViewModel's companion into this shared object so the main agent and
 * the sub-agent runner enforce EXACTLY the same contract. These cases pin
 * the behaviour that must not drift.
 */
class ToolCallPreflightTest {

    private fun tool(
        name: String,
        required: List<String>,
    ) = AgentToolDefinition(
        name = name,
        description = "tool $name",
        parameters = emptyMap(),
        required = required,
    )

    private val fileRead = tool("file_read", listOf("path", "tool_title"))
    private val fileEdit = tool("file_edit", listOf("path", "old_string", "new_string"))
    private val shell = tool("shell_execute", listOf("command"))
    private val tools = listOf(fileRead, fileEdit, shell)

    // ── well-formed calls ────────────────────────────────────────────────

    @Test
    fun `a well-formed call passes`() {
        val args = JSONObject().put("path", "/tmp/a.txt")
        assertNull(ToolCallPreflight.validate("file_read", args, tools))
    }

    @Test
    fun `an unknown tool is left to the dispatcher`() {
        // Preflight stays silent so the executor's own "Unknown tool" message
        // is not doubled up.
        assertNull(ToolCallPreflight.validate("nope", JSONObject(), tools))
    }

    @Test
    fun `a tool whose only required field is non-blocking accepts empty args`() {
        val onlyTitle = tool("x", listOf("tool_title"))
        assertNull(ToolCallPreflight.validate("x", JSONObject(), listOf(onlyTitle)))
    }

    // ── missing / malformed fields ───────────────────────────────────────

    @Test
    fun `empty args on a tool with real requirements are rejected`() {
        val err = ToolCallPreflight.validate("file_read", JSONObject(), tools)
        assertNotNull(err)
        assertTrue(err!!, err.contains("empty arguments"))
        // The message lists only REAL blockers — tool_title is non-blocking.
        assertTrue(err, err.contains("path"))
        assertTrue(err, !err.contains("tool_title"))
    }

    @Test
    fun `a missing required field is rejected`() {
        val err = ToolCallPreflight.validate("file_read", JSONObject().put("x", 1), tools)
        assertNotNull(err)
        assertTrue(err!!, err.contains("missing required parameter"))
        assertTrue(err, err.contains("path"))
    }

    @Test
    fun `an explicit null counts as missing`() {
        val err = ToolCallPreflight.validate(
            "file_read",
            JSONObject().put("path", JSONObject.NULL),
            tools,
        )
        assertNotNull(err)
        assertTrue(err!!, err.contains("path"))
    }

    @Test
    fun `an empty string is treated as missing`() {
        val err = ToolCallPreflight.validate("file_read", JSONObject().put("path", ""), tools)
        assertNotNull(err)
        assertTrue(err!!, err.contains("path"))
    }

    @Test
    fun `whitespace is NOT treated as missing`() {
        // A newline is a legal new_string / old_string payload.
        val err = ToolCallPreflight.validate(
            "file_edit",
            JSONObject().put("path", "/a").put("old_string", "  ").put("new_string", "\n"),
            tools,
        )
        assertNull(err)
    }

    @Test
    fun `file_edit new_string may be empty`() {
        // "" is the documented "delete old_string" form.
        val err = ToolCallPreflight.validate(
            "file_edit",
            JSONObject().put("path", "/a").put("old_string", "x").put("new_string", ""),
            tools,
        )
        assertNull(err)
    }

    @Test
    fun `file_edit old_string may not be empty`() {
        // Only new_string is whitelisted.
        val err = ToolCallPreflight.validate(
            "file_edit",
            JSONObject().put("path", "/a").put("old_string", "").put("new_string", "y"),
            tools,
        )
        assertNotNull(err)
        assertTrue(err!!, err.contains("old_string"))
    }

    @Test
    fun `tool_title alone never blocks a call`() {
        val err = ToolCallPreflight.validate(
            "shell_execute",
            JSONObject().put("command", "ls"),
            tools,
        )
        assertNull(err)
    }

    @Test
    fun `the error message names the tool and the fields`() {
        val err = ToolCallPreflight.validate("shell_execute", JSONObject(), tools)
        assertNotNull(err)
        assertTrue(err!!, err.contains("shell_execute"))
        assertTrue(err, err.contains("command"))
    }
}
