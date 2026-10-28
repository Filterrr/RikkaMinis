package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-verdict-consistency-v3] Source-grep drift guards for the
 * fixes whose decision logic lives in a pure object but whose EFFECT lives
 * at a call site.
 *
 * A pure predicate is never wrong — what goes wrong is the call site
 * passing the wrong input, or not calling the object at all. Unit tests
 * cannot see either. These guards assert that the production files still
 * carry the load-bearing lines, so a future refactor that drops one of them
 * fails here instead of silently reintroducing the defect.
 */
class SubagentVerdictConsistencyWiringTest {

    private val runnerSource: String by lazy {
        ProductionSources.read("tools/SubagentRunner.kt")
    }

    private val orchestrationSource: String by lazy {
        ProductionSources.read("tools/SubagentOrchestration.kt")
    }

    private val journalSource: String by lazy {
        ProductionSources.read("tools/SubagentRunJournal.kt")
    }

    /**
     * The parent-facing delivery helpers live in their own file (they were
     * moved out of SubagentRunner so they could be unit-tested on the JVM).
     */
    private val deliverySource: String by lazy {
        ProductionSources.read("tools/SubagentParentDelivery.kt")
    }

    /**
     * [feat/subagent-result-envelope] The ONE renderer every delivery
     * path goes through.
     */
    private val envelopeSource: String by lazy {
        ProductionSources.read("tools/SubagentResultEnvelope.kt")
    }

    // ── The journal must be written on the crash path ───────────────────

    @Test
    fun `the detached crash path journals its terminal record`() {
        // The cancel branch journaled; the crash branch did not. A crashed
        // detached run left the registry saying FAILED with no durable
        // record at all — the failure reason was unrecoverable.
        //
        // The guard scopes to the crash branch: the block between the
        // "detached ... crashed" log and the `finally`.
        val marker = "detached '\$skillName' crashed"
        val start = runnerSource.indexOf(marker)
        assertTrue("the detached crash branch must exist", start >= 0)
        val branch = runnerSource.substring(start, minOf(start + 2_000, runnerSource.length))
        val end = branch.indexOf("} finally {")
        val crashBranch = if (end > 0) branch.substring(0, end) else branch
        assertTrue(
            "the detached crash branch must write a journal:\n$crashBranch",
            crashBranch.contains("journal(run,"),
        )
    }

    @Test
    fun `the crash branch journals with the FAILED terminal state`() {
        val marker = "detached '\$skillName' crashed"
        val start = runnerSource.indexOf(marker)
        assertTrue(start >= 0)
        val branch = runnerSource.substring(start, minOf(start + 2_000, runnerSource.length))
        val end = branch.indexOf("} finally {")
        val crashBranch = if (end > 0) branch.substring(0, end) else branch
        assertTrue(
            "the crash journal must be terminal FAILED:\n$crashBranch",
            crashBranch.contains("\"FAILED\""),
        )
    }

    // ── The crash / cancel branches must carry a verdict ─────────────────

    @Test
    fun `the inline exception branch carries a FAILED verdict`() {
        // The default JobOutcome verdict is COMPLETED + MODEL — "the model
        // wrote a report". The crash branch must override it.
        val marker = "error = e.message ?: e.javaClass.simpleName,"
        val start = runnerSource.indexOf(marker)
        assertTrue("the inline exception branch must exist", start >= 0)
        val branch = runnerSource.substring(start, minOf(start + 800, runnerSource.length))
        assertTrue(
            "the inline exception branch must set a FAILED verdict:\n$branch",
            branch.contains("SubagentRunAssessment.Outcome.FAILED"),
        )
        assertTrue(
            "the inline exception branch must set ReportSource.NONE:\n$branch",
            branch.contains("SubagentRunAssessment.ReportSource.NONE"),
        )
    }

    @Test
    fun `the missing-registry-snapshot fallback is UNKNOWN, not COMPLETED`() {
        // The old fallback said COMPLETED + MODEL while `success` was false
        // — "failed, but the model wrote a report". UNKNOWN is the honest
        // answer.
        assertTrue(
            "the registry fallback must be UNKNOWN",
            runnerSource.contains(
                "outcome = snapshot?.runOutcome ?: SubagentRunAssessment.Outcome.UNKNOWN,",
            ),
        )
        assertFalse(
            "the registry fallback must not claim a model report",
            runnerSource.contains(
                "outcome = snapshot?.runOutcome ?: SubagentRunAssessment.Outcome.COMPLETED,",
            ),
        )
    }

