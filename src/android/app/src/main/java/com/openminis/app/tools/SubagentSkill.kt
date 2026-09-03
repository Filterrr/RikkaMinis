package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * Minimal skill info required by sub-agent config parsing and prompt building.
 * Implemented by `SkillRepository.Skill` in production; stubbed in tests to
 * avoid pulling the Android-dependent SkillRepository into JVM unit tests.
 */
interface SkillInfo {
    val name: String
    val description: String
    val body: String
    /**
     * Stable identifier for session-enablement lookups and scheduler keys.
     * Defaults to [name] — implementations that model persistence
     * (SkillRepository.Skill) override it with a UUID.
     */
    val id: String get() = name
    /**
     * Raw YAML frontmatter (fences excluded), preserved verbatim from the
     * skill's SKILL.md. May be null for plain-body skills or legacy rows
     * persisted before frontmatter preservation existed. Sub-agent config
     * parsing reads this FIRST; the body's own frontmatter (if any) is the
     * fallback.
     */
    val frontmatter: String? get() = null
}

/**
 * Sub-agent skill system — skill = independent agent instance.
 *
 * A skill marked `subagent: true` in its SKILL.md frontmatter gets its own
 * system prompt, filtered tool set, independent loop, and budget. The
 * [spawn_agent] tool dispatches to it; the sub-agent's context is fully
 * isolated from the main agent's history.
 *
 * FORBIDDEN capabilities (never passed to a sub-agent, [T-subagent-capability]):
 *   - spawn_agent     (anti-recursion)
 *   - memory_get / memory_write / memory_rollup
 *     (context + memory isolation — a sub-agent must never read or mutate
 *     the parent agent's long-term memory; its SKILL.md contract says
 *     "minus spawning further agents and memory", and the runtime now
 *     enforces exactly that. See [AgentCapabilities].)
 *
 * shell_execute / browser_use remain ALLOWED: they route through the
 * session's hardened ExecutionCoordinator / BrowserTabPool paths.
 */
object SubagentSkill {

    const val NAME = "spawn_agent"

    /**
     * [T-subagent-ui] Registry key on ChatViewModel — the main agent's
     * dispatch loop registers a run here before executing it so the chat
     * prompt pill and the second-level detail page can stream its progress.
     */
    const val RUN_REGISTRY_KEY = "subagentRunRegistry"

    /** [T-subagent-result] run_until value: run until the model finishes naturally. */
    const val RUN_UNTIL_DONE = "done"

    /**
     * [T-subagent-first-turn] run_until value: stop after the sub-agent's
     * first turn completes — i.e. after turn 1's model output AND its tool
     * calls have executed and the results are recorded. Renamed from the
     * misleading `first_turn` (which sounded like "first model output,
     * tools still pending"). The legacy value is still accepted for one
     * deprecation window and mapped to [RUN_UNTIL_TURN_COMPLETE].
     */
    const val RUN_UNTIL_TURN_COMPLETE = "turn_complete"

    /** Legacy run_until value accepted as an alias (deprecated). */
    const val RUN_UNTIL_LEGACY_FIRST_TURN = "first_turn"

    /**
     * [T-subagent-orchestration] run_until value: fire-and-forget. The spawn
     * tool result returns IMMEDIATELY with the run_id + group_id while the
     * sub-agent keeps executing in the background (chat-VM scope). The parent
     * collects results later via join_subagents / wait_any, or discards runs
     * with cancel_subagents. Detached runs survive a user stream-cancel
     * (they are not part of the cancelled streamJob) but die with the VM.
     */
    const val RUN_UNTIL_DETACH = "detach"

    /**
     * Tools that sub-agents are NEVER allowed to use — the runtime
     * executor refuses these even if a stale allowlist or schema bug
     * offers them (defense in depth alongside the capability filter).
     * [T-subagent-capability] memory tools are forbidden for isolation.
     */
    val FORBIDDEN_TOOLS: Set<String> = setOf(
        NAME,          // anti-recursion — sub-agents cannot spawn sub-agents
        "memory_get",      // memory isolation — sub-agent cannot read parent memory
        "memory_write",    // memory isolation — sub-agent cannot mutate parent memory
        "memory_rollup",   // memory isolation — sub-agent cannot distill parent memory
        // [T-subagent-orchestration] Orchestration is the PARENT's job: a
        // sub-agent cannot join/wait/cancel runs (including its own) — the
        // spawner owns its lifecycle.
        SubagentOrchestrationTools.JOIN_NAME,
        SubagentOrchestrationTools.WAIT_ANY_NAME,
        SubagentOrchestrationTools.CANCEL_NAME,
    )

    /** Default budget for sub-agents when the skill doesn't specify. */
    private const val DEFAULT_MAX_TURNS = 12
    private const val DEFAULT_MAX_OUTPUT_TOKENS = 4096

