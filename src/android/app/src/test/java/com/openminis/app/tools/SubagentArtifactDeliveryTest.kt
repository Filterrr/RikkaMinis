package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-artifact-delivery] The deliverables a run produced must
 * REACH the parent on EVERY delivery path.
 *
 * The defect this pins
 * -------------------
 * The artifact list lived in exactly one place: the runner's local
 * `mutableListOf<String>`. It reached the inline path (the inline
 * `SubagentResult` carried it) and the journal header — and NOWHERE else.
 *
 * The detached paths build their result from the registry snapshot
 * (`completeJobFromRegistry` → `JobOutcome`). `SubagentRunRegistry.Run` had
 * no artifact field at all, so:
 *
 *  - the background wake-up named no deliverables,
 *  - `join_subagents` named no deliverables,
 *  - `wait_any` named no deliverables.
 *
 * A detached research run that wrote three files delivered its report to
 * the parent with the paths buried in the model's PROSE — and prose drifts
 * (that is the whole reason [SubagentArtifactTruth] exists). The parent had
 * no runtime-vouched path to follow.
 *
 * These tests cover the DATA path (registry → JobOutcome → the rendered
 * line) and the WIRING (the runner must record into the registry at write
 * time, and each detached surface must render the line).
 */
class SubagentArtifactDeliveryTest {

    private val runner by lazy { ProductionSources.read("tools/SubagentRunner.kt") }

    private fun outcome(
        report: String = "report body",
        artifacts: List<String> = emptyList(),
        journalPath: String? = "/var/minis/workspace/.subagent/run-1.md",
    ) = SubagentOrchestration.JobOutcome(
        runId = "run-1",
        success = true,
        report = report,
        skillName = "general-agent",
        journalPath = journalPath,
        artifacts = artifacts,
    )

    // ── the data path ────────────────────────────────────────────────────

    @Test
    fun `the registry records an artifact and keeps it`() {
        val registry = SubagentRunRegistry()
        val run = registry.register(
            blockId = "b1",
            skillId = "general-agent",
            skillName = "general-agent",
            query = "do the thing",
            title = "do the thing",
            maxTurns = 10,
        )

        registry.addArtifact(run.id, "/var/minis/workspace/subagents/run-1/report.md")

        val stored = registry.runs.value.first { it.id == run.id }
        assertEquals(
            listOf("/var/minis/workspace/subagents/run-1/report.md"),
            stored.artifacts,
        )
    }

    @Test
    fun `the same artifact is recorded once`() {
        val registry = SubagentRunRegistry()
        val run = registry.register(
            blockId = "b1",
            skillId = "general-agent",
            skillName = "general-agent",
            query = "do the thing",
            title = "do the thing",
            maxTurns = 10,
        )

        registry.addArtifact(run.id, "/a.md")
        registry.addArtifact(run.id, "/a.md")

        assertEquals(1, registry.runs.value.first { it.id == run.id }.artifacts.size)
    }

    @Test
    fun `an artifact survives a terminal write`() {
        val registry = SubagentRunRegistry()
        val run = registry.register(
            blockId = "b1",
            skillId = "general-agent",
            skillName = "general-agent",
            query = "do the thing",
            title = "do the thing",
            maxTurns = 10,
        )

        registry.addArtifact(run.id, "/a.md")
        registry.finishIfActive(run.id, SubagentRunRegistry.RunStatus.SUCCESS, resultText = "done")

        assertEquals(
            listOf("/a.md"),
            registry.runs.value.first { it.id == run.id }.artifacts,
        )
    }

    @Test
    fun `a blank artifact path is ignored`() {
        val registry = SubagentRunRegistry()
        val run = registry.register(
            blockId = "b1",
            skillId = "general-agent",
            skillName = "general-agent",
            query = "do the thing",
            title = "do the thing",
            maxTurns = 10,
        )

        registry.addArtifact(run.id, "   ")

        assertTrue(registry.runs.value.first { it.id == run.id }.artifacts.isEmpty())
    }

    // ── the rendered line ────────────────────────────────────────────────

    /**
     * [feat/subagent-result-envelope] The artifact line is now part of
     * the ONE envelope — there is no separate helper any more, because
     * four copies of "how do we name the deliverables" is how they drift.
     */
    @Test
    fun `the artifact line names every deliverable`() {
        val text = SubagentResultEnvelope.render(
            facts = SubagentResultEnvelope.Facts(
                runId = "run-1",
                skillName = "general-agent",
                outcome = SubagentRunAssessment.Outcome.COMPLETED,
                reportSource = SubagentRunAssessment.ReportSource.MODEL,
                executedToolCalls = 1,
                turns = 1,
                maxTurns = 10,
                artifacts = listOf("/a.md", "/b.md"),
            ),
            framing = SubagentResultEnvelope.Framing.INLINE,
        )
        assertTrue("the envelope must name the deliverables: $text", text.contains("artifacts: /a.md, /b.md"))
    }

    @Test
    fun `no artifacts means the envelope says so`() {
        val text = SubagentResultEnvelope.render(
            facts = SubagentResultEnvelope.Facts(
                runId = "run-1",
                skillName = "general-agent",
                outcome = SubagentRunAssessment.Outcome.COMPLETED,
                reportSource = SubagentRunAssessment.ReportSource.MODEL,
                executedToolCalls = 1,
                turns = 1,
                maxTurns = 10,
            ),
            framing = SubagentResultEnvelope.Framing.INLINE,
        )
        assertTrue("the envelope must say there are none: $text", text.contains("artifacts: none"))
    }

