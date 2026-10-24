package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-artifact-path-truth] The runtime must be the authority on
 * an artifact's path.
 *
 * Real defect (2026-10-24, run `subagent-3`): the file was written to
 * `/var/minis/workspace/subagents/subagent-3/parallel_file_read_report.md`
 * and the model's report claimed
 * `/var/minis/workspace/sub/subagents/subagent-3/parallel_file_read_report.md`
 * — one extra `sub/` segment. The parent saw both paths and had no way to
 * tell which one existed. The first test below is that exact reproduction.
 */
class SubagentArtifactTruthTest {

    private val artifactsDir = "/var/minis/workspace/subagents/subagent-3"
    private val realPath = "$artifactsDir/parallel_file_read_report.md"
    private val driftedPath = "/var/minis/workspace/sub/subagents/subagent-3/parallel_file_read_report.md"

    // ── the reported defect ──────────────────────────────────────────────

    /**
     * THE regression: the drifted path in the model's report is rewritten to
     * the path the runtime actually recorded. Before the fix the parent got
     * both, contradictory, with no indication of which was real.
     */
    @Test
    fun `a drifted path in the report is rewritten to the recorded one`() {
        val report = """
            status: done
            key_findings:
            - the three files were read in one batch
            gaps: none
            artifacts:
            - $driftedPath
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(realPath),
            artifactsDir = artifactsDir,
        )

        assertFalse("the drifted path must not survive", result.report.contains(driftedPath))
        assertTrue("the real path must be present", result.report.contains(realPath))
        assertEquals(listOf(driftedPath to realPath), result.correctedPaths)
        assertTrue("drift must be reported", result.hadDrift)
    }

    /** The runtime's own list is the authority and is never second-guessed. */
    @Test
    fun `the recorded path is reported verbatim`() {
        val report = "artifacts:\n- $realPath"

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(realPath),
            artifactsDir = artifactsDir,
        )

        assertEquals(listOf(realPath), result.authoritativePaths)
        assertTrue(result.correctedPaths.isEmpty())
        assertFalse(result.hadDrift)
    }

    /**
     * A path the runtime never recorded is NOT silently rewritten and NOT
     * deleted — it is kept and marked. The file may have been produced by
     * `shell_execute`, which the runtime does not log; inventing a path for
     * it, or dropping it, would both be lies.
     */
    @Test
    fun `an unrecorded path is kept but flagged rather than rewritten`() {
        val report = """
            status: done
            artifacts:
            - /var/minis/workspace/subagents/subagent-3/from_shell.md
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(realPath),
            artifactsDir = artifactsDir,
        )

