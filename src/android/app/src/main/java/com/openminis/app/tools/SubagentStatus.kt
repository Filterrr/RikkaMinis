package com.openminis.app.tools

import com.openminis.app.tools.SubagentRunRegistry.Run
import com.openminis.app.tools.SubagentRunRegistry.RunStatus
import com.openminis.app.tools.SubagentRunRegistry.ToolStepStatus
import java.util.Locale

/**
 * [port/upstream-subagent-status] The `subagent_status` read-out — what the
 * sub-agents of THIS conversation are doing right now, and what they produced.
 *
 * Why this exists: the registry already held every fact a parent needs
 * ([Run] carries state, turn, elapsed, current tool, model, tokens, verdict
 * and result), and the second-level page rendered all of it — but the MODEL
 * had no way to ask. It could spawn, join, wait and cancel, and nothing else.
 * The consequences were concrete:
 *
 *  - a detached run that went quiet could only be inspected by BLOCKING on
 *    `join_subagents` / `wait_any` until a timeout, or by cancelling it to
 *    find out what it had done;
 *  - the model had no way to notice that a run it believed was working had
 *    already failed, so it kept waiting on a job that would never deliver;
 *  - "how much has this cost so far?" was unanswerable before the run ended.
 *
 * The upstream (OpenMinis `subagent_task` action=`status`) shape is the same
 * idea folded into the delegation tool. RikkaMinis keeps its split tool
 * surface — [SubagentOrchestrationTools] already separates join / wait / cancel
 * — so this is the fourth sibling rather than a new action on `spawn_agent`.
 *
 * EVERYTHING HERE IS PURE. No clock, no registry, no Android: the caller
 * passes the snapshot and the current time, which is what makes the exact
 * wording unit-testable on the plain JVM.
 */
object SubagentStatus {

    /**
     * Cap on runs listed.
     *
     * A status dump is a tool RESULT, so it lands in the parent's context and
     * is paid for on every later turn. Twenty runs at ~5 lines each is a
     * bounded ~2 KB; the count is reported even when the list is truncated, so
     * the model knows to narrow with `run_ids` instead of assuming that is all
     * of them.
     */
    const val MAX_RUNS = 20

    /**
     * Result preview length.
     *
     * The full report already reached the parent through the spawn / join
     * result or the wake envelope; repeating it here would pay for the same
     * text twice. Enough to recognise which run is which, not enough to
     * substitute for reading it.
     */
    const val RESULT_PREVIEW_CHARS = 400

    /** Error preview length — an exception message, not a stack trace. */
    const val ERROR_PREVIEW_CHARS = 240

    /** Report the whole conversation's runs, newest activity first. */
    fun format(runs: List<Run>, nowMs: Long, maxRuns: Int = MAX_RUNS): String {
        if (runs.isEmpty()) return "No sub-agent runs in this conversation yet."
        val ordered = order(runs)
        val shown = ordered.take(maxRuns)
        val header = headerLine(ordered)
        val body = shown.joinToString("\n\n") { line(it, nowMs) }
        return buildString {
            append(header)
            append("\n\n")
            append(body)
            if (ordered.size > shown.size) {
                append("\n\n(+${ordered.size - shown.size} more not shown — ")
                append("narrow with run_ids)")
            }
            append("\n\n")
            append(advice(ordered))
        }
    }

    /**
     * Active work first, then the newest terminal runs.
     *
     * Deliberately NOT chronological: the parent reads this to decide what to
     * do next, and what it can act on is what is still running. A finished run
     * is kept only as evidence, so it belongs at the bottom in the order it
     * ended (most recent first — the one most likely to be the subject of the
     * next question).
     */
    fun order(runs: List<Run>): List<Run> {
        val (active, terminal) = runs.partition { it.status == RunStatus.RUNNING || it.status == RunStatus.QUEUED }
        val activeSorted = active.sortedWith(
            // RUNNING before QUEUED: a queued run cannot make progress until a
            // permit frees, so it is the less actionable of the two.
            compareBy<Run> { if (it.status == RunStatus.RUNNING) 0 else 1 }
                .thenBy { it.startedAtMs },
        )
        val terminalSorted = terminal.sortedByDescending { if (it.endedAtMs > 0) it.endedAtMs else it.startedAtMs }
        return activeSorted + terminalSorted
    }

    fun headerLine(runs: List<Run>): String {
        val running = runs.count { it.status == RunStatus.RUNNING }
        val queued = runs.count { it.status == RunStatus.QUEUED }
        val done = runs.size - running - queued
        val parts = mutableListOf<String>()
        if (running > 0) parts += "$running running"
        if (queued > 0) parts += "$queued queued"
        if (done > 0) parts += "$done finished"
        return "${runs.size} sub-agent run${if (runs.size == 1) "" else "s"}: " + parts.joinToString(", ")
    }

