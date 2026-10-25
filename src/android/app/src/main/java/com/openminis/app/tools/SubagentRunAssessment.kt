package com.openminis.app.tools

/**
 * [fix/subagent-run-assessment] One verdict about a sub-agent run, derived
 * from facts the runtime already holds.
 *
 * Why this exists: three separate defects shipped from the same blind spot —
 * the runtime equated "the loop finished" with "the work was done".
 *
 * - a plan-first run ended after its plan turn, zero tools executed (PR #66)
 * - a per-turn repair appended a fabricated interruption every turn (PR #67)
 * - a husk report (`<parameter name="status">done</parameter>`) was handed to
 *   the parent as findings (PR #68)
 *
 * Each fix closed its own symptom. The structural gap stayed: `Status` only
 * had SUCCESS/FAILED, so "5 turns, 0 tool calls" and "5 turns, 12 tool calls"
 * produced the SAME header, and a parent had to re-read the body to tell them
 * apart. This object supplies the missing axis — what actually happened — so
 * the delivery surfaces can say it once, up front.
 *
 * Pure logic: no Android, no registry, no clock. Every input is a fact the
 * caller passes in.
 */
object SubagentRunAssessment {

    /**
     * What became of the run's WORK (not its loop).
     *
     * Ordered from most to least actionable. Callers that only need a boolean
     * should use [isDeliverable]; callers that phrase the result to the parent
     * should branch on the value itself.
     */
    enum class Outcome {
        /** Tools executed and the model wrote the report. The normal case. */
        COMPLETED,

        /** Tools executed, but the model produced no findings; the text is a runtime digest. */
        DEGRADED,

        /** The model declared `status: partial` — some scope remains. */
        PARTIAL,

        /** The model declared `status: failed` — it could not do the task. */
        DECLARED_FAILED,

        /** The caller asked for a first-turn readout and got it. */
        STOPPED_EARLY,

        /** No tool call executed. Whatever text exists is planning, not findings. */
        UNSTARTED,

        /** Tools ran but nothing was delivered — not even salvaged narration. */
        NO_OUTPUT,

        /** The run exceeded its wall-clock budget. */
        TIMED_OUT,

        /** The loop threw. */
        FAILED,

        /**
         * The run's terminal state could not be determined.
         *
         * The registry row was pruned (or never written) before the terminal
         * verdict could be read back, so the runtime knows the run existed
         * but not what became of it. Conservative by construction: the parent
         * must not treat any text that arrives with this verdict as findings.
         *
         * Distinct from FAILED on purpose — "we don't know" and "it errored"
         * call for different follow-ups (re-spawn vs. read the error).
         */
        UNKNOWN,
    }

    /** Where the delivered report text came from. */
    enum class ReportSource {
        /** The model wrote it. */
        MODEL,

        /** The runtime rebuilt it from tool output because the model's text was a husk. */
        RUNTIME_DIGEST,

        /** Partial narration salvaged from an aborted run. */
        PARTIAL,

        /** Nothing was delivered. */
        NONE,
    }

