package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-verdict-and-report-gate] Pins the four defects this branch
 * closes.
 *
 * Every test corresponds to a defect reproduced against a real sub-agent run
 * or against the real production code path:
 *
 * - B1: the report gate judged the run's ACCUMULATED narration, so any
 *   earlier prose diluted the closing husk past the substance threshold and
 *   the gate never fired. The parent received the bare tag as the findings.
 * - B1b: the declared `status:` was read out of the accumulated text, so a
 *   self-assessment the model wrote in an EARLIER turn was taken as this
 *   turn's — flipping the run's verdict.
 * - B5: tool calls the loop detector short-circuited at CRITICAL level were
 *   counted as executed, so a run that never touched anything reported
 *   COMPLETED.
 * - B4: the verdict existed only on the inline path. A detached run built
 *   its result from the registry snapshot, where the only signal was
 *   `status == SUCCESS`, so every detached run reached the parent as an
 *   ordinary success.
 *
 * ## Why these tests call the decision object
 *
 * The first version of this branch fixed the defects in the runner's CALL
 * SITES and tested the pure helpers. The negative verification then showed
 * the tests still passed with the fixes reverted: the pure helpers were
 * never wrong, the runner was passing them the wrong input. A test that
 * passes against the broken behaviour is not guarding anything.
 *
 * So the decision itself moved into [SubagentReportDecision] and
 * [SubagentExecutedCallCount]. These tests exercise THAT — the layer where
 * the behaviour now lives.
 */
class SubagentVerdictAndReportGateTest {

    // ─── B1 · the report gate judges the model's FINAL message ─────────────

    /**
     * The exact shape observed in a real run (2026-10-22): the closing
     * message was a husk tag followed by a paragraph of narration.
     */
    private val closingHusk = "<status>done</status>"

    private val planTurnProse =
        "I will gather the evidence, check the logs, and then report the findings clearly."

    private fun decide(
        finalMessage: String,
        accumulatedText: String = "",
        executedToolCalls: Int = 3,
        steps: List<SubagentReportHygiene.ToolDigest> = emptyList(),
    ) = SubagentReportDecision.decide(
        SubagentReportDecision.Input(
            finalMessage = finalMessage,
            accumulatedText = accumulatedText,
            executedToolCalls = executedToolCalls,
            hasToolEvidence = steps.isNotEmpty(),
            toolSteps = steps,
        ),
    )

    private val aToolStep = SubagentReportHygiene.ToolDigest(
        toolName = "shell_execute",
        toolTitle = "Run a command",
        success = true,
        output = "3 /tmp/zerotool/loop.txt",
    )

    @Test
    fun `B1 a closing husk is caught even when earlier turns carried prose`() {
        // The accumulation — what the old code judged — passes the gate.
        // This is the precondition that made the old gate miss the husk.
        val accumulated = "$planTurnProse\n\n$closingHusk"
        assertFalse(
            "precondition: the accumulated text is NOT a husk, which is why the old gate missed it",
            SubagentReportHygiene.isDegenerate(accumulated),
        )
        // The decision judges the closing message.
        val decision = decide(finalMessage = closingHusk, accumulatedText = accumulated)
        assertTrue("the closing message IS a husk and must be caught", decision.degenerate)
    }

    @Test
    fun `B1 a closing husk with tool evidence yields a runtime digest`() {
        val decision = decide(
            finalMessage = closingHusk,
            accumulatedText = "$planTurnProse\n\n$closingHusk",
            steps = listOf(aToolStep),
        )
        assertTrue(decision.degenerate)
        assertEquals(
            SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
            decision.reportSource,
        )
        assertTrue(
            "the parent must get the tool evidence, not the husk",
            decision.reportText.contains("runtime-assembled report"),
        )
        assertTrue(decision.reportText.contains("3 /tmp/zerotool/loop.txt"))
        assertEquals(SubagentRunAssessment.Outcome.DEGRADED, decision.outcome)
    }

    @Test
    fun `B1 a closing husk with no tool evidence is delivered with a warning`() {
        val decision = decide(finalMessage = closingHusk, executedToolCalls = 0)
        assertTrue(decision.degenerate)
        assertTrue(
            "the husk must not be handed over as findings",
            decision.reportText.contains("no usable findings"),
        )
    }

    @Test
    fun `B1 a real closing message is delivered as the model's own account`() {
        val report = """
            status: done
            key_findings:
            - disk 28% used (df -h /)
            gaps: none
        """.trimIndent()
        val decision = decide(
            finalMessage = report,
            accumulatedText = "irrelevant earlier prose",
        )
        assertFalse(decision.degenerate)
        assertEquals(SubagentRunAssessment.ReportSource.MODEL, decision.reportSource)
        assertEquals(report.trim(), decision.reportText)
        assertEquals(SubagentRunAssessment.Outcome.COMPLETED, decision.outcome)
    }