    /** [T-subagent-parallel] Hard cap on concurrent sub-agents per chat. */
    const val MAX_PARALLEL_CAP = 4

    /** Default concurrent-run ceiling when the skill doesn't opt in higher. */
    const val DEFAULT_MAX_PARALLEL = 2

    /**
     * Parsed from a skill's SKILL.md frontmatter. Returned by
     * [parseSubagentConfig] for every skill; [isSubagent] is false
     * for regular skills.
     */
    data class SubagentConfig(
        val isSubagent: Boolean = false,
        val maxTurns: Int = DEFAULT_MAX_TURNS,
        val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
        /** Null = allow all non-FORBIDDEN tools. Non-null = explicit allowlist. */
        val allowedTools: Set<String>? = null,
        /**
         * [T-subagent-parallel] Max sub-agents of THIS skill that may run
         * concurrently in one chat (the per-chat limiter takes the min of
         * the requester's config and the app cap). 1 = serial.
         */
        val maxParallel: Int = 1,
    )

    // ── Tool definition ──────────────────────────────────────────────────

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Spawn a sub-agent with its own system prompt, tool set, " +
            "and budget. The sub-agent runs independently and returns its final " +
            "result. Use this to delegate complex sub-tasks to a focused agent. " +
            "The skill must be defined with `subagent: true` in its SKILL.md " +
            "frontmatter and must already be installed and enabled. " +
            "Recursive spawn_agent is forbidden. " +
            "While the sub-agent runs, the user sees a prompt pill in the chat " +
            "and can open a second-level page that streams the sub-agent's " +
            "execution process (every tool call and output) in real time. " +
            "Multiple spawn_agent calls emitted in ONE turn run in parallel " +
            "(bounded by max_parallel); each spawn batch forms a group. " +
            "With run_until='detach' the call returns immediately and the " +
            "result is collected later via join_subagents / wait_any.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this sub-agent should do. " +
                    "Shown to the user as the live status label.",
            ),
            "skill_name" to AgentToolParam(
                "string",
                "The name (or id) of the sub-agent skill to invoke",
            ),
            "query" to AgentToolParam(
                "string",
                "The task, question, or instruction to give to the sub-agent. " +
                    "Make it self-contained: the sub-agent cannot see this " +
                    "conversation and only knows what you write here.",
            ),
            "run_until" to AgentToolParam(
                "string",
                "Optional. 'done' (default): return only when the sub-agent " +
                    "finishes, with its full final report. 'turn_complete': " +
                    "return after the sub-agent's FIRST turn completes — its " +
                    "model output and that turn's tool calls have executed — " +
                    "with the partial output and recorded steps. Use when the " +
                    "caller only needs an early readout. 'detach': return " +
                    "IMMEDIATELY with the run_id while the sub-agent keeps " +
                    "running in the background — collect results later with " +
                    "join_subagents, race them with wait_any, or discard with " +
                    "cancel_subagents. Multiple detached spawns in one turn run " +
                    "truly in parallel. (Legacy alias 'first_turn' is accepted " +
                    "with turn_complete's meaning.)",
                enumValues = listOf("done", "turn_complete", "detach", "first_turn"),
            ),
        ),
        required = listOf("tool_title", "skill_name", "query"),
        propertyOrdering = listOf("tool_title", "skill_name", "query", "run_until"),
    )

    // ── Config parsing ───────────────────────────────────────────────────

    /**
     * Parse sub-agent configuration from the skill's body (SKILL.md content).
     * Recognises YAML frontmatter fields:
     *   subagent: true
     *   max_turns: 12
     *   max_output_tokens: 4096
     *   allowed_tools: [file_read, file_write, shell_execute]
     *   max_parallel: 2
     *
     * (FORBIDDEN tools — spawn_agent and the memory tools — can never be
     * re-enabled via allowed_tools; see [AgentCapabilities].)
     *
     * Returns [SubagentConfig] with isSubagent=false for skills without
     * `subagent: true` in the frontmatter — existing skills are unaffected.
     */
    fun parseSubagentConfig(skill: SkillInfo): SubagentConfig {
        // [T-subagent-fm] Prefer the preserved raw frontmatter — the
        // registry strips frontmatter from [SkillInfo.body] when skills
        // were imported before preservation existed, and pre-1.0.5 skill
        // bodies never carry it at all. Fall back to body-embedded
        // frontmatter for SkillInfo implementations that don't set the
        // field (tests, lightweight wrappers).
        val frontmatter = skill.frontmatter
            ?: run {
                val lines = skill.body.lines()
                if (lines.size < 2 || !lines[0].trim().startsWith("---")) return@run null
                val endIdx = lines.subList(1, lines.size)
                    .indexOfFirst { it.trim().startsWith("---") }
                    .takeIf { it >= 0 } ?: return@run null
                lines.subList(1, endIdx + 1).joinToString("\n")
            }
        if (frontmatter.isNullOrBlank()) return SubagentConfig()

        val lines = frontmatter.lines()
        var isSubagent = false
        var maxTurns = DEFAULT_MAX_TURNS
        var maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS
        var allowedTools: Set<String>? = null
        var maxParallel = 1

        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            when {
                trimmed.startsWith("subagent:") -> {
                    val value = trimmed.substringAfter(":").trim()
                    isSubagent = value == "true" || value == "yes"
                }
                trimmed.startsWith("max_turns:") -> {
                    maxTurns = trimmed.substringAfter(":").trim().toIntOrNull() ?: DEFAULT_MAX_TURNS
                }
                trimmed.startsWith("max_output_tokens:") -> {
                    maxOutputTokens = trimmed.substringAfter(":").trim().toIntOrNull() ?: DEFAULT_MAX_OUTPUT_TOKENS
                }
                trimmed.startsWith("max_parallel:") -> {
                    // [T-subagent-parallel] Optional concurrency knob for this
                    // skill; clamped to [1, 4] — the worker serializes model
                    // calls anyway, so more than 4 interleaved agents just
                    // queues without benefit.
                    maxParallel = (trimmed.substringAfter(":").trim().toIntOrNull() ?: 1)
                        .coerceIn(1, MAX_PARALLEL_CAP)
                }
                trimmed.startsWith("allowed_tools:") -> {
                    val listStr = trimmed.substringAfter(":").trim()
                    val tools = mutableSetOf<String>()
                    if (listStr.isNotEmpty()) {
                        parseListValue(listStr)?.let { tools.addAll(it) }
                    }
                    // Multi-line form: subsequent lines starting with "- "
                    var j = i + 1
                    while (j < lines.size) {
                        val item = lines[j].trim()
                        if (item.startsWith("- ")) {
                            tools.add(item.removePrefix("- ").trim().trim('"', '\''))
                            j++
                        } else {
                            break
                        }
                    }
                    allowedTools = tools.ifEmpty { emptySet() }
                }
            }
            i++
        }

        return SubagentConfig(
            isSubagent = isSubagent,
            maxTurns = maxTurns.coerceIn(1, 100),
            maxOutputTokens = maxOutputTokens.coerceIn(256, 128_000),
            allowedTools = allowedTools,
            maxParallel = maxParallel,
        )
    }

    /**
     * Build the filtered tool list for a sub-agent.
     *
     * [T-agent-capability] Fail-closed capability filter: a tool must be
     * known in the [AgentCapabilities] catalog AND its capability must be
     * in [AgentCapabilities.SUBAGENT_BASE] (never in the hard-forbidden
     * set). Unknown tools are dropped — new main-agent tools never leak to
     * sub-agents automatically. On top of the capability check, an
     * explicit [allowedTools] allowlist further narrows the set, and
     * [SubagentSkill.FORBIDDEN_TOOLS] always wins.
     */
    fun buildFilteredTools(
        allTools: List<AgentToolDefinition>,
        allowedTools: Set<String>?,
    ): List<AgentToolDefinition> {
        return allTools.filter { tool ->
            AgentCapabilities.isToolGrantableToSubagent(tool.name) &&
                (allowedTools == null || tool.name in allowedTools)
        }
    }

    /**
     * Build the system prompt for a sub-agent from the skill body.
     * Strips frontmatter, returns the raw body text.
     * Falls back to the skill description when the body is only frontmatter.
     */
    fun buildSystemPrompt(skill: SkillInfo): String {
        val body = skill.body
        if (body.isBlank()) return skill.description

        val lines = body.lines()
        if (lines.size >= 2 && lines[0].trim().startsWith("---")) {
            val endIdx = lines.subList(1, lines.size)
                .indexOfFirst { it.trim().startsWith("---") }
                .takeIf { it >= 0 }
                ?.plus(1)
            if (endIdx != null) {
                val contentLines = if (endIdx + 1 < lines.size) {
                    lines.subList(endIdx + 1, lines.size)
                } else {
                    emptyList()
                }
                val content = contentLines.joinToString("\n").trim()
                if (content.isNotBlank()) return content
                // Frontmatter-only skill (no body content) → fall back to description.
                return skill.description
            }
        }
        return body
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Parse a YAML list value like `[file_read, file_write]` or
     * `- file_read\n- file_write`.
     */
    private fun parseListValue(value: String): Set<String>? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptySet()

        // Inline list: [item1, item2, ...]
        if (trimmed.startsWith("[")) {
            return trimmed
                .removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotBlank() }
                .toSet()
                .ifEmpty { null }
        }

        // Multi-line list: each line starts with "- "
        val items = trimmed.lines()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim().trim('"', '\'') }
            .filter { it.isNotBlank() }
        return items.toSet().ifEmpty { null }
    }
}

/** A tool call emitted by a sub-agent during its loop. */
data class SubagentToolCall(
    val id: String,
    val name: String,
    val args: JSONObject,
)