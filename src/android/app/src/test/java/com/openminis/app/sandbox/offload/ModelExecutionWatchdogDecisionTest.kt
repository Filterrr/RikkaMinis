package com.openminis.app.sandbox.offload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [budget-kill-ownership] / [cancel-kill-ownership] JVM tests for the
 * watchdog's pure ownership decisions against the atomic
 * [ModelExecutionState].
 *
 * The handoff race: run A finishes and releases the execution mutex, run B
 * acquires it, and only THEN does A's (already-scheduled) watchdog tick fire.
 * A must observe B's state as "not mine" and never kill. Two independent
 * @Volatile fields (runId + startedAtMs) could be read torn; the single
 * atomic snapshot cannot.
 */
class ModelExecutionWatchdogDecisionTest {

    private val budgetMs = 30L * 60_000L + 60_000L // must mirror TOTAL_EXECUTION_BUDGET_MS

    @Test
    fun `watchdog of finished run A must not kill newly executing run B`() {
        val now = System.currentTimeMillis()
        val stateB = ModelExecutionState("run-B", now - budgetMs - 10_000L)
        // A's watchdog polls: the current execution belongs to B — NOT A.
        assertFalse(ModelExecutionService.shouldBudgetKill(stateB, myRunId = "run-A", nowMs = now))
        assertFalse(ModelExecutionService.isExecuting(stateB, myRunId = "run-A"))
    }

    @Test
    fun `watchdog kills only when its own run owns an over-budget execution`() {
        val now = System.currentTimeMillis()
        val stateA = ModelExecutionState("run-A", now - budgetMs - 10_000L)
        assertTrue(ModelExecutionService.shouldBudgetKill(stateA, myRunId = "run-A", nowMs = now))
        // Same age but owned by someone else → no.
        assertFalse(ModelExecutionService.shouldBudgetKill(stateA, myRunId = "run-B", nowMs = now))
    }

    @Test
    fun `freshly handed-over execution is not over budget even for its owner`() {
        val now = System.currentTimeMillis()
        val stateB = ModelExecutionState("run-B", now - 1_000L)
        assertFalse(ModelExecutionService.shouldBudgetKill(stateB, myRunId = "run-B", nowMs = now))
    }

    @Test
    fun `idle execution state authorizes no kill`() {
        assertFalse(ModelExecutionService.shouldBudgetKill(null, myRunId = "run-A", nowMs = System.currentTimeMillis()))
        assertFalse(ModelExecutionService.isExecuting(null, myRunId = "run-A"))
        // Null runId (defensive) also authorizes nothing.
        val state = ModelExecutionState("run-A", 0L)
        assertFalse(ModelExecutionService.shouldBudgetKill(state, myRunId = null, nowMs = System.currentTimeMillis()))
        assertFalse(ModelExecutionService.isExecuting(state, myRunId = null))
    }

    @Test
    fun `isExecuting is exact-match ownership`() {
        val state = ModelExecutionState("run-A", 123L)
        assertTrue(ModelExecutionService.isExecuting(state, myRunId = "run-A"))
        assertFalse(ModelExecutionService.isExecuting(state, myRunId = "run-A "))
        assertFalse(ModelExecutionService.isExecuting(state, myRunId = "run-B"))
    }
}
