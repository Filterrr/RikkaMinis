package com.openminis.app.provider.workbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-workbuddy-keepalive] Pure-decision tests for the keep-alive machinery.
 *
 * These pin the scheduling/throttle/label semantics that decide whether the
 * background refresh actually runs — the places where a silent regression
 * means the user's session quietly dies anyway (the exact failure the
 * feature exists to prevent).
 */
class WorkBuddyKeepAliveTest {

    private val HOUR_MS = 60L * 60L * 1000L

    // ───────────────────────── interval sanitize ─────────────────────────

    @Test
    fun `sanitizeInterval accepts every advertised choice`() {
        for (h in WorkBuddyKeepAlive.INTERVAL_CHOICES_HOURS) {
            assertEquals(h, WorkBuddyKeepAlive.sanitizeInterval(h))
        }
    }

    @Test
    fun `sanitizeInterval collapses unknown values to the default`() {
        // Persisted prefs may carry a value from an older/newer build with a
        // different allow-list; anything unlisted must not be honoured.
        assertEquals(
            WorkBuddyKeepAlive.DEFAULT_INTERVAL_HOURS,
            WorkBuddyKeepAlive.sanitizeInterval(7),
        )
        assertEquals(
            WorkBuddyKeepAlive.DEFAULT_INTERVAL_HOURS,
            WorkBuddyKeepAlive.sanitizeInterval(-1),
        )
        assertEquals(
            WorkBuddyKeepAlive.DEFAULT_INTERVAL_HOURS,
            WorkBuddyKeepAlive.sanitizeInterval(0),
        )
    }

    // ───────────────────────── throttle ─────────────────────────

    @Test
    fun `isThrottled blocks a pass within the minimum window`() {
        val now = 10_000_000L
        val justRan = now - 5 * 60 * 1000L // 5 min ago
        assertTrue(WorkBuddyKeepAlive.isThrottled(now, justRan))
    }

    @Test
    fun `isThrottled allows a pass after the minimum window`() {
        val now = 10_000_000L
        val longAgo = now - 31 * 60 * 1000L // 31 min ago
        assertFalse(WorkBuddyKeepAlive.isThrottled(now, longAgo))
    }

    @Test
    fun `isThrottled never blocks a first-ever pass`() {
        // lastRunAt == 0 means "never run"; the throttle must not swallow it
        // or the feature would no-op forever on a fresh enable.
        assertFalse(WorkBuddyKeepAlive.isThrottled(now = 0L, lastRunAt = 0L))
        assertFalse(WorkBuddyKeepAlive.isThrottled(now = 100L, lastRunAt = 0L))
    }

    // ───────────────────────── overdue / catch-up ─────────────────────────

    @Test
    fun `isOverdue fires when the last pass is older than the cadence`() {
        val now = 100L * HOUR_MS
        val last = now - 7 * HOUR_MS
        assertTrue(WorkBuddyKeepAlive.isOverdue(now, last, intervalHours = 6))
    }

    @Test
    fun `isOverdue stays quiet inside the cadence`() {
        val now = 100L * HOUR_MS
        val last = now - 2 * HOUR_MS
        assertFalse(WorkBuddyKeepAlive.isOverdue(now, last, intervalHours = 6))
    }

    @Test
    fun `isOverdue treats never-run as not overdue`() {
        // A fresh enable must wait for its first scheduled tick, not fire an
        // immediate pass (matches the Antigravity semantics; also avoids a
        // surprise network call right after toggling the switch).
        assertFalse(WorkBuddyKeepAlive.isOverdue(now = 100L, lastRunAt = 0L, intervalHours = 6))
    }

    @Test
    fun `isOverdue honours the configured cadence not a fixed one`() {
        val now = 100L * HOUR_MS
        val last = now - 30 * HOUR_MS
        // 24h cadence: overdue. 72h cadence: not yet.
        assertTrue(WorkBuddyKeepAlive.isOverdue(now, last, intervalHours = 24))
        assertFalse(WorkBuddyKeepAlive.isOverdue(now, last, intervalHours = 72))
    }

    // ───────────────────────── fatal account list ─────────────────────────

    @Test
    fun `parseFatalAccounts splits on comma and trims`() {
        assertEquals(
            listOf("alice", "bob"),
            WorkBuddyKeepAlive.parseFatalAccounts("alice, bob"),
        )
    }

    @Test
    fun `parseFatalAccounts drops blank entries`() {
        assertEquals(
            listOf("alice"),
            WorkBuddyKeepAlive.parseFatalAccounts(", alice ,,"),
        )
    }

    @Test
    fun `parseFatalAccounts handles null and empty prefs`() {
        assertTrue(WorkBuddyKeepAlive.parseFatalAccounts(null).isEmpty())
        assertTrue(WorkBuddyKeepAlive.parseFatalAccounts("").isEmpty())
    }

    // ───────────────────────── summary format ─────────────────────────

    @Test
    fun `formatSummary renders the counts line`() {
        assertEquals("2 ok · 0 fatal · 1 transient", WorkBuddyKeepAlive.formatSummary(2, 0, 1))
    }
}
