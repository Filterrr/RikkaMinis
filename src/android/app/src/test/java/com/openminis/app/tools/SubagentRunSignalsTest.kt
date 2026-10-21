package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-run-signals] Pins three defects in the run's own signals,
 * all found auditing PRs #66-#70.
 *
 * 1. B5 — a tool call the loop detector BLOCKED was counted as executed.
 * 2. B6 — the `run_until=turn_complete` exit hardcoded its verdict instead
 *        of asking the assessment.
 * 3. B7 — the NO_OUTPUT verdict pointed the parent at a journal that was
 *        never written.
 */
class SubagentRunSignalsTest {

    // ─── 1. blocked calls are not "executed" ──────────────────────────────

    @Test
    fun `the runner counts only the calls that actually ran`() {
        val src = com.openminis.app.ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "the runner must track the blocked calls separately",
            src.contains("blockedCallIds"),
        )
        assertTrue(
            "the executed count must exclude the blocked calls",
            src.contains("executedToolCalls += toolCalls.size - blockedCallIds.size"),
        )
        assertFalse(
            "counting every proposed call is the bug",
            src.contains("executedToolCalls += toolCalls.size\n"),
        )
    }

    @Test
    fun `a run whose every call was blocked is not reported as completed`() {
        // 20 proposed calls, every one blocked at CRITICAL → 0 actually ran.
        val outcome = SubagentRunAssessment.assess(
            loopFailed = false,
            timedOut = false,
            stoppedEarly = false,
            executedToolCalls = 0,
            reportSource = SubagentRunAssessment.ReportSource.MODEL,
            declaredStatus = null,
        )
        assertEquals(SubagentRunAssessment.Outcome.UNSTARTED, outcome)
    }

    @Test
    fun `a run whose calls all ran is reported as completed`() {
        val outcome = SubagentRunAssessment.assess(
            loopFailed = false,
            timedOut = false,
            stoppedEarly = false,
            executedToolCalls = 20,
            reportSource = SubagentRunAssessment.ReportSource.MODEL,
            declaredStatus = null,
        )
        assertEquals(SubagentRunAssessment.Outcome.COMPLETED, outcome)
    }

    // ─── 2. the early-readout exit asks the assessment ────────────────────

    @Test
    fun `the early-readout exit consults the assessment instead of hardcoding it`() {
        val src = com.openminis.app.ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "the turn_complete exit must call assess()",
            src.contains("stoppedEarly = true,"),
        )
        assertFalse(
            "the hardcoded STOPPED_EARLY must be gone",
            src.contains("outcome = SubagentRunAssessment.Outcome.STOPPED_EARLY,"),
        )
    }

    @Test
    fun `a first-turn readout whose model declared failure is not a cheerful stop`() {
        val outcome = SubagentRunAssessment.assess(
            loopFailed = false,
            timedOut = false,
            stoppedEarly = true,
            executedToolCalls = 4,
            reportSource = SubagentRunAssessment.ReportSource.PARTIAL,
            declaredStatus = "failed",
        )
        assertEquals(
            "a model-declared failure must outrank the early stop",
            SubagentRunAssessment.Outcome.DECLARED_FAILED,
            outcome,
        )
    }

    @Test
    fun `a first-turn readout without a declaration still reads as stopped early`() {
        val outcome = SubagentRunAssessment.assess(
            loopFailed = false,
            timedOut = false,
            stoppedEarly = true,
            executedToolCalls = 4,
            reportSource = SubagentRunAssessment.ReportSource.PARTIAL,
            declaredStatus = null,
        )
        assertEquals(SubagentRunAssessment.Outcome.STOPPED_EARLY, outcome)
    }

    @Test
    fun `the early readout states the real turn count`() {
        val result = SubagentResult(
            status = SubagentResult.Status.SUCCESS,
            report = "partial narration",
            outcome = SubagentRunAssessment.Outcome.STOPPED_EARLY,
            reportSource = SubagentRunAssessment.ReportSource.PARTIAL,
            executedToolCalls = 4,
            turns = 2,
            maxTurns = 40,
            skillId = "general-agent",
            skillName = "general-agent",
        )
        val text = result.toPromptText(stoppedEarly = true)
        assertTrue(
            "the readout must state the real turn count:\n$text",
            text.contains("(2/40 turns)"),
        )
        assertFalse(
            "the hardcoded first-turn count is the bug:\n$text",
            text.contains("1/40 turns"),
        )
    }

    // ─── 3. the no-output exit writes the journal it points at ────────────

    @Test
    fun `the blank-output exit writes a journal`() {
        val src = com.openminis.app.ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "the blank-output exit must journal — the NO_OUTPUT verdict points there",
            src.contains("\"the run produced no text output\""),
        )
    }

    @Test
    fun `the no-output verdict points at a journal that now exists`() {
        val headline = SubagentRunAssessment.headline(
            SubagentRunAssessment.Outcome.NO_OUTPUT, 3, SubagentRunAssessment.ReportSource.NONE,
        )
        assertTrue(
            "the verdict must still point at the journal",
            headline.contains("read the journal"),
        )
    }
}
