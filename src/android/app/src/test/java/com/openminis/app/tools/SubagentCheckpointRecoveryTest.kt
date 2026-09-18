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
        val note = SubagentRunCheckpoint.resumeNote(ckpt())

        assertNotNull(note)
        assertTrue(note!!.contains("general-agent"))
        assertTrue(note.contains("summarize the release notes"))
        assertTrue(note.contains("t7"))
        assertTrue("must say the run died mid-flight", note.contains("interrupted"))
    }

    @Test
    fun `a missing or malformed checkpoint yields no note`() {
        assertNull(SubagentRunCheckpoint.resumeNote(null))
        assertNull(SubagentRunCheckpoint.resumeNote(""))
        assertNull("a non-checkpoint file must not be misread", SubagentRunCheckpoint.resumeNote("hello\nworld"))
    }

    // ── prompt assembly ──────────────────────────────────────────────────

    @Test
    fun `no interrupted runs produces no prompt`() {
        assertNull(SubagentRunCheckpoint.buildRecoveryPrompt(emptyList()))
    }

    /**
     * The assembled prompt must read as RECOVERED FRAGMENTS, not live results —
     * otherwise the parent treats a corpse's notes as a fresh finding.
     */
    @Test
    fun `the recovery prompt frames runs as interrupted fragments`() {
        val runs = listOf(
            SubagentRunCheckpoint.InterruptedRun("subagent-1.ckpt", SubagentRunCheckpoint.resumeNote(ckpt())!!),
        )

        val prompt = SubagentRunCheckpoint.buildRecoveryPrompt(runs)!!

        assertTrue(prompt.contains("1 sub-agent run(s)"))
        assertTrue("must state the runs never reported", prompt.contains("never reported"))
        assertTrue("must not masquerade as a live result", prompt.contains("RECOVERED fragments"))
        assertTrue("must offer a decision", prompt.contains("resume") && prompt.contains("discard"))
        assertTrue("the run's own state must be included", prompt.contains("general-agent"))
    }

    @Test
    fun `multiple recovered runs are all listed`() {
        val runs = listOf(
            SubagentRunCheckpoint.InterruptedRun("subagent-1.ckpt", SubagentRunCheckpoint.resumeNote(ckpt(query = "task one"))!!),
            SubagentRunCheckpoint.InterruptedRun("subagent-2.ckpt", SubagentRunCheckpoint.resumeNote(ckpt(query = "task two"))!!),
        )

        val prompt = SubagentRunCheckpoint.buildRecoveryPrompt(runs)!!

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

            val found = SubagentRunCheckpoint.scanDirectory(dir)

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
            assertTrue(SubagentRunCheckpoint.scanDirectory(dir).isEmpty())
            assertTrue("a missing directory is not an error", SubagentRunCheckpoint.scanDirectory(java.io.File(dir, "nope")).isEmpty())
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
            assertTrue(SubagentRunCheckpoint.scanDirectory(dir).isEmpty())
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
            assertEquals(3, SubagentRunCheckpoint.scanDirectory(dir, maxReported = 3).size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
