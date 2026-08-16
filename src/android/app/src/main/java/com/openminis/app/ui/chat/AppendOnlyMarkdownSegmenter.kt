package com.openminis.app.ui.chat

/**
 * One stable markdown fragment slot produced by [AppendOnlyMarkdownSegmenter].
 *
 * Invariants (the whole point of the segmenter):
 *  - A [settled] slot's `rawText` NEVER changes after publication.
 *  - The live (non-settled) slot is always the LAST slot; it may only grow
 *    (append) while the stream is producing content.
 *  - `ordinal` is a monotonic per-message sequence number — it is the stable
 *    part of the row key (`mdslot:<messageId>:<blockId>:<ordinal>`) so
 *    LazyColumn anchors never see keys deleted or reordered mid-stream.
 */
data class StableMarkdownSlot(
    val ordinal: Int,
    val rawText: String,
    val settled: Boolean,
)

/**
 * Append-only markdown fragment segmenter for a single streaming text block.
 *
 * Replaces the "re-split + coalesce on every tick / at stream end" behaviour
 * of [buildFlatChatItems] for the LIVE tail of an active assistant turn.
 *
 * The contract:
 *  - [update] takes the cumulative markdown snapshot of ONE text block plus a
 *    `streamEnded` flag. It returns the current list of stable slots.
 *  - Published (settled) slots are immutable: once a fragment boundary has
 *    closed them they are never modified, never deleted, never reordered.
 *  - New fragment boundaries only append NEW slots at the tail; the previous
 *    live slot is settled in place with its content frozen.
 *  - A regressive snapshot (shorter + prefix — see
 *    [shouldIgnoreRegressiveStreamingSnapshot]) is ignored entirely.
 *  - Any other non-append divergence (the model rewrote earlier text) cannot
 *    be repaired by key churn: the fresh content is absorbed into the live
 *    slot and [invariantErrorCount] is incremented. Published keys stay put.
 *  - `streamEnded=true` only settles the last slot — it does NOT re-split,
 *    does NOT coalesce, does NOT change keys.
 *
 * The split boundary rules deliberately match [splitMarkdownIntoBlockTexts]:
 * blank lines outside code fences and complete fenced code blocks are
 * closable boundaries; an unterminated fence keeps everything inside the live
 * slot.
 */
class AppendOnlyMarkdownSegmenter {

    private val slots = mutableListOf<StableMarkdownSlot>()
    private var lastCumulativeText: String = ""

    /** Number of times a non-append divergence was absorbed instead of re-keyed. */
    var invariantErrorCount: Int = 0
        private set

    /** Current stable slot list. Callers should treat this as immutable. */
    fun snapshot(): List<StableMarkdownSlot> = slots.toList()

    /**
     * Advance the segmenter with the latest cumulative text snapshot.
     *
     * @return the current stable slot list (same instance semantics as
     *         [snapshot], refreshed).
     */
    fun update(cumulativeText: String, streamEnded: Boolean): List<StableMarkdownSlot> {
        // Empty snapshot or a regressive one (shorter, same prefix) can never
        // change published slots. streamEnded may still settle the live slot.
        if (cumulativeText.isEmpty() ||
            shouldIgnoreRegressiveStreamingSnapshot(lastCumulativeText, cumulativeText)
        ) {
            if (streamEnded) settleLiveSlot()
            return snapshot()
        }
        lastCumulativeText = cumulativeText

        val fresh = splitMarkdownIntoBlockTexts(cumulativeText)
        if (fresh.isEmpty()) {
            // Defensive: splitter returned nothing for non-empty input.
            if (streamEnded) settleLiveSlot()
            return snapshot()
        }

        // ── 1. Match the published settled prefix against the fresh split ──
        // The splitter is deterministic: as long as the cumulative text only
        // grows, the first N fragments are byte-identical to the previously
        // published settled slots. Any mismatch is a non-append divergence.
        val settledCount = slots.count { it.settled }
        var matched = 0
        while (matched < settledCount &&
            matched < fresh.size &&
            slots[matched].rawText == fresh[matched]
        ) {
            matched++
        }

        if (matched < settledCount) {
            // Non-append divergence: earlier fragments changed. Published
            // slots are frozen — absorb the whole divergent tail into the
            // live slot instead of re-keying.
            invariantErrorCount++
            return absorbDivergence(fresh, matched, streamEnded)
        }

        // ── 2. Prefix OK — rebuild slots = frozen prefix + fresh tail ──
        val remaining = fresh.subList(settledCount, fresh.size)
        val rebuilt = ArrayList<StableMarkdownSlot>(settledCount + remaining.size)
        for (i in 0 until settledCount) rebuilt.add(slots[i]) // frozen, reused by reference
        for (i in remaining.indices) {
            val isLast = i == remaining.lastIndex
            rebuilt.add(
                StableMarkdownSlot(
                    ordinal = settledCount + i,
                    rawText = remaining[i],
                    // streamEnded settles even the last one; otherwise only
                    // the tail fragment stays live (and may grow next tick).
                    settled = streamEnded || !isLast,
                )
            )
        }
        slots.clear()
        slots.addAll(rebuilt)
        return snapshot()
    }

    /**
     * Divergence path: keep every published settled slot (content frozen),
     * and absorb ALL remaining fresh fragments into the live slot joined by
     * blank lines so no content is lost. No new settled slots are created
     * (that would change the key set), and the live slot's key stays stable.
     */
    private fun absorbDivergence(fresh: List<String>, fromIndex: Int, streamEnded: Boolean): List<StableMarkdownSlot> {
        val settledCount = slots.count { it.settled }
        val rebuilt = ArrayList<StableMarkdownSlot>(settledCount + 1)
        for (i in 0 until settledCount) rebuilt.add(slots[i])
        val liveText = if (fromIndex < fresh.size) {
            fresh.subList(fromIndex, fresh.size).joinToString("\n\n")
        } else {
            // Divergence shrank the tail below the settled prefix: keep the
            // previous live content (nothing sensible to absorb).
            slots.lastOrNull()?.rawText.orEmpty()
        }
        rebuilt.add(
            StableMarkdownSlot(
                ordinal = settledCount,
                rawText = liveText,
                settled = streamEnded,
            )
        )
        slots.clear()
        slots.addAll(rebuilt)
        return snapshot()
    }

    private fun settleLiveSlot() {
        if (slots.isEmpty()) return
        val lastIdx = slots.lastIndex
        if (!slots[lastIdx].settled) {
            slots[lastIdx] = slots[lastIdx].copy(settled = true)
        }
    }
}
