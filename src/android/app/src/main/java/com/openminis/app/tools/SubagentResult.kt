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
    fun toPromptText(stoppedEarly: Boolean = false): String = when {
        stoppedEarly && status == Status.SUCCESS -> buildString {
            append("Sub-agent '$skillName' first-turn readout (1/$maxTurns turns):")
            append("\n\n").append(SubagentRunAssessment.headline(outcome, executedToolCalls, reportSource))
            append("\n\n---\n")
            append(report.ifBlank { "(no text output produced)" })
            append("\n---")
            journalPath?.let { append("\n\nFull journal: $it") }
        }
        status == Status.SUCCESS -> buildString {
            // [fix/subagent-run-assessment] The verdict comes FIRST — before
            // the turn count, before artifacts. The parent must be able to
            // triage without reading the report body; "completed in 5
            // turn(s)" alone was identical for a real run and an empty one.
            append("Sub-agent '$skillName' ran $turns turn(s).")
            append("\n").append(SubagentRunAssessment.headline(outcome, executedToolCalls, reportSource))
            declaredStatusAnnotation()?.let { append(it) }
            if (artifacts.isNotEmpty()) {
                append("\nArtifacts written: ")
                append(artifacts.joinToString(", "))
            }
            if (report.isNotBlank()) {
                append("\n\n---\n").append(report)
            } else {
                append("\n\n(no text output produced)")
            }
            journalPath?.let {
                append("\n\n(Journaled at $it — survives interruption.)")
            }
        }
        else -> buildString {
            append("Sub-agent '$skillName' ended after $turns turn(s).")
            append("\n").append(SubagentRunAssessment.headline(outcome, executedToolCalls, reportSource))
            if (report.isNotBlank()) {
                append("\n\nPartial output:\n---\n").append(report).append("\n---")
            }
            append("\n\nError: ").append(error ?: "unknown error")
            journalPath?.let {
                append("\n\nRecoverable: the full partial report is journaled at $it — file_read it to retrieve everything the sub-agent produced before failing.")
            }
        }
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
