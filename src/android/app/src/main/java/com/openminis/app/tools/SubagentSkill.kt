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
        // [port/upstream-subagent-status] Reading siblings' state is the
        // same leak as joining them: it hands a sub-agent context about
        // work it cannot see and was not asked about.
        SubagentOrchestrationTools.STATUS_NAME,
        // [port/upstream-subagent-steer] A sub-agent must never be able to
        // inject instructions into another run.
        SubagentOrchestrationTools.STEER_NAME,
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

    /**
     * [T-subagent-parallel] Hard cap on concurrent sub-agents per chat.
     *
     * [feat/subagent-budget-hardening] 4 → 6. The old number was sized
     * against "four agents talking to one relay"; today a chat routinely
     * fans out two research runs and two file/verification runs, and the
     * 4th slot is taken by whatever the previous batch left finishing. 6
     * still bounds the pill row and the chat's write concurrency.
     */
    const val MAX_PARALLEL_CAP = 6

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
     *
     * [feat/subagent-max-turns-75] The shipped sub-agent skills now declare
     * `max_turns: 75`, so 900s no longer covers the TURN budget: at the
     * ~15s/turn this calibration assumed, a 75-turn run needs ~1125s and was
     * being killed by the CLOCK at ~turn 60 — the turn budget would have been
     * decoration. The default moves to 1800s (30 min), which covers the full
     * 75 turns with margin for retry backoff, and keeps the 3600s max as the
     * ceiling for callers that want to raise `timeout_seconds` explicitly.
     *
     * The two budgets are deliberately kept in step: the wall-clock default
     * must be able to deliver the turn budget the skills advertise, or the
     * advertised budget is a lie. Raising one without the other is how a
     * 40-turn run "had time to spare" while a 75-turn run silently died at
     * turn 60 with a TIMED_OUT verdict that looks like the model's fault.
     */
    /**
     * [feat/subagent-parent-minutes-budget] This is now the LEGACY default:
     * it applies only when the caller supplies neither `max_minutes` nor
     * `timeout_seconds`. The normal path derives the wall clock from the
     * parent's minute estimate, so no hand-tuned default can fall out of
     * step with a turn number again.
     */
    const val DEFAULT_TIMEOUT_SECONDS = 1800
    const val MAX_TIMEOUT_SECONDS = 3600

    /**
     * [feat/subagent-max-turns-75] The fallback turn budget the shipped
     * sub-agent skills declare. This is the NUMBER the legacy wall-clock
     * path has to be able to deliver — see [wallClockSecondsForTurnBudget].
     *
     * [feat/subagent-parent-minutes-budget] It is no longer the run's
     * budget: the spawning parent's `max_minutes` answer decides (see
     * [resolveRunBudget]). This constant survives as the shipped fallback
     * the wiring guards pin, so a caller that supplies no estimate cannot
     * end up unbounded.
     */
    const val SHIPPED_SUBAGENT_MAX_TURNS = 75

    /**
     * [feat/subagent-max-turns-75] Seconds-per-turn assumed when checking
     * that a wall-clock budget can deliver a turn budget.
     *
     * 15s/turn is the pace the original 900s calibration was measured at
     * ("900s keeps ~60 turns of headroom for a healthy relay"). It is
     * deliberately CONSERVATIVE: the real cost of a turn is a model
     * round-trip plus its tool calls, and the calibration itself was taken
     * on a relay that was spending a third of the wall clock on
     * transient-retry backoff.
     */
    const val SECONDS_PER_TURN_ESTIMATE = 15

    /** [feat/subagent-budget-hardening] Bounds for a calibrated pace. */
    const val MIN_SECONDS_PER_TURN = 5
    const val MAX_SECONDS_PER_TURN = 120

    /**
     * [feat/subagent-max-turns-75] The wall-clock budget needed to deliver
     * [turns] turns, with a margin for retry backoff.
     *
     * Why this exists: the turn budget and the wall-clock budget are two
     * different limits, and the run stops at WHICHEVER comes first. A
     * skill that advertises `max_turns: 75` under a 900s default wall-clock
     * budget is advertising a budget the run can never reach — the clock
     * kills it first, and the parent sees a TIMED_OUT verdict that reads
     * like the model ran out of patience when in fact it was never given
     * the time it was promised.
     *
     * This function is the single place that relationship is expressed, so
     * the two numbers cannot drift apart silently.
     *
     * @param turns the advertised turn budget.
     * @return the wall-clock seconds required, or null when the ceiling
     *   ([MAX_TIMEOUT_SECONDS]) cannot deliver it — in which case the turn
     *   budget itself is unreachable no matter what timeout is chosen.
     */
    fun wallClockSecondsForTurnBudget(turns: Int): Int? {
        if (turns <= 0) return 0
        // 25% headroom over the raw estimate: retry backoff and a slow
        // relay are the norm, not the exception.
        val required = turns * SECONDS_PER_TURN_ESTIMATE * 5 / 4
        return if (required > MAX_TIMEOUT_SECONDS) null else required
    }

    // ── [feat/subagent-parent-minutes-budget] TIME IS THE BUDGET ─────────
    //
    // The parent agent now answers ONE question per spawn: how many minutes
    // does this task need? The turn budget is DERIVED from that answer
    // instead of being a fixed number baked into a skill's frontmatter.
    //
    // Why: `max_turns: 75` was a promise about a NUMBER OF ROUND-TRIPS, and
    // the only thing the parent can actually judge before delegating is how
    // long the work will take ("this is a ten-minute repo sweep"). Reading
    // a 75-turn budget told the parent nothing it could reason about, which
    // is why the wall clock and the turn budget had to be hand-kept in step
    // (DEFAULT_TIMEOUT_SECONDS 900 → 1800). One parent-supplied number
    // derives both, so they cannot drift.
    //
    // The skill's `max_turns` is NOT removed: it stays as the fallback when
    // a caller does not supply `max_minutes` (older callers, tests, direct
    // API use). Nothing in the shipped path relies on it any more.

    /** Minimum parent-declared budget: one minute. */
    const val MIN_MINUTES = 1

    /**
     * [feat/subagent-budget-hardening] No shell command may START with less
     * than this much wall clock left in the run: there would be no time left
     * to report, and the run would end TIMED_OUT having learned nothing new.
     */
    const val MIN_SHELL_BUDGET_SECONDS = 60

    /**
     * [feat/subagent-budget-hardening] Headroom kept back from a shell
     * timeout so the final turn can still write its report instead of being
     * cut off by the deadline mid-tool.
     */
    const val SHELL_BUDGET_RESERVE_SECONDS = 30

    /**
     * [feat/subagent-budget-hardening] Tools that can occupy the runtime for
     * MINUTES and have no timeout knob a sub-agent can pass (`browser_use`,
     * `web_search`). Below this much remaining wall clock they must not be
     * STARTED — the deadline is checked only at turn boundaries, so a long
     * tool begun now guarantees the run ends with no time to report.
     */
    const val MIN_LONG_TOOL_BUDGET_SECONDS = 60

    /** Tools that may still run on the FINAL turn — landing the report. */
    val REPORT_WRITE_TOOLS: Set<String> = setOf("file_write", "file_edit")

    /** Tools capped by the long-tool gate (shell has its own, tighter cap). */
    val LONG_RUNNING_TOOLS: Set<String> = setOf("shell_execute", "browser_use", "web_search")

    /**
     * Maximum parent-declared budget: the wall-clock ceiling expressed in
     * minutes (60 at the shipped 3600s [MAX_TIMEOUT_SECONDS]). A spawn that
     * asks for more is clamped and the clamp is reported in the tool result.
     */
    const val MAX_MINUTES = MAX_TIMEOUT_SECONDS / 60

    /**
     * [feat/subagent-parent-minutes-budget] Turn ceiling for a derived
     * budget: one hour at the calibrated 15 s/turn pace.
     *
     * The old 100 was explained as a token-reservation bound, but the
     * reservation is currently inert on purpose — `ChatViewModel` builds the
     * parent budget with `maxEstimatedTokens = null`, and
     * [com.openminis.app.agent.runtime.TokenBudgetChildGuard] is a permissive
     * no-op until that dimension is switched on. A 100-turn ceiling therefore
     * protected nothing while making a parent's honest "40 minutes" estimate
     * undeliverable (100 × 15 s = 25 min). 240 covers the full 3600 s wall
     * clock; if the token dimension is ever enabled, cap by TOKENS there —
     * not by silently shortening the turns the parent asked for.
     */
    const val MAX_TURNS_CEILING = 240

    /** Where a run's budget came from — surfaced so the parent can see it. */
    enum class BudgetSource {
        /** Derived from the parent's `max_minutes` answer. */
        PARENT_MINUTES,

        /** Legacy fallback: the skill's `max_turns` frontmatter. */
        SKILL_TURNS,
    }

    /**
     * A run's resolved budget: the model-turn ceiling the runner loop
     * enforces and the wall-clock seconds the scheduler/deadline enforces.
     *
     * The two travel TOGETHER from now on. A run stops at whichever comes
     * first, so deriving one without the other is how a "30-minute" task
     * dies at turn 60 with a TIMED_OUT verdict that looks like the model
     * gave up.
     */
    data class RunBudget(
        val maxTurns: Int,
        val timeoutSeconds: Int,
        /** The parent's minute answer, or null when the legacy path ran. */
        val parentMinutes: Int?,
        val source: BudgetSource,
    )

    /**
     * Turns that fit in [seconds] at the calibrated [SECONDS_PER_TURN_ESTIMATE]
     * pace, clamped to [1, MAX_TURNS_CEILING].
     *
     * Rounds UP: a 61-second budget must allow one turn, not zero.
     */
    /**
     * [feat/subagent-budget-hardening] Turn cap used for the COST ceiling
     * shown to the user. The ledger's "≤ N tok" is a theoretical maximum;
     * multiplying it by a 240-turn ceiling made a normal delegation read as
     * a million-token commitment. 60 turns is the realistic upper bound for
     * a single delegated task (15 min at the calibrated pace) and keeps the
     * number honest without pretending it is a reservation.
     */
    const val WORST_CASE_TURN_CAP = 60

    /**
     * [feat/subagent-budget-hardening] The run's cost CEILING for display.
     * NOT a reservation: the token guard is inert today (see
     * [MAX_TURNS_CEILING]); this only feeds the "≤ N tok" readout.
     */
    fun worstCaseTokensForBudget(maxTurns: Int, perTurnOutputCeiling: Int): Long =
        maxTurns.coerceIn(0, WORST_CASE_TURN_CAP).toLong() *
            perTurnOutputCeiling.coerceAtLeast(0).toLong()

    fun turnBudgetForTimeoutSeconds(
        seconds: Int,
        secondsPerTurn: Int = SECONDS_PER_TURN_ESTIMATE,
    ): Int {
        val bounded = seconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val pace = secondsPerTurn.coerceIn(MIN_SECONDS_PER_TURN, MAX_SECONDS_PER_TURN)
        val raw = (bounded + pace - 1) / pace
        return raw.coerceIn(1, MAX_TURNS_CEILING)
    }

    /** Turns that fit in [minutes] — the parent-facing form. */
    fun turnBudgetForMinutes(
        minutes: Int,
        secondsPerTurn: Int = SECONDS_PER_TURN_ESTIMATE,
    ): Int = turnBudgetForTimeoutSeconds(
        minutes.coerceIn(MIN_MINUTES, MAX_MINUTES) * 60,
        secondsPerTurn,
    )

    /**
     * Resolve the budget for one spawn.
     *
     * Priority:
     *  1. `max_minutes` > 0 — the parent judged the task; the wall-clock
     *     budget is that many minutes, the turn budget is derived from it.
     *     An explicit `timeout_seconds` still acts as a CAP (never as an
     *     extension): a parent that says "10 minutes" and also passes
     *     `timeout_seconds: 300` gets 5 minutes, because both were stated
     *     ceilings and the tighter one is the honest one.
     *  2. `max_minutes` absent — legacy behaviour, unchanged: the skill's
     *     `max_turns` sets the turns, and the wall clock is whatever can
     *     deliver them (or the caller's explicit `timeout_seconds`).
     *
     * @param parentMinutes `max_minutes` from the spawn args; <= 0 = absent.
     * @param explicitTimeoutSeconds `timeout_seconds`; <= 0 = absent.
     * @param skillMaxTurns the skill's parsed `max_turns` (legacy fallback).
     */
    fun resolveRunBudget(
        parentMinutes: Int,
        explicitTimeoutSeconds: Int,
        skillMaxTurns: Int,
        /**
         * [feat/subagent-budget-hardening] Calibrated seconds per turn from
         * THIS chat's finished runs (see SubagentBudgetCalibration), or the
         * 15 s default when there is not enough history.
         */
        secondsPerTurn: Int = SECONDS_PER_TURN_ESTIMATE,
    ): RunBudget {
        if (parentMinutes > 0) {
            val minutes = parentMinutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
            val asked = minutes * 60
            val timeoutSeconds = if (explicitTimeoutSeconds > 0) {
                minOf(asked, explicitTimeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS))
            } else {
                asked
            }
            return RunBudget(
                maxTurns = turnBudgetForTimeoutSeconds(timeoutSeconds, secondsPerTurn),
                timeoutSeconds = timeoutSeconds,
                parentMinutes = minutes,
                source = BudgetSource.PARENT_MINUTES,
            )
        }
        val turns = skillMaxTurns.coerceIn(1, MAX_TURNS_CEILING)
        val timeoutSeconds = if (explicitTimeoutSeconds > 0) {
            explicitTimeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        } else {
            (wallClockSecondsForTurnBudget(turns) ?: 0)
                .coerceAtLeast(DEFAULT_TIMEOUT_SECONDS)
                .coerceIn(1, MAX_TIMEOUT_SECONDS)
        }
        return RunBudget(
            maxTurns = turns,
            timeoutSeconds = timeoutSeconds,
            parentMinutes = null,
            source = BudgetSource.SKILL_TURNS,
        )
    }

    /** Budget fraction at which the child is told to start wrapping up. */
    const val BUDGET_WARN_FRACTION = 0.7

    /** Budget fraction at which the child is told to write the report NOW. */
    const val BUDGET_URGENT_FRACTION = 0.9

    /**
     * [feat/subagent-budget-hardening] The in-run budget note, re-stated as
     * the run approaches its limits.
     *
     * Why this exists: the runtime preamble tells the child its budget ONCE,
     * at birth. Measured behaviour is that the model then works as if it were
     * unbounded — it discovers the limit by dying at it, and the parent gets
     * TIMED_OUT with whatever was on screen. A short note appended to the
     * SYSTEM prompt (not history, so alternation is untouched) at 70 % and
     * 90 % is enough to change the shape of the ending: findings on disk,
     * a report instead of a corpse.
     *
     * @param remainingSeconds wall clock left in the run.
     * @param totalSeconds the run's wall clock budget (<= 0 = unknown).
     * @param turnsLeft model turns left.
     * @param maxTurns the turn budget (<= 0 = unknown).
     * @return "" while there is plenty of both; one line otherwise.
     */
    fun budgetNote(
        remainingSeconds: Int,
        totalSeconds: Int,
        turnsLeft: Int,
        maxTurns: Int,
    ): String {
        if (remainingSeconds <= 0) return ""
        val timeKnown = totalSeconds > 0
        val elapsedFraction = if (timeKnown) {
            1.0 - (remainingSeconds.toDouble() / totalSeconds.toDouble())
        } else {
            0.0
        }
        val turnLeftFraction = if (maxTurns > 0) {
            turnsLeft.toDouble() / maxTurns.toDouble()
        } else {
            1.0
        }
        val urgent = elapsedFraction >= BUDGET_URGENT_FRACTION ||
            (maxTurns > 0 && turnsLeft <= 2)
        val warn = elapsedFraction >= BUDGET_WARN_FRACTION ||
            (maxTurns > 0 && turnLeftFraction <= 0.25)
        if (!urgent && !warn) return ""

        val minutes = if (remainingSeconds >= 60) "${remainingSeconds / 60} min" else "${remainingSeconds}s"
        val turnsText = if (maxTurns > 0) " / $turnsLeft turn(s)" else ""
        return if (urgent) {
            "\n\n[Budget] ~$minutes$turnsText left — STOP starting new work. " +
                "Write the final report NOW with what you already have; " +
                "anything unfinished belongs in `gaps:`, not in another tool call."
        } else {
            "\n\n[Budget] ~$minutes$turnsText left. Write intermediate findings to disk now " +
                "and reserve time to write the final report — do not leave the report to the last turn."
        }
    }

    /**
     * One-line description of a resolved budget, for the spawn tool result /
     * runtime context. Kept here so the wording cannot drift between the two
     * places that render it.
     */
    fun describeBudget(budget: RunBudget): String = when (budget.source) {
        BudgetSource.PARENT_MINUTES ->
            "${budget.parentMinutes} min budget (parent estimate) → " +
                "${budget.timeoutSeconds}s wall clock / ${budget.maxTurns} turns"
        BudgetSource.SKILL_TURNS ->
            "skill max_turns fallback → ${budget.maxTurns} turns / " +
                "${budget.timeoutSeconds}s wall clock"
    }

    /**
     * [T-subagent-queue-budget] How long an INLINE spawn will sit in the
     * scheduler queue before the spawn fails with queue_timeout. The parent
     * turn is parked on an inline call returning, so unlike a detached spawn
     * (which may legitimately wait its whole budget for a slot) its queue
     * wait is user-visible freeze and gets its own short cap regardless of
     * timeout_seconds. 30s mirrors the foreground slot wait ExTV shipped.
     */
    const val INLINE_QUEUE_WAIT_MS = 30_000L

    /** [feat/subagent-budget-hardening] Floor for an inline queue wait. */
    const val MIN_INLINE_QUEUE_WAIT_MS = 30_000L

    /** Ceiling for an inline queue wait — no inline spawn parks for 10 min. */
    const val MAX_INLINE_QUEUE_WAIT_MS = 300_000L

    /**
     * [feat/subagent-budget-hardening] How long an INLINE spawn may wait for
     * a slot, scaled to the run's own budget.
     *
     * The flat 30 s was calibrated for short mechanical spawns; a research
     * run that legitimately needs 20 minutes failed to even START whenever
     * two other runs were in flight. A tenth of the task's budget (30 s …
     * 5 min) keeps the wait proportional: a 2-minute task still gives up in
     * 30 s, a 20-minute task waits up to 2 minutes.
     */
    fun inlineQueueWaitMs(budgetSeconds: Int): Long {
        if (budgetSeconds <= 0) return MIN_INLINE_QUEUE_WAIT_MS
        return (budgetSeconds * 1000L / 10)
            .coerceIn(MIN_INLINE_QUEUE_WAIT_MS, MAX_INLINE_QUEUE_WAIT_MS)
    }

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
        /**
         * [feat/subagent-parent-minutes-budget] LEGACY FALLBACK ONLY.
         *
         * This used to BE the run's budget. It is now what the run falls back
         * to when the spawning parent supplies no `max_minutes` estimate —
         * the normal path derives the turn ceiling (and the wall clock) from
         * that answer via [resolveRunBudget]. Kept parsed so older skills,
         * older callers and direct API use keep working unchanged, and so a
         * task with no duration estimate still has SOME bound.
         */
        val maxTurns: Int = DEFAULT_MAX_TURNS,
        /**
         * [feat/subagent-budget-hardening] `report_last_turn: true|false`
         * (default TRUE). When on, the FINAL turn may only call
         * file_write/file_edit so the run ends with a deliverable instead of
         * one last command. A skill whose last step genuinely must be a
         * command can opt out.
         */
        val reportLastTurn: Boolean = true,
        /**
         * [feat/subagent-budget-hardening] `read_only: true` in the
         * frontmatter. The allowlist already hides write tools; this flag
         * additionally makes the runtime PROBE for files the run created
         * through channels the allowlist cannot express (browser downloads
         * and screenshots) and report them.
         */
        val readOnly: Boolean = false,
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
        /**
         * [feat/subagent-roster-routing] The model this skill's sub-agent
         * should run on, from `model:` in the frontmatter. Null = inherit
         * whatever the spawn resolved to.
         *
         * TRAE has this field and it earns its place: a deep-research
         * sub-agent wants a strong model and a mechanical one wants a cheap
         * one, and without a per-skill default the parent has to decide on
         * every spawn (and get it wrong in the parent's context, where the
         * model catalogue may not even be visible).
         */
        val model: String? = null,
        /**
         * [feat/subagent-roster-routing] `disallowed_tools:` — a BLACKLIST
         * applied AFTER the allowlist. Null = no blacklist.
         *
         * The allowlist alone cannot express "everything except X": an
         * allowlist is an enumeration, so "all tools but the destructive
         * one" would have to list every tool. The blacklist is the other
         * half of the same permission model, and it wins over the
         * allowlist when both mention a tool.
         */
        val disallowedTools: Set<String> = emptySet(),
        /**
         * [feat/subagent-roster-routing] `mcp_servers:` — the MCP servers
         * this sub-agent may call, by name.
         *
         * This closes a hole the allowlist cannot: MCP tools are reached
         * through the SHELL in this app (the MCP CLI), so a sub-agent with
         * `shell_execute` could call ANY configured MCP server — including
         * ones the parent had gated. Naming the servers here makes the
         * grant explicit and per-skill.
         */
        val mcpServers: Set<String> = emptySet(),
    )

    // ── Tool definition ──────────────────────────────────────────────────

    fun definition(
        modelCatalogHint: String = "",
        groupHint: String = "",
        rosterHint: String = "",
        /**
         * [feat/subagent-budget-hardening] One line of EVIDENCE for the
         * budget question ("recent general-agent runs: median 6 min, n=7"),
         * built from this chat's finished runs by
         * SubagentBudgetCalibration. Empty when there is not enough history.
         */
        durationHint: String = "",
    ): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Spawn a sub-agent with its own system prompt, tool set, " +
            "and budget. The sub-agent runs independently and returns its final " +
            "result. Use this to delegate complex sub-tasks to a focused agent. " +
            "You MUST judge the run's budget yourself: pass `max_minutes` — how " +
            "long this task will take — and the wall clock AND the turn ceiling " +
            "are derived from your answer (~15 s per turn). Estimate the work, " +
            "and round UP when unsure: an under-estimate kills a healthy run " +
            "mid-task as TIMED_OUT. " +
            "You can also omit `agent` entirely: the built-in `general` profile runs " +
            "with the same tool set as you, so a one-off delegated task needs no " +
            "SKILL.md. Installed sub-agent skills still work and override the profile. " +
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
            "result is collected later via join_subagents / wait_any. " +
            // [T-list-agents-on-demand] Roster moved OUT of the always-on
            // schema: every installed skill name used to sit in this
            // description on every turn (~150 tokens) even though a spawn
            // rarely fails. The name list now lives in the failure path (see
            // SubagentRosterHint.unknownAgentMessage) and behind the
            // list_agents tool, where it is paid for only when needed.
            "Built-ins: general | researcher | coder | reviewer | ops. " +
            "For installed sub-agent skills call list_agents.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this sub-agent should do. " +
                    "Shown to the user as the live status label.",
            ),
            "agent" to AgentToolParam(
                "string",
                "Which agent to run. Either a BUILT-IN profile id — general | " +
                    "researcher | coder | reviewer | ops — or the name/id of an " +
                    "installed sub-agent skill. Omit for `general`. " +
                    "Do NOT invent a name: an unknown name is rejected and the " +
                    "delegation is lost for this turn.",
            ),
            "skill_name" to AgentToolParam(
                "string",
                "Legacy alias of `agent`, kept so older prompts keep working. " +
                    "Ignored when `agent` is present.",
            ),
            // [T-agent-profiles] 调用方现场收窄能力，不需要先造 skill。
            // 逗号分隔而不是 array：Gemini 对 ARRAY 节点强制要求 items，
            // 一个纯字符串参数能少一整类 schema 事故。
            "knowledge" to AgentToolParam(
                "string",
                "Optional. Comma-separated names of installed skills whose BODY should be " +
                    "attached as reference material (they do not need subagent: true). " +
                    "Use it to hand a built-in agent domain knowledge without giving it that " +
                    "skill's runtime: e.g. agent=\"general\", knowledge=\"my-ssh-cheatsheet\". " +
                    "This changes no permissions.",
            ),
            "capabilities" to AgentToolParam(
                "string",
                "Optional. Comma-separated capability names to NARROW the agent's " +
                    "tool set, e.g. \"file_read,web_search\". Allowed values: " +
                    "file_read, file_write, shell, browser, web_search, memory_read, " +
                    "memory_write, self_inspect. This can only REMOVE tools — asking " +
                    "for something the chosen agent does not have is ignored, never granted.",
            ),
            "role" to AgentToolParam(
                "string",
                "Optional one-off instruction appended to the agent's system prompt " +
                    "(<= 2000 chars), e.g. \"You are auditing a Rust parser.\". " +
                    "It does NOT change permissions or budget.",
            ),
            "query" to AgentToolParam(
                "string",
                "The task, question, or instruction to give to the sub-agent. " +
                    "Make it self-contained: the sub-agent cannot see this " +
                    "conversation and only knows what you write here.",
            ),
            // [feat/subagent-parent-minutes-budget] THE budget question.
            // The skill's fixed `max_turns` is no longer the run's budget —
            // YOU decide, per task, how many minutes this delegation needs,
            // and both the wall clock and the turn ceiling are derived from
            // your answer. Judge the WORK, not the number of round-trips:
            // a couple of commands is 1-2 minutes, a multi-step repo sweep
            // 5-10, deep research or a rebuild-and-verify loop 15-30.
            "max_minutes" to AgentToolParam(
                "integer",
                "REQUIRED. How many minutes this sub-agent task needs, in " +
                    "your judgement ($MIN_MINUTES-$MAX_MINUTES; larger values " +
                    "are clamped). This is the run's ONE budget: the " +
                    "wall-clock cap is this many minutes and the turn " +
                    "ceiling is derived from it (~$SECONDS_PER_TURN_ESTIMATE s " +
                    "per turn). Estimate the WORK, not the round-trips — " +
                    "1-2 min for a couple of commands or a single file read, " +
                    "5-10 min for a multi-step file/code sweep, 15-30 min for " +
                    "deep research or a rebuild-and-verify loop, 45-60 min " +
                    "only for genuinely long batches. An under-estimate kills " +
                    "a healthy run mid-task with a TIMED_OUT verdict; an " +
                    "over-estimate only wastes headroom, so when genuinely " +
                    "unsure round UP." +
                    (if (durationHint.isNotBlank()) " $durationHint" else ""),
            ),
            // [feat/subagent-budget-hardening] Continuation instead of a
            // re-run: a budget-stopped task keeps its evidence (artifacts on
            // disk + partial report), so paying for the same reads twice is
            // pure waste.
            "resume_run_id" to AgentToolParam(
                "string",
                "Optional. Resume a PREVIOUS run of this chat instead of " +
                    "starting from zero: pass its run_id (returned by spawn_agent, or " +
                    "listed by subagent_status) and put only WHAT IS LEFT in `query`. " +
                    "The run must be finished (TIMED_OUT / FAILED / CANCELLED — a live run " +
                    "is refused: join it instead). It may use a DIFFERENT skill_name — " +
                    "the hand-off names the skill that ran before and its tool set, so " +
                    "switching (e.g. read-only research → general-agent to write the " +
                    "result) is allowed; " +
                    "The continuation is handed the previous partial report, the artifacts " +
                    "it already wrote and their directory, so it should verify those files " +
                    "rather than redo the work — and it gets a FRESH max_minutes budget. " +
                    "The last ${SubagentRunRegistry.MAX_RETAINED_RUNS} runs stay in memory " +
                    "and terminal runs are ALSO archived on disk, so a resume survives an app " +
                    "restart; an unknown id is an error, not a fresh spawn.",
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
            //
            // [feat/subagent-parent-minutes-budget] Normally OMIT this: the
            // budget comes from `max_minutes`. When passed alongside it, it
            // can only TIGHTEN the budget, never extend it — the topic of
            // "how long does this take" belongs to the parent's estimate.
            "timeout_seconds" to AgentToolParam(
                "integer",
                "Optional, rarely needed. A tighter wall-clock cap for the " +
                    "whole run, in seconds (max $MAX_TIMEOUT_SECONDS). Budget " +
                    "comes from `max_minutes`; this can only SHORTEN it, and " +
                    "never extends a claimed budget. On expiry the run is " +
                    "stopped and reported as TIMED_OUT with its partial " +
                    "output — this is per RUN, not per tool call " +
                    "(shell_execute has its own). Detached runs are also " +
                    "subject to it, so a background spawn cannot outlive its " +
                    "usefulness.",
            ),
            // [port/upstream-subagent-progress] Opt-in PUSH. Off by default
            // on purpose: a progress note costs the parent a whole turn, and
            // subagent_status answers the same question on demand for free.
            // Only a DETACHED run can report: an inline run returns its own
            // result at the end of the call, so there is nobody to notify
            // while it works.
            "progress_report" to AgentToolParam(
                "string",
                "Optional, detached runs only. 'none' (default): the parent hears " +
                    "nothing until the run finishes. 'frequent': a short note every 15 " +
                    "seconds, ONLY when the run actually changed (new tool, new turn, new " +
                    "text) — a run sitting in one long command stays quiet. 'moderate': " +
                    "one note a minute regardless. Every note is a message in this " +
                    "conversation and costs you a turn, so leave it at 'none' unless you " +
                    "genuinely need to react mid-run. Capped at " +
                    "${SubagentProgress.MAX_PROGRESS_MESSAGES} notes per run. Ignored when " +
                    "run_until is not 'detach'.",
                enumValues = listOf("none", "frequent", "moderate"),
            ),
        ),
        required = listOf("tool_title", "query", "max_minutes"),
        propertyOrdering = listOf(
            "tool_title", "agent", "skill_name", "query", "max_minutes",
            "knowledge", "capabilities", "role", "resume_run_id",
            "run_until", "model", "timeout_seconds", "progress_report",
        ),
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
     * [feat/subagent-parent-minutes-budget] `max_turns` is a FALLBACK budget:
     * the run's real budget comes from the spawning parent's `max_minutes`
     * answer (see [resolveRunBudget]). The key is still parsed so a skill
     * spawned by a caller that supplies no estimate keeps a sane bound.
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
    fun parseSubagentConfig(
        skill: SkillInfo,
        /**
         * [T-skill-permissions-snapshot] 数据库里已确认过的权限行。
         *
         * 非 null 时，文件里的权限行被**丢弃**，只用这份 + 文件里的非权限行
         * 重新拼出 frontmatter 再解析。于是 agent 用 shell 改 SKILL.md 里的
         * `allowed_tools` 不再有任何效果——运行时不看文件的那一段。
         *
         * null 表示调用方没有快照能力（测试、profile 合成），保持旧行为。
         */
        permissionsSnapshot: String? = null,
    ): SubagentConfig {
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

        // [T-skill-permissions-snapshot] 用「文件里的非权限行 + 库里的权限行」
        // 重建一份 frontmatter。空快照 = 已确认"没有权限声明"，此时文件里
        // 新塞进来的 `subagent: true` 也不会生效。
        val effectiveFrontmatter = if (permissionsSnapshot == null) {
            frontmatter
        } else {
            val kept = SkillWriteGuard.splitGuarded(frontmatter).first
            when {
                kept.isBlank() -> permissionsSnapshot
                permissionsSnapshot.isBlank() -> kept
                else -> kept + "\n" + permissionsSnapshot
            }
        }

        // [feat/subagent-roster-routing] A real frontmatter parser, not a
        // hand-rolled key matcher.
        //
        // The old loop matched keys with `startsWith("key:")` and parsed
        // list values in two separate branches, which broke on:
        //  - a value containing a colon (`description: use this: for that`
        //    lost everything after the second colon);
        //  - a quoted value containing a comma (`allowed_tools: ["a, b"]`
        //    split inside the quotes);
        //  - a `#` inside a value (treated as a comment start).
        //
        // SubagentFrontmatter handles those, and is unit-tested on the
        // plain JVM.
        val fm = SubagentFrontmatter.parse(effectiveFrontmatter)

        return SubagentConfig(
            isSubagent = fm.boolean("subagent") ?: false,
            maxTurns = (fm.int("max_turns") ?: DEFAULT_MAX_TURNS).coerceIn(1, MAX_TURNS_CEILING),
            reportLastTurn = fm.boolean("report_last_turn") ?: true,
            readOnly = fm.boolean("read_only") ?: false,
            allowedTools = fm.list("allowed_tools")?.toSet(),
            maxParallel = (fm.int("max_parallel") ?: 1).coerceIn(1, MAX_PARALLEL_CAP),
            thinkingLevel = parseThinkingLevelName(fm.string("thinking").orEmpty()),
            planFirst = fm.boolean("plan_first") ?: true,
            // [feat/subagent-roster-routing] The TRAE-shaped fields.
            model = fm.string("model")?.trim()?.takeIf { it.isNotEmpty() },
            disallowedTools = fm.list("disallowed_tools")?.toSet().orEmpty(),
            mcpServers = fm.list("mcp_servers")?.toSet().orEmpty(),
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
        disallowedTools: Set<String> = emptySet(),
    ): List<AgentToolDefinition> {
        return allTools.filter { tool ->
            AgentCapabilities.isToolGrantableToSubagent(tool.name) &&
                SubagentAllowedTools.isAllowed(tool.name, allowedTools, disallowedTools)
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
    fun buildSystemPrompt(
        skill: SkillInfo,
        runtimeContext: String? = null,
        extraInstructions: String? = null,
    ): String {
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
        // [feat/subagent-roster-routing] A CONFIGURED roster entry carries
        // INSTRUCTIONS — the speciality the user wrote for that agent. It
        // is APPENDED, not substituted: the skill body is the runtime
        // contract (how to work, what to report), the instructions are the
        // mission. Appending keeps both, and putting the mission last
        // means it is the part the model remembers when the prompt is long.
        val withInstructions = if (extraInstructions.isNullOrBlank()) {
            base
        } else {
            "$base\n\n# Mission from the configured sub-agent roster\n\n$extraInstructions"
        }
        if (runtimeContext.isNullOrBlank()) return withInstructions
        return "$RUNTIME_PREAMBLE_MARKER\n$runtimeContext\n\n# Task from the parent agent\n\n$withInstructions"
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
    /**
     * The runtime preamble the sub-agent's system prompt starts with.
     *
     * @param now local wall-clock time.
     * @param runId the run's id, or blank outside a run.
     * @param maxTurns the run's TURN budget — how many model turns the run
     *   gets before the runtime stops it. The skill body tells the model to
     *   "stay within your turn budget" (general-agent SKILL.md), but the
     *   budget itself lives in the frontmatter, which
     *   [buildSystemPrompt] STRIPS before the body reaches the model. The
     *   model was therefore told to respect a limit it was never told.
     * @param timeoutSeconds the run's WALL-CLOCK budget in seconds, or null
     *   when unknown. Same problem, same fix: the model cannot pace itself
     *   against a deadline it cannot see.
     */
    fun buildRuntimeContext(
        now: LocalDateTime = LocalDateTime.now(),
        runId: String = "",
        maxTurns: Int? = null,
        timeoutSeconds: Int? = null,
    ): String {
        val dir = artifactsDirFor(runId)
        val budget = buildString {
            if (maxTurns != null && maxTurns > 0) {
                append(" Your turn budget is $maxTurns model turns ")
                append("(including the planning turn), so pace the work to fit ")
                append("and write intermediate findings to disk early.")
            }
            if (timeoutSeconds != null && timeoutSeconds > 0) {
                val minutes = timeoutSeconds / 60
                append(" Your wall-clock budget is ")
                append(if (minutes >= 1) "$minutes minute(s)" else "$timeoutSeconds second(s)")
                append("; the run stops when either limit is reached, so leave time to report.")
            }
        }
        return "Current date/time: " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) +
            " (local time). Durable artifacts directory: $dir (persists across the session — " +
            "write deliverables there, reference them by path)." +
            budget +
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
     *
     * [fix/subagent-plan-contract] The plan contract now lives in
     * [SubagentPlanContract] — one source of truth shared by the system
     * prompt, the execution nudge and the skill body, so the three cannot
     * contradict each other. These two remain as delegates so every
     * existing caller and test keeps working.
     */
    const val PLAN_TAG = SubagentPlanContract.PLAN_TAG

    /**
     * Directive appended to the sub-agent's system prompt when the skill has
     * `plan_first` on (the default). Phrased so the planning turn is
     * CHEAP — outline only, no tool calls — and so a plan that changes
     * mid-run is expected, not forbidden: the list is communication, not a
     * contract the model must obey after reality disagrees with it.
     *
     * [fix/subagent-plan-contract] Delegates to
     * [SubagentPlanContract.DIRECTIVE] — the single source of truth.
     */
    val PLAN_DIRECTIVE: String = SubagentPlanContract.DIRECTIVE

    /** Marker prepended to the run transcript when the planning turn begins. */
    const val PLAN_TURN_NOTICE = SubagentPlanContract.PLAN_TURN_NOTICE

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