    @Test
    fun `B1 the delivered report is the closing message, never the accumulation`() {
        val accumulation = "$planTurnProse\n\n$closingHusk"
        val decision = decide(
            finalMessage = closingHusk,
            accumulatedText = accumulation,
        )
        assertFalse(
            "the accumulation must not be delivered as the report",
            decision.reportText.contains("gather the evidence"),
        )
    }

    @Test
    fun `B1 a brief but tag-free closing message is delivered as a report`() {
        // Brevity is not the failure mode; tag markup with nothing behind it
        // is. A one-line real answer must survive intact.
        val decision = decide(finalMessage = "HTTP 200 from example.com")
        assertFalse(decision.degenerate)
        assertEquals("HTTP 200 from example.com", decision.reportText)
        assertEquals(SubagentRunAssessment.Outcome.COMPLETED, decision.outcome)
    }

    @Test
    fun `B1 an empty closing message yields no report, not the accumulation`() {
        val decision = decide(
            finalMessage = "",
            accumulatedText = planTurnProse,
            executedToolCalls = 0,
        )
        assertEquals(SubagentRunAssessment.ReportSource.NONE, decision.reportSource)
        // Zero executed calls outranks the report shape: nothing ran, so the
        // verdict is UNSTARTED, not NO_OUTPUT.
        assertEquals(SubagentRunAssessment.Outcome.UNSTARTED, decision.outcome)
        assertTrue(
            "the plan's prose must not be delivered as the report",
            !decision.reportText.contains("gather the evidence"),
        )
    }

    @Test
    fun `B1 an empty closing message after real work is NO_OUTPUT`() {
        val decision = decide(
            finalMessage = "",
            accumulatedText = planTurnProse,
            executedToolCalls = 3,
            steps = listOf(aToolStep),
        )
        assertEquals(SubagentRunAssessment.ReportSource.NONE, decision.reportSource)
        assertEquals(SubagentRunAssessment.Outcome.NO_OUTPUT, decision.outcome)
        assertTrue(
            "the plan's prose must not be delivered as the report",
            !decision.reportText.contains("gather the evidence"),
        )
    }

    // ─── B1b · the declared status comes from the final message ────────────

    @Test
    fun `B1b an earlier turn's status is not this turn's self-assessment`() {
        val accumulated = "status: partial\n\n$closingHusk"
        // The accumulation still parses as `partial` — that is what the old
        // code read.
        assertEquals("partial", SubagentSkill.parseReportStatus(accumulated))
        // The closing message has no status field of its own.
        assertNull(SubagentSkill.parseReportStatus(closingHusk))
        // The decision must not pick up the previous turn's self-assessment.
        val decision = decide(finalMessage = closingHusk, accumulatedText = accumulated)
        assertNull(decision.declaredStatus)
    }

    @Test
    fun `B1b the closing message's own status is read`() {
        val decision = decide(
            finalMessage = """
                key_findings:
                - found 2 of 3 endpoints
                status: partial
            """.trimIndent(),
        )
        assertEquals("partial", decision.declaredStatus)
        assertEquals(SubagentRunAssessment.Outcome.PARTIAL, decision.outcome)
    }

    @Test
    fun `B1b a runtime digest's status is not reported as the model's`() {
        val decision = decide(
            finalMessage = closingHusk,
            steps = listOf(aToolStep),
        )
        // The digest carries its own `status: partial` line — the runtime's
        // wording, not the model's self-assessment.
        assertTrue(decision.reportText.contains("status: partial"))
        assertNull(
            "a runtime digest's status must not be read as the model's",
            decision.declaredStatus,
        )
    }

    // ─── B5 · only calls that actually executed count ──────────────────────

    @Test
    fun `B5 calls blocked by the loop detector do not count as executed`() {
        val proposed = listOf("c0", "c1", "c2")
        val blocked = setOf("c0", "c1", "c2")
        val executed = SubagentExecutedCallCount.count(proposed, blocked)
        assertEquals(0, executed)
        assertEquals(
            "the old count said 3 — every call was blocked",
            3,
            proposed.size,
        )
    }

    @Test
    fun `B5 only the blocked calls are excluded`() {
        val executed = SubagentExecutedCallCount.count(
            proposedCallIds = listOf("c0", "c1", "c2"),
            blockedCallIds = setOf("c1"),
        )
        assertEquals(2, executed)
    }

