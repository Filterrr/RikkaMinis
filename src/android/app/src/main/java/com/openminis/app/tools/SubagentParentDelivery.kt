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
 * [feat/subagent-artifact-delivery] One line naming what the run produced.
 *
 * The inline path already had this wording
 * ("Artifacts written: …", [SubagentResult.toPromptText]); the detached
 * paths had NOTHING. A detached run that wrote three deliverables reached
 * the parent with the paths buried in the model's prose — and prose drifts,
 * which is why the runtime reconciles it
 * ([SubagentArtifactTruth]). The detached surfaces now name the
 * deliverables from the runtime's own write log.
 *
 * Returns null for an empty list: "no artifacts" is a fact the parent
 * learns from the absence of the line, not from a line saying so. A
 * research-only run produces no artifacts and should not carry a
 * decorative empty line.
 */
internal fun artifactListLine(artifacts: List<String>): String? {
    if (artifacts.isEmpty()) return null
    return "Artifacts written: " + artifacts.joinToString(", ")
}

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
    // [fix/subagent-verdict-consistency-v3] The wake prompt carries TWO
    // independent facts, and the old code conflated them:
    //
    //   - the RUNTIME status (registry RunStatus): how the run ENDED —
    //     TIMED_OUT, CANCELLED, FAILED.
    //   - the WORK verdict (Outcome): what the run DELIVERED — a full
    //     report, a runtime digest, a partial result, nothing.
    //
    // A run can time out AND still have produced partial notes; a run can
    // finish without throwing AND deliver only a digest. Reading the
    // binary `success` flag answered neither question ("SUCCESS" for a
    // digest, "FAILED" for a timeout that left usable notes).
    //
    // So the header names the runtime status when it is not the plain
    // success case, and the verdict is always stated — the parent needs to
    // know both WHY the run ended and WHETHER the text below is findings.
    val runtimeStatus = snapshot?.status?.name
    val verdict = if (outcome.cancelled) {
        SubagentRunAssessment.Outcome.FAILED
    } else {
        outcome.effectiveOutcome
    }
    val status = SubagentRunAssessment.summaryStatus(
        runtimeStatus = runtimeStatus,
        verdict = verdict,
        hasReport = outcome.report.isNotBlank(),
    )
    appendLine("[Sub-agent '${outcome.skillName}' finished in the background — status $status]")
    appendLine("run_id: ${outcome.runId}")
    snapshot?.let { run ->
        if (run.modelLabel.isNotBlank()) appendLine("model: ${run.modelLabel}")
        if (run.tokensIn >= 0 || run.tokensOut >= 0) {
            appendLine("tokens: in=${run.tokensIn.coerceAtLeast(0)} out=${run.tokensOut.coerceAtLeast(0)}")
        }
        appendLine("turns: ${run.turn}/${run.maxTurns}")
    }
    if (!outcome.error.isNullOrBlank()) appendLine("error: ${outcome.error}")
    // [feat/subagent-artifact-delivery] Name the deliverables. Before this
    // the wake-up carried the report text only, so a detached run's files
    // were reachable only through the model's prose.
    artifactListLine(outcome.artifacts)?.let { appendLine(it) }
    if (outcome.report.isNotBlank()) {
        appendLine()
        appendLine(boundReportForParent(outcome.report, outcome.journalPath, maxReportChars))
    }
    outcome.journalPath?.let {
        appendLine()
        append("Full report: $it (file_read it for anything truncated above).")
    }
}.trim()
