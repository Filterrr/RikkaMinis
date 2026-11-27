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

    /**
     * [T-agent-status] Read this run's own remaining budget
     * (`agent_status`).
     *
     * Grantable to sub-agents on purpose: a sub-agent runs against its OWN
     * budget and hit the same blindness — it would spend its last turns on a
     * task it could not finish. The tool is read-only and exposes nothing
     * about the parent or its siblings, so there is no isolation cost.
     */
    SELF_INSPECT,

    /**
     * [T-ask-user] Park the current turn on a question to the user
     * (`ask_user`).
     *
     * Exists as its own capability so the decision "can a sub-agent block on
     * a human?" is explicit. It cannot: a child parked on a dialog holds a
     * scheduler slot (the parent's inline wait burns its own budget), and a
     * batch of children all asking at once turns one question into a queue.
     * Only the main agent asks.
     */
    ASK_USER,

    /**
     * [T-scheduled-tasks] Schedule a reminder on the device (`schedule_task`).
     *
     * Parent-only, like spawning: a sub-agent arranging future wake-ups would
     * be a second schedule nobody is watching, and the device's alarm budget
     * is a shared resource.
     */
    SCHEDULE,
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
        "apply_patch" -> AgentCapability.FILE_WRITE
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
        // [port/upstream-subagent-status] Parent-only for the same reason:
        // a sub-agent must not be able to inspect its siblings (or its
        // parent's other work) — that is context it was never given.
        "subagent_status" -> AgentCapability.SPAWN_AGENT
        // [port/upstream-subagent-steer] Parent-only: a sub-agent steering
        // a sibling would be a second, invisible instruction channel into a
        // run nobody asked it to manage.
        "steer_subagent" -> AgentCapability.SPAWN_AGENT
        "agent_status" -> AgentCapability.SELF_INSPECT
        // [T-agent-profiles] Listing agents is part of deciding to delegate,
        // so it shares SPAWN_AGENT (never grantable to a sub-agent).
        "list_agents" -> AgentCapability.SPAWN_AGENT
        "ask_user" -> AgentCapability.ASK_USER
        "schedule_task" -> AgentCapability.SCHEDULE
        // [T-shell-job] Background jobs are still shell execution: same
        // sandbox, same command surface, so they ride the SHELL capability
        // rather than inventing a second grant path.
        "shell_job" -> AgentCapability.SHELL
        else -> null
    }

    /**
     * Capabilities a sub-agent holds by default: full parity with the main
     * agent EXCEPT memory (context/memory isolation — a research sub-agent
     * must not mutate the parent's long-term memory) and recursion.
     */
    /**
     * [T-agent-profiles] capability → 它涵盖的工具名（反向映射）。
     *
     * 存在的唯一理由：内置 profile 用 capability 表达权限，而运行时的过滤
     * 是按工具名的。这张表必须和 [capabilityOf] 一一对应——`toolsFor` 漏掉
     * 一个工具，那个工具就对所有 profile 静默消失；多列一个，就等于绕过了
     * capability 的语义。`AgentCapabilityRoundTripTest` 守着这两条。
     */
    fun toolsFor(capabilities: Set<AgentCapability>): Set<String> =
        capabilities.flatMapTo(mutableSetOf()) { toolsOf(it) }

    fun toolsOf(capability: AgentCapability): Set<String> = when (capability) {
        AgentCapability.FILE_READ -> setOf("file_read", "read_image")
        AgentCapability.FILE_WRITE -> setOf("file_write", "file_edit", "apply_patch")
        AgentCapability.SHELL -> setOf("shell_execute", "shell_job")
        AgentCapability.BROWSER -> setOf("browser_use")
        AgentCapability.WEB_SEARCH -> setOf("web_search")
        AgentCapability.MEMORY_READ -> setOf("memory_get")
        AgentCapability.MEMORY_WRITE -> setOf("memory_write", "memory_rollup")
        AgentCapability.SPAWN_AGENT -> setOf("spawn_agent")
        AgentCapability.TASK_LIST -> setOf("todo_write")
        AgentCapability.SELF_INSPECT -> setOf("agent_status")
        AgentCapability.ASK_USER -> setOf("ask_user")
        AgentCapability.SCHEDULE -> setOf("schedule_task")
    }

    val SUBAGENT_BASE: Set<AgentCapability> = setOf(
        AgentCapability.FILE_READ,
        AgentCapability.FILE_WRITE,
        AgentCapability.SHELL,
        AgentCapability.BROWSER,
        // [T-subagent-websearch] Research sub-agents are the main consumer of
        // search; without this they fall back to hand-rolled navigation.
        AgentCapability.WEB_SEARCH,
        AgentCapability.SELF_INSPECT,
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
