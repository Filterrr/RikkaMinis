package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-artifact-reconcile-shell-scope] The reconciliation must
 * cover EVERY run — not only the ones that happened to use `file_write`.
 *
 * ## The defect (measured, 2026-10-28)
 *
 * The runtime's write log is populated only by `file_write` / `file_edit`.
 * `reconcileArtifactPaths` began with
 *
 *     if (report.isBlank() || artifacts.isEmpty()) return report
 *
 * so a run whose deliverables were produced by `shell_execute` — the
 * ordinary way a sub-agent works — skipped the reconciliation ENTIRELY.
 *
 * A controlled experiment made the asymmetry visible. Two sub-agents were
 * given the same task, differing in ONE variable (how the file was
 * written), and both cited one real path plus two fabricated ones:
 *
 * | group | wrote the file with | fabricated paths flagged? |
 * |---|---|---|
 * | W | `file_write` | YES — 2 flagged |
 * | S | `shell_execute` | NO — 0 flagged, no warning at all |
 *
 * The guarantee the runtime advertised ("the parent is never handed two
 * contradictory sets of paths") was conditional on a tool choice the
 * MODEL made, not on anything the runtime controlled.
 *
 * ## The second defect: a warning that lies
 *
 * The single warning class said a cited path "may have been produced by
 * shell_execute, or may not exist". The runtime never checked the
 * filesystem, so it could not tell those two apart:
 *
 *  - a REAL shell artifact was flagged as "may not exist" — the warning
 *    was wrong;
 *  - a FABRICATED path got the same soft warning — the warning was too
 *    weak.
 *
 * The parent could not distinguish a deliverable from an invention.
 *
 * ## The fix
 *
 * The reconciliation is driven by the REPORT's paths, not by the write
 * log's emptiness, and an injected existence probe splits the unlogged
 * paths into "real, produced by shell_execute" and "does not exist".
 */
class SubagentArtifactReconcileShellScopeTest {

    private val artifactsDir = "/var/minis/workspace/subagents/subagent-6"

    /** A path the model invented — it is on no disk. */
    private val ghostPath = "/var/minis/workspace/subagents/subagent-6/ghost.txt"

    /** A path a `shell_execute` really produced, but the runtime never logged. */
    private val shellPath = "/var/minis/workspace/subagents/subagent-6/from_shell.md"

    /** The set of paths that exist on disk in this scenario. */
    private fun probeExisting(vararg existing: String): (String) -> Boolean {
        val set = existing.toSet()
        return { path -> path in set }
    }

    // ── the core defect: an EMPTY write log must not skip the check ─────

    /**
     * THE regression. The write log is EMPTY (the deliverable came from
     * `shell_execute`), and the report cites a path that does not exist.
     * Before the fix the reconciliation returned the report untouched and
     * the fabricated path reached the parent unmarked.
     */
    @Test
    fun `an empty write log does not skip the reconciliation`() {
        val report = """
            status: SUCCESS
            key_findings:
            - wrote the summary to $ghostPath
            artifacts:
            - $ghostPath
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = emptyList(),
            artifactsDir = artifactsDir,
            pathExists = probeExisting(),
        )

        assertTrue(
            "a fabricated path must be called out even when the write log is empty",
            result.report.contains("DO NOT EXIST on disk"),
        )
        assertTrue(
            "the fabricated path must be named",
            result.report.contains("ghost.txt"),
        )
        assertEquals(listOf(ghostPath), result.missingPaths)
        assertTrue("the reconciliation must report the drift", result.hadDrift)
        assertTrue("the reconciliation must report the absence", result.hadMissing)
    }

    /**
     * The mirror image: a REAL shell artifact must be accepted as a
     * deliverable. Before the fix it was flagged with the same warning as
     * a fabricated path; now it is recognised and named as real.
     */
    @Test
    fun `a real shell artifact is recognised rather than called a phantom`() {
        val report = """
            status: SUCCESS
            key_findings:
            - the summary is at $shellPath
            artifacts:
            - $shellPath
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = emptyList(),
            artifactsDir = artifactsDir,
            pathExists = probeExisting(shellPath),
        )

        assertFalse(
            "a file that exists must NOT be reported as missing",
            result.report.contains("DO NOT EXIST on disk"),
        )
        assertTrue(
            "a real shell artifact must be named as existing",
            result.report.contains("DO exist on disk"),
        )
        assertTrue(
            "the real artifact must still be visible",
            result.report.contains("from_shell.md"),
        )
        assertTrue("nothing is missing", result.missingPaths.isEmpty())
    }

    /**
     * Both classes at once — the scenario the controlled experiment
     * produced. The parent must be able to tell them apart by reading the
     * report.
     */
    @Test
    fun `real and fabricated shell paths are told apart`() {
        val report = """
            status: SUCCESS
            key_findings:
            - real: $shellPath
            - fake: $ghostPath
            artifacts:
            - $shellPath
            - $ghostPath
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = emptyList(),
            artifactsDir = artifactsDir,
            pathExists = probeExisting(shellPath),
        )

        assertEquals(
            "only the fabricated path is missing",
            listOf(ghostPath),
            result.missingPaths,
        )
        val missingLine = result.report.lines().first { it.contains("DO NOT EXIST") }
        val existsLine = result.report.lines().first { it.contains("DO exist on disk") }
        assertTrue("the missing note must name the ghost", missingLine.contains("ghost.txt"))
        assertFalse("the missing note must not name the real file", missingLine.contains("from_shell.md"))
        assertTrue("the exists note must name the real file", existsLine.contains("from_shell.md"))
        assertFalse("the exists note must not name the ghost", existsLine.contains("ghost.txt"))
    }

    // ── prose paths are covered too ──────────────────────────────────────

    /**
     * A path cited in `key_findings` — not in the `artifacts:` section —
     * was never checked at all. The parent's first instinct is to follow a
     * path it finds in the findings.
     */
    @Test
    fun `a fabricated path cited in prose is caught`() {
        val report = """
            status: SUCCESS
            key_findings:
            - read the result from $ghostPath
            artifacts:
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = emptyList(),
            artifactsDir = artifactsDir,
            pathExists = probeExisting(),
        )

        assertTrue(
            "a fabricated path in prose must be called out",
            result.report.contains("DO NOT EXIST on disk"),
        )
        assertTrue("the ghost must be named", result.report.contains("ghost.txt"))
    }

    /** A real path in prose needs no comment — only a fake one does. */
    @Test
    fun `a real path cited in prose is not flagged`() {
        val report = """
            status: SUCCESS
            key_findings:
            - read the result from $shellPath
            artifacts:
        """.trimIndent()

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = emptyList(),
            artifactsDir = artifactsDir,
            pathExists = probeExisting(shellPath),
        )

        assertFalse(
            "a real path must not be reported as missing",
            result.report.contains("DO NOT EXIST on disk"),
        )
        assertTrue("nothing is missing", result.missingPaths.isEmpty())
    }

    // ── the warning must not lie ─────────────────────────────────────────

    /**
     * Without a probe the runtime knows NEITHER, so the note must not
     * claim existence in either direction. The old text ("may have been
     * produced by shell_execute, or may not exist") was true of neither.
     */
    @Test
    fun `without a probe the note claims neither existence nor absence`() {
        val report = "status: SUCCESS\nartifacts:\n- $ghostPath"

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = emptyList(),
            artifactsDir = artifactsDir,
            pathExists = null,
        )

        assertFalse(
            "must not claim the path is missing",
            result.report.contains("DO NOT EXIST"),
        )
        assertFalse(
            "must not claim the path exists",
            result.report.contains("DO exist on disk"),
        )
        assertTrue(
            "must say the existence could not be checked",
            result.report.contains("could not be checked"),
        )
    }

    /**
     * A path the runtime DID record is authoritative — the probe must not
     * downgrade it. (The write log is the strongest evidence there is.)
     */
    @Test
    fun `a recorded path is never reported as missing even if the probe says no`() {
        val recorded = "$artifactsDir/recorded.md"
        val report = "status: SUCCESS\nartifacts:\n- $recorded"

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = listOf(recorded),
            artifactsDir = artifactsDir,
            pathExists = { false },
        )

        assertFalse(
            "a recorded path must not be reported as missing",
            result.report.contains("DO NOT EXIST"),
        )
        assertTrue("nothing is missing", result.missingPaths.isEmpty())
    }

    /** A host path is not the runtime's business — it must not be probed. */
    @Test
    fun `a host path is left alone`() {
        val report = "status: SUCCESS\nartifacts:\n- /tmp/somewhere.txt"

        val result = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = emptyList(),
            artifactsDir = artifactsDir,
            pathExists = { false },
        )

        assertFalse(
            "a host path must not be called missing",
            result.report.contains("DO NOT EXIST"),
        )
        assertTrue("host paths must survive", result.report.contains("/tmp/somewhere.txt"))
    }
}