    /**
     * Derive the verdict.
     *
     * Precedence is deliberate and each step is load-bearing:
     *
     * 1. A thrown loop is FAILED no matter what else is true — a run that
     *    crashed did not complete, and a stale tool count must not mask it.
     * 2. Exceeding the wall clock is TIMED_OUT, likewise.
     * 3. `executedToolCalls == 0` is UNSTARTED **before** any report-shape
     *    signal. If nothing ran, no amount of confident prose changes what the
     *    parent should do next: start the work, not resume it.
     * 4. A run that executed tools but delivered no text at all is NO_OUTPUT.
     *    Checked here, before the report-shape signals, because there is no
     *    report to shape — and calling it COMPLETED would hand the parent a
     *    deliverable that is literally empty.
     * 5. A runtime digest is DEGRADED — the evidence is real but the
     *    interpretation is missing, which is exactly what the parent needs to
     *    know before quoting it as a conclusion.
     * 6. A model-declared `failed` is DECLARED_FAILED. The model said it could
     *    not do the task; a healthy tool count does not override that.
     * 7. A model-declared `partial` is PARTIAL.
     * 8. A caller-requested first-turn stop is STOPPED_EARLY — expected, not a
     *    defect, but not a completed run either.
     * 9. Otherwise COMPLETED.
     */
    fun assess(
        loopFailed: Boolean,
        timedOut: Boolean,
        stoppedEarly: Boolean,
        executedToolCalls: Int,
        reportSource: ReportSource,
        declaredStatus: String?,
    ): Outcome = when {
        loopFailed -> Outcome.FAILED
        timedOut -> Outcome.TIMED_OUT
        executedToolCalls <= 0 -> Outcome.UNSTARTED
        reportSource == ReportSource.NONE -> Outcome.NO_OUTPUT
        reportSource == ReportSource.RUNTIME_DIGEST -> Outcome.DEGRADED
        declaredStatus?.lowercase() == "failed" -> Outcome.DECLARED_FAILED
        declaredStatus?.lowercase() == "partial" -> Outcome.PARTIAL
        stoppedEarly -> Outcome.STOPPED_EARLY
        else -> Outcome.COMPLETED
    }

    /**
     * True when [source] IS the model's own words — either its finished report
     * or the narration it managed to produce before an abort. A runtime digest
     * is excluded: it is assembled by the runtime, so any `status:` line in it
     * is the runtime's, and must not be reported as the model's self-assessment.
     */
    fun isModelAuthored(source: ReportSource): Boolean =
        source == ReportSource.MODEL || source == ReportSource.PARTIAL

    /**
     * True when the parent may treat this run's report as the work it asked
     * for. Only COMPLETED qualifies: DEGRADED still needs the parent to draw
     * its own conclusions, PARTIAL and DECLARED_FAILED say the task is
     * unfinished or impossible, and the rest did not deliver.
     */
    fun isDeliverable(outcome: Outcome): Boolean = outcome == Outcome.COMPLETED

    /**
     * The verdict a run ACTUALLY has, once the binary `success` flag and the
     * [Outcome] are reconciled.
     *
     * [fix/subagent-verdict-consistency-v3] The two fields can disagree:
     * `JobOutcome`'s defaults are `success = false` with
     * `outcome = COMPLETED`, so a caller that sets only `success = false`
     * produces the contradictory pair "the model wrote a report / the run
     * failed". Reading either field alone then tells the parent something
     * false — a DEGRADED digest labelled SUCCESS, or a crashed run
     * labelled COMPLETED.
     *
     * The reconciliation rule is deliberately narrow: only the pair
     * (success = false, COMPLETED) is contradictory, because COMPLETED is
     * the single verdict that claims the model delivered a report. Every
     * other verdict already says something about the work, and a
     * `success = false` alongside it is consistent (a TIMED_OUT run is
     * still a timed-out run).
     */
    fun effective(outcome: Outcome, success: Boolean): Outcome =
        if (!success && outcome == Outcome.COMPLETED) Outcome.FAILED else outcome

    /**
     * A short UPPER-CASE token for [outcome].
     *
     * Used in compact status lines — the per-run header of a join section,
     * the wait_any winner line — where the full [headline] would be too
     * verbose. COMPLETED maps to "SUCCESS" so the common case keeps the
     * word every parent already reads.
     */
    fun statusLabel(outcome: Outcome): String = when (outcome) {
        Outcome.COMPLETED -> "SUCCESS"
        Outcome.DEGRADED -> "DEGRADED"
        Outcome.PARTIAL -> "PARTIAL"
        Outcome.DECLARED_FAILED -> "DECLARED_FAILED"
        Outcome.STOPPED_EARLY -> "STOPPED_EARLY"
        Outcome.UNSTARTED -> "UNSTARTED"
        Outcome.NO_OUTPUT -> "NO_OUTPUT"
        Outcome.TIMED_OUT -> "TIMED_OUT"
        Outcome.FAILED -> "FAILED"
        Outcome.UNKNOWN -> "UNKNOWN"
    }