        assertTrue(
            "the unrecorded path must be called out",
            result.report.contains("NOT recorded by the runtime"),
        )
        assertTrue(
            "the unrecorded path must still be visible",
            result.report.contains("from_shell.md"),
        )
        assertEquals(
            listOf("/var/minis/workspace/subagents/subagent-3/from_shell.md"),
            result.unverifiedPaths,
        )
    }

    // ── canonicalisation ─────────────────────────────────────────────────

    /** A relative write is resolved against the run's artifacts directory. */
    @Test
    fun `a relative path resolves against the artifacts directory`() {
        assertEquals(
            realPath,
            SubagentArtifactTruth.canonicalize("parallel_file_read_report.md", artifactsDir),
        )
    }

    /** `.` and `..` segments are folded; duplicate slashes collapse. */
    @Test
    fun `dot and duplicate segments are folded`() {
        assertEquals(
            realPath,
            SubagentArtifactTruth.canonicalize("./a/../parallel_file_read_report.md", artifactsDir),
        )
        assertEquals(
            realPath,
            SubagentArtifactTruth.canonicalize("$artifactsDir//parallel_file_read_report.md", artifactsDir),
        )
    }

    /** A path outside the sandbox is not the runtime's business. */
    @Test
    fun `a path outside the sandbox is left alone`() {
        val report = "artifacts:\n- /tmp/somewhere.txt\n- https://example.com/a.txt"

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(realPath),
            artifactsDir = artifactsDir,
        )

        assertTrue("host paths must survive", result.report.contains("/tmp/somewhere.txt"))
        assertTrue("urls must survive", result.report.contains("https://example.com/a.txt"))
        assertTrue("no drift may be reported for them", result.unverifiedPaths.isEmpty())
    }

    // ── the artifacts: section ───────────────────────────────────────────

    /**
     * The contract's `artifacts:` field must list what the runtime recorded.
     * A model that omitted the field must not make the parent hunt for the
     * deliverable in prose.
     */
    @Test
    fun `a missing artifacts section is appended from the runtime log`() {
        val report = """
            status: done
            key_findings:
            - something
            gaps: none
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(realPath),
            artifactsDir = artifactsDir,
        )

        assertTrue("the section must be added", result.report.contains("artifacts:"))
        assertTrue("the real path must be listed", result.report.contains(realPath))
    }

    /** An INLINE `artifacts: <path>` on the header line is a section too. */
    @Test
    fun `an inline artifacts header is treated as a section`() {
        val report = "status: done\nartifacts: $driftedPath"

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(realPath),
            artifactsDir = artifactsDir,
        )

        assertFalse("the drifted inline path must not survive", result.report.contains(driftedPath))
        assertTrue("the real path must be listed", result.report.contains(realPath))
    }

    /**
     * The section body ends at the next top-level contract field. A later
     * `gaps:` section must not be swallowed into `artifacts:`.
     */
    @Test
    fun `the artifacts section ends at the next top-level field`() {
        val report = """
            status: done
            artifacts:
            - $driftedPath
            gaps: the model forgot one file
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(realPath),
            artifactsDir = artifactsDir,
        )

        assertTrue("the gaps section must survive", result.report.contains("gaps: the model forgot one file"))
        assertTrue("the real path must be listed", result.report.contains(realPath))
    }

    /** A bullet whose text contains a colon is not a top-level field. */
    @Test
    fun `a bullet with a colon does not end the artifacts section`() {
        val report = """
            status: done
            artifacts:
            - $driftedPath — note: written in turn 3
            gaps: none
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(realPath),
            artifactsDir = artifactsDir,
        )

        assertTrue("the gaps section must survive", result.report.contains("gaps: none"))
        assertTrue("the real path must be listed", result.report.contains(realPath))
    }

    /** The model's own description for a path is kept — only the path is fixed. */
    @Test
    fun `the model description for an artifact is preserved`() {
        val report = """
            status: done
            artifacts:
            - $driftedPath — the parallel read results
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(realPath),
            artifactsDir = artifactsDir,
        )

        assertTrue("the description must survive", result.report.contains("the parallel read results"))
        assertTrue("the real path must be listed", result.report.contains(realPath))
        assertFalse("the drifted path must not survive", result.report.contains(driftedPath))
    }

    /** Several artifacts: each is matched to its own recorded path. */
    @Test
    fun `several artifacts are each repaired individually`() {
        val a = "$artifactsDir/a.md"
        val b = "$artifactsDir/b.md"
        val report = """
            status: done
            artifacts:
            - /var/minis/workspace/sub/subagents/subagent-3/a.md
            - /var/minis/workspace/sub/subagents/subagent-3/b.md
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(a, b),
            artifactsDir = artifactsDir,
        )

        assertTrue(result.report.contains(a))
        assertTrue(result.report.contains(b))
        assertFalse(result.report.contains("/sub/subagents/"))
        assertEquals(2, result.correctedPaths.size)
    }

    /**
     * A recorded path the model FORGOT to mention is added — the contract's
     * fourth field must not be silently incomplete.
     */
    @Test
    fun `a recorded path the model forgot is added to the section`() {
        val report = """
            status: done
            key_findings:
            - something
            gaps: none
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(realPath),
            artifactsDir = artifactsDir,
        )

        assertTrue("the forgotten artifact must be listed", result.report.contains(realPath))
    }

    // ── degenerate inputs ────────────────────────────────────────────────

    /** No artifacts and no report: nothing to do, and no crash. */
    @Test
    fun `an empty report with no artifacts is returned unchanged`() {
        val result = SubagentArtifactTruth.reconcile("", emptyList(), artifactsDir)

        assertEquals("", result.report)
        assertFalse(result.hadDrift)
    }

    /** A report with no artifact section and no writes is untouched. */
    @Test
    fun `a report with no artifacts is untouched`() {
        val report = "status: done\nkey_findings:\n- something\n"

        val result = SubagentArtifactTruth.reconcile(report, emptyList(), artifactsDir)

        assertEquals(report.trimEnd(), result.report.trimEnd())
        assertFalse(result.hadDrift)
    }

    /** Two writes of the same file collapse to one authoritative entry. */
    @Test
    fun `duplicate writes collapse to one authoritative path`() {
        val result = SubagentArtifactTruth.reconcile(
            "artifacts:\n- $realPath",
            listOf(realPath, realPath),
            artifactsDir,
        )

        assertEquals(listOf(realPath), result.authoritativePaths)
    }

    /**
     * A trailing full stop is sentence punctuation, not part of the path —
     * the repaired path must stay resolvable.
     */
    @Test
    fun `trailing sentence punctuation is not part of the path`() {
        val report = "See $driftedPath for details."

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(realPath),
            artifactsDir = artifactsDir,
        )

        assertTrue(result.report.contains(realPath))
        assertFalse(result.report.contains(driftedPath))
    }

    /**
     * A relative path in the report resolves to the recorded absolute one —
     * the parent gets a path it can actually open.
     */
    @Test
    fun `a relative path in the report resolves to the recorded absolute path`() {
        val report = """
            status: done
            artifacts:
            - parallel_file_read_report.md
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(realPath),
            artifactsDir = artifactsDir,
        )

        assertTrue(result.report.contains(realPath))
        assertFalse(
            "a bare relative path is not a resolvable anchor",
            result.report.contains("\n- parallel_file_read_report.md"),
        )
    }
}
