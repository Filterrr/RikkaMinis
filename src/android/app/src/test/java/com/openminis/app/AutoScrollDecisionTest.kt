package com.openminis.app

import com.openminis.app.ui.chat.ScrollStateSnapshot
import com.openminis.app.ui.chat.ScrollIntent
import com.openminis.app.ui.chat.ScrollVerdict
import com.openminis.app.ui.chat.decideAutoFollow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the consolidated auto-follow decision engine.
 *
 * These lock in the behavioural contract of decideAutoFollow so that
 * refactoring a caller can't silently change when the list follows.
 * Each test pins a gate that real-device debugging uncovered — most
 * notably the [P0-0-jump-fix] isNearBottom/userScrolledAway guards
 * that stop content insertion from yanking a reading user.
 */
class AutoScrollDecisionTest {

    // ─── Baseline snapshot helpers ───

    private fun baseState() = ScrollStateSnapshot(
        userScrolledAway = false,
        isNearBottom = true,
        isScrollInProgress = false,
        isStreaming = true,
        nowMs = 50_000L,
        lastInterruptMs = 10_000L,
        lastUserAppendMs = 0L,
        firstVisibleItemIndex = 0,
        firstVisibleItemScrollOffset = 0,
        bottomItemOffset = 0,
        avgItemSize = 200f,
        sendFollowGraceMs = 2_000L,
    )

    // ─── USER_SEND: unconditional ───

    @Test
    fun userSend_scrollsUnconditionally() {
        // Even with userScrolledAway and far from bottom, USER_SEND must snap.
        val s = baseState().copy(
            userScrolledAway = true,
            isNearBottom = false,
            firstVisibleItemIndex = 40,
        )
        val v = decideAutoFollow(ScrollIntent.USER_SEND, s)
        assertTrue("USER_SEND must always ScrollTo", v is ScrollVerdict.ScrollTo)
    }

    // ─── USER_MESSAGE_APPEND: isNearBottom + not scrolled away ───