    @Test
    fun `B5 a run whose calls were all blocked is not COMPLETED`() {
        val executed = SubagentExecutedCallCount.count(
            proposedCallIds = listOf("c0", "c1", "c2"),
            blockedCallIds = setOf("c0", "c1", "c2"),
        )
        val decision = decide(
            finalMessage = "I looked at the logs and here is what I found.",
            executedToolCalls = executed,
        )
        assertEquals(SubagentRunAssessment.Outcome.UNSTARTED, decision.outcome)
        assertFalse(
            "a run that executed nothing must not be deliverable",
            SubagentRunAssessment.isDeliverable(decision.outcome),
        )
    }

    @Test
    fun `B5 the count accumulates across turns`() {
        val total = SubagentExecutedCallCount.add(
            current = 2,
            proposedCallIds = listOf("c3", "c4", "c5"),
            blockedCallIds = setOf("c4"),
        )
        assertEquals(4, total)
    }

    @Test
    fun `B5 zero executed calls outranks a confident report`() {
        val decision = decide(
            finalMessage = "The analysis is complete and all systems are healthy.",
            executedToolCalls = 0,
        )
        assertEquals(SubagentRunAssessment.Outcome.UNSTARTED, decision.outcome)
    }

    // ─── B4 · the verdict reaches the detached paths ───────────────────────

    @Test
    fun `B4 a job outcome can carry a non-success verdict`() {
        val outcome = SubagentOrchestration.JobOutcome(
            runId = "run-1",
            success = true,
            report = "some text",
            skillName = "general-agent",
            outcome = SubagentRunAssessment.Outcome.UNSTARTED,
            reportSource = SubagentRunAssessment.ReportSource.NONE,
            executedToolCalls = 0,
        )
        assertEquals(SubagentRunAssessment.Outcome.UNSTARTED, outcome.outcome)
        assertEquals(SubagentRunAssessment.ReportSource.NONE, outcome.reportSource)
        assertEquals(0, outcome.executedToolCalls)
    }

    @Test
    fun `B4 a bare job outcome still means a plain model report`() {
        // The defaults must preserve the pre-existing meaning for every
        // construction site that does not set a verdict.
        val outcome = SubagentOrchestration.JobOutcome(
            runId = "run-1",
            success = true,
            report = "some text",
            skillName = "general-agent",
        )
        assertEquals(SubagentRunAssessment.Outcome.COMPLETED, outcome.outcome)
        assertEquals(SubagentRunAssessment.ReportSource.MODEL, outcome.reportSource)
    }

    @Test
    fun `B4 the registry persists the verdict with the run`() {
        val registry = SubagentRunRegistry()
        val run = registry.register("b1", "general-agent", "general-agent", "q", "t", 12)
        registry.finishIfActive(
            run.id,
            SubagentRunRegistry.RunStatus.SUCCESS,
            resultText = "text",
            outcome = SubagentRunAssessment.Outcome.DEGRADED,
            reportSource = SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
            executedToolCalls = 4,
        )
        val snapshot = registry.runs.value.firstOrNull { it.id == run.id }
        assertEquals(SubagentRunAssessment.Outcome.DEGRADED, snapshot?.runOutcome)
        assertEquals(SubagentRunAssessment.ReportSource.RUNTIME_DIGEST, snapshot?.reportSource)
        assertEquals(4, snapshot?.executedToolCalls)
    }

    @Test
    fun `B4 a detached run reports its verdict, not a bare success`() {
        // What the detached path does: read the registry snapshot and build a
        // JobOutcome. With the verdict stored, the parent sees UNSTARTED.
        val registry = SubagentRunRegistry()
        val run = registry.register("b1", "general-agent", "general-agent", "q", "t", 12)
        registry.finishIfActive(
            run.id,
            SubagentRunRegistry.RunStatus.SUCCESS,
            resultText = "",
            outcome = SubagentRunAssessment.Outcome.UNSTARTED,
            reportSource = SubagentRunAssessment.ReportSource.NONE,
            executedToolCalls = 0,
        )
        val snapshot = registry.runs.value.firstOrNull { it.id == run.id }
        val jobOutcome = SubagentOrchestration.JobOutcome(
            runId = run.id,
            success = snapshot?.status == SubagentRunRegistry.RunStatus.SUCCESS,
            report = snapshot?.resultText ?: "",
            skillName = "general-agent",
            outcome = snapshot?.runOutcome ?: SubagentRunAssessment.Outcome.COMPLETED,
            reportSource = snapshot?.reportSource ?: SubagentRunAssessment.ReportSource.MODEL,
            executedToolCalls = snapshot?.executedToolCalls ?: 0,
        )
        assertEquals(SubagentRunAssessment.Outcome.UNSTARTED, jobOutcome.outcome)
        assertFalse(
            "an UNSTARTED detached run must not read as deliverable",
            SubagentRunAssessment.isDeliverable(jobOutcome.outcome),
        )
    }
}
