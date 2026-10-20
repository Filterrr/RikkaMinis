package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-run-assessment] Tests for the single work verdict.
 *
 * The point of this layer: `SubagentResult.Status` only says whether the LOOP
 * finished. These tests pin that the OUTCOME distinguishes the situations the
 * loop cannot — nothing executed, runtime-rebuilt report, model-declared
 * partial — because the parent triages from it.
 */
class SubagentRunAssessmentTest {

    private fun result(
        status: SubagentResult.Status = SubagentResult.Status.SUCCESS,
        report: String = "findings",
        outcome: SubagentRunAssessment.Outcome = SubagentRunAssessment.Outcome.COMPLETED,
        source: SubagentRunAssessment.ReportSource = SubagentRunAssessment.ReportSource.MODEL,
        executed: Int = 3,
    ) = SubagentResult(
        status = status,
        report = report,
        outcome = outcome,
        reportSource = source,
        executedToolCalls = executed,
        turns = 5,
        maxTurns = 40,
        skillId = "general-agent",
        skillName = "general-agent",
        journalPath = "/var/minis/workspace/.subagent/subagent-1.md",
        artifacts = emptyList(),
    )

    // ─── Outcome derivation ───────────────────────────────────────────────

    @Test
    fun `a normal run that executed tools and wrote a report is completed`() {
        val o = SubagentRunAssessment.assess(
            loopFailed = false, timedOut = false, stoppedEarly = false,
            executedToolCalls = 4,
            reportSource = SubagentRunAssessment.ReportSource.MODEL,
            declaredStatus = null,
        )
        assertEquals(SubagentRunAssessment.Outcome.COMPLETED, o)
        assertTrue(SubagentRunAssessment.isDeliverable(o))
    }

    @Test
    fun `a run that executed nothing is unstarted even when it looks fine`() {
        // The field failure behind PR #66/#68: loop finished, report present
        // (a plan or a husk), zero tool calls. Must NOT read as completed.
        val o = SubagentRunAssessment.assess(
            loopFailed = false, timedOut = false, stoppedEarly = false,
            executedToolCalls = 0,
            reportSource = SubagentRunAssessment.ReportSource.MODEL,
            declaredStatus = null,
        )
        assertEquals(SubagentRunAssessment.Outcome.UNSTARTED, o)
        assertFalse(SubagentRunAssessment.isDeliverable(o))
    }

    @Test
    fun `a runtime-rebuilt report is its own outcome, not completed`() {
        // Tools ran, but the model contributed no findings (PR #68). The
        // parent must know the text is evidence, not interpretation.
        val o = SubagentRunAssessment.assess(
            loopFailed = false, timedOut = false, stoppedEarly = false,
            executedToolCalls = 5,
            reportSource = SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
            declaredStatus = null,
        )
        assertEquals(SubagentRunAssessment.Outcome.DEGRADED, o)
        assertFalse(SubagentRunAssessment.isDeliverable(o))
    }

    @Test
    fun `model-declared partial is not reported as completed`() {
        val o = SubagentRunAssessment.assess(
            loopFailed = false, timedOut = false, stoppedEarly = false,
            executedToolCalls = 3,
            reportSource = SubagentRunAssessment.ReportSource.MODEL,
            declaredStatus = "partial",
        )
        assertEquals(SubagentRunAssessment.Outcome.PARTIAL, o)
        assertFalse(SubagentRunAssessment.isDeliverable(o))
    }

    @Test
    fun `loop failure and timeout win over every other signal`() {
        listOf(
            Triple(true, false, SubagentRunAssessment.Outcome.FAILED),
            Triple(false, true, SubagentRunAssessment.Outcome.TIMED_OUT),
        ).forEach { (failed, timedOut, expected) ->
            val o = SubagentRunAssessment.assess(
                loopFailed = failed, timedOut = timedOut, stoppedEarly = false,
                executedToolCalls = 9,
                reportSource = SubagentRunAssessment.ReportSource.MODEL,
                declaredStatus = null,
            )
            assertEquals(expected, o)
            assertFalse(SubagentRunAssessment.isDeliverable(o))
        }
    }

