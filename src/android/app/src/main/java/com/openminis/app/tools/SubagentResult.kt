package com.openminis.app.tools

/**
 * [T-subagent-result] Structured terminal result of a sub-agent run.
 *
 * Before this class the runner only returned a hand-concatenated prompt
 * string — fine for the LLM, lossy for the runtime (turns, journal path,
 * artifacts and error state were unstructured or dropped). Callers that
 * only need model-facing text use [toPromptText]; the runtime (journaling,
 * recovery pointers, future multi-agent aggregation) consumes the fields.
 */
data class SubagentResult(
    val status: Status,
    /** Full final report text (all turns' narration + max-turns note). */
    val report: String,
    /** Turns actually executed (1-based count of completed model rounds). */
    val turns: Int,
    /** Configured turn budget the run was subject to. */
    val maxTurns: Int,
    /** Skill id the run executed. */
    val skillId: String,
    /** Skill display name. */
    val skillName: String,
    /**
     * Durable journal path (session-sandbox Linux path) when the report
     * was persisted — the recovery anchor for interrupted runs. Null when
     * journaling failed or was unnecessary.
     */
    val journalPath: String? = null,
    /**
     * Durable artifacts the run produced (file paths it wrote). Populated
     * from file_write / file_edit successes; empty for pure-research runs.
     */
    val artifacts: List<String> = emptyList(),
    /** Error detail when [status] is [Status.FAILED]. */
    val error: String? = null,
    /**
     * [A2] The sub-agent's self-declared report status, parsed from the
     * report tail by [SubagentSkill.parseReportStatus]: "partial", "failed",
     * or null (declared done / no structured contract / prose report).
     * Runtime [status] stays SUCCESS — the model DID finish its loop —
     * while the declared outcome is surfaced as an automatic prompt-text
     * annotation so the parent can decide to follow up without re-reading
     * the full report.
     */
    val declaredStatus: String? = null,
    /**
     * [feat/subagent-report-summary] The report's contract fields, PARSED.
     *
     * Before this, only `declaredStatus` was ever consumed as structure;
     * `key_findings`, `gaps` and `artifacts` reached the parent as prose
     * inside [report], and `summary` did not exist at all.
     *
     * The parsed form is what makes a one-line parent-facing digest
     * possible — the parent needs "what did this run conclude / what did
     * it not establish / what did it produce", and re-deriving that from
     * prose on every delivery path is exactly the work this object
     * removes.
     *
     * Null when the report was never parsed (a runtime-assembled digest,
     * a crash with no text). It is a nullable rather than a default
     * instance: "we did not parse a report" and "we parsed a report with
     * no findings" are different facts.
     */
    val parsed: SubagentReportContract.ParsedReport? = null,
    /**
     * [fix/subagent-run-assessment] The single verdict about the WORK, as
     * opposed to [status], which only says whether the LOOP finished.
     *
     * Before this field the parent saw "Sub-agent 'X' completed in 5 turn(s)"
     * for runs that had gathered nothing, failed to write a report, or been
     * rebuilt by the runtime — three different situations behind one header.
     * Rendered first in [toPromptText] so triage needs no reading.
     */
    val outcome: SubagentRunAssessment.Outcome = SubagentRunAssessment.Outcome.COMPLETED,
    /** Where the report text came from — a model account or a runtime rebuild. */
    val reportSource: SubagentRunAssessment.ReportSource =
        SubagentRunAssessment.ReportSource.MODEL,
    /** Tool calls that actually executed (not merely proposed). */
    val executedToolCalls: Int = 0,
) {
    enum class Status { SUCCESS, FAILED }

    /** True when the run's [journalPath] can be file_read for recovery. */
    val isRecoverable: Boolean get() = journalPath != null

    /**
     * Model-facing rendering — the prompt text the parent agent receives
     * as the spawn_agent tool result. Structured header + raw report keeps
     * the LLM contract stable while the runtime keeps the fields.
     *
     * @param stoppedEarly true for a `run_until=first_turn`/`turn_complete`
     *   early readout — renders the short first-turn framing instead of the
     *   full-completion framing.
     */
    fun toPromptText(stoppedEarly: Boolean = false): String {
        // [feat/subagent-result-envelope] The inline path used to have its
        // OWN renderer — a fourth copy of the same idea, with no budget
        // and no cost line. It now renders through the ONE envelope; the
        // only per-path difference is the framing and the body budget.
        val framing = if (stoppedEarly && status == Status.SUCCESS) {
            SubagentResultEnvelope.Framing.EARLY_READOUT
        } else {
            SubagentResultEnvelope.Framing.INLINE
        }
        val facts = SubagentResultEnvelope.Facts(
            runId = skillId,
            skillName = skillName,
            outcome = outcome,
            reportSource = reportSource,
            executedToolCalls = executedToolCalls,
            turns = turns,
            maxTurns = maxTurns,
            report = report,
            parsed = parsed,
            artifacts = artifacts,
            journalPath = journalPath,
            error = error,
            declaredStatus = declaredStatus,
        )
        return SubagentResultEnvelope.render(facts, framing)
    }

    companion object {
        /** Terminal-state token used in the journal's `terminal status:` line. */
        fun journalTerminal(status: Status, stoppedEarly: Boolean): String = when {
            status == Status.FAILED -> "FAILED"
            stoppedEarly -> "STOPPED_FIRST_TURN"
            else -> "SUCCESS"
        }

        /**
         * [T-subagent-runtime-preamble] True when [systemPrompt] carries the
         * runtime preamble injected by [SubagentSkill.buildSystemPrompt].
         */
        fun hasRuntimePreamble(systemPrompt: String): Boolean =
            systemPrompt.startsWith(SubagentSkill.RUNTIME_PREAMBLE_MARKER)
    }

    /**
     * [A2] Prompt-text annotation for a SUCCESS run whose own report declares
     * a degraded outcome (partial / failed). Rendered directly under the
     * header line so the parent sees it before the report body.
     */
    private fun declaredStatusAnnotation(): String? = when (declaredStatus) {
        "partial" -> "\n⚠️ The sub-agent's report declares status: partial — " +
            "it did NOT complete the task as specified. Check its `gaps` field " +
            "and the journaled artifacts before relying on this result."
        "failed" -> "\n⚠️ The sub-agent's report declares status: failed — " +
            "treat this result as an error report, not a deliverable."
        else -> null
    }
}
