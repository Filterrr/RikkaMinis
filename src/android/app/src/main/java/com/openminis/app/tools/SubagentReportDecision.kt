package com.openminis.app.tools

/**
 * [fix/subagent-verdict-and-report-gate] The run's REPORT decision, as one
 * pure function of the facts the runtime holds.
 *
 * ## Why this object exists
 *
 * Three defects in this area were all the same shape: the runtime held the
 * right facts but passed the WRONG one to a pure decision function, so the
 * decision came out wrong and no test could catch it.
 *
 * - The report gate judged the run's accumulated narration instead of the
 *   model's closing message. Any earlier prose pushed a closing husk past
 *   the substance threshold, so the gate never fired and the parent
 *   received the bare tag as the findings. (Reproduced with a real run:
 *   the closing message was `<status>done</status>` plus a paragraph of
 *   narration — 4 meaningful characters alone, 69 accumulated.)
 * - The declared `status:` was read out of that same accumulation, so a
 *   self-assessment the model wrote in an EARLIER turn was taken as this
 *   turn's — flipping the run's verdict between COMPLETED and PARTIAL.
 * - Tool calls the loop detector short-circuited at CRITICAL level were
 *   counted as executed, so a run that never touched anything reported
 *   COMPLETED.
 *
 * Each of those was a call-site choice. Patching the call sites fixes the
 * symptom; it does not make the behaviour testable, because the test can
 * only exercise the pure function — and the pure function was never wrong.
 *
 * So the DECISION moved here. The runner now holds two texts (the model's
 * closing message and the accumulated narration) and asks this object what
 * to deliver and what verdict the run has. There is no longer a call site
 * that can pick the wrong input, and the whole decision is testable.
 *
 * Pure + JVM-testable: no Android, no registry, no clock.
 */
object SubagentReportDecision {

    /**
     * The facts a terminal run has.
     *
     * @param finalMessage the model's most recent message — the thing the
     *   report contract describes.
     * @param accumulatedText every turn's narration, concatenated. Used for
     *   the fallback path only (when the closing message is empty); NEVER
     *   used to judge the closing message.
     * @param executedToolCalls tool calls that ACTUALLY executed — a
     *   loop-detector short-circuit does not count.
     * @param hasToolEvidence true when the run recorded at least one tool
     *   step, so a runtime digest can be rebuilt from it.
     * @param toolSteps the executed steps, for the digest.
     */
    data class Input(
        val finalMessage: String,
        val accumulatedText: String,
        val executedToolCalls: Int,
        val hasToolEvidence: Boolean,
        val toolSteps: List<SubagentReportHygiene.ToolDigest> = emptyList(),
    )

    /**
     * What the parent receives, and what the run's verdict is.
     *
     * @param reportText the text to deliver to the parent.
     * @param reportSource where that text came from.
     * @param declaredStatus the model's own self-assessment, or null.
     * @param outcome the verdict about the work.
     * @param degenerate true when the closing message was a husk.
     */
    data class Decision(
        val reportText: String,
        val reportSource: SubagentRunAssessment.ReportSource,
        val declaredStatus: String?,
        val outcome: SubagentRunAssessment.Outcome,
        val degenerate: Boolean,
    )

    /**
     * Decide.
     *
     * The order matters and each step is load-bearing:
     *
     * 1. An empty closing message means the run produced no report. The
     *    accumulated narration is NOT a substitute — it is the plan's
     *    prose, and handing it over as "the report" is the exact failure
     *    the report gate exists to prevent. Such a run is NO_OUTPUT.
     * 2. Otherwise the closing message is judged. If it is a husk and the
     *    run has tool evidence, a runtime digest is rebuilt from that
     *    evidence (DEGRADED — the evidence is real, the interpretation is
     *    not). If it is a husk with no evidence, the husk is delivered
     *    with an explicit warning rather than as findings.
     * 3. A real closing message is delivered as the model's own account
     *    (MODEL).
     * 4. The declared status is read from the CLOSING MESSAGE — never from
     *    the accumulation.
     * 5. The verdict is derived by [SubagentRunAssessment.assess].
     */
    fun decide(input: Input): Decision {
        val finalMessage = input.finalMessage.trim()

        // ── 1. no report at all ──
        if (finalMessage.isEmpty()) {
            val outcome = SubagentRunAssessment.assess(
                loopFailed = false,
                timedOut = false,
                stoppedEarly = false,
                executedToolCalls = input.executedToolCalls,
                reportSource = SubagentRunAssessment.ReportSource.NONE,
                declaredStatus = null,
            )
            return Decision(
                reportText = "",
                reportSource = SubagentRunAssessment.ReportSource.NONE,
                declaredStatus = null,
                outcome = outcome,
                degenerate = false,
            )
        }

        // ── 2. the closing message is a husk ──
        val verdict = SubagentReportHygiene.judge(finalMessage)
        if (verdict.degenerate) {
            val digest = if (input.hasToolEvidence && input.toolSteps.isNotEmpty()) {
                SubagentReportHygiene.assembleFromTools(input.toolSteps)
            } else null
            if (digest != null) {
                val outcome = SubagentRunAssessment.assess(
                    loopFailed = false,
                    timedOut = false,
                    stoppedEarly = false,
                    executedToolCalls = input.executedToolCalls,
                    reportSource = SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
                    declaredStatus = null,
                )
                return Decision(
                    reportText = digest,
                    reportSource = SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
                    declaredStatus = null,
                    outcome = outcome,
                    degenerate = true,
                )
            }
            val outcome = SubagentRunAssessment.assess(
                loopFailed = false,
                timedOut = false,
                stoppedEarly = false,
                executedToolCalls = input.executedToolCalls,
                reportSource = SubagentRunAssessment.ReportSource.MODEL,
                declaredStatus = null,
            )
            return Decision(
                reportText = finalMessage + "\n\n⚠️ [no usable findings: the model's final message " +
                    "contained no substantive text (only structural tags were present) and this " +
                    "run recorded no tool output to reconstruct from.]",
                reportSource = SubagentRunAssessment.ReportSource.MODEL,
                declaredStatus = null,
                outcome = outcome,
                degenerate = true,
            )
        }

        // ── 3. a real closing message ──
        val declared = SubagentSkill.parseReportStatus(finalMessage)
        val outcome = SubagentRunAssessment.assess(
            loopFailed = false,
            timedOut = false,
            stoppedEarly = false,
            executedToolCalls = input.executedToolCalls,
            reportSource = SubagentRunAssessment.ReportSource.MODEL,
            declaredStatus = declared,
        )
        return Decision(
            reportText = finalMessage,
            reportSource = SubagentRunAssessment.ReportSource.MODEL,
            declaredStatus = declared,
            outcome = outcome,
            degenerate = false,
        )
    }
}
