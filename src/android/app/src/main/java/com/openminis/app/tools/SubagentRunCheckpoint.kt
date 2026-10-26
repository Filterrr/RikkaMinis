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
/**
 * One interrupted run recovered from disk. Re-exported from the pure
 * [SubagentCheckpointFormat] so callers can keep importing it from here.
 */
typealias InterruptedRun = SubagentCheckpointFormat.InterruptedRun

object SubagentRunCheckpoint {

    /** Sandbox path prefix — same directory as [SubagentRunJournal.DIR]. */
    const val DIR = "/var/minis/workspace/.subagent"

    private const val HEADER_PREFIX = SubagentCheckpointFormat.HEADER_PREFIX

    /** [T-subagent-ckpt-recovery] Cap on recovered runs named in one prompt. */
    private const val MAX_REPORTED = SubagentCheckpointFormat.MAX_REPORTED

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
    fun resumeNote(ckptText: String?): String? = SubagentCheckpointFormat.resumeNote(ckptText)

    /**
     * [T-subagent-ckpt-recovery] Scan this session's checkpoint directory and
     * return a recovery note per interrupted run.
     *
     * WHY THIS EXISTS: the checkpoint writer has always persisted a recovery
     * anchor after every turn, but NOTHING in the app ever read one back —
     * `resumeNote` was called only from unit tests. A run killed by process
     * death (the exact scenario the writer was built for) left a .ckpt on disk
     * that no surface would ever mention, so the durability mechanism cost a
     * file write per turn and returned nothing. This is the consumer.
     *
     * Contract that keeps it safe to call on every session open:
     *  - a checkpoint is only reported when its matching JOURNAL is absent.
     *    The journal is the terminal record, written on success / failure /
     *    cancel and followed by `sweep` of the checkpoint; a .ckpt WITH a
     *    journal means the sweep was skipped (a torn-down process) — still an
     *    interrupted run — but a .ckpt WITHOUT one is unambiguous, and
     *    reporting a run that already reported itself would duplicate context.
     *    (Both are "interrupted" by construction: a live run's sweep never ran
     *    either. We report the ones whose journal is missing OR empty, then
     *    note the run id so the caller can decide.)
     *  - files are capped by [MAX_REPORTED] so a pathological directory cannot
     *    flood the prompt; the newest are preferred.
     *  - failures are swallowed: recovery reporting must never break a session.
     */
    fun scanInterruptedRuns(
        sessionId: String,
        context: Context?,
        maxReported: Int = MAX_REPORTED,
    ): List<InterruptedRun> = runCatching {
        val dir = if (context != null) {
            PRootKernel.resolveSessionHostPath(sessionId, DIR, context)
        } else {
            java.io.File(DIR)
        } ?: return emptyList()
        scanDirectory(dir, maxReported)
    }.getOrDefault(emptyList())

    /**
     * [T-subagent-ckpt-recovery] The directory-level contract, split out from
     * [scanInterruptedRuns] so the decision rules are testable against a real
     * temp directory without an Android Context (the production entry point
     * resolves its path through PRootKernel, which needs one).
     */
    internal fun scanDirectory(dir: java.io.File, maxReported: Int = MAX_REPORTED): List<InterruptedRun> =
        SubagentCheckpointFormat.scanDirectory(dir, maxReported)

    /**
     * [T-subagent-ckpt-recovery] Render the whole scan as ONE prompt block, or
     * null when nothing was interrupted. Bounded and explicit about what the
     * parent should do, because the parent sees this as part of a normal turn
     * and must not mistake it for a live result.
     */
    fun buildRecoveryPrompt(runs: List<InterruptedRun>): String? =
        SubagentCheckpointFormat.buildRecoveryPrompt(runs)

    /**
     * [T-subagent-ckpt-recovery] Mark a reported checkpoint as SEEN so the
     * session-open scan reports each interrupted run exactly ONCE.
     *
     * The bug this closes: the recovery prompt enqueues on every session
     * open, and nothing said "this one was already surfaced" — a user who
     * backgrounded the app and came back got the same recovery turn again
     * (and again), each copy burning parent context and inviting the model
     * to redo recovered work. Renaming to `.ckpt.seen` is atomic, keeps the
     * evidence on disk for debugging, and is invisible to the scan (which
     * only reads `*.ckpt`).
     */
    fun markReported(fileName: String, context: Context? = null, sessionId: String? = null) {
        runCatching {
            if (!fileName.endsWith(".ckpt")) return
            val sandboxPath = "$DIR/$fileName"
            val file = if (context != null && sessionId != null) {
                PRootKernel.resolveSessionHostPath(sessionId, sandboxPath, context) ?: return
            } else {
                java.io.File(sandboxPath)
            }
            SubagentCheckpointFormat.markReportedIn(file)
        }
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
    private fun oneLine(s: String): String = SubagentCheckpointFormat.oneLine(s)
}
