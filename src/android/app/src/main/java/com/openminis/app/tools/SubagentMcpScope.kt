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
     * The MCP commands that TOUCH A NAMED SERVER.
     *
     * The scope is about which servers the agent may reach, so every
     * command that names one is in scope — not just `call`. `info` leaks
     * the server's URL and headers, `tools` enumerates its capabilities,
     * `ping` reaches out to it, and `enable`/`disable`/`remove` change
     * the user's configuration.
     *
     * `list` names no server, so it is not gated by the scope: seeing
     * WHICH servers exist is not the same as reaching them, and the
     * roster hint already tells the parent what exists.
     */
    private val SERVER_SCOPED_COMMANDS = setOf(
        "call", "tools", "refresh", "info", "ping", "enable", "disable", "remove",
    )

    /**
     * The commands that must never run from a sub-agent at all.
     *
     * `add` registers a NEW server — so a sub-agent could add its own
     * server and then call it, which is exactly the hole the scope
     * exists to close. `shutdown` stops the user's MCP daemon, which is
     * not the agent's to stop.
     */
    private val FORBIDDEN_COMMANDS = setOf("add", "shutdown")

    /**
     * The commands that are KNOWN and harmless with respect to the scope:
     * they name no server.
     *
     * They are listed explicitly so the "unknown verb is refused" rule
     * does not catch them. `list` names no server — seeing WHICH servers
     * exist is not the same as reaching them, and the roster hint
     * already tells the parent what exists.
     */
    private val SAFE_COMMANDS = setOf("list")

    /**
     * What the command tries to do with MCP, or null when it does not
     * touch MCP at all.
     */
    sealed interface Attempt {
        /** The command names a server; [server] is the name. */
        data class Server(val server: String, val command: String) : Attempt

        /**
         * The command is forbidden outright.
         *
         * [reason] says WHY, and the distinction matters: a verb on the
         * forbidden list is refused because the CLI says so, while an
         * UNKNOWN verb is refused because we cannot reason about it.
         * Both are refused, but they are refused for different reasons —
         * and a test that cannot tell them apart cannot tell whether the
         * forbidden list is still doing its job.
         */
        data class Forbidden(val command: String, val reason: Reason) : Attempt

        enum class Reason {
            /** The verb is explicitly on the forbidden list. */
            EXPLICIT,
            /** The verb is not one we know; refused conservatively. */
            UNKNOWN,
        }
    }

    /**
     * The MCP attempt a command makes, or null when the command does not
     * invoke MCP at all.
     *
     * The recognised shapes are the ones the CLI actually accepts:
     *   minis-mcp-cli call SERVER TOOL ...
     *   minis-mcp-cli --server SERVER ...
     *   minis-mcp-cli add --name SERVER ...
     */
    fun attemptInCommand(command: String): Attempt? {
        if (!command.contains(MCP_BINARY)) return null
        // Split on shell separators so a command like
        // `echo hi && minis-mcp-cli call evil x` is still seen.
        val segments = splitSegments(command)
        for (segment in segments) {
            val tokens = tokenize(segment)
            val idx = tokens.indexOfFirst { it == MCP_BINARY || it.endsWith("/$MCP_BINARY") }
            if (idx < 0) continue
            val attempt = attemptFrom(tokens, idx)
            if (attempt != null) return attempt
        }
        return null
    }

    /**
     * The server named by an MCP invocation, given the token list and the
     * index of the binary.
     */
    private fun attemptFrom(tokens: List<String>, binaryIndex: Int): Attempt? {
        var i = binaryIndex + 1
        var verb: String? = null
        while (i < tokens.size) {
            val t = tokens[i]
            if (verb == null) {
                when {
                    t in FORBIDDEN_COMMANDS ->
                        return Attempt.Forbidden(t, Attempt.Reason.EXPLICIT)
                    t in SAFE_COMMANDS -> return null
                    t in SERVER_SCOPED_COMMANDS -> {
                        verb = t
                        // `call SERVER TOOL` — the server is the NEXT token.
                        val server = tokens.getOrNull(i + 1)?.takeIf { !it.startsWith("-") }
                            ?: return null
                        return Attempt.Server(server, t)
                    }
                    t == "--server" -> {
                        val server = tokens.getOrNull(i + 1)?.takeIf { !it.startsWith("-") }
                            ?: return null
                        return Attempt.Server(server, "server")
                    }
                    t.startsWith("--server=") -> {
                        val server = t.removePrefix("--server=").takeIf { it.isNotEmpty() }
                            ?: return null
                        return Attempt.Server(server, "server")
                    }
                    t == "--name" -> {
                        // `add --name SERVER` — but `add` is forbidden
                        // outright, so this only matters if a future CLI
                        // version grows another verb that takes --name.
                        val server = tokens.getOrNull(i + 1)?.takeIf { !it.startsWith("-") }
                            ?: return null
                        return Attempt.Server(server, "name")
                    }
                    t.startsWith("-") -> i++ // another flag
                    else -> {
                        // A subcommand we do not know: `minis-mcp-cli <verb>
                        // ...`. The verb might be one the CLI will add
                        // later, so it is treated as FORBIDDEN rather than
                        // ignored — a command we cannot reason about must
                        // not silently reach a server.
                        return Attempt.Forbidden(t, Attempt.Reason.UNKNOWN)
                    }
                }
            }
            i++
        }
        return null
    }

    /** The server named by an MCP invocation, or null. */
    fun serverInCommand(command: String): String? {
        val attempt = attemptInCommand(command)
        return (attempt as? Attempt.Server)?.server
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
     * The model-facing refusal for a command that is not permitted from a
     * sub-agent at all.
     */
    fun forbiddenText(command: String): String =
        "Error: the MCP command '$command' is not permitted from a sub-agent. " +
            "Registering or reconfiguring MCP servers is the user's decision, not the " +
            "agent's. The command was NOT executed. " +
            "Do NOT retry it and do NOT try to reach the same capability through another " +
            "route; work with the servers the skill is allowed, or finish and state the " +
            "capability gap as a caveat in your report."

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