    /** One run, as a short block. Public for the UI/tests to reuse. */
    fun line(run: Run, nowMs: Long): String = buildString {
        append("#").append(shortId(run.id))
        run.title.takeIf { it.isNotBlank() }?.let { append(" · ").append(it.trim()) }
        run.skillName.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        if (run.groupId.isNotBlank()) append(" · group ").append(run.groupId)

        append("\n  state:   ").append(stateLabel(run))
        append(" (").append(elapsedLabel(elapsedMs(run, nowMs))).append(")")
        if (run.turn > 0) {
            append(", turn ").append(run.turn)
            if (run.maxTurns > 0) append("/").append(run.maxTurns)
        }
        run.queueWaitMs?.takeIf { it > 0 }?.let { append(", queued ").append(elapsedLabel(it)) }

        // What it is doing RIGHT NOW — the answer to "is it stuck?".
        currentActivity(run)?.let { append("\n  now:     ").append(it) }

        run.modelLabel.takeIf { it.isNotBlank() }?.let { append("\n  model:   ").append(it) }
        costLabel(run)?.let { append("\n  tokens:  ").append(it) }

        // A terminal run's own words. Preview only: the whole report already
        // reached the parent on the delivery path, and repeating it here is a
        // second charge for the same text.
        if (!run.isActive) {
            val preview = run.resultText.trim()
            if (preview.isNotEmpty()) {
                append("\n  result:  ").append(preview(preview, RESULT_PREVIEW_CHARS))
            } else {
                // Absence is a fact, not an empty report. Say so rather than
                // leaving the line off and letting the parent assume success.
                append("\n  result:  (nothing delivered)")
            }
            run.error?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("\n  error:   ").append(preview(it, ERROR_PREVIEW_CHARS))
            }
            // [port/upstream-subagent-error-kind] The actionable half: what
            // KIND of failure, and therefore what the next move is. Omitted
            // when the text cannot be classified — a wrong category is worse
            // than none.
            SubagentErrorKind.annotation(run.error)?.let {
                append("\n  why:     ").append(it)
            }
        }
    }

    /**
     * The run's verdict.
     *
     * [RunStatus.SUCCESS] is only "the loop reached its terminal write". The
     * verdict about the WORK is [Run.runOutcome], and the two disagree often
     * enough that shipping the loop status as the answer is what
     * [SubagentRunAssessment] exists to prevent — a DEGRADED digest reported as
     * SUCCESS is exactly the failure that made a parent read a runtime digest
     * as findings. So: a finished run's label comes from the verdict, and only
     * a run that has no verdict falls back to the raw status.
     */
    fun stateLabel(run: Run): String = when (run.status) {
        RunStatus.QUEUED -> "QUEUED"
        RunStatus.RUNNING -> "RUNNING"
        // Cancellation is the CALLER's action, not a judgement about the work,
        // so it outranks the verdict (which cancel records as FAILED).
        RunStatus.CANCELLED -> "CANCELLED"
        RunStatus.TIMED_OUT -> "TIMED_OUT"
        RunStatus.FAILED -> "FAILED"
        RunStatus.SUCCESS ->
            run.runOutcome?.let { SubagentRunAssessment.statusLabel(it) } ?: "SUCCESS"
    }

    /**
     * The tool call in flight, or the last thing it did.
     *
     * A running run with no in-flight step is between turns (the model is
     * thinking) — said explicitly, because "no step" otherwise reads as
     * "nothing is happening", which is the ambiguity this whole tool removes.
     */
    fun currentActivity(run: Run): String? {
        if (!run.isActive) return null
        val running = run.steps.lastOrNull { it.status == ToolStepStatus.RUNNING }
        if (running != null) {
            val label = running.toolTitle.takeIf { it.isNotBlank() }
            return running.toolName + (label?.let { " — $it" } ?: "")
        }
        val last = run.steps.lastOrNull() ?: return "starting (no tool call yet)"
        val label = last.toolTitle.takeIf { it.isNotBlank() }
        return "between turns, last: " + last.toolName + (label?.let { " — $it" } ?: "")
    }

    /** "in 12.3k / out 1.9k", or null when the provider reported nothing. */
    fun costLabel(run: Run): String? {
        val hasIn = run.tokensIn >= 0
        val hasOut = run.tokensOut >= 0
        if (!hasIn && !hasOut) return null
        return buildString {
            append("in ").append(if (hasIn) kilo(run.tokensIn) else "unknown")
            append(" / out ").append(if (hasOut) kilo(run.tokensOut) else "unknown")
        }
    }

    fun elapsedMs(run: Run, nowMs: Long): Long {
        val end = if (run.endedAtMs > 0) run.endedAtMs else nowMs
        return (end - run.startedAtMs).coerceAtLeast(0L)
    }

    /** Coarse, bounded, locale-free — this is read by a model, not a human. */
    fun elapsedLabel(ms: Long): String {
        val totalSec = ms / 1000L
        return when {
            totalSec < 60L -> "${totalSec}s"
            totalSec < 3600L -> "${totalSec / 60L}m${totalSec % 60L}s"
            else -> "${totalSec / 3600L}h${(totalSec % 3600L) / 60L}m"
        }
    }

    fun kilo(n: Int): String =
        if (n < 1000) "$n" else String.format(Locale.US, "%.1fk", n / 1000.0)

    /** Run ids are long; the prefix is what every other surface accepts. */
    fun shortId(id: String): String = if (id.length <= 8) id else id.take(8)

    private fun preview(text: String, max: Int): String {
        // Collapse newlines: a multi-line preview would break the one-line-per-
        // field shape the rest of the block uses and make it harder to scan.
        val one = text.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= max) one else one.take(max).trimEnd() + "…"
    }

    /**
     * The next move, named.
     *
     * The parent's standing instruction is "never poll in a loop", so a status
     * report that leaves the model to invent a follow-up is a trap. This says
     * which of the four orchestration tools applies to what is on screen.
     */
    private fun advice(runs: List<Run>): String = when {
        runs.any { it.status == RunStatus.RUNNING || it.status == RunStatus.QUEUED } ->
            "Running runs keep going; collect them with join_subagents (waits) or " +
                "wait_any (first to finish). Do not poll status in a loop — " +
                "a spawn with run_until='done' returns its own result."
        runs.any { it.status == RunStatus.CANCELLED } ->
            "Nothing is running. Cancelled runs can be discarded; every other " +
                "run here is finished, and its result above is final."
        runs.any { !it.isActive } ->
            "Nothing is running. These runs are finished — their results are final; " +
                "re-read one with run_ids if you need more than the preview."
        else -> "Nothing is running."
    }
}