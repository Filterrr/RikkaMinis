package com.openminis.app.tools

import java.io.File

/**
 * [feat/subagent-roster-routing] The PURE half of the checkpoint format —
 * parsing, directory scanning, recovery-prompt assembly.
 *
 * Why this file exists: `SubagentRunCheckpoint.kt` mixed two very different
 * kinds of code. The format logic (parse a checkpoint, decide which
 * checkpoints describe a genuinely interrupted run, assemble the prompt the
 * parent reads) is pure string + file work and deserves a unit test. The
 * PATH RESOLUTION (mapping a sandbox path to the host file through
 * PRootKernel, which needs a `Context`) is Android glue.
 *
 * Mixing them meant the pure half could only be tested under Gradle —
 * i.e. it was effectively untested, and the rules that decide "is this run
 * REALLY interrupted?" (journal present? already reported?) shipped without
 * a guard.
 *
 * This file holds the pure half; `SubagentRunCheckpoint` keeps the Android
 * entry points and delegates here. No behaviour change — the bodies moved
 * verbatim.
 */
internal object SubagentCheckpointFormat {

    /** Prefix of the header line. */
    const val HEADER_PREFIX = "ckpt-v1|"

    /** Cap on recovered runs named in one prompt. */
    const val MAX_REPORTED = 5

    /** One interrupted run recovered from disk. */
    data class InterruptedRun(val name: String, val note: String)

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
     * [T-subagent-ckpt-recovery] The directory-level contract, split out from
     * the scan so the decision rules are testable against a real temp
     * directory without an Android Context.
     *
     * Contract that keeps it safe to call on every session open:
     *  - a checkpoint is only reported when its matching JOURNAL is absent.
     *    The journal is the terminal record, written on success / failure /
     *    cancel and followed by `sweep` of the checkpoint; a .ckpt WITH a
     *    journal means the sweep was skipped (a torn-down process) — still an
     *    interrupted run — but a .ckpt WITHOUT one is unambiguous, and
     *    reporting a run that already reported itself would duplicate context.
     *  - files are capped by [maxReported] so a pathological directory cannot
     *    flood the prompt; the newest are preferred.
     */
    fun scanDirectory(dir: File, maxReported: Int = MAX_REPORTED): List<InterruptedRun> {
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        if (files.isEmpty()) return emptyList()
        // Journal presence decides. The terminal paths write `<runId>.md` and
        // THEN sweep the checkpoint; finding both means the process died
        // between the two writes (or the sweep failed) — the journal already
        // carries the complete record, so reporting it again as "interrupted"
        // would duplicate context for no gain. Only a checkpoint with NO
        // journal describes a run whose findings would otherwise be lost.
        val journalIds = files
            .filter { it.name.endsWith(".md") }
            .map { it.name.removeSuffix(".md") }
            .toSet()
        return files
            .filter { it.name.endsWith(".ckpt") }
            .filterNot { it.name.removeSuffix(".ckpt") in journalIds }
            .sortedByDescending { it.lastModified() }
            .take(maxReported)
            .mapNotNull { f ->
                val note = resumeNote(runCatching { f.readText() }.getOrNull()) ?: return@mapNotNull null
                if (note.isBlank()) null else InterruptedRun(name = f.name, note = note)
            }
    }

    /**
     * [T-subagent-ckpt-recovery] Render the whole scan as ONE prompt block, or
     * null when nothing was interrupted. Bounded and explicit about what the
     * parent should do, because the parent sees this as part of a normal turn
     * and must not mistake it for a live result.
     */
    fun buildRecoveryPrompt(runs: List<InterruptedRun>): String? {
        if (runs.isEmpty()) return null
        return buildString {
            appendLine("[sub-agent recovery] ${runs.size} sub-agent run(s) from this chat were ")
            appendLine("interrupted by process death and never reported their findings:")
            for (r in runs) {
                appendLine()
                appendLine(r.note)
            }
            appendLine()
            append("These are RECOVERED fragments, not live results — their work stopped mid-flight. ")
            append("Decide per run: resume it (re-spawn with the task above and any partial artifacts), ")
            append("or discard it if the task is no longer relevant.")
        }.trim()
    }

    /**
     * [T-subagent-ckpt-recovery] Mark a reported checkpoint as SEEN so a
     * scan reports each interrupted run exactly ONCE.
     *
     * The bug this closes: the recovery prompt enqueues on every session
     * open, and nothing said "this one was already surfaced" — a user who
     * backgrounded the app and came back got the same recovery turn again
     * (and again), each copy burning parent context and inviting the model
     * to redo recovered work. Renaming to `.ckpt.seen` is atomic, keeps the
     * evidence on disk for debugging, and is invisible to the scan (which
     * only reads `*.ckpt`).
     *
     * Rename can fail across mount boundaries; the fallback is delete, so
     * the goal (report once) still holds.
     */
    fun markReportedIn(file: File) {
        if (!file.name.endsWith(".ckpt")) return
        val seen = File(file.parentFile, "${file.name}.seen")
        if (!file.renameTo(seen)) {
            file.delete()
        }
    }

    /** Collapse newlines/tabs so a record always stays on one line. */
    fun oneLine(s: String): String =
        s.replace("\r", " ").replace("\n", " ⏎ ").replace("\t", " ")
}
