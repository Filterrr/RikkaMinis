package com.openminis.app.tools

/**
 * [T-agent-capability] Capability model for agent tool surfaces.
 *
 * Every tool a (sub-)agent can hold maps to exactly one capability; a tool
 * with NO mapping is ungrantable — this is the fail-closed pivot. When the
 * main agent gains a new dangerous tool (`send_email`, `install_package`,
 * `device_control`, …) and nobody updates this catalog, sub-agents simply
 * never see it: the old denylist model would have silently granted it
 * (fail-open).
 *
 * Hard-forbidden capabilities are structurally unreachable for sub-agents:
 * the schema filter drops the tools AND the runtime executor refuses them,
 * so a stale allowlist cannot resurrect the behavior.
 */
enum class AgentCapability {
    /** Read files / images (`file_read`, `read_image`). */
    FILE_READ,

    /** Create or modify files (`file_write`, `file_edit`). */
    FILE_WRITE,

    /** Execute shell commands in the sandbox (`shell_execute`). */
    SHELL,

    /** Drive the automated browser (`browser_use`). */
    BROWSER,

    /**
     * [T-subagent-websearch] One-shot web search (`web_search`).
     *
     * Why this needed its own capability rather than riding on [BROWSER]:
     * `web_search` is a composite (navigate + wait + extract) that the parent
     * model was told to delegate, and the sub-agent prompt advertises tool
     * parity — but the tool was missing from this catalog, so the fail-closed
     * filter dropped it and every research sub-agent silently lost its
     * primary retrieval tool (it had to hand-roll navigate → get_readable,
     * spending 2–3 extra turns per query). Read-only and stateless (the
     * browser pool serializes per tab), so it is safe for sub-agents.
     */
    WEB_SEARCH,

    /** Read the agent memory store (`memory_get`). */
    MEMORY_READ,

    /** Write / distill the agent memory store (`memory_write`, `memory_rollup`). */
    MEMORY_WRITE,

    /** Spawn further sub-agents (`spawn_agent`). */
    SPAWN_AGENT,

    /**
     * [feat/subagent-roster-routing] The session task list (`todo_write`).
     *
     * It was MISSING from this catalog, so the fail-closed filter dropped
     * it — the same omission that silently took `web_search` away from
     * every research sub-agent.
     *
     * But unlike `web_search`, the fix is NOT to grant it. `todo_write`
     * REPLACES the whole session task list — the one the user sees in the
     * chat's top bar. A sub-agent writing to it would overwrite the
     * parent's plan with its own, and the user would watch the parent's
     * progress list change underneath them.
     *
     * So it gets an explicit capability and an explicit exclusion. The
     * exclusion is now a DECISION, recorded in the catalog, rather than
     * an accident of a missing line.
     */
    TASK_LIST,
}

object AgentCapabilities {

    /**
     * The capability catalog — tool name → capability. Tools absent from
     * this map are UNKNOWN and therefore never grantable (fail-closed).
     * When adding a main-agent tool, add it here and consciously decide
     * whether its capability belongs in [SUBAGENT_BASE].
     */
    fun capabilityOf(toolName: String): AgentCapability? = when (toolName) {
        "file_read" -> AgentCapability.FILE_READ
        "read_image" -> AgentCapability.FILE_READ
        "file_write" -> AgentCapability.FILE_WRITE
        "file_edit" -> AgentCapability.FILE_WRITE
        "shell_execute" -> AgentCapability.SHELL
        "browser_use" -> AgentCapability.BROWSER
        // [T-subagent-websearch] Registered so the fail-closed filter GRANTS it
        // instead of dropping it — see AgentCapability.WEB_SEARCH for why the
        // omission was a real capability loss, not a conservative default.
        "web_search" -> AgentCapability.WEB_SEARCH
        "memory_get" -> AgentCapability.MEMORY_READ
        "memory_write" -> AgentCapability.MEMORY_WRITE
        "memory_rollup" -> AgentCapability.MEMORY_WRITE
        "spawn_agent" -> AgentCapability.SPAWN_AGENT
        // [T-subagent-orchestration] Parent-only orchestration tools share
        // the SPAWN_AGENT capability — never grantable to sub-agents, so a
        // sub-agent can neither join others' results nor cancel siblings.
        "todo_write" -> AgentCapability.TASK_LIST
        "join_subagents" -> AgentCapability.SPAWN_AGENT
        "wait_any" -> AgentCapability.SPAWN_AGENT
        "cancel_subagents" -> AgentCapability.SPAWN_AGENT
        else -> null
    }

