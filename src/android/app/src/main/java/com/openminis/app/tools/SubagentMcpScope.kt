package com.openminis.app.tools

/**
 * [feat/subagent-roster-routing] P2 — the `mcp_servers:` scope.
 *
 * The hole this closes: MCP servers are reached through the SHELL in this
 * app (the `minis-mcp-cli` binary), not through registered tools. So a
 * sub-agent holding `shell_execute` could call ANY configured MCP server —
 * including the ones the parent had deliberately gated — simply by typing
 * the command. A tool allowlist cannot close that, because there is no
 * tool to allow or deny: the capability is the shell.
 *
 * The scope is enforced where the capability actually lives: the shell
 * command. Every command is scanned for an MCP invocation, and a command
 * that names a server outside the scope is refused BEFORE it runs.
 *
 * ## The scope is opt-IN, and that is deliberate
 *
 * A skill that declares `mcp_servers:` gets exactly those servers. A skill
 * that declares NOTHING gets no MCP access at all — because the whole
 * point of the field is to make the grant explicit. If an undeclared
 * skill could still reach every server, the field would be decorative and
 * the hole would remain.
 *
 * The consequence is stated plainly here because it is a behaviour change:
 * a sub-agent skill that relied on reaching MCP servers by shell without
 * declaring them now cannot. That is the intended trade — an implicit
 * grant of every server is exactly what the field exists to remove.
 */
internal object SubagentMcpScope {

    /**
     * The server names a command tries to reach, or null when the command
     * does not invoke MCP at all.
     *
     * The recognised shapes are the ones the CLI actually accepts:
     *   minis-mcp-cli call SERVER TOOL ...
     *   minis-mcp-cli --server SERVER ...
     *
     * The scan is deliberately conservative about the SERVER and precise
     * about the TOOL: the server is what the scope governs, and a tool
     * name that happens to be called "call" must not be mistaken for the
     * verb.
     */
    fun serverInCommand(command: String): String? {
        if (!command.contains(MCP_BINARY)) return null
        // Split on shell separators so a command like
        // `echo hi && minis-mcp-cli call evil x` is still seen.
        val segments = splitSegments(command)
        for (segment in segments) {
            val tokens = tokenize(segment)
            val idx = tokens.indexOfFirst { it == MCP_BINARY || it.endsWith("/$MCP_BINARY") }
            if (idx < 0) continue
            val server = serverAfter(tokens, idx)
            if (server != null) return server
        }
        return null
    }

    private const val MCP_BINARY = "minis-mcp-cli"

    /**
     * The server named by an MCP invocation, given the token list and the
     * index of the binary.
     */
    private fun serverAfter(tokens: List<String>, binaryIndex: Int): String? {
        var i = binaryIndex + 1
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "call" -> {
                    // `call <server> <tool>` — the server is the NEXT token.
                    return tokens.getOrNull(i + 1)?.takeIf { !it.startsWith("-") }
                }
                t == "--server" -> {
                    return tokens.getOrNull(i + 1)?.takeIf { !it.startsWith("-") }
                }
                t.startsWith("--server=") -> {
                    return t.removePrefix("--server=").takeIf { it.isNotEmpty() }
                }
                t.startsWith("-") -> i++ // another flag
                else -> {
                    // A subcommand we do not know: `minis-mcp-cli <verb>
                    // ...`. If the verb is not `call`, the server may be
                    // given by --server, which is handled above. Stop here
                    // rather than guessing that the verb is the server.
                    return null
                }
            }
            i++
        }
        return null
    }

    /** Split a shell command on the separators that start a new command. */
    private fun splitSegments(command: String): List<String> =
        command.split(Regex("""(?:\|\||&&|;|\||\n)"""))

    /**
     * Split a segment into tokens, honouring single and double quotes so
     * that `minis-mcp-cli call "my server" tool` names `my server`.
     */
    private fun tokenize(segment: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var hasToken = false
        for (c in segment) {
            when {
                quote != null && c == quote -> { quote = null; hasToken = true }
                quote != null -> current.append(c)
                c == '\'' || c == '"' -> { quote = c; hasToken = true }
                c.isWhitespace() -> {
                    if (hasToken) { tokens += current.toString(); current.clear(); hasToken = false }
                }
                else -> { current.append(c); hasToken = true }
            }
        }
        if (hasToken) tokens += current.toString()
        return tokens
    }

    /**
     * The model-facing refusal for a command that reaches a server outside
     * the scope.
     *
     * The wording names the allowed servers, because the model can act on
     * it: use a permitted server, or report the capability gap. It also
     * says explicitly that the command was NOT run, so the model does not
     * go looking for output that will never arrive.
     */
    fun refusalText(server: String, allowed: Set<String>): String {
        val permitted = if (allowed.isEmpty()) {
            "(none — this skill declares no MCP servers)"
        } else {
            allowed.sorted().joinToString(", ")
        }
        return "Error: the MCP server '$server' is outside this skill's mcp_servers scope. " +
            "This skill may only reach: $permitted. " +
            "The command was NOT executed. " +
            "Do NOT retry it and do NOT try to reach the same capability through another " +
            "route; use a permitted server, or finish and state the capability gap as a " +
            "caveat in your report."
    }
}
