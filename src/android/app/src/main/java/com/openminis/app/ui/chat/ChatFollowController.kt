package com.openminis.app.ui.chat

/**
 * User-intent follow state machine for the chat list (pure reducer).
 *
 * Replaces the reverseLayout-era `stickToBottom` flag + anchor-guard watcher
 * with an explicit two-mode protocol:
 *
 *  - [FollowMode.FOLLOWING]: the viewport tracks the bottom. Only a committed
 *    data revision (StreamRowsChanged) or an explicit user action (Send /
 *    Resume / Retry / FabDown / InitialOpen) may raise a pending bottom
 *    request; the renderer additionally requires the bottom sentinel to be
 *    out of view and the list not mid-gesture before actually scrolling.
 *  - [FollowMode.DETACHED]: the user read history. Tokens, tools, stream end,
 *    automatic retries and IME changes NEVER scroll the list.
 *
 * Mode transitions are driven exclusively by [FollowEvent]s. Position
 * observation (is bottom sentinel visible?) is used only to DECIDE — never to
 * trigger a scroll from inside a position listener, which would create the
 * feedback loop the anchor-guard suffered from.
 *
 * Pure class: no Android / Compose dependencies, fully JVM-testable.
 */
enum class FollowMode { FOLLOWING, DETACHED }

enum class BottomRequestReason {
    INITIAL_OPEN,
    SEND,
    RESUME,
    RETRY,
    FAB_DOWN,
    STREAM_PROGRESS,
}

data class FollowState(
    val mode: FollowMode = FollowMode.FOLLOWING,
    val pendingBottomRequest: BottomRequestReason? = null,
    /** Monotonic data revision — bumped on every committed row change. */
    val rowRevision: Long = 0L,
) {
    val isFollowing: Boolean get() = mode == FollowMode.FOLLOWING
    val hasPendingBottomRequest: Boolean get() = pendingBottomRequest != null
}

sealed interface FollowEvent {
    /** Session opened with no focus target — start following. */
    data object InitialOpen : FollowEvent
    /** A real pointer drag started — block ALL programmatic scrolls meanwhile. */
    data object UserDragStart : FollowEvent
    /** Drag ended. [atBottom] is the renderer's sentinel-based verdict. */
    data class UserDragEnd(val atBottom: Boolean) : FollowEvent
    /** A data revision was committed (new rows published / live tail grown). */
    data object StreamRowsChanged : FollowEvent
    data object Send : FollowEvent
    data object Resume : FollowEvent
    data object Retry : FollowEvent
    data object FabDown : FollowEvent
    /** IME open/close/resize changed the viewport — deliberately a no-op. */
    data object ImeViewportChanged : FollowEvent
}

/**
 * Pure reducer. Returns the next state; never throws; never scrolls.
 */
