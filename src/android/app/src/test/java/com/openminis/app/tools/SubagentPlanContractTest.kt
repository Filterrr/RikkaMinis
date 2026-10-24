package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-plan-contract] The plan rules used to live in three places
 * and contradict each other:
 *
 *  1. the system-prompt directive said "the plan turn makes NO tool calls";
 *  2. the execution nudge said "a turn with no tool call terminates this
 *     run";
 *  3. the skill body said "writing <task-plan> does not end the task".
 *
 * (1) and (2) are the same rule with opposite outcomes, so a model had to
 * guess which one the runtime enforces. These tests pin the CONSISTENCY
 * property that was missing, not the exact wording — the wording is
 * allowed to change as long as the three statements agree.
 */
class SubagentPlanContractTest {

    @Test
    fun `the directive names the plan turn as the exception to the tool-call rule`() {
        val directive = SubagentPlanContract.DIRECTIVE
        // The plan turn is the ONE turn where "no tool call" is correct.
        assertTrue(
            "the directive must scope the exception to the plan turn: $directive",
            directive.contains("ONE exception"),
        )
        assertTrue(directive.contains("make NO tool calls"))
    }

    @Test
    fun `the directive states that the run continues after the plan turn`() {
        val directive = SubagentPlanContract.DIRECTIVE
        assertTrue(
            "the directive must say the run continues: $directive",
            directive.contains("does NOT end here"),
        )
    }

    @Test
    fun `the directive scopes the terminate rule to the turns after the plan`() {
        val directive = SubagentPlanContract.DIRECTIVE
        assertTrue(
            "the terminate rule must be scoped to the turns AFTER the plan: $directive",
            directive.contains("From the turn AFTER the plan"),
        )
    }

    @Test
    fun `both nudges state the same stop condition`() {
        // The two nudges differ in setup (a checklist exists / does not) but
        // must agree on the mechanic, or the model gets a different rule
        // depending on how its plan turn went.
        assertTrue(SubagentPlanContract.EXECUTION_NUDGE.contains("terminates this run"))
        assertTrue(SubagentPlanContract.NO_PLAN_NUDGE.contains("terminates this run"))
    }

    @Test
    fun `both nudges name a concrete tool to call`() {
        assertTrue(SubagentPlanContract.EXECUTION_NUDGE.contains("shell_execute"))
        assertTrue(SubagentPlanContract.NO_PLAN_NUDGE.contains("shell_execute"))
    }

    @Test
    fun `the no-plan nudge does not point at a checklist that does not exist`() {
        assertFalse(
            SubagentPlanContract.NO_PLAN_NUDGE.contains("Your plan is recorded"),
        )
        assertTrue(SubagentPlanContract.NO_PLAN_NUDGE.contains("No <task-plan> block was emitted"))
    }

    @Test
    fun `the two nudges are actually different messages`() {
        assertFalse(
            SubagentPlanContract.EXECUTION_NUDGE == SubagentPlanContract.NO_PLAN_NUDGE,
        )
    }

    /**
     * The single-source-of-truth property itself: the skill and the plan
     * gate must not carry their own copies of the contract.
     */
    @Test
    fun `the skill and the plan gate delegate instead of holding copies`() {
        assertTrue(SubagentSkill.PLAN_DIRECTIVE == SubagentPlanContract.DIRECTIVE)
        assertTrue(SubagentPlanGate.EXECUTION_NUDGE == SubagentPlanContract.EXECUTION_NUDGE)
        assertTrue(SubagentPlanGate.NO_PLAN_NUDGE == SubagentPlanContract.NO_PLAN_NUDGE)
        assertTrue(SubagentSkill.PLAN_TAG == SubagentPlanContract.PLAN_TAG)
        assertTrue(SubagentSkill.PLAN_TURN_NOTICE == SubagentPlanContract.PLAN_TURN_NOTICE)
    }

    @Test
    fun `the plan tag is the same everywhere`() {
        assertTrue(SubagentPlanContract.DIRECTIVE.contains("<${SubagentPlanContract.PLAN_TAG}>"))
        assertTrue(SubagentPlanContract.NO_PLAN_NUDGE.contains("<${SubagentPlanContract.PLAN_TAG}>"))
    }
}