    /**
     * Capabilities a sub-agent holds by default: full parity with the main
     * agent EXCEPT memory (context/memory isolation — a research sub-agent
     * must not mutate the parent's long-term memory) and recursion.
     */
    val SUBAGENT_BASE: Set<AgentCapability> = setOf(
        AgentCapability.FILE_READ,
        AgentCapability.FILE_WRITE,
        AgentCapability.SHELL,
        AgentCapability.BROWSER,
        // [T-subagent-websearch] Research sub-agents are the main consumer of
        // search; without this they fall back to hand-rolled navigation.
        AgentCapability.WEB_SEARCH,
    )

    /**
     * Capabilities that can NEVER be granted to a sub-agent — not by the
     * default, not by a skill's `allowed_tools`, not by any future
     * config knob.
     *
     * [feat/subagent-roster-routing] This set was DEAD: nothing in the
     * production code consulted it. Every capability listed here already
     * had its tool in [SubagentSkill.FORBIDDEN_TOOLS], and that check
     * comes FIRST — so the capability-level check could never be
     * reached. Two lists describing the same rule, one of them
     * unreachable, and a future edit that touched only one of them would
     * have widened the grant silently.
     *
     * The two lists are now DERIVED from one source: the tool names in
     * [SubagentSkill.FORBIDDEN_TOOLS] are mapped to their capability,
     * and that mapping is the hard-forbidden set. A tool that is not in
     * the capability map contributes nothing — it is already refused by
     * the fail-closed lookup.
     */
    val SUBAGENT_HARD_FORBIDDEN: Set<AgentCapability> =
        SubagentSkill.FORBIDDEN_TOOLS
            .mapNotNull { capabilityOf(it) }
            .toSet()

    /**
     * True when a sub-agent may hold [toolName].
     *
     * [feat/subagent-roster-routing] The HARD-FORBIDDEN check was
     * MISSING here.
     *
     * The set's own documentation says the exclusions are enforced twice
     * — the schema filter and the runtime executor — and the filter was
     * the schema filter. But this function only consulted
     * [SUBAGENT_BASE], so the hard-forbidden capabilities were blocked
     * only because nobody had happened to add them to the base set.
     *
     * That is a single point of failure for the three capabilities that
     * are supposed to be unreachable by ANY configuration. Adding one of
     * them to the base set — a plausible-looking edit — would have
     * silently granted it, and the documentation would have kept
     * promising the opposite.
     *
     * The check is now explicit, so the promise and the code agree.
     */
    fun isToolGrantableToSubagent(toolName: String): Boolean =
        isGrantable(
            toolName = toolName,
            base = SUBAGENT_BASE,
            hardForbidden = SUBAGENT_HARD_FORBIDDEN,
        )

    /**
     * The grant rule, with the two sets passed in.
     *
     * [feat/subagent-roster-routing] The sets are parameters so the rule
     * can be TESTED where it matters.
     *
     * The real sets are `val`s, so a test could only observe that
     * `memory_read` is refused — which stays true even if the
     * hard-forbidden check is deleted, because the capability is absent
     * from the base set anyway. Such a test cannot tell the two
     * defences apart, and therefore cannot tell whether the hard
     * forbidden one is still there.
     *
     * With the sets as parameters, the question "is a hard-forbidden
     * capability refused even when the base set contains it?" is
     * answerable — and that is exactly the question a future edit to
     * the base set would turn on.
     */
    internal fun isGrantable(
        toolName: String,
        base: Set<AgentCapability>,
        hardForbidden: Set<AgentCapability>,
    ): Boolean {
        if (toolName in SubagentSkill.FORBIDDEN_TOOLS) return false
        val capability = capabilityOf(toolName) ?: return false
        if (capability in hardForbidden) return false
        return capability in base
    }
}
