package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [followReducer] / [consumeBottomRequest] — the user-intent
 * follow state machine.
 *
 * Acceptance-critical cases from the fix plan:
 *  - DETACHED + 100 StreamRowsChanged → 0 bottom requests.
 *  - FAB Down / Send / Resume each raise exactly ONE request — no settle
 *    second call.
 *  - DragStart immediately blocks programmatic scrolls.
 */
class ChatFollowControllerTest {

    private fun apply(state: FollowState, vararg events: FollowEvent): FollowState =
        events.fold(state) { s, e -> followReducer(s, e) }

    // ── explicit intents ────────────────────────────────────────────────────

    @Test fun `initial open follows and raises one request`() {
        val s = apply(FollowState(), FollowEvent.InitialOpen)
        assertEquals(FollowMode.FOLLOWING, s.mode)
        assertEquals(BottomRequestReason.INITIAL_OPEN, s.pendingBottomRequest)
    }

    @Test fun `send re-engages follow with exactly one request`() {
        val s = apply(FollowState(), FollowEvent.Send)
        assertEquals(FollowMode.FOLLOWING, s.mode)
        assertEquals(BottomRequestReason.SEND, s.pendingBottomRequest)
        // No settle second call: the next state after consuming has no request.
        val consumed = consumeBottomRequest(s)
        assertNull(consumed.pendingBottomRequest)
    }

    @Test fun `fab down resume retry each raise exactly one request`() {
        for (event in listOf(FollowEvent.FabDown, FollowEvent.Resume, FollowEvent.Retry)) {
            val s = apply(FollowState(), event)
            assertTrue("$event should set a pending request", s.hasPendingBottomRequest)
            assertEquals(FollowMode.FOLLOWING, s.mode)
            val consumed = consumeBottomRequest(s)
            assertFalse(consumed.hasPendingBottomRequest)
        }
    }

    @Test fun `explicit intent from detached re-engages follow`() {
        val detached = FollowState(mode = FollowMode.DETACHED)
        val s = apply(detached, FollowEvent.FabDown)
        assertEquals(FollowMode.FOLLOWING, s.mode)
        assertEquals(BottomRequestReason.FAB_DOWN, s.pendingBottomRequest)
    }

    // ── drag protocol ───────────────────────────────────────────────────────

    @Test fun `drag start drops any pending request and blocks scrolls`() {
        val s = apply(FollowState(), FollowEvent.Send) // has a pending request
        val duringDrag = apply(s, FollowEvent.UserDragStart)
        assertNull(duringDrag.pendingBottomRequest)
        assertEquals(FollowMode.FOLLOWING, duringDrag.mode) // mode not flipped mid-gesture
    }

    @Test fun `drag end at bottom keeps following`() {
        val s = apply(FollowState(), FollowEvent.UserDragEnd(atBottom = true))
        assertEquals(FollowMode.FOLLOWING, s.mode)
        assertNull(s.pendingBottomRequest)
    }

    @Test fun `drag end away from bottom detaches`() {
        val s = apply(FollowState(), FollowEvent.UserDragEnd(atBottom = false))
        assertEquals(FollowMode.DETACHED, s.mode)
        assertNull(s.pendingBottomRequest)
    }

    // ── acceptance: 100 StreamRowsChanged while detached → 0 requests ───────

    @Test fun `detached never raises a request no matter how much data arrives`() {
        var s = apply(FollowState(), FollowEvent.UserDragEnd(atBottom = false))
        var requests = 0
        repeat(100) {
            s = apply(s, FollowEvent.StreamRowsChanged)
            if (s.hasPendingBottomRequest) requests++
        }
        assertEquals(0, requests)
        assertEquals(FollowMode.DETACHED, s.mode)
        assertEquals(100L, s.rowRevision) // data revision still tracked
    }

    @Test fun `detached ignores tokens tools stream end and retries`() {
        var s = apply(FollowState(), FollowEvent.UserDragEnd(atBottom = false))
        // Simulate a full agent turn while the user reads history. Retry is a
        // USER-explicit intent (reducer re-engages follow on Retry) — an
        // AUTOMATIC re-run manifests as plain data revisions, which is what
        // this simulates: none of it may yank the reader.
        s = apply(s, FollowEvent.StreamRowsChanged)
        s = apply(s, FollowEvent.StreamRowsChanged)
        s = apply(s, FollowEvent.StreamRowsChanged)
        s = apply(s, FollowEvent.StreamRowsChanged)
        assertNull(s.pendingBottomRequest)
        assertEquals(FollowMode.DETACHED, s.mode)
    }

    // ── following + stream progress ─────────────────────────────────────────

    @Test fun `following raises a progress request per committed revision`() {
        var s = apply(FollowState(), FollowEvent.InitialOpen)
        s = consumeBottomRequest(s)
        s = apply(s, FollowEvent.StreamRowsChanged)
        assertEquals(BottomRequestReason.STREAM_PROGRESS, s.pendingBottomRequest)
        s = consumeBottomRequest(s)
        s = apply(s, FollowEvent.StreamRowsChanged)
        assertEquals(BottomRequestReason.STREAM_PROGRESS, s.pendingBottomRequest)
    }

    @Test fun `drag back to bottom resumes following and progress requests`() {
        var s = apply(FollowState(), FollowEvent.UserDragEnd(atBottom = false))
        s = apply(s, FollowEvent.StreamRowsChanged)
        assertNull(s.pendingBottomRequest)
        // User drags back down — sentinel visible.
        s = apply(s, FollowEvent.UserDragEnd(atBottom = true))
        assertEquals(FollowMode.FOLLOWING, s.mode)
        s = apply(s, FollowEvent.StreamRowsChanged)
        assertEquals(BottomRequestReason.STREAM_PROGRESS, s.pendingBottomRequest)
    }

