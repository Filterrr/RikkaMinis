package com.openminis.app.tools

/**
 * [feat/subagent-budget-hardening] The hand-off text for a CONTINUED run.
 *
 * Why this exists: when a run hits its wall clock it is stopped with a
 * partial report and its artifacts already on disk — and the parent's only
 * way to finish the job was to spawn the SAME task again, paying for every
 * read, every command and every dead end a second time. The runtime already
 * keeps the evidence (registry snapshot, per-run artifacts directory,
 * journal), so a continuation only has to be TOLD what is already done.
 *
 * Design note: this deliberately does NOT replay the previous transcript.
 * Rebuilding history from segments risks two USERs in a row (providers
 * reject it; see [SubagentSteer] for the history of that bug class), and a
 * half-reconstructed tool-call chain is worse than a written summary. What
 * the model needs is: what was attempted, what exists on disk, where to keep
 * writing, and that its budget is fresh.
 *
 * Pure + JVM-testable: no registry, no Android, no coroutines.
 */
internal object SubagentResume {

    /** Cap on the previous report quoted into the continuation prompt. */
    const val MAX_REPORT_CHARS = 2400

    /** Cap on artifact paths listed — enough anchors, not a file dump. */
    const val MAX_ARTIFACTS = 20

    /**
     * Build the prefix that turns "run it again" into "continue it".
     *
     * @param previousRunId the terminal run being continued.
     * @param skillName its skill (already validated to match by the caller).
     * @param statusLabel terminal status, e.g. "TIMED_OUT".
     * @param stopReason human-readable reason (registry `error`), may be blank.
     * @param turns turns the previous attempt completed.
     * @param maxTurns its turn budget.
     * @param resultText its partial report; may be blank.
     * @param artifacts paths it wrote; may be empty.
     * @param workspaceDir the directory those paths live in — the ONE place
     *   the continuation is told to keep writing, so its deliverables do not
     *   split across two run directories.
     */
    fun context(
        previousRunId: String,
        skillName: String,
        statusLabel: String,
        stopReason: String,
        turns: Int,
        maxTurns: Int,
        resultText: String,
        artifacts: List<String>,
        workspaceDir: String,
    ): String {
        val turnText = if (maxTurns > 0) "$turns/$maxTurns turn(s)" else "$turns turn(s)"
        val reasonText = stopReason.trim().takeIf { it.isNotEmpty() }?.let { " — $it" }.orEmpty()
        val report = resultText.trim()
        val quoted = when {
            report.isEmpty() -> "(the previous attempt produced no report text)"
            report.length <= MAX_REPORT_CHARS -> report
            else -> report.take(MAX_REPORT_CHARS).trimEnd() +
                "\n…(truncated — the rest is in the previous journal)"
        }
        val files = artifacts.map { it.trim() }.filter { it.isNotEmpty() }
        val filesText = if (files.isEmpty()) {
            "- (none recorded by the runtime — check the directory before starting over)"
        } else {
            files.take(MAX_ARTIFACTS).joinToString("\n") { "- $it" } +
                if (files.size > MAX_ARTIFACTS) {
                    "\n- …and ${files.size - MAX_ARTIFACTS} more in the same directory"
                } else ""
        }
        return buildString {
            append("[Continuation of a previous run] You are RESUMING run ")
            append(previousRunId).append(" of '").append(skillName).append("', ")
            append("which stopped as ").append(statusLabel).append(" after ")
            append(turnText).append(reasonText).append(".\n\n")
            append("Its partial deliverable (possibly incomplete):\n\"\"\"\n")
            append(quoted).append("\n\"\"\"\n\n")
            append("Files it already wrote, under ").append(workspaceDir).append(":\n")
            append(filesText).append("\n\n")
            append("Treat those files as authoritative for what is DONE — read them before ")
            append("repeating any work, and do NOT re-run commands whose results are already ")
            append("evidenced above. Keep writing into the same directory so the deliverable ")
            append("stays in one place. You have a FRESH budget for this continuation: spend ")
            append("it on what is LEFT, then write the final report.")
        }
    }
}