    @Test
    fun userMsgAppend_scrolledAway_skips() {
        val s = baseState().copy(userScrolledAway = true)
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.USER_MESSAGE_APPEND, s))
    }

    @Test
    fun userMsgAppend_notNearBottom_skips() {
        val s = baseState().copy(isNearBottom = false)
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.USER_MESSAGE_APPEND, s))
    }

    @Test
    fun userMsgAppend_following_scrolls() {
        val s = baseState().copy(isNearBottom = true, userScrolledAway = false)
        assertTrue(
            decideAutoFollow(ScrollIntent.USER_MESSAGE_APPEND, s) is ScrollVerdict.ScrollTo
        )
    }

    // ─── STREAM_GLIDE: streaming + 1s + same-item only ───

    @Test
    fun streamGlide_notStreaming_skips() {
        val s = baseState().copy(isStreaming = false)
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.STREAM_GLIDE, s))
    }

    @Test
    fun streamGlide_withinInterruptGrace_skips() {
        // lastInterrupt pulled very recent (< 1000ms)
        val s = baseState().copy(lastInterruptMs = 49_800L) // 200ms ago
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.STREAM_GLIDE, s))
    }

    @Test
    fun streamGlide_indexPushedByInsertion_skips() {
        // [P0-0-jump-fix] firstIdx > 0 means new row inserted at index 0;
        // the glide must NOT fight that — TRAILING_ROW handles it.
        val s = baseState().copy(firstVisibleItemIndex = 1)
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.STREAM_GLIDE, s))
    }

    @Test
    fun streamGlide_perfectlyPinned_skips() {
        // Already at (0,0) — no-op.
        val s = baseState().copy(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.STREAM_GLIDE, s))
    }

    @Test
    fun streamGlide_driftedWithinItem_scrolls() {
        // firstIdx==0 but firstOff>0 → drift within the bottom item → glide.
        val s = baseState().copy(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 120,
        )
        val v = decideAutoFollow(ScrollIntent.STREAM_GLIDE, s)
        assertTrue("drift within bottom item must glide", v is ScrollVerdict.ScrollTo)
    }

    @Test
    fun streamGlide_coldStartOverflow_snaps() {
        // firstIdx==0, firstOff>avg → no measured size yet → snap, not glide.
        val s = baseState().copy(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 300,
            avgItemSize = 0f,
        )
        assertTrue(decideAutoFollow(ScrollIntent.STREAM_GLIDE, s) is ScrollVerdict.ScrollTo)
    }

    // ─── STREAM_END_SETTLE: idle + isNearBottom + 1s ───

    @Test
    fun streamEndSettle_notNearBottom_skips() {
        val s = baseState().copy(isStreaming = false, isNearBottom = false)
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.STREAM_END_SETTLE, s))
    }

    @Test
    fun streamEndSettle_followingAtBottom_pins() {
        val s = baseState().copy(isStreaming = false, isNearBottom = true)
        assertTrue(decideAutoFollow(ScrollIntent.STREAM_END_SETTLE, s) is ScrollVerdict.ScrollTo)
    }

    // ─── STREAM_END_LATE_REPIN: needs firstIdx>0 ───

    @Test
    fun lateRepin_alreadyAtIndex0_skips() {
        val s = baseState().copy(
            isStreaming = false,
            isNearBottom = true,
            firstVisibleItemIndex = 0,
        )
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.STREAM_END_LATE_REPIN, s))
    }

    @Test
    fun lateRepin_driftedAway_pins() {
        val s = baseState().copy(
            isStreaming = false,
            isNearBottom = true,
            firstVisibleItemIndex = 2,
            lastInterruptMs = 5_000L, // > 1.5s ago
        )
        assertTrue(decideAutoFollow(ScrollIntent.STREAM_END_LATE_REPIN, s) is ScrollVerdict.ScrollTo)
    }

    // ─── LAYOUT_DRIFT_SNAP ───

    @Test
    fun layoutDriftSnap_readingHistory_driftedUp_skips() {
        // [P2-scroll-read-history] The core bug: content insertion pushed the
        // viewport up to index 1 while the user was reading history there.
        // Old code fired (bottomItemOffset<0 alone) and yanked the user back
        // to (0,0). Now firstVisibleItemIndex>0 means the viewport has left
        // the bottom row — whether by drag OR by insertion drift — so the
        // drift-snap must NOT compensate. Only TRAILING_ROW/STREAM_* handle
        // pushing a bottom-anchored reader down.
        val s = baseState().copy(
            isNearBottom = false,
            firstVisibleItemIndex = 1,
            bottomItemOffset = -50,
        )
        assertEquals(
            "must NOT yank a reader who drifted into history",
            ScrollVerdict.Skip,
            decideAutoFollow(ScrollIntent.LAYOUT_DRIFT_SNAP, s),
        )
    }

    @Test
    fun layoutDriftSnap_anchoredBottomRow_driftedNegative_pins() {
        // The legitimate drift-snap case: the user IS anchored on the bottom
        // row (firstVisibleItemIndex==0) and that row's content grew, pushing
        // its offset negative. isNearBottom may read false (transient reflow)
        // but we must still compensate to keep index 0 pinned.
        val s = baseState().copy(
            isNearBottom = false,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 5,
            bottomItemOffset = -50,
        )
        val v = decideAutoFollow(ScrollIntent.LAYOUT_DRIFT_SNAP, s)
        assertTrue("bottom-anchored negative drift must snap", v is ScrollVerdict.ScrollTo)
    }

    @Test
    fun layoutDriftSnap_positiveOffset_skips() {
        // bottom item at/above viewport → no drift to fix.
        val s = baseState().copy(bottomItemOffset = 40)
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.LAYOUT_DRIFT_SNAP, s))
    }

    @Test
    fun layoutDriftSnap_scrolling_skips() {
        // Don't fight an active touch.
        val s = baseState().copy(bottomItemOffset = -50, isScrollInProgress = true)
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.LAYOUT_DRIFT_SNAP, s))
    }

    // ─── RESERVE_CHANGE: isNearBottom || sendGrace ───

    @Test
    fun reserveChange_notNearBottom_noSendGrace_skips() {
        val s = baseState().copy(isNearBottom = false, lastUserAppendMs = 0L)
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.RESERVE_CHANGE, s))
    }

    @Test
    fun reserveChange_sendGraceWindow_bypassesNearBottom() {
        // Within SEND_FOLLOW_GRACE_MS of a user message, isNearBottom waived.
        val s = baseState().copy(
            isNearBottom = false,
            lastUserAppendMs = 49_000L, // 1000ms ago, within 2s grace
        )
        val v = decideAutoFollow(ScrollIntent.RESERVE_CHANGE, s)
        assertTrue("send-grace must bypass isNearBottom", v is ScrollVerdict.ScrollTo)
    }

    // ─── TRAILING_ROW: isNearBottom + (sendGrace || streaming) + 1s ───

    @Test
    fun trailingRow_readHistoryViaPushedUpContent_skips() {
        // [P0-0-jump-fix] The core bug: content insertion pushed viewport to
        // index 1 (isNearBottom=false) but userScrolledAway=false. Old code
        // fired the pin and yanked the user. Must Now skip.
        val s = baseState().copy(
            isNearBottom = false,
            firstVisibleItemIndex = 1,
            userScrolledAway = false,
            lastUserAppendMs = 0L, // not in send grace
        )
        val v = decideAutoFollow(ScrollIntent.TRAILING_ROW, s)
        assertEquals("must NOT yank pushed-up reader", ScrollVerdict.Skip, v)
    }

    @Test
    fun trailingRow_freshlySent_pins() {
        // Inside send-grace window: pin the new typing row up.
        val s = baseState().copy(
            isNearBottom = true,
            lastUserAppendMs = 49_500L, // 500ms ago
        )
        assertTrue(
            decideAutoFollow(ScrollIntent.TRAILING_ROW, s) is ScrollVerdict.ScrollTo
        )
    }

    @Test
    fun trailingRow_streamingActiveAtBottom_pins() {
        // Streaming and at bottom → pin new tool rows up.
        val s = baseState().copy(
            isNearBottom = true,
            isStreaming = true,
            userScrolledAway = false,
        )
        assertTrue(
            decideAutoFollow(ScrollIntent.TRAILING_ROW, s) is ScrollVerdict.ScrollTo
        )
    }

    @Test
    fun trailingRow_scrolledAway_skips() {
        // User explicitly reading history further up.
        val s = baseState().copy(
            isNearBottom = false,
            userScrolledAway = true,
            firstVisibleItemIndex = 5,
        )
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.TRAILING_ROW, s))
    }

    // ─── FORCE_SCROLL: respects viewport (resume/retry/rerun) ───

    @Test
    fun forceScroll_scrolledAway_skips() {
        // [fix/force-scroll-respect-viewport] The core bug: an agent loop
        // self-retry must NOT yank a user who scrolled up to read history
        // back to the bottom.
        val s = baseState().copy(
            userScrolledAway = true,
            isNearBottom = false,
            firstVisibleItemIndex = 23,
        )
        assertEquals(ScrollVerdict.Skip, decideAutoFollow(ScrollIntent.FORCE_SCROLL, s))
    }

    @Test
    fun forceScroll_atBottomFollowing_scrolls() {
        // User has not left the bottom → retry re-pins normally.
        val s = baseState().copy(userScrolledAway = false, isNearBottom = true)
        assertTrue(
            decideAutoFollow(ScrollIntent.FORCE_SCROLL, s) is ScrollVerdict.ScrollTo
        )
    }

    @Test
    fun forceScroll_scrollingMidRetry_stillScrolls() {
        // No isScrollInProgress gate: a retry into a new turn re-pins even
        // during an in-flight scroll (mirrors RESERVE_CHANGE).
        val s = baseState().copy(userScrolledAway = false, isScrollInProgress = true)
        assertTrue(
            decideAutoFollow(ScrollIntent.FORCE_SCROLL, s) is ScrollVerdict.ScrollTo
        )
    }
}
