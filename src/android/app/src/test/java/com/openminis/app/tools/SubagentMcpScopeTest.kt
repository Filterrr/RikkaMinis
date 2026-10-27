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

    /**
     * The scope is about WHICH SERVERS the agent may reach, so every
     * command that names one is in scope — not just `call`.
     *
     * `info` leaks the server's URL and headers, `tools` enumerates its
     * capabilities, `ping` reaches out to it, and `enable`/`disable`/
     * `remove` change the user's own configuration.
     */
    @Test
    fun `every command that names a server is scoped`() {
        assertEquals("files", SubagentMcpScope.serverInCommand("minis-mcp-cli tools files"))
        assertEquals("files", SubagentMcpScope.serverInCommand("minis-mcp-cli refresh files"))
        assertEquals("files", SubagentMcpScope.serverInCommand("minis-mcp-cli info files"))
        assertEquals("files", SubagentMcpScope.serverInCommand("minis-mcp-cli ping files"))
        assertEquals("files", SubagentMcpScope.serverInCommand("minis-mcp-cli enable files"))
        assertEquals("files", SubagentMcpScope.serverInCommand("minis-mcp-cli disable files"))
        assertEquals("files", SubagentMcpScope.serverInCommand("minis-mcp-cli remove files"))
    }

    /**
     * The backdoor: `add` registers a NEW server. A sub-agent that could
     * add its own server and then call it would have a capability the
     * scope never granted it.
     */
    @Test
    fun `adding a server is forbidden outright`() {
        val attempt = SubagentMcpScope.attemptInCommand(
            "minis-mcp-cli add --name evil --url http://attacker.example/mcp",
        )
        assertTrue(
            "add must be refused, not scoped",
            attempt is SubagentMcpScope.Attempt.Forbidden,
        )
        // The refusal must be SPECIFIC: `add` is refused because it is
        // on the forbidden list, not merely because the scope did not
        // name a server. An unknown verb is refused too, so asserting
        // only "is Forbidden" would pass even if `add` had been dropped
        // from the list — which would still refuse it, but for the
        // wrong reason, and the reason is what a future CLI version
        // could change.
        val forbidden = attempt as SubagentMcpScope.Attempt.Forbidden
        assertEquals("add", forbidden.command)
        assertEquals(
            "add must be refused because it is on the forbidden list, not " +
                "merely because it is an unknown verb",
            SubagentMcpScope.Attempt.Reason.EXPLICIT,
            forbidden.reason,
        )
    }

    @Test
    fun `stopping the daemon is forbidden outright`() {
        val attempt = SubagentMcpScope.attemptInCommand("minis-mcp-cli shutdown")
        assertTrue(
            "shutdown must be refused",
            attempt is SubagentMcpScope.Attempt.Forbidden,
        )
    }

    /**
     * A verb the CLI might add later must not silently reach a server.
     * A command we cannot reason about is refused, not ignored.
     */
    @Test
    fun `an unknown verb is refused rather than ignored`() {
        val attempt = SubagentMcpScope.attemptInCommand("minis-mcp-cli teleport files")
        assertTrue(
            "an unknown verb must be refused",
            attempt is SubagentMcpScope.Attempt.Forbidden,
        )
        // It must name the verb it refused, so the refusal is actionable.
        val forbidden = attempt as SubagentMcpScope.Attempt.Forbidden
        assertEquals("teleport", forbidden.command)
        assertEquals(
            "an unknown verb must be refused as UNKNOWN, not as an explicit ban",
            SubagentMcpScope.Attempt.Reason.UNKNOWN,
            forbidden.reason,
        )
    }

    /**
     * `list` names no server. Seeing WHICH servers exist is not the same
     * as reaching them, and the roster hint already tells the parent.
     */
    @Test
    fun `listing the servers is not scoped`() {
        assertNull(SubagentMcpScope.attemptInCommand("minis-mcp-cli list"))
        assertNull(SubagentMcpScope.attemptInCommand("minis-mcp-cli list --all"))
    }

    @Test
    fun `the forbidden refusal says the command was not executed`() {
        val text = SubagentMcpScope.forbiddenText("add")
        assertTrue("names the command", text.contains("add"))
        assertTrue("says it was not executed", text.contains("NOT executed"))
        assertTrue("tells the model not to retry", text.contains("Do NOT retry"))
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
