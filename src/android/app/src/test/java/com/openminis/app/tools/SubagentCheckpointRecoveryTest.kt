package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-ckpt-recovery] The checkpoint writer has always persisted a
 * per-turn recovery anchor, but until this change NOTHING read one back —
 * `resumeNote` was reachable only from tests, so a run killed by process
 * death left findings on disk that no surface would ever mention.
 *
 * These tests cover the two pure pieces that decide what the user/model is
 * told: what a checkpoint parses into, and what the assembled recovery prompt
 * says. The directory scan itself is exercised separately against a temp dir
 * (no Android Context needed when `context = null`).
 */
class SubagentCheckpointRecoveryTest {

    private fun ckpt(
        skillId: String = "general-agent",
        skillName: String = "general-agent",
        query: String = "summarize the release notes",
        turn: Int = 7,
        preview: String = "found three breaking changes",
    ): String = buildString {
        append("ckpt-v1|")
        append(listOf(skillId, skillName, query).joinToString("|"))
        append("\n")
        append("t$turn|2026-09-18T10:11:12Z|$preview")
        append("\n")
    }

    // ── parsing ──────────────────────────────────────────────────────────

    @Test
    fun `a well-formed checkpoint renders skill, task and progress`() {
        val note = SubagentCheckpointFormat.resumeNote(ckpt())

        assertNotNull(note)
        assertTrue(note!!.contains("general-agent"))
        assertTrue(note.contains("summarize the release notes"))
        assertTrue(note.contains("t7"))
        assertTrue("must say the run died mid-flight", note.contains("interrupted"))
    }

    @Test
    fun `a missing or malformed checkpoint yields no note`() {
        assertNull(SubagentCheckpointFormat.resumeNote(null))
        assertNull(SubagentCheckpointFormat.resumeNote(""))
        assertNull("a non-checkpoint file must not be misread", SubagentCheckpointFormat.resumeNote("hello\nworld"))
    }

    // ── prompt assembly ──────────────────────────────────────────────────

    @Test
    fun `no interrupted runs produces no prompt`() {
        assertNull(SubagentCheckpointFormat.buildRecoveryPrompt(emptyList()))
    }

    /**
     * The assembled prompt must read as RECOVERED FRAGMENTS, not live results —
     * otherwise the parent treats a corpse's notes as a fresh finding.
     */
    @Test
    fun `the recovery prompt frames runs as interrupted fragments`() {
        val runs = listOf(
            SubagentCheckpointFormat.InterruptedRun("subagent-1.ckpt", SubagentCheckpointFormat.resumeNote(ckpt())!!),
        )

        val prompt = SubagentCheckpointFormat.buildRecoveryPrompt(runs)!!

        assertTrue(prompt.contains("1 sub-agent run(s)"))
        assertTrue("must state the runs never reported", prompt.contains("never reported"))
        assertTrue("must not masquerade as a live result", prompt.contains("RECOVERED fragments"))
        assertTrue("must offer a decision", prompt.contains("resume") && prompt.contains("discard"))
        assertTrue("the run's own state must be included", prompt.contains("general-agent"))
    }

    @Test
    fun `multiple recovered runs are all listed`() {
        val runs = listOf(
            SubagentCheckpointFormat.InterruptedRun("subagent-1.ckpt", SubagentCheckpointFormat.resumeNote(ckpt(query = "task one"))!!),
            SubagentCheckpointFormat.InterruptedRun("subagent-2.ckpt", SubagentCheckpointFormat.resumeNote(ckpt(query = "task two"))!!),
        )

        val prompt = SubagentCheckpointFormat.buildRecoveryPrompt(runs)!!

        assertTrue(prompt.contains("2 sub-agent run(s)"))
        assertTrue(prompt.contains("task one"))
        assertTrue(prompt.contains("task two"))
    }

    // ── directory scan ───────────────────────────────────────────────────

