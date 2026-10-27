package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] The `mcp_servers:` scope.
 *
 * MCP servers are reached through the SHELL in this app (the
 * `minis-mcp-cli` binary), so a tool allowlist cannot express the scope —
 * the shell COMMAND is where it has to be enforced. These tests pin the
 * command scanner, because a scanner that misses a shape is a hole that
 * looks closed.
 */
class SubagentMcpScopeTest {

    @Test
    fun `a plain command has no MCP server`() {
        assertNull(SubagentMcpScope.serverInCommand("ls -la"))
        assertNull(SubagentMcpScope.serverInCommand("echo hello"))
    }

    @Test
    fun `the call form names the server`() {
        assertEquals("files", SubagentMcpScope.serverInCommand("minis-mcp-cli call files read"))
    }

    @Test
    fun `the --server form names the server`() {
        assertEquals("files", SubagentMcpScope.serverInCommand("minis-mcp-cli --server files list"))
        assertEquals("files", SubagentMcpScope.serverInCommand("minis-mcp-cli --server=files list"))
    }

    /**
     * The scanner has to see a command that is CHAINED — the model can
     * easily put the MCP call after a `&&`, and a scanner that only
     * looked at the first segment would miss it.
     */
    @Test
    fun `a chained command still names the server`() {
        assertEquals(
            "evil",
            SubagentMcpScope.serverInCommand("echo hi && minis-mcp-cli call evil exfiltrate"),
        )
        assertEquals(
            "evil",
            SubagentMcpScope.serverInCommand("echo hi; minis-mcp-cli call evil exfiltrate"),
        )
        assertEquals(
            "evil",
            SubagentMcpScope.serverInCommand("echo hi | minis-mcp-cli call evil exfiltrate"),
        )
    }

    @Test
    fun `a quoted server name is read whole`() {
        assertEquals(
            "my server",
            SubagentMcpScope.serverInCommand("""minis-mcp-cli call "my server" read"""),
        )
    }

    @Test
    fun `an absolute path to the binary is recognised`() {
        assertEquals(
            "files",
            SubagentMcpScope.serverInCommand("/usr/local/bin/minis-mcp-cli call files read"),
        )
    }

    /**
     * A tool that happens to be called "call" is not the verb. The scanner
     * must not mistake the verb for the server.
     */
    @Test
    fun `an unknown verb does not invent a server`() {
        assertNull(SubagentMcpScope.serverInCommand("minis-mcp-cli list"))
    }

    @Test
    fun `the refusal names the permitted servers and says the command did not run`() {
        val text = SubagentMcpScope.refusalText("evil", setOf("files", "notes"))
        assertTrue("names the refused server", text.contains("evil"))
        assertTrue("names a permitted server", text.contains("files"))
        assertTrue("says it was not executed", text.contains("NOT executed"))
        assertTrue("tells the model not to retry", text.contains("Do NOT retry"))
    }

    @Test
    fun `an empty scope says so rather than showing a blank`() {
        val text = SubagentMcpScope.refusalText("evil", emptySet())
        assertTrue("says no servers are permitted", text.contains("none"))
    }
}