    @Test
    fun `no tool calls beats a partial declaration`() {
        // Both true: the run did nothing AND said partial. "Nothing executed"
        // is the more actionable fact — a follow-up must start the work, not
        // resume it.
        val o = SubagentRunAssessment.assess(
            loopFailed = false, timedOut = false, stoppedEarly = false,
            executedToolCalls = 0,
            reportSource = SubagentRunAssessment.ReportSource.MODEL,
            declaredStatus = "partial",
        )
        assertEquals(SubagentRunAssessment.Outcome.UNSTARTED, o)
    }

    // ─── Delivery surfaces ────────────────────────────────────────────────

    @Test
    fun `every non-completed outcome forbids claiming successful delivery`() {
        SubagentRunAssessment.Outcome.values()
            .filter { it != SubagentRunAssessment.Outcome.COMPLETED }
            .forEach { o ->
                assertFalse("${o.name} must not claim successful delivery", SubagentRunAssessment.isDeliverable(o))
            }
        assertTrue(SubagentRunAssessment.isDeliverable(SubagentRunAssessment.Outcome.COMPLETED))
    }

    @Test
    fun `headline names the run state which completed did not`() {
        val completed = SubagentRunAssessment.headline(
            SubagentRunAssessment.Outcome.COMPLETED, 4, SubagentRunAssessment.ReportSource.MODEL,
        )
        assertTrue(completed.contains("4 tool call"))

        val unstarted = SubagentRunAssessment.headline(
            SubagentRunAssessment.Outcome.UNSTARTED, 0, SubagentRunAssessment.ReportSource.MODEL,
        )
        assertTrue(unstarted.contains("0 tool call"))

        val degraded = SubagentRunAssessment.headline(
            SubagentRunAssessment.Outcome.DEGRADED, 5, SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
        )
        // The parent must be told the text is not the model's words.
        assertTrue(degraded.contains("digest") || degraded.contains("not the model"))
    }

    // ─── Prompt text: the surface the parent actually reads ───────────────

    @Test
    fun `prompt text puts the verdict before the report body`() {
        val text = result().toPromptText()
        val verdictIdx = text.indexOf("4 tool call").let { if (it < 0) text.indexOf("3 tool call") else it }
        val bodyIdx = text.indexOf("findings")
        assertTrue("verdict missing from prompt text: $text", verdictIdx > 0)
        assertTrue("verdict must precede the report body", verdictIdx < bodyIdx)
    }

    @Test
    fun `an unstarted run and a real run produce different prompt headers`() {
        val real = result(executed = 4).toPromptText()
        val empty = result(
            report = "a plan", executed = 0,
            outcome = SubagentRunAssessment.Outcome.UNSTARTED,
        ).toPromptText()
        assertFalse(
            "header must distinguish a real run from an empty one",
            real.lines().first() == empty.lines().first() &&
                real.lines().getOrNull(1) == empty.lines().getOrNull(1),
        )
        assertTrue(empty.contains("0 tool call"))
        assertTrue(real.contains("4 tool call"))
    }

    @Test
    fun `a runtime digest is labelled as such in the prompt text`() {
        val text = result(
            outcome = SubagentRunAssessment.Outcome.DEGRADED,
            source = SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
        ).toPromptText()
        assertTrue("digest provenance must be visible: $text", text.contains("digest"))
    }

    @Test
    fun `failure prompt text still carries partial output and the error`() {
        val text = result(
            status = SubagentResult.Status.FAILED,
            report = "half-done",
            outcome = SubagentRunAssessment.Outcome.TIMED_OUT,
            source = SubagentRunAssessment.ReportSource.PARTIAL,
        ).copy(error = "TIMED_OUT after 5 turn(s)").toPromptText()
        assertTrue(text.contains("half-done"))
        assertTrue(text.contains("TIMED_OUT"))
        assertTrue(text.contains("Partial output"))
    }
}
