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

    // ── [OPT4-semaphore-2] registry-based overloads (width-2 concurrency) ──

    @Test
    fun `registry - two concurrent runs keep independent identities`() {
        val now = System.currentTimeMillis()
        val states = mapOf(
            "run-A" to ModelExecutionState("run-A", now - budgetMs - 10_000L), // over budget
            "run-B" to ModelExecutionState("run-B", now - 1_000L),             // fresh
        )
        // A's watchdog sees A over budget (A's own entry, not clobbered by B).
        assertTrue(ModelExecutionService.shouldBudgetKill(states, myRunId = "run-A", nowMs = now))
        // B is fresh — B's watchdog does nothing.
        assertFalse(ModelExecutionService.shouldBudgetKill(states, myRunId = "run-B", nowMs = now))
        // Ownership is per-run in both directions.
        assertTrue(ModelExecutionService.isExecuting(states, myRunId = "run-A"))
        assertTrue(ModelExecutionService.isExecuting(states, myRunId = "run-B"))
        assertFalse(ModelExecutionService.isExecuting(states, myRunId = "run-C"))
    }

    @Test
    fun `registry - retired run authorizes nothing`() {
        val now = System.currentTimeMillis()
        val states = mapOf("run-B" to ModelExecutionState("run-B", now - budgetMs - 10_000L))
        // A retired its entry; A's watchdog must not fire on B's identity.
        assertFalse(ModelExecutionService.shouldBudgetKill(states, myRunId = "run-A", nowMs = now))
        assertFalse(ModelExecutionService.isExecuting(states, myRunId = "run-A"))
    }

    @Test
    fun `registry - null runId is safe`() {
        val states = mapOf("run-A" to ModelExecutionState("run-A", 0L))
        assertFalse(ModelExecutionService.shouldBudgetKill(states, myRunId = null, nowMs = 1L))
        assertFalse(ModelExecutionService.isExecuting(states, myRunId = null))
    }
}