    // ── The detached paths must consume the verdict ──────────────────────

    /**
     * [feat/subagent-result-envelope] The verdict label now lives in the
     * ONE envelope renderer, and the three detached paths frame
     * themselves through it. These guards therefore moved from "this
     * file contains this exact indented snippet" to "the envelope
     * computes the label with summaryStatus" plus "each framing asks
     * for it".
     *
     * The old shape-pinning guards were checking a call site that no
     * longer exists — they went red when the code was RIGHT (the
     * verdict label is still rendered, in the envelope) and would have
     * gone green if the envelope had dropped it. Pinning the BEHAVIOUR
     * (the envelope renders the label) instead of the LOCATION is what
     * keeps them meaningful.
     */
    @Test
    fun `the join prompt renders the verdict label, not a binary success word`() {
        assertTrue(
            "the envelope must compute the verdict label with summaryStatus",
            envelopeSource.contains("SubagentRunAssessment.summaryStatus("),
        )
        assertTrue(
            "the join framing must render the verdict label",
            envelopeSource.contains("Framing.JOIN ->"),
        )
        assertFalse(
            "the join prompt must not collapse every verdict into SUCCESS/FAILED",
            runnerSource.contains("outcome.success -> \"SUCCESS\""),
        )
    }

    @Test
    fun `the wait_any winner line renders the verdict label`() {
        assertTrue(
            "the wait_any framing must render the verdict label",
            envelopeSource.contains("Framing.WAIT_ANY ->"),
        )
        assertTrue(
            "the wait_any path must render through the envelope",
            runnerSource.contains("SubagentResultEnvelope.Framing.WAIT_ANY"),
        )
    }

    @Test
    fun `the background wake prompt renders the verdict label`() {
        assertTrue(
            "the wake framing must render the verdict label",
            envelopeSource.contains("Framing.WAKE ->"),
        )
        assertFalse(
            "the wake prompt must not fall back to the binary success flag",
            deliverySource.contains(
                "val status = snapshot?.status?.name ?: if (outcome.success) \"SUCCESS\" else \"FAILED\"",
            ),
        )
    }

    @Test
    fun `the wake prompt keeps the runtime status alongside the verdict`() {
        // The runtime status says HOW the run ended (it ran out of clock);
        // the verdict says WHAT it delivered. Both must reach the parent —
        // a timeout that left usable notes is not a plain failure.
        assertTrue(
            "the wake prompt must read the registry's runtime status",
            deliverySource.contains("runtimeStatus = snapshot?.status?.name"),
        )
    }

    @Test
    fun `the orchestration waitAny race uses the delivered flag`() {
        assertTrue(
            "the race must skip runs that did not deliver",
            orchestrationSource.contains(
                "if (successOnly && (completed.cancelled || !completed.delivered)) {",
            ),
        )
        assertFalse(
            "the race must not fall back to the binary success flag",
            orchestrationSource.contains(
                "if (successOnly && (completed.cancelled || !completed.success)) {",
            ),
        )
    }

    @Test
    fun `the join summary counts delivered runs`() {
        assertTrue(
            "the summary must count delivered runs",
            orchestrationSource.contains("val ok = outcomes.count { it.delivered }"),
        )
    }

    // ── The journal must scrub secrets ───────────────────────────────────

    @Test
    fun `the journal scrubs the report text before writing it`() {
        assertTrue(
            "the journal must scrub the report",
            journalSource.contains("appendLine(scrub(resultText))"),
        )
        assertFalse(
            "the journal must not write the raw report",
            journalSource.contains("appendLine(resultText)"),
        )
    }

    @Test
    fun `the journal scrubs the task text and the error note`() {
        assertTrue(
            "the journal must scrub the task query",
            journalSource.contains("appendLine(scrub(run.query))"),
        )
        assertTrue(
            "the journal must scrub the error note",
            journalSource.contains("appendLine(\"- note: \${scrub(it)}\")"),
        )
    }

    @Test
    fun `the journal scrubs the transcript and the step outputs`() {
        assertTrue(
            "the journal must scrub the transcript segments",
            journalSource.contains("appendLine(scrub(segment.content.trim()))"),
        )
        assertTrue(
            "the journal must scrub the step outputs",
            journalSource.contains("appendLine(scrub(step.output))"),
        )
    }
}
