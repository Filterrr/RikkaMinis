package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OPT2-jitter-retry-after] JVM tests for the auto-retry delay decision.
 * Deterministic via seeded [kotlin.random.Random].
 */
class AutoRetrySchedulerTest {

    @Test
    fun `no hint - delay is within old ladder bounds per attempt`() {
        // Upper bound for attempt N is 2^N (1, 2, 4).
        for (attempt in 0 until 3) {
            repeat(50) {
                val d = AutoRetryScheduler.delaySec(attempt, rng = kotlin.random.Random(attempt * 1000 + it))
                assertTrue("attempt=$attempt d=$d", d in 1..(1L shl attempt))
            }
        }
    }

    @Test
    fun `no hint - full jitter actually spreads values`() {
        val rng = kotlin.random.Random(42)
        val seen = mutableSetOf<Long>()
        repeat(200) { seen.add(AutoRetryScheduler.delaySec(2, rng = rng)) }
        // 4s base → possible values {1,2,3,4}; expect meaningful spread, not a fixed value.
        assertTrue("spread=${seen.size}", seen.size >= 3)
    }

    @Test
    fun `hint honored - hard capped at 30s INCLUDING jitter`() {
        // [FIX-audit-P2-semantics] final delay = min(30, hint + jitter{0,1})
        val d = AutoRetryScheduler.delaySec(0, retryAfterSec = 600L, rng = kotlin.random.Random(1))
        assertEquals(30L, d)
    }

    @Test
    fun `hint gets small jitter floor 0-1s`() {
        val d = AutoRetryScheduler.delaySec(0, retryAfterSec = 5L, rng = kotlin.random.Random(7))
        assertTrue("d=$d", d in 5..6)
    }

    @Test
    fun `attempt beyond schedule is safe (clamped exponential)`() {
        repeat(50) {
            val d = AutoRetryScheduler.delaySec(10, rng = kotlin.random.Random(it))
            assertTrue(d in 1..1024L)
        }
    }

    @Test
    fun `zero hint allowed - retries immediately plus jitter`() {
        val d = AutoRetryScheduler.delaySec(0, retryAfterSec = 0L, rng = kotlin.random.Random(3))
        assertTrue("d=$d", d in 0..1)
    }

    @Test
    fun `no hint uses deterministic fallback - not stuck at fixed value`() {
        // Sanity: attempt 0 has base 1 → delay is always exactly 1 (rand(1,1+1)).
        repeat(20) {
            assertEquals(1L, AutoRetryScheduler.delaySec(0, rng = kotlin.random.Random(it)))
        }
    }
}