fun followReducer(state: FollowState, event: FollowEvent): FollowState = when (event) {
    is FollowEvent.InitialOpen -> state.copy(
        mode = FollowMode.FOLLOWING,
        pendingBottomRequest = BottomRequestReason.INITIAL_OPEN,
    )

    // Explicit user intents — re-engage follow and raise exactly ONE request.
    // (The old code double-fired initial+settle scrolls; there is no settle
    // second call in this protocol.)
    is FollowEvent.Send -> state.copy(
        mode = FollowMode.FOLLOWING,
        pendingBottomRequest = BottomRequestReason.SEND,
    )
    is FollowEvent.Resume -> state.copy(
        mode = FollowMode.FOLLOWING,
        pendingBottomRequest = BottomRequestReason.RESUME,
    )
    is FollowEvent.Retry -> state.copy(
        mode = FollowMode.FOLLOWING,
        pendingBottomRequest = BottomRequestReason.RETRY,
    )
    is FollowEvent.FabDown -> state.copy(
        mode = FollowMode.FOLLOWING,
        pendingBottomRequest = BottomRequestReason.FAB_DOWN,
    )

    // While the finger is down nothing may scroll; mode itself is untouched
    // (the mode flip happens on drag END, when we know where the finger
    // stopped). Drop any in-flight request so a queued scroll cannot fire
    // mid-gesture.
    is FollowEvent.UserDragStart -> state.copy(pendingBottomRequest = null)

    is FollowEvent.UserDragEnd -> state.copy(
        mode = if (event.atBottom) FollowMode.FOLLOWING else FollowMode.DETACHED,
        // No request from a drag itself: either the user is back at the
        // bottom (natural follow — next data revision will re-request) or
        // detached (nothing may scroll).
        pendingBottomRequest = null,
    )

    is FollowEvent.StreamRowsChanged -> {
        // Data revision is committed. In FOLLOWING this may raise a bottom
        // request (renderer still gates on sentinel visibility + no gesture).
        // In DETACHED it must never raise one — tokens/tools/stream end may
        // not yank a history reader.
        state.copy(
            rowRevision = state.rowRevision + 1,
            pendingBottomRequest = if (state.isFollowing) {
                BottomRequestReason.STREAM_PROGRESS
            } else {
                state.pendingBottomRequest
            },
        )
    }

    // IME changing the viewport is not a follow intent — no request, no mode
    // change. (The old settle-after-interaction logic fought IME animations.)
    is FollowEvent.ImeViewportChanged -> state
}

/**
 * Consume the pending bottom request — call once the renderer has acted on it
 * (or decided it must not act, e.g. a gesture is in progress). Exactly one
 * request per event is the protocol; there is no auto-retry / settle re-fire.
 */
fun consumeBottomRequest(state: FollowState): FollowState =
    state.copy(pendingBottomRequest = null)

/**
 * [fix/history-open-at-bottom-04] What the bottom-scroll consumer should do
 * for the current request, as a pure decision (no Compose / Android deps).
 * This is the single gate the ChatScreen effect delegates to; keeping it pure
 * makes the "scroll exactly once / never yank a dragging reader / never
 * scroll when a focus target owns position" contract JVM-testable.
 */
enum class BottomScrollAction {
    /** Scroll-to-bottom (sentinel = last item), then consume the request exactly once. */
    SCROLL_TO_BOTTOM,

    /** No scroll this run; consume the request (draft / focus / not-following / forbidden). */
    SKIP_AND_CONSUME,

    /** Keep the INITIAL_OPEN request pending — session data not loaded yet. */
    WAIT_FOR_DATA,
}

/**
 * Decision rules (mirror the consumer effect exactly):
 *  1. A focus target owns position → never scroll to bottom (consume).
 *  2. INITIAL_OPEN before data is ready → wait (keep pending).
 *  3. FOLLOWING + no in-flight scroll + no user drag + sentinel off-screen:
 *       rows present → scroll (data-ready); rows absent → safe consume.
 *  4. Anything else (not following, mid-scroll, dragging, sentinel already
 *     visible) → consume without scrolling (no re-fire, no yank).
 */
internal fun decideBottomScroll(
    reason: BottomRequestReason?,
    sessionLoaded: Boolean,
    sentinelVisible: Boolean,
    hasRows: Boolean,
    isFollowing: Boolean,
    isScrollInProgress: Boolean,
    isUserDragging: Boolean,
    focusTarget: Boolean,
): BottomScrollAction {
    if (focusTarget) return BottomScrollAction.SKIP_AND_CONSUME
    if (reason == BottomRequestReason.INITIAL_OPEN && !sessionLoaded) {
        return BottomScrollAction.WAIT_FOR_DATA
    }
    val shouldScroll = isFollowing &&
        !isScrollInProgress &&
        !isUserDragging &&
        !sentinelVisible
    if (shouldScroll) {
        return if (hasRows) BottomScrollAction.SCROLL_TO_BOTTOM
        else BottomScrollAction.SKIP_AND_CONSUME
    }
    return BottomScrollAction.SKIP_AND_CONSUME
}
