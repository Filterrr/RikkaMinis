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

    /** Read the agent memory store (`memory_get`). */
    MEMORY_READ,

    /** Write / distill the agent memory store (`memory_write`, `memory_rollup`). */
    MEMORY_WRITE,

    /** Spawn further sub-agents (`spawn_agent`). */
    SPAWN_AGENT,
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
        "memory_get" -> AgentCapability.MEMORY_READ
        "memory_write" -> AgentCapability.MEMORY_WRITE
        "memory_rollup" -> AgentCapability.MEMORY_WRITE
        "spawn_agent" -> AgentCapability.SPAWN_AGENT
        // [T-subagent-orchestration] Parent-only orchestration tools share
        // the SPAWN_AGENT capability — never grantable to sub-agents, so a
        // sub-agent can neither join others' results nor cancel siblings.
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
    )

    /**
     * Capabilities that can NEVER be granted to a sub-agent — not by the
     * default, not by a skill's `allowed_tools`, not by any future
     * config knob. Enforced twice: schema filter (tools never offered)
     * and runtime executor (calls never executed).
     */
    val SUBAGENT_HARD_FORBIDDEN: Set<AgentCapability> = setOf(
        AgentCapability.MEMORY_READ,
        AgentCapability.MEMORY_WRITE,
        AgentCapability.SPAWN_AGENT,
    )

    /** True when a sub-agent may hold [toolName]. */
    fun isToolGrantableToSubagent(toolName: String): Boolean {
        if (toolName in SubagentSkill.FORBIDDEN_TOOLS) return false
        val capability = capabilityOf(toolName) ?: return false
        return capability in SUBAGENT_BASE
    }
}