    // ── the delivery surfaces ────────────────────────────────────────────

    @Test
    fun `the wake prompt names the deliverables`() {
        val prompt = buildWakePrompt(
            outcome = outcome(artifacts = listOf("/var/minis/workspace/subagents/run-1/report.md")),
            snapshot = null,
        )

        assertTrue(
            "the wake prompt must name what the run produced: $prompt",
            prompt.contains("artifacts: /var/minis/workspace/subagents/run-1/report.md"),
        )
    }

    /**
     * [feat/subagent-result-envelope] The envelope renders a FIXED set of
     * fields — including `artifacts: none`. That is a deliberate change
     * from the old wake renderer, which omitted the line entirely.
     *
     * The reason: the envelope is a contract. A parent that reads the
     * fields positionally (which it will, because the fields are now
     * IDENTICAL on every path) cannot tell "no artifacts" from "the
     * renderer forgot to mention them". An explicit `none` is a fact; a
     * missing line is an ambiguity.
     */
    @Test
    fun `the wake prompt of an artifact-less run says there are none`() {
        val prompt = buildWakePrompt(outcome = outcome(), snapshot = null)

        assertTrue(
            "a run that produced nothing must say so explicitly: $prompt",
            prompt.contains("artifacts: none"),
        )
    }

    /**
     * The inline path already named the deliverables — but only on the
     * SUCCESS branch. A FAILED run can still have produced deliverables
     * (that is exactly the work worth salvaging), and the failed branch
     * named none.
     */
    @Test
    fun `a failed inline run still names its deliverables`() {
        val result = SubagentResult(
            status = SubagentResult.Status.FAILED,
            report = "partial work",
            turns = 3,
            maxTurns = 10,
            skillId = "general-agent",
            skillName = "general-agent",
            outcome = SubagentRunAssessment.Outcome.FAILED,
            executedToolCalls = 2,
            artifacts = listOf("/var/minis/workspace/subagents/run-1/notes.md"),
            error = "boom",
        )

        val text = result.toPromptText()
        assertTrue(
            "a failed run's deliverables must be named: $text",
            text.contains("artifacts: /var/minis/workspace/subagents/run-1/notes.md"),
        )
    }

    @Test
    fun `a first-turn readout names the deliverables it already wrote`() {
        val result = SubagentResult(
            status = SubagentResult.Status.SUCCESS,
            report = "wrote one file",
            turns = 1,
            maxTurns = 10,
            skillId = "general-agent",
            skillName = "general-agent",
            outcome = SubagentRunAssessment.Outcome.STOPPED_EARLY,
            executedToolCalls = 1,
            artifacts = listOf("/var/minis/workspace/subagents/run-1/a.md"),
        )

        val text = result.toPromptText(stoppedEarly = true)
        assertTrue(
            "an early readout must still name what it wrote: $text",
            text.contains("artifacts: /var/minis/workspace/subagents/run-1/a.md"),
        )
    }

    // ── wiring guards ────────────────────────────────────────────────────

    /**
     * The registry is what every detached path reads. If the runner records
     * the artifact ONLY in its local list, the pure tests above stay green
     * (they test the registry directly) while the detached surfaces keep
     * delivering nothing — the exact "pure function correct, call site
     * wrong" shape this repo has been bitten by repeatedly.
     */
    @Test
    fun `the runner records a successful write into the registry`() {
        assertTrue(
            "a successful file write must be recorded in the registry",
            runner.contains("registry.addArtifact(runId, canonical)"),
        )
    }

    @Test
    fun `the detached outcome reads the artifacts back from the registry`() {
        assertTrue(
            "the detached outcome must carry the registry's artifact list",
            runner.contains("artifacts = snapshot?.artifacts.orEmpty()"),
        )
    }

    /**
     * [feat/subagent-result-envelope] The artifact line moved INTO the
     * one envelope — there is no per-path helper any more, because four
     * copies of "how do we name the deliverables" is how they drift.
     *
     * So the guard now asks two questions: does the envelope render the
     * artifacts, and does every detached path go through it?
     */
    @Test
    fun `every detached surface renders the artifact line`() {
        val delivery = ProductionSources.read("tools/SubagentParentDelivery.kt")
        val envelope = ProductionSources.read("tools/SubagentResultEnvelope.kt")
        assertTrue(
            "the envelope must render the artifact line",
            envelope.contains("artifacts: "),
        )
        assertTrue(
            "the envelope must receive the artifact list",
            envelope.contains("val artifacts: List<String>"),
        )
        assertTrue(
            "the wake prompt must render through the envelope",
            delivery.contains("SubagentResultEnvelope.Framing.WAKE"),
        )
        assertTrue(
            "the join prompt must render through the envelope",
            runner.contains("SubagentResultEnvelope.Framing.JOIN"),
        )
        assertTrue(
            "the wait_any winner must render through the envelope",
            runner.contains("SubagentResultEnvelope.Framing.WAIT_ANY"),
        )
    }
}
