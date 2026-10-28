package com.openminis.app.tools

/**
 * [fix/subagent-verdict-consistency-v3] Parent-facing delivery of a
 * sub-agent's terminal outcome — the report budget, the wait_any winner
 * line and the background wake-up turn.
 *
 * Why this file exists: these three functions decide what the PARENT
 * believes about a run, and they were living inside [SubagentRunner] — a
 * file that drags in Context, the provider gateway and the sandbox
 * coordinator, so none of them could be unit-tested on the JVM. The logic
 * is pure; only its location prevented the test.
 *
 * [SubagentDeliveryTest] and [SubagentVerdictConsistencyTest] now cover
 * them without an Android device.
 *
 * Top-level + pure so the wording contract stays unit-testable.
 */

/** [T-subagent-delivery] Cap on how much report text rides into the parent turn. */
private const val WAKE_REPORT_MAX_CHARS = 4000

/**
 * [T-subagent-report-budget] Cap on ONE joined run's report in a
 * `join_subagents` / `wait_any` result. Slightly larger than the wake-up cap
 * (a join is an explicit "give me the results" call, so a little more depth is
 * warranted) but still bounded: N reports share one parent turn, so an
 * unbounded per-report cap is an unbounded turn.
 */
internal const val JOIN_REPORT_MAX_CHARS = 6000

/**
 * [feat/subagent-result-envelope] Turn a detached outcome into the
 * envelope's [SubagentResultEnvelope.Facts].
 *
 * The detached paths read their facts from the registry snapshot (the
 * same snapshot the verdict comes from), so the envelope they render is
 * built from ONE source of truth — not from a re-derivation that could
 * drift from the inline path.
 *
 * The model / token / turn numbers come from the snapshot when there is
 * one; a snapshot that is missing (the run was pruned) degrades to
 * "not reported" rather than to a zero.
 */
internal fun envelopeFacts(
    outcome: SubagentOrchestration.JobOutcome,
    snapshot: SubagentRunRegistry.Run?,
): SubagentResultEnvelope.Facts = SubagentResultEnvelope.Facts(
    runId = outcome.runId,
    skillName = outcome.skillName,
    outcome = if (outcome.cancelled) {
        SubagentRunAssessment.Outcome.FAILED
    } else {
        outcome.effectiveOutcome
    },
    reportSource = outcome.reportSource,
    executedToolCalls = outcome.executedToolCalls,
    turns = snapshot?.turn ?: 0,
    maxTurns = snapshot?.maxTurns ?: 0,
    modelLabel = snapshot?.modelLabel.orEmpty(),
    tokensIn = snapshot?.tokensIn ?: -1,
    tokensOut = snapshot?.tokensOut ?: -1,
    report = outcome.report,
    parsed = outcome.parsed,
    artifacts = outcome.artifacts,
    journalPath = outcome.journalPath,
    error = outcome.error,
    declaredStatus = outcome.declaredStatus,
)

/**
 * [feat/subagent-result-envelope] Cut a report body to fit a budget.
 *
 * A thin delegate to the envelope's own cutter: ONE implementation of
 * "how do we cut", so the inline / wake / join / wait_any paths cannot
 * drift on the cut marker or the recovery route.
 */
internal fun boundReportForParent(
    report: String,
    journalPath: String?,
    maxChars: Int,
): String = SubagentResultEnvelope.boundBody(report, journalPath, maxChars)

/**
 * [T-subagent-report-budget] Render the wait_any winner's report with the SAME
 * budget as join/wake. wait_any was the third unbounded sink for the same
 * text: it fires during competitive fan-outs, i.e. exactly when several large
 * reports exist, and it returned one of them verbatim.
 */
internal fun boundWaitAnyReport(
    outcome: SubagentOrchestration.JobOutcome,
    maxChars: Int = JOIN_REPORT_MAX_CHARS,
): String = boundReportForParent(
    report = outcome.report.ifBlank { "(no report text)" },
    journalPath = outcome.journalPath,
    maxChars = maxChars,
)


/**
 * [T-subagent-delivery] Render the parent-facing turn for a detached run's
 * terminal outcome.
 *
 * Framed as an EVENT, not a user request, so the parent model folds it into
 * its plan instead of answering it conversationally. The report is bounded — a
 * detached run that produced a 50 KB dump must not spend the parent's whole
 * window on it; the journal holds the full text and is named so the parent can
 * read selectively.
 *
 * [feat/subagent-result-envelope] It renders through the ONE envelope now —
 * the same object the inline path renders — so the wake-up and the inline
 * result can never disagree about what a run produced. Only the framing and
 * the body budget differ.
 *
 * Top-level + pure (no runner state) so the wording contract is unit-testable.
 */
internal fun buildWakePrompt(
    outcome: SubagentOrchestration.JobOutcome,
    snapshot: SubagentRunRegistry.Run?,
    maxReportChars: Int = WAKE_REPORT_MAX_CHARS,
): String {
    val facts = envelopeFacts(outcome, snapshot).copy(
        // [feat/subagent-result-envelope] The wake-up's first line names
        // the RUNTIME status as well as the verdict — a run can time out
        // AND still have delivered partial notes, and "how the run ended"
        // is a different fact from "what it delivered".
        runtimeStatus = snapshot?.status?.name,
    )
    return SubagentResultEnvelope.render(
        facts = facts,
        framing = SubagentResultEnvelope.Framing.WAKE,
        bodyMaxChars = maxReportChars,
    ).trim()
}
