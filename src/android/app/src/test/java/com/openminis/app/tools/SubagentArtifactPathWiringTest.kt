package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-artifact-path-truth] WIRING guard.
 *
 * The reconciliation logic lives in a pure object and is unit-tested there.
 * What the unit tests CANNOT see is whether the runner calls it — and that
 * is exactly where this class of defect lives. A runner that keeps the raw
 * report would leave every pure test green while the parent still received
 * the drifted path.
 *
 * So these tests grep the production source and assert the call sites exist.
 * The pattern is the one the repo already uses for wiring drift
 * (see `ProductionSources`).
 */
class SubagentArtifactPathWiringTest {

    private val runner by lazy { ProductionSources.read("tools/SubagentRunner.kt") }

    /** The runner must normalise the path it RECORDS, not store it verbatim. */
    @Test
    fun `the runner records the canonical path rather than the raw argument`() {
        assertTrue(
            "the write log must be canonicalised before it is stored",
            runner.contains("SubagentArtifactTruth.canonicalize("),
        )
        assertTrue(
            "the raw path must no longer be added straight to the artifact list",
            !runner.contains("extractPath(argsJson)?.let { artifacts?.add(it) }"),
        )
    }

    /** The reconciliation must be applied on the SUCCESS exit path. */
    @Test
    fun `the success exit reconciles the delivered report`() {
        assertTrue(
            "the delivered text must be reconciled",
            runner.contains("val deliveredText = reconcileArtifactPaths(reportText, artifacts, run.id)"),
        )
    }

    /**
     * Every terminal exit must reconcile. A partial report that drifted is
     * just as useless to a parent as a complete one that drifted.
     */
    @Test
    fun `every terminal exit reconciles the report`() {
        val callSites = Regex("""reconcileArtifactPaths\((\w+), artifacts, run\.id\)""").findAll(runner)
            .map { it.groupValues[1] }
            .toList()

        assertTrue(
            "there must be reconciliation call sites, found none",
            callSites.isNotEmpty(),
        )
        // `partial` (timeout / early stop / exception / cancellation) and
        // `reportText` (success) are the exits that carry a report onward.
        assertTrue(
            "the timeout / early-stop / exception / cancellation exits must reconcile: found $callSites",
            callSites.count { it == "partial" } >= 4,
        )
        assertTrue(
            "the success exit must reconcile: found $callSites",
            callSites.contains("reportText"),
        )
    }

    /** The helper must be a private method with the run id passed explicitly. */
    @Test
    fun `the reconciliation helper exists with an explicit run id`() {
        assertTrue(
            "the helper must exist",
            runner.contains("private fun reconcileArtifactPaths("),
        )
        assertTrue(
            "the helper must take the run id explicitly — a relative path has no " +
                "meaning without the base it is resolved against",
            runner.contains("runId: String,"),
        )
    }

    /** The pure object must exist with the reconciliation entry point. */
    @Test
    fun `the pure reconciliation object exists`() {
        val obj = ProductionSources.read("tools/SubagentArtifactTruth.kt")
        assertTrue(obj.contains("object SubagentArtifactTruth"))
        assertTrue(obj.contains("fun reconcile("))
        assertTrue(obj.contains("fun canonicalize("))
    }

    /**
     * The runtime's authoritative list must be the one the report is built
     * from — a report that still lists the model's own paths is not fixed.
     */
    @Test
    fun `the artifacts section is rebuilt from the runtime log`() {
        val obj = ProductionSources.read("tools/SubagentArtifactTruth.kt")
        assertTrue(
            "the section must be rebuilt from the authoritative list",
            obj.contains("rebuildArtifactsSection(text, authoritative"),
        )
        assertEquals(
            "the reconciliation must be pure — no registry, no clock, no filesystem",
            0,
            Regex("""\bregistry\b|Clock\.|System\.currentTimeMillis|File\(""")
                .findAll(stripKotlinComments(obj))
                .count(),
        )
    }

    /**
     * Strip line and block comments so a purity check does not trip on the
     * documentation ("Pure + JVM-testable: no Android, no registry, …").
     * The guard is about CODE, not about prose.
     */
    private fun stripKotlinComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    // ── [fix/subagent-artifact-reconcile-shell-scope] ───────────────────
    //
    // The unit tests in SubagentArtifactReconcileShellScopeTest cover the
    // PURE behaviour. They CANNOT cover whether the runner reaches that
    // behaviour — and the defect lived exactly there.

    /**
     * THE regression, at the wiring level.
     *
     * `reconcileArtifactPaths` used to start with
     *
     *     if (report.isBlank() || artifacts.isEmpty()) return report
     *
     * `artifacts` is populated only by `file_write` / `file_edit`, so a run
     * whose deliverables came from `shell_execute` skipped the
     * reconciliation entirely. Measured 2026-10-28: two sub-agents given
     * the same task, differing only in how they wrote the file — the
     * `file_write` one had its fabricated paths flagged, the
     * `shell_execute` one had NO warning at all.
     *
     * The pure tests stay GREEN when this early return comes back (the
     * pure function is still correct — nobody calls it). Only a guard on
     * the runner's own source can see it.
     */
    @Test
    fun `the reconciliation must not be skipped when the write log is empty`() {
        val body = runner
        assertTrue(
            "the runner must reconcile even with an empty write log — a run that " +
                "produced its files with shell_execute has an empty log, and skipping " +
                "the reconciliation is what let fabricated paths through unmarked",
            !body.contains("artifacts.isEmpty()) return report"),
        )
        assertTrue(
            "the blank-report guard must remain",
            body.contains("if (report.isBlank()) return report"),
        )
    }

    /**
     * The existence probe must be INJECTED, not hard-coded — the pure
     * object may not touch the filesystem (the purity guard above asserts
     * it does not).
     */
    @Test
    fun `the existence probe is injected into the reconciliation`() {
        assertTrue(
            "the runner must inject an existence probe — without it the runtime " +
                "cannot tell a real shell artifact from a fabricated path",
            runner.contains("pathExists = ::artifactPathExists"),
        )
        assertTrue(
            "the probe must be defined",
            runner.contains("private fun artifactPathExists("),
        )
        assertTrue(
            "the probe must actually check the filesystem",
            runner.contains("file != null && file.exists()"),
        )
    }

    /**
     * The probe must resolve through the SAME path resolution the file
     * tools use — otherwise the runtime would answer "this exists" about a
     * path the parent cannot open.
     */
    @Test
    fun `the existence probe uses the session path resolution`() {
        assertTrue(
            "the probe must resolve through PRootKernel.resolveSessionHostPath",
            runner.contains("PRootKernel.resolveSessionHostPath(deps.activeSessionId(), path, context)"),
        )
    }

    /**
     * The old single warning class said "may have been produced by
     * shell_execute, or may not exist" — true of neither a real artifact
     * nor a fabricated one. The two cases must now be distinguishable.
     */
    @Test
    fun `the warning distinguishes a missing path from a shell produced one`() {
        val obj = ProductionSources.read("tools/SubagentArtifactTruth.kt")
        assertTrue(
            "a path that does not exist must be called out as missing",
            obj.contains("DO NOT EXIST on disk"),
        )
        assertTrue(
            "a path that exists must be called out as real",
            obj.contains("DO exist on disk"),
        )
        // Strip the comments before the wording check: the DEFECT's
        // wording is quoted in this file's documentation, and a guard that
        // greps raw source trips on its own explanation of the bug.
        assertTrue(
            "the old ambiguous wording must be gone from the CODE",
            !stripKotlinComments(obj).contains("or may not exist"),
        )
    }
}
