package com.openminis.app.ui.chat

/**
 * Auto-scroll follow decision engine for the reverseLayout LazyColumn.
 *
 * Consolidates the 8 bespoke gate chains into a single pure function
 * [decideAutoFollow] that takes a [ScrollIntent] and the current scroll
 * state, and returns a [ScrollVerdict].
 *
 * Pure function — no side effects, no Android dependencies, JVM-testable.
 */

/**
 * The category of the scroll-triggering event.
 */
enum class ScrollIntent(val label: String) {
    /** User just tapped send / Enter. Unconditional scroll to bottom. */
    USER_SEND("user-send"),

    /** Reactive catch-all for user-message append (messages.size change). */
    USER_MESSAGE_APPEND("user-msg-append"),

    /** Per-token glide during streaming. */
    STREAM_GLIDE("stream-glide"),

    /** Stream just finished — re-pin to catch async self-sizing. */
    STREAM_END_SETTLE("stream-end-settle"),

    /** Second-stage settle, 900ms after stream-end. */
    STREAM_END_LATE_REPIN("stream-end-late-repin"),

    /** LayoutInfo-driven safety net: bottom item pushed below viewport. */
    LAYOUT_DRIFT_SNAP("layout-drift-snap"),

    /** bottomReserve changed (toolbar appearing/disappearing). */
    RESERVE_CHANGE("reserve-change"),

    /** New trailing tool/typing row appeared. */
    TRAILING_ROW("trailing-row"),

    /** ViewModel forceScrollToBottom (resume/retry/rerun) — respects the
     *  user's viewport: Skip if they scrolled away to read history. Fired by
     *  both explicit user gestures and agent-loop self-retries; the latter
     *  must not yank the user back to the bottom mid-multi-turn. */
    FORCE_SCROLL("force-scroll"),
}

/**
 * Snapshot of the LazyColumn scroll state and relevant timestamps.
 * All values are read from remembered state at the decision point.
 */
data class ScrollStateSnapshot(
    val userScrolledAway: Boolean,
    val isNearBottom: Boolean,
    val isScrollInProgress: Boolean,
    val isStreaming: Boolean,
    val nowMs: Long,
    val lastInterruptMs: Long,
    val lastUserAppendMs: Long,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val bottomItemOffset: Int,
    /** avgItemSize in pixels (for stream-glide distance estimation). */
    val avgItemSize: Float,
    /** SEND_FOLLOW_GRACE_MS constant from the caller. */
    val sendFollowGraceMs: Long,
)

/**
 * What the scroll engine should do.
 */
sealed class ScrollVerdict {
    /** Do not scroll. */
    data object Skip : ScrollVerdict()

    /**
     * Scroll to (index, offset). reason is logged as the ScrollSrc tag.
     */
    data class ScrollTo(val index: Int, val offset: Int, val reason: String) : ScrollVerdict()

    /**
     * Scroll by a delta (used by LAYOUT-DRIFT-CLIP). reason is logged.
     */
    data class ScrollBy(val delta: Float, val reason: String) : ScrollVerdict()
}

/**
 * Pure decision function: given the intent and the current state,
 * returns the verdict.
 *
 * Rules encoded per intent (preserving the pre-consolidation behavior):
 *
 * COMMON GATE (all intents):
 *   - If userScrolledAway → Skip (user explicitly left the bottom)
 *   - EXCEPTION: USER_SEND is unconditional (user just tapped send)
 *
 * PER-INTENT GATES:
 *   USER_SEND: unconditional (isNearBottom irrelevant)
 *   USER_MESSAGE_APPEND: isNearBottom must be true
 *   STREAM_GLIDE: isStreaming=true, 1s post-interrupt, firstIdx==0
 *   STREAM_END_SETTLE: !isStreaming, isNearBottom, 1s post-interrupt
 *   STREAM_END_LATE_REPIN: !isStreaming, isNearBottom, 1.5s post-interrupt, firstIdx!=0
 *   LAYOUT_DRIFT_SNAP: bottomItemOffset<0 only (deliberately NOT gated on isNearBottom)
 *   RESERVE_CHANGE: isNearBottom || sendGrace (within 2s of send)
 *   TRAILING_ROW: isNearBottom, 1s post-interrupt, (sendGrace || streaming)
 *   FORCE_SCROLL: COMMON GATE only (resume/retry/rerun when the user has
 *     NOT left the bottom; never yanks someone who scrolled away to read)
 */
