package com.openminis.app.ui.chat

import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * Pure compaction-domain helpers extracted from [ChatViewModel.compactAll].
 *
 * These are deliberately side-effect-free and state-free: they take the
 * agent history (plus the caller's explicit inputs) and return an index.
 * This makes them trivially JVM-testable, and is the first step of the
 * FE-4 ChatViewModel split (route A: pull out pure logic before any
 * state-access interface is designed).
 *
 * Extracted verbatim from ChatViewModel.compactAll (fcf9470) — behavior is
 * byte-for-byte identical to the inline logic it replaces.
 */

/**
 * Resolves the compact anchor index within [history].
 *
 * Mirrors iOS `AIChatViewModel+Compaction.swift` tail-walk-back logic:
 *
 *  - When [anchorIdxOverride] is supplied (compact-before path), walk back
 *    from that index to the closest entry with a non-empty [LLMMessage.dbMessageId],
 *    bounded to `[0..override]`.
 *  - Otherwise (compact-all path), walk back from the tail to the closest
 *    persisted entry, then apply the `[Compact-keep-answer-active]` rule:
 *    fall back to the last persisted USER prompt (skipping pure tool-result
 *    entries) so the just-delivered answer stays in the active region.
 *
 * @return the anchor index, or -1 when no persisted message exists.
 */
fun resolveCompactAnchorIdx(
    history: List<LLMMessage>,
    anchorIdxOverride: Int?,
): Int {
    if (history.isEmpty()) return -1
    return if (anchorIdxOverride != null) {
        // compactBefore() supplied a specific anchor — walk back from
        // there to the closest entry with a dbMessageId.
        var i = anchorIdxOverride.coerceIn(0, history.lastIndex)
        while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
        i
    } else {
        // compactAll() — walk back from the tail to the closest
        // persisted entry.
        var i = history.lastIndex
        while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
        // [Compact-keep-answer-active] keep the answer OUTSIDE the compaction
        // — fall back to the last persisted USER prompt so everything after it
        // (tool work + the final answer) stays in the active, un-grayed region.
        // Skip pure tool-result entries (role=USER but contentParts all ToolResult).
        if (i > 0) {
            while (i >= 0 && (history[i].role != LLMMessage.Role.USER ||
                    history[i].contentParts.all { p -> p is AgentContentPart.ToolResult } ||
                    history[i].dbMessageId.isNullOrEmpty())
            ) i -= 1
        }
        i
    }
}

/**
 * Resolves the compact range's starting index (inclusive) from the previous
 * compact marker.
 *
 *  - `null` marker → start at 0.
 *  - v2 marker → prev anchor is [CompactMarkerEntity.lastCompactedMessageId];
 *    start at `prevIdx + 1` (anchor was inclusive).
 *  - v1 marker → prev anchor is `firstKeptMessageId ?: boundaryMessageId`;
 *    start AT `prevIdx` (right edge was exclusive).
 *  - prev anchor not present in current history → restart from top (0).
 *
 * @return the effective start index (>= 0).
 */
fun resolveCompactStartIdx(
    history: List<LLMMessage>,
    prevMarker: CompactMarkerEntity?,
): Int {
    if (prevMarker == null) return 0
    val prevAnchorOrFirstKept: String? = if (prevMarker.version >= 2) {
        prevMarker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
    } else {
        prevMarker.firstKeptMessageId?.takeIf { it.isNotEmpty() }
            ?: prevMarker.boundaryMessageId?.takeIf { it.isNotEmpty() }
    }
    val prevIdx = prevAnchorOrFirstKept?.let { id ->
        history.indexOfFirst { it.dbMessageId == id }
    } ?: -1
    return if (prevIdx < 0) 0   // prev anchor not in current history — restart from top
    else if (prevMarker.version >= 2) prevIdx + 1
    else prevIdx
}
