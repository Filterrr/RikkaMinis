package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    /** Default turn budget for sub-agents when the skill doesn't specify. */
    private const val DEFAULT_MAX_TURNS = 12

    /**
     * [T-subagent-runtime-preamble] Marker line the runtime prepends to the
     * system prompt when [buildSystemPrompt] injects the runtime preamble.
     * Tests (and any downstream consumer) can detect injected prompts via
     * [SubagentResult.hasRuntimePreamble].
     */
    const val RUNTIME_PREAMBLE_MARKER = "[runtime context]"

    /**
     * Canonical final-report contract keys. [parseReportStatus] scans the
     * report's tail for these keys (labelled "field:" at line start) so a
     * report's declared outcome can decorate the prompt text the parent
     * receives — the [A2] auto-annotation of partial/failed outcomes.
     */
    val REPORT_STATUS_KEYS: Set<String> = setOf("status", "结果状态", "状态")
    val REPORT_FAILED_STATUS_TOKENS: Set<String> = setOf("failed", "失败")
    val REPORT_PARTIAL_STATUS_TOKENS: Set<String> = setOf("partial", "部分完成", "未完成", "不完整")

    /**
     * [A2] Extract the sub-agent's self-declared report `status` from the
     * final report text. Scans only the last [REPORT_SCAN_WINDOW] lines —
     * the structured contract pins status to the report tail — and accepts
     * a status line even when a nested "status" string appears first in
     * prose above the contract block.
     *
     * Recognised shapes: `status: partial`, `- status: done`,
     * `- status: partial (budget exhausted…)`, `结果状态：部分完成`.
     * Returns null when no recognisable status line exists (prose reports,
     * truncated output) — callers then fall back to runtime-derived state.
     */
    fun parseReportStatus(report: String): String? {
        if (report.isBlank()) return null
        val window = report.trimEnd().lines().takeLast(REPORT_SCAN_WINDOW)
        for (raw in window.reversed()) {  // most recent self-assessment wins
            val line = raw.trim()
            if (line.isEmpty()) continue
            val label = line.substringBefore(':').substringBefore('：').trim()
                .removePrefix("-").removePrefix("*").trim().lowercase()
            if (label !in REPORT_STATUS_KEYS) continue
            val value = line.substringAfter(':').substringAfter('：').trim().lowercase()
            val token = value.substringBefore('(').substringBefore('（').trim()
            return when {
                token in REPORT_FAILED_STATUS_TOKENS -> "failed"
                token in REPORT_PARTIAL_STATUS_TOKENS -> "partial"
                else -> null  // done / unknown / empty → no annotation
            }
        }
        return null
    }

    private const val REPORT_SCAN_WINDOW = 10

    /** [T-subagent-parallel] Hard cap on concurrent sub-agents per chat. */
    const val MAX_PARALLEL_CAP = 4

    /** Default concurrent-run ceiling when the skill doesn't opt in higher. */
    const val DEFAULT_MAX_PARALLEL = 2

    /**
     * [T-subagent-run-timeout] Per-run wall-clock defaults, measured from
     * SPAWN time (queue wait included).
     *
     * Calibrated on dogfood runs subagent-1/2 (2026-09-14): two research
     * spawns on qwen3.8-flash exhausted the OLD 600s default at turns 14–17
     * of 48 while still mid-task — a third of the wall clock gone to
     * transient-retry backoff on an unstable relay. 900s keeps ~60 turns of
     * headroom for a healthy relay; the max exists so a detached run cannot
     * outlive its usefulness overnight.
     */
    const val DEFAULT_TIMEOUT_SECONDS = 900
    const val MAX_TIMEOUT_SECONDS = 3600

    /**
     * [T-subagent-queue-budget] How long an INLINE spawn will sit in the
     * scheduler queue before the spawn fails with queue_timeout. The parent
     * turn is parked on an inline call returning, so unlike a detached spawn
     * (which may legitimately wait its whole budget for a slot) its queue
     * wait is user-visible freeze and gets its own short cap regardless of
     * timeout_seconds. 30s mirrors the foreground slot wait ExTV shipped.
     */
    const val INLINE_QUEUE_WAIT_MS = 30_000L

    /**
     * [T-subagent-model-routing] Optional tail appended to the `model`
     * parameter description so the parent can see what it may pick without
     * guessing. Supplied by the caller (the catalog lives behind a repository
     * the tools layer must not reference); empty keeps the schema static.
     */
    const val MAX_CATALOG_HINT_MODELS = 30

    /**
     * [T-subagent-model-routing] Byte ceiling on the hint — the entry CAP is
     * not enough on its own, since 30 entries with verbose display names can
     * still be kilobytes riding in every parent request.
     */
    const val MAX_CATALOG_HINT_CHARS = 600

    /**
     * [T-subagent-model-routing] Render a model list for embedding in the
     * spawn_agent schema.
     *
     * BUDGET: this text rides in EVERY parent request's tool schema, so it is
     * deliberately capped at [MAX_CATALOG_HINT_MODELS] names and counts as
     * prompt bytes, not prose. A 200-model catalog would otherwise add
     * kilobytes to every single turn to benefit the rare spawn that names a
     * model. Beyond the cap we say only how many exist and point at the uuid
     * form — the resolver still accepts an exact uuid/model-id for a model
     * that is not listed here, so truncation costs nothing but discoverability.
     */
    fun buildCatalogHint(
        candidates: List<SubagentModelResolver.Candidate>,
        limit: Int = MAX_CATALOG_HINT_MODELS,
        maxChars: Int = MAX_CATALOG_HINT_CHARS,
    ): String {
        if (candidates.isEmpty()) return ""
        val shown = candidates.take(limit)
        val names = shown.joinToString(", ") { it.describe() }
        val hint = if (candidates.size <= limit) {
            "Known models: $names."
        } else {
            "Known models (first $limit of ${candidates.size}): $names. " +
                "A model uuid or exact model id is also accepted."
        }
        // Second guard: a few dozen entries can still be enormous when display
        // names are long. Cutting mid-list is acceptable — the resolver takes
        // the full catalog, so an unlisted model stays routable by exact id.
        return if (hint.length <= maxChars) hint
        else hint.take(maxChars).trimEnd() + " …(list truncated; an exact model uuid or model id also works)"
    }

    /**
     * [T-subagent-model-spread] Render the configured MODEL GROUP names for
     * embedding in the spawn_agent schema — the discoverability half of
     * "a spawn may name a group" (`model = "group:<name>"` or a bare name).
     *
     * Same budget discipline as [buildCatalogHint]: this rides in every
     * parent request, so it is capped at [MAX_CATALOG_HINT_MODELS] names and
     * [MAX_CATALOG_HINT_CHARS] characters. A group NOT listed here stays
     * addressable by its exact name or id — truncation costs discoverability,
     * never capability.
     */
    fun buildGroupHint(
        groupNames: List<String>,
        limit: Int = MAX_CATALOG_HINT_MODELS,
        maxChars: Int = MAX_CATALOG_HINT_CHARS,
    ): String {
        val names = groupNames.map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return ""
        val shown = names.take(limit)
        val list = shown.joinToString(", ")
        val hint = if (names.size <= limit) {
            "Model groups: $list."
        } else {
            "Model groups (first $limit of ${names.size}): $list."
        }
        return if (hint.length <= maxChars) hint
        else hint.take(maxChars).trimEnd() + " …(list truncated; an exact group name or id also works)"
    }

    /**
     * Parsed from a skill's SKILL.md frontmatter. Returned by
     * [parseSubagentConfig] for every skill; [isSubagent] is false
     * for regular skills.
     *
     * [T-subagent-output-from-model] The per-turn output ceiling is NOT a
     * skill property. It is read at run time from the resolved model's
     * `maxOutputTokens` (管理提供商 / Manage Providers → per-model "Max Output
     * Tokens", folded into [com.openminis.app.data.model.ModelEntry.model]) via
     * [com.openminis.app.provider.LLMProvider.effectiveMaxOutputTokens] — the
     * same single source of truth the main loop's `dynamicMaxTokens` consumes.
     * A skill-frontmatter `max_output_tokens` value would be a second, silent
     * knob that drifts from the model the run actually uses (the spawn `model`
     * argument may route it to a different model); the frontmatter key is
     * therefore no longer recognised and is ignored when present.
     */
    data class SubagentConfig(
        val isSubagent: Boolean = false,
        val maxTurns: Int = DEFAULT_MAX_TURNS,
        /** Null = allow all non-FORBIDDEN tools. Non-null = explicit allowlist. */
        val allowedTools: Set<String>? = null,
        /**
         * [T-subagent-parallel] Max sub-agents of THIS skill that may run
         * concurrently in one chat (the per-chat limiter takes the min of
         * the requester's config and the app cap). 1 = serial.
         */
        val maxParallel: Int = 1,
        /**
         * [T-subagent-thinking] Reasoning level this skill asks its sub-agent
         * to run with, from `thinking:` in the frontmatter. Defaults to OFF:
         * reasoning tokens are billed like any other output and multiply by
         * the turn count, so a delegated run must opt IN explicitly rather
         * than silently inheriting the parent's level. Ignored (downgraded to
         * OFF) when the resolved model cannot reason — see
         * [com.openminis.app.tools.resolveSubagentThinkingLevel].
         */
        val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        /**
         * [fix/subagent-plan-first] `plan_first: true|false` in the
         * frontmatter (default TRUE). When on, the run's first model turn is
         * a PLANNING turn: the system prompt requires the model to emit a
         * `<task-plan>` list BEFORE any tool call, the runner extracts it
         * into the registry, and the detail page renders it as a task
         * checklist. The user sees the intended flow before execution starts
         * instead of reverse-engineering it from the tool pills.
         */
        val planFirst: Boolean = true,
    )

    // ── Tool definition ──────────────────────────────────────────────────

    fun definition(modelCatalogHint: String = "", groupHint: String = ""): AgentToolDefinition = AgentToolDefinition(
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
            "When this chat runs on a MODEL GROUP, a multi-spawn batch " +
            "automatically spreads its children across the group's members " +
            "(child 1 → member a, child 2 → member b, …), skipping members " +
            "already taken by other live sub-agents. " +
            "Spawning is idempotent while a task is active: if a run with the " +
            "same skill_name and query is still queued or running, the call " +
            "returns that existing run_id instead of creating a duplicate — " +
            "join or wait on it rather than spawning the same task again. " +
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
            // [T-subagent-model-routing] Delegate cheap work to a cheaper
            // model. Ported from ExTV's three-form resolver: a model uuid, a
            // provider model id, or a display name. Unknown/ambiguous values
            // FAIL with the valid options rather than silently inheriting the
            // parent model — a typo must not bill the expensive route.
            "model" to AgentToolParam(
                "string",
                "Optional. Run this sub-agent on a different (usually cheaper) " +
                    "model: a model uuid, a provider model id, or a display " +
                    "name (case-insensitive exact match). Omit to inherit the " +
                    "parent's model. An unknown or ambiguous value fails the " +
                    "spawn and the error lists what would have matched — do " +
                    "NOT retry with a guessed name. This argument may also " +
                    "name a MODEL GROUP (prefix 'group:' or a bare group " +
                    "name/id): in a multi-spawn turn each child then takes a " +
                    "different member of that group (1 号 → member a, 2 号 → " +
                    "member b, …), and members already occupied by other " +
                    "running sub-agents are skipped." +
                    (if (modelCatalogHint.isNotBlank()) " $modelCatalogHint" else "") +
                    (if (groupHint.isNotBlank()) " $groupHint" else ""),
            ),
            // [T-subagent-run-timeout] Wall-clock ceiling for ONE run.
            // RikkaMinis previously had no run-level timeout at all: an
            // unattended detached run could keep calling tools forever (each
            // turn is fine, the LOOP is not), and a hung sub-agent held a
            // scheduler permit nobody else could take.
            "timeout_seconds" to AgentToolParam(
                "integer",
                "Optional. Wall-clock cap for the whole run (default " +
                    "$DEFAULT_TIMEOUT_SECONDS; max $MAX_TIMEOUT_SECONDS). On " +
                    "expiry the run is stopped and reported as TIMED_OUT with " +
                    "its partial output — this is per RUN, not per tool call " +
                    "(shell_execute has its own). Detached runs are also " +
                    "subject to it, so a background spawn cannot outlive its " +
                    "usefulness.",
            ),
        ),
        required = listOf("tool_title", "skill_name", "query"),
        propertyOrdering = listOf("tool_title", "skill_name", "query", "run_until", "model", "timeout_seconds"),
    )

    // ── Config parsing ───────────────────────────────────────────────────

    /**
     * Parse sub-agent configuration from the skill's body (SKILL.md content).
     * Recognises YAML frontmatter fields:
     *   subagent: true
     *   max_turns: 12
     *   allowed_tools: [file_read, file_write, shell_execute]
     *   max_parallel: 2
     *
     * (FORBIDDEN tools — spawn_agent and the memory tools — can never be
     * re-enabled via allowed_tools; see [AgentCapabilities].)
     *
     * [T-subagent-output-from-model] `max_output_tokens` is deliberately NOT
     * read: the per-turn output ceiling lives in the model's own config
     * (管理提供商 → Max Output Tokens) and is resolved from the run's actual
     * provider at spawn time. A legacy skill still carrying the key is parsed
     * normally — the line is simply ignored.
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
        var allowedTools: Set<String>? = null
        var maxParallel = 1
        // [T-subagent-thinking] `thinking:` in the frontmatter. Accepts the
        // same names the chat's level picker shows (case-insensitive) plus a
        // couple of obvious synonyms; an unrecognised value stays OFF rather
        // than guessing a level the user did not ask for.
        var thinkingLevel = ThinkingLevel.OFF
        // [fix/subagent-plan-first] Default ON — see SubagentConfig.planFirst.
        var planFirst = true

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
                // [T-subagent-output-from-model] `max_output_tokens:` in the
                // frontmatter is intentionally not honoured any more — the
                // per-turn output ceiling comes from the model config (管理提供商
                // → Max Output Tokens) resolved at spawn time. The key is not
                // matched here, so a legacy skill carrying it is parsed
                // normally with the line ignored.
                trimmed.startsWith("max_parallel:") -> {
                    // [T-subagent-parallel] Optional concurrency knob for this
                    // skill; clamped to [1, 4] — the worker serializes model
                    // calls anyway, so more than 4 interleaved agents just
                    // queues without benefit.
                    maxParallel = (trimmed.substringAfter(":").trim().toIntOrNull() ?: 1)
                        .coerceIn(1, MAX_PARALLEL_CAP)
                }
                trimmed.startsWith("thinking:") -> {
                    thinkingLevel = parseThinkingLevelName(trimmed.substringAfter(":").trim())
                }
                // [fix/subagent-plan-first] `plan_first:` — opt OUT is the
                // only non-default value an author needs to know about.
                trimmed.startsWith("plan_first:") -> {
                    val value = trimmed.substringAfter(":").trim().lowercase()
                    planFirst = value != "false" && value != "no" && value != "off"
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
            allowedTools = allowedTools,
            maxParallel = maxParallel,
            thinkingLevel = thinkingLevel,
            planFirst = planFirst,
        )
    }

    /**
     * [T-subagent-thinking] Parse a frontmatter `thinking:` value into a
     * [ThinkingLevel]. Case-insensitive, accepting the names the chat's level
     * picker uses plus the two common synonyms ("off"/"none" and "medium").
     * Anything unrecognised resolves to [ThinkingLevel.OFF]: guessing a level
     * the skill did not ask for would spend the user's tokens on reasoning
     * they never requested.
     */
    fun parseThinkingLevelName(raw: String): ThinkingLevel = when (raw.trim().lowercase()) {
        "low" -> ThinkingLevel.LOW
        "medium", "med" -> ThinkingLevel.MEDIUM
        "high" -> ThinkingLevel.HIGH
        "xhigh", "x-high" -> ThinkingLevel.XHIGH
        "max" -> ThinkingLevel.MAX
        "ultra" -> ThinkingLevel.ULTRA
        else -> ThinkingLevel.OFF
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
     *
     * [T-subagent-runtime-preamble] When [runtimeContext] is non-null, a
     * short preamble (marker + context + user task) is prepended to the
     * body. This closes a real capability gap: the sub-agent previously had
     * no idea of the current date/time or where its durable artifacts
     * belong, and typically burned its first turn discovering both. The
     * parameter is optional so existing callers (and exact-match prompt
     * tests) keep their old behaviour by default.
     */
    fun buildSystemPrompt(skill: SkillInfo, runtimeContext: String? = null): String {
        val body = skill.body
        val base = when {
            body.isBlank() -> skill.description
            else -> {
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
                        if (content.isNotBlank()) content
                        // Frontmatter-only skill (no body content) → fall back to description.
                        else skill.description
                    } else {
                        body
                    }
                } else {
                    body
                }
            }
        }
        if (runtimeContext.isNullOrBlank()) return base
        return "$RUNTIME_PREAMBLE_MARKER\n$runtimeContext\n\n# Task from the parent agent\n\n$base"
    }

    /**
     * [T-subagent-runtime-preamble] Runtime context line for [buildSystemPrompt]:
     * current local date/time (the sub-agent previously had to spend a turn
     * running `date` to learn what day it is) and the durable workspace
     * root for artifacts. Pure function of [now] → JVM-testable.
     *
     * [T-subagent-dir-isolation] [runId] gives the run its OWN artifacts
     * directory under [SubagentSkill.ARTIFACTS_DIR]. Concurrent sub-agents
     * used to share `/var/minis/workspace/` as the stated deliverable root,
     * and the general-agent skill actively pushed them there ("create a task
     * subdirectory when several files are involved") — so a fan-out of N
     * research runs had N models independently choosing filenames like
     * `report.md` / `notes.md` in the same directory: last writer wins,
     * partial overwrites, and a parent that cannot tell which artifact came
     * from which run. A per-run directory removes the collision class
     * entirely and makes every path in the final report self-attributing.
     * Blank [runId] (tests, callers outside a run) keeps the shared root.
     */
    fun buildRuntimeContext(now: LocalDateTime = LocalDateTime.now(), runId: String = ""): String {
        val dir = artifactsDirFor(runId)
        return "Current date/time: " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) +
            " (local time). Durable artifacts directory: $dir (persists across the session — " +
            "write deliverables there, reference them by path)." +
            if (runId.isBlank()) ""
            else " This directory belongs to THIS sub-agent run; other agents have their own — " +
                "never write outside it, and reference your deliverables by these paths in the report."
    }

    /**
     * [T-subagent-dir-isolation] The artifacts directory for one run: the
     * shared root when no run id is known, else a dedicated
     * `/var/minis/workspace/subagents/<runId>/` directory. The run id is
     * unique within a chat (monotonic registry counter), and the chat owns
     * the session sandbox, so two concurrent runs can never share a
     * directory — that is the whole point.
     */
    fun artifactsDirFor(runId: String): String =
        if (runId.isBlank()) SHARED_WORKSPACE_ROOT else "$ARTIFACTS_DIR/$runId"

    /** Shared fallback root (pre-isolation behaviour, still the parent's own). */
    const val SHARED_WORKSPACE_ROOT = "/var/minis/workspace/"

    /** Per-run artifacts root — one subdirectory per run id below it. */
    const val ARTIFACTS_DIR = "/var/minis/workspace/subagents"

    // ── [fix/subagent-plan-first] Plan-first flow ───────────────────────────

    /**
     * The `<task-plan>` tag the planning turn MUST wrap its task list in.
     * Chosen as a plain XML-ish marker because every model family the app
     * routes through emits it reliably, and the runner strips it from the
     * visible narration either way — a model that ignores the instruction
     * costs nothing but the plan display.
     */
    const val PLAN_TAG = "task-plan"

    /**
     * Directive appended to the sub-agent's system prompt when the skill has
     * `plan_first` on (the default). Phrased so the planning turn is
     * CHEAP — outline only, no tool calls — and so a plan that changes
     * mid-run is expected, not forbidden: the list is communication, not a
     * contract the model must obey after reality disagrees with it.
     */
    const val PLAN_DIRECTIVE: String = """
        |# Task plan (required first)
        |
        |Before doing ANY work, output your plan for this task inside a <task-plan> block:
        |
        |<task-plan>
        |- step one
        |- step two
        |- step three
        |</task-plan>
        |
        |Rules:
        |- 3-7 steps, one line each, verb-first and concrete ("Search X", "Compare A vs B", "Write report to <path>").
        |- The plan turn makes NO tool calls — outline only, then STOP. Execution starts next turn.
        |- The list shows the user what you intend to do; deviate when the work demands it, but if the plan changes materially, emit an updated <task-plan> block before continuing.
    """.trimMargin()

    /** Marker prepended to the run transcript when the planning turn begins. */
    const val PLAN_TURN_NOTICE = "[planning]"

    /**
     * Extract the FIRST `<task-plan>…</task-plan>` block from [text] and
     * return (planText, textWithoutBlock). Returns (null, [text]) when no
     * complete block exists. Opened-but-never-closed tags are left alone —
     * a stream may legitimately be cut mid-tag, and stripping a half block
     * would eat narration around it.
     *
     * Pure + JVM-testable. Whitespace inside the tag name is not tolerated
     * (the directive pins the exact spelling); case follows the directive.
     */
    fun extractTaskPlan(text: String): Pair<String, String>? {
        val open = "<$PLAN_TAG>"
        val close = "</$PLAN_TAG>"
        val start = text.indexOf(open)
        if (start < 0) return null
        val contentStart = start + open.length
        val end = text.indexOf(close, contentStart)
        if (end < 0) return null
        val plan = text.substring(contentStart, end).trim()
        if (plan.isEmpty()) return null
        val cleaned = text.removeRange(start, end + close.length)
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return plan to cleaned
    }

    /**
     * Parse a plan's body into item lines: `- step` / `* step` / `1. step`
     * bullets, one per line. Non-bullet prose inside the block is kept as a
     * single trailing "note" item only when there are NO bullets at all
     * (prose-only plans still deserve display); with bullets present, stray
     * prose is dropped rather than guessed into steps.
     */
    fun parseTaskPlanItems(planText: String): List<String> {
        val items = planText.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val bulleted = Regex("^[-*•]\\s+(.*)$").find(line)
                ?: Regex("^\\d+[.)]\\s+(.*)$").find(line)
            bulleted?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (items.isNotEmpty()) return items.take(12)
        val prose = planText.trim()
        return if (prose.isEmpty()) emptyList() else listOf(prose.take(500))
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