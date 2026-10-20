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

        /** The caller asked for a first-turn readout and got it. */
        STOPPED_EARLY,

        /** No tool call executed. Whatever text exists is planning, not findings. */
        UNSTARTED,

        /** The run exceeded its wall-clock budget. */
        TIMED_OUT,

        /** The loop threw. */
        FAILED,
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
     * 4. A runtime digest is DEGRADED — the evidence is real but the
     *    interpretation is missing, which is exactly what the parent needs to
     *    know before quoting it as a conclusion.
     * 5. A model-declared `partial` is PARTIAL.
     * 6. A caller-requested first-turn stop is STOPPED_EARLY — expected, not a
     *    defect, but not a completed run either.
     * 7. Otherwise COMPLETED.
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
        reportSource == ReportSource.RUNTIME_DIGEST -> Outcome.DEGRADED
        declaredStatus?.lowercase() == "partial" -> Outcome.PARTIAL
        stoppedEarly -> Outcome.STOPPED_EARLY
        else -> Outcome.COMPLETED
    }

    /**
     * True when the parent may treat this run's report as the work it asked
     * for. Only COMPLETED qualifies: DEGRADED still needs the parent to draw
     * its own conclusions, PARTIAL is explicitly unfinished, and the rest did
     * not deliver.
     */
    fun isDeliverable(outcome: Outcome): Boolean = outcome == Outcome.COMPLETED

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
            Outcome.STOPPED_EARLY ->
                "[stopped early] $tools executed in the first turn, as requested. This is a " +
                    "readout, not a finished task."
            Outcome.UNSTARTED ->
                "[unstarted] 0 tool calls executed - nothing was read, run or written. Any text " +
                    "below is planning, not findings. Re-spawn with an explicit instruction to " +
                    "execute before reporting."
            Outcome.TIMED_OUT ->
                "[timed out] $tools executed before the run exceeded its time budget. What " +
                    "follows is partial output."
            Outcome.FAILED ->
                "[failed] $tools executed before the run errored. What follows is partial output."
        }.let { if (reportSource == ReportSource.NONE) "$it\n(no text was produced)" else it }
    }
}
