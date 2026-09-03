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
            append("Sub-agent '$skillName' first-turn readout (1/$maxTurns turns):\n\n---\n")
            append(report.ifBlank { "(no text output produced)" })
            append("\n---")
            journalPath?.let { append("\n\nFull journal: $it") }
        }
        status == Status.SUCCESS -> buildString {
            append("Sub-agent '$skillName' completed in $turns turn(s).")
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
            append("Sub-agent '$skillName' encountered an error after $turns turn(s).\n")
            if (report.isNotBlank()) {
                append("\nPartial output:\n---\n").append(report).append("\n---\n")
            }
            append("\nError: ").append(error ?: "unknown error")
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
    }
}
