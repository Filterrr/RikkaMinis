package com.openminis.app.ui.chat

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * LazyColumn stops running its drag handler once canScrollForward/Backward
 * are both false, so the platform stretch overscroll never fires when the
 * list content fits the viewport. Wrap the list in this box to restore it:
 * the outer scrollable always accepts gestures, consumes 0, and pipes the
 * delta into a shared stretch effect. The inner LazyColumn, given the same
 * effect, forwards its own overflow too.
 *
 * Usage:
 *   AlwaysStretchOverscrollBox { effect ->
 *     LazyColumn(overscrollEffect = effect, ...) { ... }
 *   }
 */
@Composable
fun AlwaysStretchOverscrollBox(
    modifier: Modifier = Modifier,
    content: @Composable (OverscrollEffect?) -> Unit,
) {
    val effect = rememberOverscrollEffect()
    val noopState = rememberScrollableState { 0f }
    val boxModifier = if (effect != null) {
        modifier
            .fillMaxSize()
            .scrollable(
                state = noopState,
                orientation = Orientation.Vertical,
                overscrollEffect = effect,
            )
            .overscroll(effect)
    } else {
        modifier.fillMaxSize()
    }
    Box(modifier = boxModifier) {
        content(effect)
    }
}

/**
 * "Hit the bottom edge" gesture detector — the single trigger for the
 * stickToBottom state machine.
 *
 * The user's chosen semantic is: *swiping/scrolling until the viewport can
 * go no further toward the newest message engages follow; scrolling away
 * from it disengages*. This is the active-intent complement to the passive
 * position gates (userScrolledAway / isNearBottom / firstVisibleItemIndex)
 * that were the root cause of the reverseLayout "jump" class of bugs — any
 * of those flips on content insertion even when the user never gestured,
 * so they re-yank a reader back to the bottom.
 *
 * We hook [NestedScrollConnection.onPostScroll]: nested scroll is dispatched
 * ONLY while a gesture is in flight (finger/fling), never by programmatic
 * scrollToItem/scrollBy — so a non-zero [available] here means the user
 * genuinely pushed against an edge during a gesture, regardless of how much
 * content was inserted concurrently underneath them.
 *
 * `atBottomEdge` is the caller-supplied, battle-tested position check
 * (`isNearBottom` == firstVisibleItemIndex==0 within threshold): when the
 * viewport is already at the bottom AND a UserInput gesture still produces
 * unconsumed vertical delta, the ONLY place the user could be pushing is the
 * physical bottom edge — so the leftover's sign is irrelevant under
 * reverseLayout, and we can avoid any sign-derivation risk (the class of
 * subtle bugs the earlier patch rounds kept hitting). When the viewport is
 * NOT at the bottom, a leftover simply means smooth-scrolling overshoot or
 * a mid-list drag punch — never a bottom edge — so we ignore it.
 */
internal class BottomEdgeDetector(
    private val atBottomEdge: () -> Boolean,
    private val onHitBottom: () -> Unit,
) : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (available.y == 0f) return Offset.Zero
        if (atBottomEdge()) {
            onHitBottom()
        }
        return Offset.Zero
    }
}

/**
 * Composable creator for the [BottomEdgeDetector], remembered across
 * recompositions. The detector holds no state itself — it only forwards the
 * edge-hit signal to the caller-owned `stickToBottom` state.
 */
@Composable
internal fun rememberBottomEdgeDetector(
    atBottomEdge: () -> Boolean,
    onHitBottom: () -> Unit,
): NestedScrollConnection {
    return remember(atBottomEdge, onHitBottom) { BottomEdgeDetector(atBottomEdge, onHitBottom) }
}