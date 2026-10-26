package com.openminis.app.tools

/**
 * [fix/subagent-allowed-tools-enforcement] The runtime gate for a skill's
 * `allowed_tools` allowlist.
 *
 * ## The defect this closes
 *
 * `allowed_tools` was enforced in exactly ONE place: the SCHEMA filter
 * ([SubagentSkill.buildFilteredTools]), which decides which tool definitions
 * the model is shown. The RUNTIME dispatcher ([SubagentRunner.executeSubagentTool])
 * checked only:
 *
 *   - the hard-forbidden set (spawn_agent / memory_* / join / wait / cancel),
 *   - the capability catalog (is the tool known and grantable at all?).
 *
 * It never consulted `config.allowedTools`. A skill that declared
 * `allowed_tools: [file_read, read_image, browser_use]` therefore had
 * `file_write` and `shell_execute` REMOVED from the schema — but a model that
 * emitted those tool names anyway (from its prior context, from a hallucinated
 * schema, or from a cached tool list) got them EXECUTED.
 *
 * Measured on the real runtime (2026-10-22, subagent-4): a
 * `read-only-researcher` run wrote `/var/minis/workspace/subagent_test/repro_write.txt`
 * with `file_write` and created `repro_shell.txt` with `shell_execute`. Both
 * files landed on disk and the parent agent's independent md5 check matched.
 * The only calls that were refused were names the runtime did not know at
 * all (`bash`, `glob`, `grep`, `read`).
 *
 * That is the worst possible shape for a skill whose entire purpose is
 * "investigate untrusted sources without mutating anything": the schema
 * looked safe, the runtime was not.
 *
 * ## Why the schema filter cannot be the only gate
 *
 * The schema filter is a REQUEST-SIDE filter. It governs what the model is
 * TOLD it may call. The runtime gate governs what actually HAPPENS. A
 * schema-only allowlist is one stale tool list, one provider quirk, or one
 * model that ignores the schema away from isolation.
 *
 * The repo's own convention is explicit about this: `executeSubagentTool`'s
 * doc comment claims "the schema filter already excluded forbidden tools, but
 * this refuse-with-explanation path guarantees a stale allowlist or future
 * regression can never EXECUTE them (defense in depth — capability checks
 * must fail closed twice)". The capability check failed closed twice; the
 * ALLOWLIST check failed closed ONCE. This object closes the second gap.
 *
 * Pure + JVM-testable: no Android, no registry, no coroutines.
 */
object SubagentAllowedTools {

    /**
     * True when [toolName] may be executed by a run whose skill declared
     * [allowedTools] and [disallowedTools].
     *
     * @param allowedTools the skill's `allowed_tools` allowlist, or null when
     *   the skill declared none (null = unrestricted, subject to the hard
     *   forbidden set and the capability catalog, which are checked
     *   separately).
     * @param disallowedTools the skill's `disallowed_tools` blacklist. It is
     *   applied AFTER the allowlist and WINS: an allowlist cannot express
     *   "everything except the destructive one" without enumerating every
     *   tool, and a blacklist cannot express "only these". Both together
     *   cover both shapes — and when they conflict, the deny wins, because
     *   the whole point of a permission model is that the restrictive
     *   reading is the safe one.
     */
    fun isAllowed(
        toolName: String,
        allowedTools: Set<String>?,
        disallowedTools: Set<String> = emptySet(),
    ): Boolean {
        if (toolName in disallowedTools) return false
        return allowedTools == null || toolName in allowedTools
    }

    /**
     * The model-facing refusal for a tool outside the skill's allowlist.
     *
     * The wording names the allowlist (not the capability set) because that
     * is what the skill author wrote, and it tells the model what to do next
     * — use a permitted tool or report the gap. A generic "forbidden" message
     * is what the model saw before, and it could not tell a policy refusal
     * from a missing tool.
     *
     * A BLACKLIST refusal is worded differently: the tool was otherwise
     * available and the skill explicitly removed it, so saying "not in
     * allowed_tools" would be misleading — the model would try to "fix" it
     * by adding the tool to a list it cannot edit.
     */
    fun refusalText(
        toolName: String,
        allowedTools: Set<String>?,
        disallowedTools: Set<String> = emptySet(),
    ): String {
        if (toolName in disallowedTools) {
            return "Error: Tool '$toolName' is explicitly denied by this skill's " +
                "disallowed_tools list. Do NOT retry it and do NOT try to reach the " +
                "same capability through another tool. Use a permitted tool, or " +
                "finish and state the capability gap as a caveat in your report."
        }
        val permitted = allowedTools?.sorted()?.joinToString(", ") ?: "(unrestricted)"
        return "Error: Tool '$toolName' is not in this skill's allowed_tools list. " +
            "This skill may only use: $permitted. " +
            "Do NOT retry '$toolName' and do NOT try to reach the same capability " +
            "through another tool. Use a permitted tool, or finish and state the " +
            "capability gap as a caveat in your report."
    }
}