fun decideAutoFollow(intent: ScrollIntent, s: ScrollStateSnapshot): ScrollVerdict {
    // ─── USER_SEND: unconditional ───
    // [P2-scroll-user-send] Was fully unconditional — yanked a user who
    // sent while reading history (log: user-send/initial at firstIdx=16)
    // straight to the bottom. The ChatScreen send handlers now snapshot
    // the anchor pre-insert and skip when the reader is in history; mirror
    // that here so any future caller of decideAutoFollow(USER_SEND) gets
    // the same behavior: scroll only when anchored on the bottom row.
    if (intent == ScrollIntent.USER_SEND) {
        if (s.firstVisibleItemIndex > 0) return ScrollVerdict.Skip
        return ScrollVerdict.ScrollTo(0, 0, intent.label)
    }

    // ─── All other intents: userScrolledAway → Skip ───
    if (s.userScrolledAway) return ScrollVerdict.Skip

    // ─── isScrollInProgress handled per-branch below, EXACTLY where the
    //     original code gated on it. USER_SEND / USER_MESSAGE_APPEND /
    //     RESERVE_CHANGE never gated on it originally (a send-snap must
    //     still fire even mid-scroll), so they do not gate here either.
    return when (intent) {
        ScrollIntent.USER_SEND -> {
            // Unreachable (handled above), but keep exhaustive.
            ScrollVerdict.ScrollTo(0, 0, intent.label)
        }

        ScrollIntent.USER_MESSAGE_APPEND -> {
            if (!s.isNearBottom) return ScrollVerdict.Skip
            ScrollVerdict.ScrollTo(0, 0, intent.label)
        }

        ScrollIntent.STREAM_GLIDE -> {
            if (s.isScrollInProgress) return ScrollVerdict.Skip
            if (!s.isStreaming) return ScrollVerdict.Skip
            val sinceInterrupt = s.nowMs - s.lastInterruptMs
            if (sinceInterrupt < 1000L) return ScrollVerdict.Skip
            // [T261] REMOVED the firstIdx==0 gate. It was added in the
            // 1st-round scroll patch (884d9f1) to stop a reader who HAD
            // scrolled away from being yanked back while streaming grew the
            // index-0 block. But the COMMON GATE already covers that: a
            // reader who scrolled away has userScrolledAway=true (fed from
            // !stickToBottom), so they never reach this branch. The extra
            // firstIdx>0 gate instead KILLED the legit case where the user
            // explicitly tapped the JumpToBottom FAB (stickToBottom=true)
            // and streaming then pushed the viewport above the bottom row —
            // the glide refused to pull them back, so follow silently died.
            // FAB re-stick is the explicit "follow to the bottom" intent, so
            // a firstIdx>0 here must still glide. New row insertion at index
            // 0 is handled by TRAILING_ROW regardless; this branch only
            // decides whether the live stream keeps the viewport pinned.
            // If already perfectly pinned, skip.
            if (s.firstVisibleItemIndex == 0 && s.firstVisibleItemScrollOffset == 0) {
                return ScrollVerdict.Skip
            }
            // Estimate remaining distance and decide glide vs snap.
            val avg = s.avgItemSize.coerceAtLeast(1f)
            val remaining = s.firstVisibleItemIndex * avg + s.firstVisibleItemScrollOffset
            if (remaining <= 0f) return ScrollVerdict.Skip
            // Cold start: no avg item size yet → snap instead of glide.
            if (s.avgItemSize <= 0f) {
                return ScrollVerdict.ScrollTo(0, 0, intent.label)
            }
            // Step toward bottom in bounded increments.
            // This is handled by the caller's scroll loop; the verdict
            // here means "start a glide session".
            return ScrollVerdict.ScrollTo(0, 0, intent.label)
        }

        ScrollIntent.STREAM_END_SETTLE -> {
            if (s.isScrollInProgress) return ScrollVerdict.Skip
            val sinceInterrupt = s.nowMs - s.lastInterruptMs
            if (sinceInterrupt < 1000L) return ScrollVerdict.Skip
            // [P0-0-jump-fix] isNearBottom guard
            if (!s.isNearBottom) return ScrollVerdict.Skip
            ScrollVerdict.ScrollTo(0, 0, intent.label)
        }

        ScrollIntent.STREAM_END_LATE_REPIN -> {
            if (s.isScrollInProgress) return ScrollVerdict.Skip
            val sinceInterrupt = s.nowMs - s.lastInterruptMs
            if (sinceInterrupt < 1500L) return ScrollVerdict.Skip
            // [P0-0-jump-fix] isNearBottom guard
            if (!s.isNearBottom) return ScrollVerdict.Skip
            // Only fire if viewport actually drifted from index 0
            if (s.firstVisibleItemIndex == 0) return ScrollVerdict.Skip
            ScrollVerdict.ScrollTo(0, 0, intent.label)
        }

        ScrollIntent.LAYOUT_DRIFT_SNAP -> {
            // [Txxx-android-drift-snap-gate] DELIBERATELY NOT gated on
            // isNearBottom. When new messages are inserted at index 0,
            // firstVisibleItemIndex advances past 0 before the bottom
            // anchor settles, so isNearBottom flips false exactly when
            // we NEED the drift-snap.
            // BUT: isScrollInProgress IS gated (as in original 1679) —
            // don't compete with an active user drag/fling.
            //
            // [P2-scroll-read-history] The one true discriminator between
            //  (a) "content inserted under a bottom-anchored user — compensate"
            //  (b) "user is reading history up the list — leave them alone"
            // is firstVisibleItemIndex, NOT bottomItemOffset. In reverseLayout
            // ANY insertion pushes index 0's offset negative even when the
            // user has drifted 2+ rows up reading (log: drift-snap fired with
            // firstIdx=2). bottomItemOffset<0 alone is true in BOTH cases and
            // yanks the reader back to (0,0). So: only compensate drift when
            // the viewport is still anchored on the bottom row (firstIdx==0);
            // a user who has scrolled into history (firstIdx>0) — whether by
            // drag or by insertion drift — must never be snapped back.
            if (s.isScrollInProgress) return ScrollVerdict.Skip
            if (s.firstVisibleItemIndex > 0) return ScrollVerdict.Skip
            if (s.bottomItemOffset >= 0) return ScrollVerdict.Skip
            ScrollVerdict.ScrollTo(0, 0, intent.label)
        }

        ScrollIntent.RESERVE_CHANGE -> {
            val sinceSend = s.nowMs - s.lastUserAppendMs
            val sendGrace = s.lastUserAppendMs > 0L && sinceSend in 0..s.sendFollowGraceMs
            if (!sendGrace && !s.isNearBottom) return ScrollVerdict.Skip
            ScrollVerdict.ScrollTo(0, 0, intent.label)
        }

        ScrollIntent.FORCE_SCROLL -> {
            // [fix/force-scroll-respect-viewport] ViewModel asked to go to
            // bottom (resume/retry/rerun). COMMON GATE above already returned
            // Skip if the user scrolled away — here, if we reach this branch
            // the user has not left the bottom, so follow unconditionally
            // (no isScrollInProgress gate, mirroring RESERVE_CHANGE: a
            // retry-into-new-turn should still re-pin mid-frame).
            ScrollVerdict.ScrollTo(0, 0, intent.label)
        }

        ScrollIntent.TRAILING_ROW -> {
            if (s.isScrollInProgress) return ScrollVerdict.Skip
            val sinceInterrupt = s.nowMs - s.lastInterruptMs
            if (sinceInterrupt < 1000L) return ScrollVerdict.Skip
            if (!s.isNearBottom) return ScrollVerdict.Skip
            val sinceSend = s.nowMs - s.lastUserAppendMs
            val sendGrace = s.lastUserAppendMs > 0L && sinceSend <= s.sendFollowGraceMs
            val streamingActive = s.isStreaming && !s.userScrolledAway
            if (!sendGrace && !streamingActive) return ScrollVerdict.Skip
            ScrollVerdict.ScrollTo(0, 0, intent.label)
        }
    }
}