    /**
     * The scan must report a checkpoint whose JOURNAL is absent (a run killed
     * before any terminal write) and must SKIP one that has a journal — the
     * journal is the complete record, and re-reporting it as "interrupted"
     * would duplicate context for no gain.
     */
    @Test
    fun `the scan reports orphan checkpoints and skips journaled ones`() {
        val dir = java.nio.file.Files.createTempDirectory("ckpt-scan").toFile()
        try {
            // Orphan: a checkpoint with no terminal journal — the killed-run case.
            java.io.File(dir, "subagent-1.ckpt").writeText(ckpt(query = "orphan task"))
            // Journaled: the terminal path ran (or partially ran) — already recorded.
            java.io.File(dir, "subagent-2.ckpt").writeText(ckpt(query = "journaled task"))
            java.io.File(dir, "subagent-2.md").writeText("# terminal record")
            // A journal WITHOUT a checkpoint is the normal completed case.
            java.io.File(dir, "subagent-3.md").writeText("# terminal record")

            val found = SubagentCheckpointFormat.scanDirectory(dir)

            assertEquals(1, found.size)
            assertTrue("only the orphan may be reported", found.single().note.contains("orphan task"))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** An empty or absent directory is simply "nothing to recover". */
    @Test
    fun `a directory with no checkpoints reports nothing`() {
        val dir = java.nio.file.Files.createTempDirectory("ckpt-empty").toFile()
        try {
            assertTrue(SubagentCheckpointFormat.scanDirectory(dir).isEmpty())
            assertTrue("a missing directory is not an error", SubagentCheckpointFormat.scanDirectory(java.io.File(dir, "nope")).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A malformed checkpoint file is skipped, not reported as garbage. */
    @Test
    fun `a malformed checkpoint is skipped`() {
        val dir = java.nio.file.Files.createTempDirectory("ckpt-bad").toFile()
        try {
            java.io.File(dir, "subagent-9.ckpt").writeText("not a checkpoint at all")
            assertTrue(SubagentCheckpointFormat.scanDirectory(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The report cap bounds a pathological directory. */
    @Test
    fun `the scan caps how many runs it reports`() {
        val dir = java.nio.file.Files.createTempDirectory("ckpt-many").toFile()
        try {
            repeat(10) { i ->
                java.io.File(dir, "subagent-$i.ckpt").writeText(ckpt(query = "task $i"))
            }
            assertEquals(3, SubagentCheckpointFormat.scanDirectory(dir, maxReported = 3).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── report-once marker ───────────────────────────────────────────────

    /**
     * [T-subagent-ckpt-recovery] A reported checkpoint must never be reported
     * again: the recovery prompt enqueues on every session open, and without
     * a seen-marker a user who reopens the chat gets the same turn re-injected
     * (and again, and again). markReported moves the file out of the scan's
     * view while keeping the evidence on disk.
     */
    @Test
    fun `a reported checkpoint is not reported twice`() {
        val dir = java.nio.file.Files.createTempDirectory("ckpt-once").toFile()
        try {
            java.io.File(dir, "subagent-1.ckpt").writeText(ckpt(query = "orphan task"))
            assertEquals(1, SubagentCheckpointFormat.scanDirectory(dir).size)

            // Simulate what the session-open path does after enqueueing.
            val seen = java.io.File(dir, "subagent-1.ckpt.seen")
            assertTrue(
                "the marker must keep the evidence on disk",
                java.io.File(dir, "subagent-1.ckpt").renameTo(seen),
            )

            assertTrue("a marked checkpoint must leave the scan", SubagentCheckpointFormat.scanDirectory(dir).isEmpty())
            assertTrue("the file survives as .seen for debugging", seen.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * markReported tolerates non-checkpoint names and missing files.
     *
     * The PURE half is exercised here: the production entry point resolves
     * the sandbox path through PRootKernel (Android), then delegates the
     * rename to [SubagentCheckpointFormat.markReportedIn]. Testing the pure
     * half directly keeps the rule "only a .ckpt is renamed, and the rename
     * is atomic-or-delete" pinned on the plain JVM.
     */
    @Test
    fun `markReported is safe on odd input`() {
        val dir = java.nio.file.Files.createTempDirectory("ckpt-mark").toFile()
        try {
            val journal = java.io.File(dir, "subagent-1.md")
            journal.writeText("journal")
            val seen = java.io.File(dir, "subagent-1.ckpt.seen")
            seen.writeText("already seen")
            val missing = java.io.File(dir, "subagent-404.ckpt")

            // No throw for a journal name, a .seen name, or a nonexistent id.
            SubagentCheckpointFormat.markReportedIn(journal)
            SubagentCheckpointFormat.markReportedIn(seen)
            SubagentCheckpointFormat.markReportedIn(missing)

            assertTrue("a journal is never renamed", journal.exists())
            assertTrue("a .seen file is never renamed", seen.exists())
            assertFalse("a missing checkpoint is not an error", missing.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
