package com.openminis.app.tools

import android.content.Context
import com.openminis.app.sandbox.PRootKernel

/**
 * [T-subagent-checkpoint] Mid-run checkpointing for sub-agent runs.
 *
 * [SubagentRunJournal] persists TERMINAL state only: a run killed by
 * process death before reaching a terminal handler (cancel / success /
 * fatal) leaves NO trace on disk — the journal write sites all live in
 * the runner's exception/return paths, which process death never
 * executes. This writer closes that gap: the runner appends a compact
 * progress line after every completed turn, so a dead run always has a
 * recovery anchor describing how far it got and what it was doing.
 *
 * File layout (one file per run, append-only):
 *   /var/minis/workspace/.subagent/<runId>.ckpt
 *   line 1: header  — `ckpt-v1|<skillId>|<skillName>|<query>`
 *   line 2+: progress — `t<turns>|<ISO-8601 ts>|<lastTextPreview>`
 *
 * Recovery contract: on process death the parent agent (or the user)
 * can `file_read` the .ckpt to see the last-turn state even though no
 * terminal journal was ever written. [resumeNote] renders a ready-to-
 * inject recovery summary for that purpose. The checkpoint is deleted
 * by the terminal journal path ([sweep]) so a live run never leaves a
 * stale ckpt behind.
 *
 * Every failure is swallowed — checkpointing must never break the run.
 * Writes are deliberately cheap (single appendText, no fsync) because
 * they run on the sub-agent's hot path once per turn.
 */
object SubagentRunCheckpoint {

    /** Sandbox path prefix — same directory as [SubagentRunJournal.DIR]. */
    const val DIR = "/var/minis/workspace/.subagent"

    private const val HEADER_PREFIX = "ckpt-v1|"

    /**
     * Write (or rewrite) the run's checkpoint file: a header line with
     * run identity + one progress line for the just-finished turn.
     * Rewriting keeps the file at exactly two lines — append-only would
     * grow unbounded across many turns for long-running research runs.
     */
    fun write(
        run: SubagentRunRegistry.Run,
        turns: Int,
        lastTextPreview: String,
        context: Context? = null,
    ): String? = runCatching {
        val sandboxPath = "$DIR/${run.id}.ckpt"
        val file = if (context != null) {
            PRootKernel.resolveSessionHostPath(run.sessionId, sandboxPath, context) ?: return null
        } else {
            java.io.File(sandboxPath)
        }
        file.parentFile?.mkdirs()
        val header = HEADER_PREFIX +
            listOf(run.skillId, run.skillName, run.query)
                .joinToString("|") { oneLine(it).take(200) }
        val progress = "t$turns|" +
            java.time.Instant.now().toString() + "|" +
            oneLine(lastTextPreview).take(300)
        file.writeText(header + "\n" + progress + "\n")
        sandboxPath
    }.getOrNull()

    /**
     * Render a human/agent-readable recovery summary from a checkpoint
     * file's raw text. Returns null when the file is missing or malformed
     * (no header line) — callers should treat that as "no checkpoint".
     */
    fun resumeNote(ckptText: String?): String? {
        if (ckptText.isNullOrBlank()) return null
        val lines = ckptText.lines().filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.takeIf { it.startsWith(HEADER_PREFIX) } ?: return null
        val parts = header.removePrefix(HEADER_PREFIX).split('|')
        val skillId = parts.getOrNull(0).orEmpty()
        val skillName = parts.getOrNull(1).orEmpty()
        val query = parts.getOrNull(2).orEmpty()
        val progress = lines.getOrNull(1).orEmpty()
        return buildString {
            appendLine("[sub-agent interrupted mid-run — checkpoint recovered]")
            appendLine("- skill: $skillName (id: $skillId)")
            if (query.isNotBlank()) appendLine("- task: $query")
            if (progress.isNotBlank()) appendLine("- progress: $progress")
            appendLine("- the run died before producing a terminal report; resume by re-spawning " +
                "the skill with this context, or read any partial artifacts it listed.")
        }.trim()
    }

    /**
     * Best-effort delete of the checkpoint file once the run reached a
     * terminal state (the journal takes over as the durable record).
     * Never throws.
     */
    fun sweep(runId: String, context: Context? = null, sessionId: String? = null) {
        runCatching {
            val sandboxPath = "$DIR/$runId.ckpt"
            val file = if (context != null && sessionId != null) {
                PRootKernel.resolveSessionHostPath(sessionId, sandboxPath, context)
                    ?: return
            } else {
                java.io.File(sandboxPath)
            }
            file.delete()
        }
    }

    /** Collapse newlines/tabs so a record always stays on one line. */
    private fun oneLine(s: String): String =
        s.replace("\r", " ").replace("\n", " ⏎ ").replace("\t", " ")
}
