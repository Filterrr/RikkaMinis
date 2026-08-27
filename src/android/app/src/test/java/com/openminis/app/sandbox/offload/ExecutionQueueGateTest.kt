package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ExecutionQueueGate] (head-of-line-blocking fix, 2026-08-27).
 *
 * Pins the three contract points that un-wedged the ":modelservice" queue:
 *   - a queued request whose client cancelled NEVER executes (CANCELLED wins
 *     over an available lock — a dead client must not consume head time);
 *   - the queue wait is BOUNDED: a never-locking, never-cancelled wait
 *     returns TIMEOUT after exactly the budget, not forever;
 *   - a healthy wait still ACQUIRES as soon as the head releases the mutex.
 *
 * All timing is injected (fake clock / recording sleep) — no real sleeps, per
 * the failure-matrix harness conventions (docs/stability/failure-matrix.md).
 */
class ExecutionQueueGateTest {

    private class FakeClock(startMs: Long = 0L) {
        var now = startMs
        val sleeps = mutableListOf<Long>()
        fun advance(ms: Long) { now += ms }
    }

    private fun gate(
        clock: FakeClock,
        timeoutMs: Long,
        cancelled: () -> Boolean = { false },
        lockStates: MutableList<Boolean>? = null,
    ) = ExecutionQueueGate.await(
        timeoutMs = timeoutMs,
        isCancelled = cancelled,
        tryLock = {
            val s = lockStates?.removeFirstOrNull() ?: false
            if (s) true else false
        },
        pollMs = 200L,
        nowMs = { clock.now },
        sleep = {
            clock.sleeps += it
            // Each simulated poll tick advances the fake clock by the poll
            // interval — the production loop's real-time analogue.
            clock.advance(it)
        },
    )

    // ── ACQUIRED ─────────────────────────────────────────────────────

    @Test
    fun `free lock acquires immediately without sleeping`() {
        val clock = FakeClock()
        val result = gate(clock, timeoutMs = 60_000L, lockStates = mutableListOf(true))
        assertEquals(ExecutionQueueGate.Result.ACQUIRED, result)
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun `acquires on retry once the head releases the mutex`() {
        val clock = FakeClock()
        val result = gate(clock, timeoutMs = 60_000L, lockStates = mutableListOf(false, false, true))
        assertEquals(ExecutionQueueGate.Result.ACQUIRED, result)
        assertEquals(2, clock.sleeps.size)
    }

    // ── CANCELLED (head-of-line fix: queue is cancel-aware) ──────────

    @Test
    fun `cancel before first attempt wins even when lock is free`() {
        // The gate checks cancellation BEFORE acquisition: a request whose
        // client already gave up must never execute, even if the mutex is
        // instantly available.
        val clock = FakeClock()
        val result = gate(
            clock,
            timeoutMs = 60_000L,
            cancelled = { true },
            lockStates = mutableListOf(true),
        )
        assertEquals(ExecutionQueueGate.Result.CANCELLED, result)
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun `cancel arriving mid-wait bails out instead of acquiring later`() {
        // The client cancels after 2 poll ticks while the mutex is still held:
        // the gate must bail with CANCELLED rather than keep waiting for the
        // head (the old withLock had no such exit).
        val clock = FakeClock()
        var cancelAt = 2
        val result = ExecutionQueueGate.await(
            timeoutMs = 60_000L,
            isCancelled = { cancelAt <= 0 },
            tryLock = { false },
            pollMs = 200L,
            nowMs = { clock.now },
            sleep = {
                clock.sleeps += it
                clock.advance(it)
                cancelAt--
            },
        )
        assertEquals(ExecutionQueueGate.Result.CANCELLED, result)
        assertEquals(2, clock.sleeps.size)
    }

    // ── TIMEOUT (head-of-line fix: queue wait is bounded) ────────────

    @Test
    fun `wedged head and no cancel times out at the budget, not forever`() {
        val clock = FakeClock()
        val result = gate(clock, timeoutMs = 1_000L, lockStates = MutableList(1000) { false })
        assertEquals(ExecutionQueueGate.Result.TIMEOUT, result)
        // 1000ms budget / 200ms poll → exactly 5 sleeps, then the deadline
        // check trips: the loop terminates deterministically.
        assertEquals(5, clock.sleeps.size)
        assertEquals(1_000L, clock.now)
    }

    @Test
    fun `timeout budget is measured from entry, not per attempt`() {
        val clock = FakeClock(startMs = 500L)
        val result = ExecutionQueueGate.await(
            timeoutMs = 600L,
            isCancelled = { false },
            tryLock = { false },
            pollMs = 200L,
            nowMs = { clock.now },
            sleep = {
                clock.advance(it)
            },
        )
        assertEquals(ExecutionQueueGate.Result.TIMEOUT, result)
        // Deadline = 500 + 600 = 1100; sleeps of 200 → clock hits 1100 after
        // 3 sleeps and the next boundary check returns TIMEOUT.
        assertEquals(1_100L, clock.now)
    }

    // ── invariant: the loop always terminates ────────────────────────

    @Test
    fun `no infinite loop when sleep is a no-op`() {
        // If a caller injects a no-op sleeper (or the thread is interrupted
        // and production swallows it), the CLOCK bound alone must still end
        // the wait — this is what makes the queue wait finite in production
        // even under interruption storms.
        var iterations = 0
        val result = ExecutionQueueGate.await(
            timeoutMs = 1_000L,
            isCancelled = { false },
            tryLock = { iterations++; false },
            pollMs = 200L,
            nowMs = { iterations * 200L },
            sleep = { /* no-op: time only advances via nowMs */ },
        )
        assertEquals(ExecutionQueueGate.Result.TIMEOUT, result)
        assertTrue(iterations in 1..10) // 1000/200 = 5 attempts (+boundary)
    }
}