    // ── IME ─────────────────────────────────────────────────────────────────

    @Test fun `ime viewport change is a deliberate no-op`() {
        val s = apply(FollowState(), FollowEvent.Send)
        val withIme = apply(s, FollowEvent.ImeViewportChanged)
        // Request untouched, mode untouched, revision untouched.
        assertEquals(s, withIme)
    }

    @Test fun `ime change while detached does not yank`() {
        var s = apply(FollowState(), FollowEvent.UserDragEnd(atBottom = false))
        s = apply(s, FollowEvent.ImeViewportChanged)
        s = apply(s, FollowEvent.ImeViewportChanged)
        assertEquals(FollowMode.DETACHED, s.mode)
        assertNull(s.pendingBottomRequest)
    }

    // ── revision monotonicity ───────────────────────────────────────────────

    @Test fun `row revision is monotonic and never regresses`() {
        var s = FollowState()
        var prev = s.rowRevision
        val events = listOf(
            FollowEvent.StreamRowsChanged,
            FollowEvent.UserDragEnd(atBottom = false),
            FollowEvent.StreamRowsChanged,
            FollowEvent.ImeViewportChanged,
            FollowEvent.FabDown,
            FollowEvent.StreamRowsChanged,
        )
        for (e in events) {
            s = apply(s, e)
            assertTrue("revision must be monotonic (${s.rowRevision} < $prev)", s.rowRevision >= prev)
            prev = s.rowRevision
        }
    }

    // ── fix/history-open-at-bottom-verify: sentinel-visible consumption ─────

    @Test fun `initial open retains pending request while sentinel is off-screen`() {
        assertTrue(retainInitialOpenUntilSentinelVisible(BottomRequestReason.INITIAL_OPEN, sentinelVisible = false))
    }

    @Test fun `initial open consumes once the sentinel is visible`() {
        assertFalse(retainInitialOpenUntilSentinelVisible(BottomRequestReason.INITIAL_OPEN, sentinelVisible = true))
    }

    @Test fun `non-open reasons never retain even with sentinel off-screen`() {
        for (reason in listOf(
            BottomRequestReason.SEND,
            BottomRequestReason.RESUME,
            BottomRequestReason.RETRY,
            BottomRequestReason.FAB_DOWN,
            BottomRequestReason.STREAM_PROGRESS,
        )) {
            assertFalse("$reason must not retain on sentinel off-screen", retainInitialOpenUntilSentinelVisible(reason, sentinelVisible = false))
        }
    }

    @Test fun `no pending request is never retained`() {
        assertFalse(retainInitialOpenUntilSentinelVisible(null, sentinelVisible = false))
    }

    /**
     * Mirrors the ChatScreen consumer's cold-open flow: raise INITIAL_OPEN,
     * the layout is empty (sentinel off-screen → request survives), then rows
     * land but the sentinel is NOT yet visible (the scroll hasn't landed) →
     * the request STILL survives and re-drives; only when the sentinel is
     * provably on screen is the request consumed exactly once. This is the
     * pure contract that makes cold-open never end up stuck at the top:
     * the request cannot be consumed before the bottom is actually reached.
     */
    @Test fun `cold open keeps initial request pending until the sentinel is visible then consumes once`() {
        var s = apply(FollowState(), FollowEvent.InitialOpen)
        assertEquals(BottomRequestReason.INITIAL_OPEN, s.pendingBottomRequest)

        // Consumer run 1: empty layout — sentinel off-screen → retain.
        if (retainInitialOpenUntilSentinelVisible(s.pendingBottomRequest, sentinelVisible = false)) {
            // retained — mimic the consumer, leave s unchanged
        } else {
            s = consumeBottomRequest(s)
        }
        assertEquals(BottomRequestReason.INITIAL_OPEN, s.pendingBottomRequest)

        // Consumer run 2: rows have landed but the pending scroll has NOT
        // landed yet (sentinel still off-screen) → must STILL retain, so the
        // layoutInfo re-drive re-scrolls instead of consuming into the top.
        if (retainInitialOpenUntilSentinelVisible(s.pendingBottomRequest, sentinelVisible = false)) {
            // retained — re-drive re-fires the bottom scroll
        } else {
            s = consumeBottomRequest(s)
        }
        assertEquals(BottomRequestReason.INITIAL_OPEN, s.pendingBottomRequest)

        // Consumer run 3: the scroll has landed — sentinel visible → consume.
        assertFalse(retainInitialOpenUntilSentinelVisible(s.pendingBottomRequest, sentinelVisible = true))
        s = consumeBottomRequest(s)
        assertNull(s.pendingBottomRequest)
        assertFalse(s.hasPendingBottomRequest)
    }

    /**
     * The failure mode the original fix left open: the viewport IS at the
     * bottom (sentinel visible) BEFORE the consumer runs — e.g. a short
     * session whose rows fit the viewport, or a previous scroll already
     * landed. The consumer must still consume in that run (no fake retain),
     * and must NOT scroll again (no double-scroll yank).
     */
    @Test fun `cold open consumes immediately when sentinel already visible`() {
        var s = apply(FollowState(), FollowEvent.InitialOpen)
        // First run: sentinel already visible (short session / scroll landed).
        assertFalse(retainInitialOpenUntilSentinelVisible(s.pendingBottomRequest, sentinelVisible = true))
        s = consumeBottomRequest(s)
        assertNull(s.pendingBottomRequest)
        // A second run must find nothing pending — the request is gone.
        assertFalse(retainInitialOpenUntilSentinelVisible(s.pendingBottomRequest, sentinelVisible = true))
    }
}
