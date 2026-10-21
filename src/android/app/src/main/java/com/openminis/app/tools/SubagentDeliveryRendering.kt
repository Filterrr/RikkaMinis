package com.openminis.app.tools

/**
 * [fix/subagent-verdict-delivery] The parent-facing renderings of a
 * sub-agent's terminal state.
 *
 * These were top-level functions in [SubagentRunner], which meant the
 * wording contract could only be tested by loading the whole runner (and
 * with it the entire Android dependency graph). They are pure — no runner
 * state, no Android — so they live here where a JVM test can reach them.
 */
/**
 * [T-subagent-delivery] Cap on how much report text rides into the parent turn.
 */
internal const val WAKE_REPORT_MAX_CHARS = 4000

/**
 * [T-subagent-report-budget] Cap on ONE joined run's report in a
 * `join_subagents` / `wait_any` result. Slightly larger than the wake-up cap
 * (a join is an explicit "give me the results" call, so a little more depth is
 * warranted) but still bounded: N reports share one parent turn, so an
 * unbounded per-report cap is an unbounded turn.
 */
internal const val JOIN_REPORT_MAX_CHARS = 6000

/**
 * [T-subagent-report-budget] Bound a report before it rides in a parent
 * turn. The journal holds the full text and is named so the parent can
 * read selectively.
 */
internal fun boundReportForParent(
    report: String,
    journalPath: String?,
    maxChars: Int,
): String {
    if (report.length <= maxChars) return report
    val recovery = if (!journalPath.isNullOrBlank()) {
        "full text: file_read $journalPath"
    } else {
        "the run was not journaled — re-spawn with a narrower scope to get the rest"
    }
    return report.take(maxChars) +
        "\n…[report truncated at $maxChars chars — $recovery]"
}

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
 * Top-level + pure (no runner state) so the wording contract is unit-testable.
 */
internal fun buildWakePrompt(
    outcome: SubagentOrchestration.JobOutcome,
    snapshot: SubagentRunRegistry.Run?,
    maxReportChars: Int = WAKE_REPORT_MAX_CHARS,
): String = buildString {
    val status = snapshot?.status?.name ?: if (outcome.success) "SUCCESS" else "FAILED"
    appendLine("[Sub-agent '${outcome.skillName}' finished in the background — status $status]")
    appendLine("run_id: ${outcome.runId}")
    snapshot?.let { run ->
        if (run.modelLabel.isNotBlank()) appendLine("model: ${run.modelLabel}")
        if (run.tokensIn >= 0 || run.tokensOut >= 0) {
            appendLine("tokens: in=${run.tokensIn.coerceAtLeast(0)} out=${run.tokensOut.coerceAtLeast(0)}")
        }
        appendLine("turns: ${run.turn}/${run.maxTurns}")
        // [fix/subagent-verdict-delivery] The verdict — what the work
        // actually achieved — so this path says the same thing the inline
        // path says. Before this, a detached run that executed ZERO tools
        // and produced only a plan arrived here as a bare "status SUCCESS"
        // plus the plan as its findings: the parent had no way to tell
        // that nothing was read, run or written.
        if (run.verdict.isNotBlank()) appendLine("verdict: ${run.verdict}")
    }
    if (!outcome.error.isNullOrBlank()) appendLine("error: ${outcome.error}")
    if (outcome.report.isNotBlank()) {
        appendLine()
        appendLine(boundReportForParent(outcome.report, outcome.journalPath, maxReportChars))
    }
    outcome.journalPath?.let {
        appendLine()
        append("Full report: $it (file_read it for anything truncated above).")
    }
}.trim()
