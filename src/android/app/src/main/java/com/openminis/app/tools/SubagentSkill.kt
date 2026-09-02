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
 * FORBIDDEN tools (never passed to a sub-agent):
 *   - spawn_agent    (anti-recursion)
 *   - shell_execute  (terminal/android privilege escalation)
 *   - browser_use    (browser is a shared resource; a sub-agent shouldn't
 *                     race the main agent's browser tabs)
 */
object SubagentSkill {

    const val NAME = "spawn_agent"

    /**
     * [T-subagent-ui] Registry key on ChatViewModel — the main agent's
     * dispatch loop registers a run here before executing it so the chat
     * prompt pill and the second-level detail page can stream its progress.
     */
    const val RUN_REGISTRY_KEY = "subagentRunRegistry"

    /** Tools that sub-agents are NEVER allowed to use. */
    val FORBIDDEN_TOOLS: Set<String> = setOf(
        NAME,              // anti-recursion
        "shell_execute",   // privilege escalation (terminal, android-* CLIs)
        "browser_use",     // shared browser resource — no racing
    )

    /** Default budget for sub-agents when the skill doesn't specify. */
    private const val DEFAULT_MAX_TURNS = 12
    private const val DEFAULT_MAX_OUTPUT_TOKENS = 4096

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
            "execution process (every tool call and output) in real time.",
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
                    "finishes, with its full final report. 'first_turn': return " +
                    "after the sub-agent's first turn with its partial output — " +
                    "use when the caller only needs an early readout.",
                enumValues = listOf("done", "first_turn"),
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
     *   allowed_tools: [file_read, file_write, memory_get]
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
        )
    }

    /**
     * Build the filtered tool list for a sub-agent.
     * Excludes [FORBIDDEN_TOOLS] and, when [allowedTools] is non-null,
     * only includes tools whose name is in that set.
     */
    fun buildFilteredTools(
        allTools: List<AgentToolDefinition>,
        allowedTools: Set<String>?,
    ): List<AgentToolDefinition> {
        return allTools.filter { tool ->
            tool.name !in FORBIDDEN_TOOLS &&
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