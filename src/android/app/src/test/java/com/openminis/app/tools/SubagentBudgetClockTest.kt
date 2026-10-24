package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-queue-budget] The run budget must not be consumed by the
 * time a spawn waits for a concurrency slot.
 */
class SubagentBudgetClockTest {

    @Test
    fun `the run clock starts full`() {
        val clock = SubagentBudgetClock.startRun(900, nowNanos = 0L)
        assertEquals(900_000L, clock.remainingMillis())
        assertFalse(clock.isExhausted())
    }

    @Test
    fun `a fresh run has its whole budget even after a long queue wait`() {
        // The parent spent 10 minutes waiting for a slot. The run starts
        // AFTER that, so it must still have its full 15 minutes.
        val queueWaitNanos = 600L * 1_000_000_000L
        val clock = SubagentBudgetClock.startRun(900, nowNanos = queueWaitNanos)
        assertEquals(900_000L, clock.remainingMillis())
        assertFalse(
            "the queue wait must not eat the run budget",
            clock.isExhausted(),
        )
    }

    @Test
    fun `the budget drains as the run proceeds`() {
        val clock = SubagentBudgetClock.startRun(900, nowNanos = 0L)
        clock.check(nowNanos = 300L * 1_000_000_000L)
        assertEquals(600_000L, clock.remainingMillis())
    }

    @Test
    fun `the budget is exhausted exactly at the deadline`() {
        val clock = SubagentBudgetClock.startRun(900, nowNanos = 0L)
        assertEquals(
            SubagentBudgetClock.Verdict.OK,
            clock.check(nowNanos = 899L * 1_000_000_000L),
        )
        assertEquals(
            SubagentBudgetClock.Verdict.RUN_BUDGET_EXHAUSTED,
            clock.check(nowNanos = 900L * 1_000_000_000L),
        )
        assertTrue(clock.isExhausted())
    }

    @Test
    fun `remaining never goes negative`() {
        val clock = SubagentBudgetClock.startRun(900, nowNanos = 0L)
        clock.check(nowNanos = 5_000L * 1_000_000_000L)
        assertEquals(0L, clock.remainingMillis())
    }

    @Test
    fun `the old behaviour would have starved a queued run`() {
        // Reproduces the OLD semantics: one absolute deadline computed at
        // spawn time, consumed by both the queue wait and the run.
        val spawnAt = 0L
        val timeoutNanos = 900L * 1_000_000_000L
        val oldDeadline = spawnAt + timeoutNanos
        val queueWaitNanos = 600L * 1_000_000_000L
        val runStartsAt = spawnAt + queueWaitNanos

        val oldRemainingMs = ((oldDeadline - runStartsAt) / 1_000_000L).coerceAtLeast(0L)
        assertEquals(
            "the old single-deadline semantics leave only 5 minutes",
            300_000L,
            oldRemainingMs,
        )

        val newClock = SubagentBudgetClock.startRun(900, nowNanos = runStartsAt)
        assertEquals(
            "the new semantics give the run its full budget",
            900_000L,
            newClock.remainingMillis(),
        )
    }
}
