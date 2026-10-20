package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-plan-execute] Pins the gate that stopped a plan-only run from
 * ending the whole sub-agent loop.
 *
 * The regression this covers: `PLAN_DIRECTIVE` required the first turn to
 * carry no tool call, while the runner exited on `toolCalls.isEmpty()`. A model
 * that obeyed the directive therefore produced a run with zero tool calls and
 * a SUCCESS header — exactly what was observed in practice (1 turn, transcript
 * containing nothing but a `<task-plan>`, status SUCCESS).
 */
class SubagentPlanGateTest {

    // ─── 1. the plan turn is not terminal ──────────────────────────────────

    @Test
    fun `plan emitted this turn with no tools is nudged to execute`() {
        val decision = SubagentPlanGate.decide(
            planFirst = true,
            planCaptured = true,
            emittedPlanThisTurn = true,
            nudgeIssued = false,
            maxTurnsReached = false,
        )
        assertEquals(SubagentPlanGate.Decision.NUDGE_TO_EXECUTE, decision)
    }

    @Test
    fun `prose answer with no plan is nudged once`() {
        val decision = SubagentPlanGate.decide(
            planFirst = true,
            planCaptured = false,
            emittedPlanThisTurn = false,
            nudgeIssued = false,
            maxTurnsReached = false,
        )
        assertEquals(SubagentPlanGate.Decision.NUDGE_TO_EXECUTE, decision)
    }

    // ─── 2. termination is still guaranteed ────────────────────────────────

    @Test
    fun `nudge fires at most once per run`() {
        val decision = SubagentPlanGate.decide(
            planFirst = true,
            planCaptured = true,
            emittedPlanThisTurn = true,
            nudgeIssued = true,
            maxTurnsReached = false,
        )
        assertEquals(SubagentPlanGate.Decision.FINISH, decision)
    }

    @Test
    fun `turn budget exhausted finishes instead of spending an unusable nudge`() {
        val decision = SubagentPlanGate.decide(
            planFirst = true,
            planCaptured = true,
            emittedPlanThisTurn = true,
            nudgeIssued = false,
            maxTurnsReached = true,
        )
        assertEquals(SubagentPlanGate.Decision.FINISH, decision)
    }

    @Test
    fun `plan_first off keeps the historical tool-less = done semantics`() {
        val decision = SubagentPlanGate.decide(
            planFirst = false,
            planCaptured = false,
            emittedPlanThisTurn = false,
            nudgeIssued = false,
            maxTurnsReached = false,
        )
        assertEquals(SubagentPlanGate.Decision.FINISH, decision)
    }

    @Test
    fun `stale plan from an earlier turn plus a tool-less turn finishes`() {
        // The model planned on turn 1, worked on turns 2-4, and this turn is
        // its wrap-up prose. Nudging here would be wrong — the run is done.
        val decision = SubagentPlanGate.decide(
            planFirst = true,
            planCaptured = true,
            emittedPlanThisTurn = false,
            nudgeIssued = false,
            maxTurnsReached = false,
        )
        assertEquals(SubagentPlanGate.Decision.FINISH, decision)
    }

    // ─── 3. nudge wording ──────────────────────────────────────────────────

    @Test
    fun `plan nudge names a tool and forbids restating the plan`() {
        val text = SubagentPlanGate.nudgeText(emittedPlanThisTurn = true)
        assertTrue(text.contains("EXECUTE"))
        assertTrue(text.contains("shell_execute"))
        assertTrue(text.contains("Do NOT restate the plan"))
        // The mechanic the model was reasoning about incorrectly must be
        // spelled out, or a weak model just re-answers.
        assertTrue(text.contains("terminates this run"))
    }

    @Test
    fun `no-plan nudge does not reference a checklist that does not exist`() {
        val text = SubagentPlanGate.nudgeText(emittedPlanThisTurn = false)
        assertFalse(text.contains("Your plan is recorded"))
        assertTrue(text.contains("No <task-plan> block was emitted"))
        assertTrue(text.contains("terminates this run"))
    }

    @Test
    fun `nudge text differs between the two reasons`() {
        assertFalse(
            SubagentPlanGate.nudgeText(true) == SubagentPlanGate.nudgeText(false),
        )
    }

    // ─── 4. directive no longer tells the model the run ends after planning ─

    @Test
    fun `directive says the run continues after the plan turn`() {
        val directive = SubagentSkill.PLAN_DIRECTIVE
        assertTrue(directive.contains("does NOT end here"))
        assertTrue(directive.contains("every turn must actually advance the work"))
        // The old wording, which a model reasonably read as "stop for good".
        assertFalse(directive.contains("outline only, then STOP"))
    }
}