    /**
     * The status word a parent-facing summary should show.
     *
     * [fix/subagent-verdict-consistency-v3] A run has TWO independent facts
     * and a summary that shows only one of them is misleading:
     *
     *   - the RUNTIME status: how the run ENDED — it ran out of clock, the
     *     user stopped it, the loop threw.
     *   - the WORK verdict: what the run DELIVERED — a full report, a
     *     runtime digest, a partial result, nothing.
     *
     * A timed-out run that left usable notes is TIMED_OUT, not FAILED; a
     * run that finished cleanly but only produced a digest is DEGRADED, not
     * SUCCESS. Conflating them is how a digest reached a parent as if it
     * were the answer.
     *
     * The runtime status wins when it carries information the verdict cannot
     * (TIMED_OUT, CANCELLED); otherwise the verdict speaks for the work.
     *
     * @param runtimeStatus the registry's RunStatus name, or null when the
     *   registry row is gone.
     * @param verdict the reconciled work verdict.
     * @param hasReport whether any report text came back.
     */
    fun summaryStatus(runtimeStatus: String?, verdict: Outcome, hasReport: Boolean): String {
        val label = statusLabel(verdict)
        return when (runtimeStatus) {
            "TIMED_OUT" -> if (hasReport) "TIMED_OUT (partial)" else "TIMED_OUT"
            "CANCELLED" -> "CANCELLED"
            else -> label
        }
    }

    /**
     * One line naming the state, for the prompt text the parent reads. Kept
     * short — it sits above the report and must not compete with it.
     */
    fun headline(outcome: Outcome, executedToolCalls: Int, reportSource: ReportSource): String {
        val tools = "$executedToolCalls tool call" + if (executedToolCalls == 1) "" else "s"
        return when (outcome) {
            Outcome.COMPLETED ->
                "[completed] $tools executed; the report below is the model's own account."
            Outcome.DEGRADED ->
                "[degraded] $tools executed, but the model produced no findings - the report " +
                    "below is a RUNTIME-ASSEMBLED digest of raw tool output. Treat it as evidence, " +
                    "not as the model's interpretation, and draw the conclusions yourself."
            Outcome.PARTIAL ->
                "[partial] $tools executed; the model declared the task unfinished. The report " +
                    "states what remains."
            Outcome.DECLARED_FAILED ->
                "[declared failed] $tools executed, but the model reports it could not complete " +
                    "the task. What follows is its error report, not a deliverable - read its " +
                    "gaps before deciding whether to re-spawn."
            Outcome.STOPPED_EARLY ->
                "[stopped early] $tools executed in the first turn, as requested. This is a " +
                    "readout, not a finished task."
            Outcome.UNSTARTED ->
                "[unstarted] 0 tool calls executed - nothing was read, run or written. Any text " +
                    "below is planning, not findings. Re-spawn with an explicit instruction to " +
                    "execute before reporting."
            Outcome.NO_OUTPUT ->
                "[no output] $tools executed, but the run delivered no text at all. The work " +
                    "may or may not have happened; re-spawn, or read the journal for the raw " +
                    "tool output."
            Outcome.TIMED_OUT ->
                "[timed out] $tools executed before the run exceeded its time budget." +
                    if (reportSource == ReportSource.NONE) " Nothing was delivered."
                    else " What follows is partial output."
            Outcome.FAILED ->
                "[failed] $tools executed before the run errored." +
                    if (reportSource == ReportSource.NONE) " Nothing was delivered."
                    else " What follows is partial output."
            Outcome.UNKNOWN ->
                "[unknown] the run's terminal state could not be determined — its record was " +
                    "dropped before the verdict was written. Nothing below is confirmed as " +
                    "findings; re-spawn, or read the journal for whatever the run left behind."
        }
    }
}
