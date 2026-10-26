package com.openminis.app.tools

/**
 * [feat/subagent-roster-routing] Recovering a PARTIAL report from a run
 * that did not finish cleanly.
 *
 * ## The defect this closes
 *
 * A sub-agent that ends NORMALLY gets its report judged by
 * [SubagentReportDecision]: when the model's closing message carries no
 * usable findings, the runtime assembles a digest from the tool calls the
 * run actually executed, and the parent receives that.
 *
 * A sub-agent that CRASHES, TIMES OUT or is CANCELLED did NOT get that
 * treatment. Those paths passed the raw accumulated text — often EMPTY,
 * because a tool-driven run rarely narrates — so the parent received
 * "FAILED / no report" while the journal held the only copy of the work.
 *
 * Measured on three live audit sub-agents: 16 turns and 8+ successful
 * tool calls each, all three delivered ZERO findings to the parent. The
 * evidence existed, in the journals, and the parent's default view of the
 * delegation was empty. A delegation that returns nothing is
 * indistinguishable from a delegation that was never useful — so the
 * parent had no basis to decide whether to re-spawn, and the work was
 * lost twice over.
 *
 * ## The rule
 *
 * A run that did not finish cleanly must still deliver what it DID find:
 *
 *  - the accumulated text, when there is any — it is the model's own
 *    words and the most valuable thing it produced;
 *  - otherwise, a digest assembled from the tool steps — raw evidence,
 *    clearly labelled as such, never dressed up as a conclusion;
 *  - otherwise, nothing, with the reason stated.
 *
 * The digest is marked `partial` and names the gap, because the whole
 * point of this subsystem's report hygiene is that the parent must never
 * mistake a fragment for a finding.
 */
internal object SubagentPartialRecovery {

    /** One recovered report, with the provenance the parent needs. */
    data class Recovered(
        /** The text to deliver. May be empty (nothing survived). */
        val report: String,
        /** Where the text came from — the parent needs this to weigh it. */
        val source: SubagentRunAssessment.ReportSource,
    )

    /**
     * One tool step, reduced to what the digest needs. Kept as a plain
     * data holder so this object stays free of the registry's types (and
     * therefore testable on the plain JVM).
     */
    data class Step(
        val toolName: String,
        val toolTitle: String,
        val success: Boolean,
        val output: String,
    )

    /**
     * Recover what a run produced before it stopped.
     *
     * @param accumulatedText the model's own accumulated output so far.
     * @param steps the tool calls that actually executed.
     * @param stopReason a short phrase naming WHY the run stopped
     *   ("crashed (Stream error)", "timed out after 900s", "cancelled").
     *   It is quoted in the gap line so the parent knows the evidence is
     *   incomplete and why.
     */
    fun recover(
        accumulatedText: String,
        steps: List<Step>,
        stopReason: String,
    ): Recovered {
        val text = accumulatedText.trim()
        if (text.isNotEmpty()) {
            return Recovered(text, SubagentRunAssessment.ReportSource.PARTIAL)
        }
        val digest = SubagentReportHygiene.assembleFromTools(
            steps.map {
                SubagentReportHygiene.ToolDigest(
                    toolName = it.toolName,
                    toolTitle = it.toolTitle,
                    success = it.success,
                    output = it.output,
                )
            },
        ) ?: return Recovered("", SubagentRunAssessment.ReportSource.NONE)
        return Recovered(
            report = "$digest\n\ngaps: the run $stopReason before producing a summary — only the " +
                "tool output above survives. Treat it as partial evidence, not a conclusion. " +
                "The full transcript is in the journal path below.",
            source = SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
        )
    }
}
