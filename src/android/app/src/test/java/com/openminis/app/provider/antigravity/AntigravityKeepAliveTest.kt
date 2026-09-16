package com.openminis.app.provider.antigravity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-antigravity-keepalive] Pure decision logic of the keep-alive feature:
 * interval clamping, the 30-minute pass throttle, the foreground catch-up
 * (overdue) predicate, and fatal-account parsing. All side-effect-free —
 * the scheduling/alarm plumbing around them is deliberately thin wrappers
 * over these functions, so pinning them pins the feature's semantics.
 */
class AntigravityKeepAliveTest {

    private val HOUR_MS = 3_600_000L
    private val now = 1_750_000_000_000L

    // ── interval clamping ──────────────────────────────────────────────────

    @Test
    fun `every offered cadence survives the clamp`() {
        AntigravityKeepAlive.INTERVAL_CHOICES_HOURS.forEach { h ->
            assertEquals(h, AntigravityKeepAlive.sanitizeInterval(h))
        }
    }

    @Test
    fun `unknown intervals collapse to the default`() {
        listOf(0, -1, 2, 5, 23, 24 * 365, Int.MAX_VALUE).forEach { h ->
            assertEquals(
                "interval $h must clamp to default",
                AntigravityKeepAlive.DEFAULT_INTERVAL_HOURS,
                AntigravityKeepAlive.sanitizeInterval(h),
            )
        }
    }

    // ── 30-minute throttle ─────────────────────────────────────────────────

    @Test
    fun `never-run is never throttled`() {
        assertFalse(AntigravityKeepAlive.isThrottled(now, lastRunAt = 0L))
    }

    @Test
    fun `passes within 30 minutes are throttled, beyond are not`() {
        assertTrue(AntigravityKeepAlive.isThrottled(now, now - 29 * 60_000L))
        assertTrue(AntigravityKeepAlive.isThrottled(now, now - 1))
        assertFalse(AntigravityKeepAlive.isThrottled(now, now - 31 * 60_000L))
        assertFalse("boundary: exactly 30 min old passes", AntigravityKeepAlive.isThrottled(now, now - 30 * 60_000L))
    }

    // ── overdue (foreground catch-up) ──────────────────────────────────────

    @Test
    fun `never-run waits for the first scheduled tick`() {
        assertFalse(AntigravityKeepAlive.isOverdue(now, lastRunAt = 0L, intervalHours = 1))
    }

    @Test
    fun `a pass older than the cadence is overdue`() {
        assertTrue(AntigravityKeepAlive.isOverdue(now, now - 7 * HOUR_MS, intervalHours = 6))
        assertFalse(AntigravityKeepAlive.isOverdue(now, now - 5 * HOUR_MS, intervalHours = 6))
        // exactly at the boundary: not yet overdue (needs to be OLDER than)
        assertFalse(AntigravityKeepAlive.isOverdue(now, now - 6 * HOUR_MS, intervalHours = 6))
    }

    @Test
    fun `daily cadence tolerates what the hourly one rejects`() {
        val fiveHoursAgo = now - 5 * HOUR_MS
        assertTrue(AntigravityKeepAlive.isOverdue(now, fiveHoursAgo, intervalHours = 1))
        assertFalse(AntigravityKeepAlive.isOverdue(now, fiveHoursAgo, intervalHours = 24))
    }

    // ── fatal accounts ─────────────────────────────────────────────────────

    @Test
    fun `fatal list tolerates whitespace and empties`() {
        assertEquals(
            listOf("a@x.com", "b@y.com"),
            AntigravityKeepAlive.parseFatalAccounts(" a@x.com ,, b@y.com ,"),
        )
    }

    @Test
    fun `empty or null fatal pref means no fatal accounts`() {
        assertTrue(AntigravityKeepAlive.parseFatalAccounts("").isEmpty())
        assertTrue(AntigravityKeepAlive.parseFatalAccounts(null).isEmpty())
        assertTrue(AntigravityKeepAlive.parseFatalAccounts("  ").isEmpty())
    }

    // ── summary ────────────────────────────────────────────────────────────

    @Test
    fun `pass summary carries all three classes`() {
        assertEquals(
            "3 ok · 1 fatal · 2 transient",
            AntigravityKeepAlive.formatSummary(ok = 3, fatal = 1, transientFail = 2),
        )
    }
}
