package com.openminis.app.ui.chat

import androidx.annotation.VisibleForTesting

/**
 * Session-lifetime stable row ledger for the chat transcript.
 *
 * Lifecycle: one instance per [ChatViewModel] lifetime (survives forward/back
 * navigation together with the LazyListState). Dropped when the VM dies — the
 * next cold open rebuilds rows from persisted messages via the canonical
 * [buildFlatChatItems].
 *
 * The contract (the reason this class exists):
 *  - Once a row key has been published it is NEVER deleted or reordered while
 *    its message stays the active turn. Only prefix appends happen.
 *  - New user/assistant messages append their rows at the tail.
 *  - The active assistant message's text is split by [AppendOnlyMarkdownSegmenter]
 *    per text block: settled fragments freeze forever, only the live tail slot
 *    grows. Stream end only settles — no re-split, no coalesce, no key churn.
 *  - thinking / tool / info / error rows are appended in FIRST-APPEARANCE
 *    order and never re-inserted by the fixed pass order of the builder.
 *  - Transient rows (typing indicator, error banner) may be dropped when they
 *    disappear — they live at the absolute tail and their removal does not
 *    move any anchored row. This is the single documented exception.
 *  - A retry / edit / resume that rewrites the turn's text-block structure
 *    (block ids changed) resets only THAT message's text rows.
 *
 * All methods are pure state transitions over [FlatChatItem] / [ChatMessage] —
 * no Android dependencies, fully JVM-testable.
 */
internal class StableChatRowLedger {

    private val rows = mutableListOf<FlatChatItem>()
    private var lastMessageIndex = -1

    /** Id of messages[0] at seed / first reconcile — the incremental-append
     *  contract only holds while the HEAD is unchanged (load-older prepends
     *  or a message deletion invalidates it and forces a full rebuild). */
    private var headMessageId: String? = null

    /** messageId → (textBlockId → segmenter). Cleared per message on text reset. */
    private val segmenters = mutableMapOf<String, MutableMap<String, AppendOnlyMarkdownSegmenter>>()

    /**
     * messageIds whose text rows are segmenter-owned (mdslot-style mdblock
     * rows produced inside [reconcileMessage]). Attached on first text-block
     * update; detached on text-reset. This is the authoritative "already
     * segmented" signal — key prefixes are NOT a reliable discriminator
     * because AssistantMarkdownBlock.key is fixed to "mdblock:...".
     */
    private val segmentedMessages = mutableSetOf<String>()

    /**
     * The ordered ids of messages already published by [seed] / [reconcile],
     * so far. [isIncrementallyCompatible] matches the FULL published prefix
     * against the latest canonical list — not just the head and a count — so
     * a same-index replacement or mid-list deletion that keeps count and head
     * unchanged still forces a reseed instead of silently leaving stale rows.
     */
    private val reconciledMessageIds = mutableListOf<String>()

    /**
     * messageIds of assistant messages that are still "live" in the UI
     * (streaming / awaiting model response / a RUNNING tool block). Once a
     * turn is no longer the last message it still gets status-synced against
     * the latest canonical snapshot so stale RUNNING/typing state is drained;
     * when the terminal state is written the id leaves this set.
     */
    private val activeAssistantIds = mutableSetOf<String>()

    /** Test-only: count of tracked live assistant message ids. */
    @VisibleForTesting internal fun activeAssistantIdsCount(): Int = activeAssistantIds.size

    /** Current published rows. Treat the result as immutable. */
    fun snapshot(): List<FlatChatItem> = rows.toList()

    /**
     * Whether [messages] can be reconciled incrementally against the current
     * ledger state. False when the message list was structurally changed in
     * a way the append-only model cannot follow: a new head (load-older,
     * compact, deletion), a truncated tail, or a same-index replacement
     * within the already-published prefix (queued placeholder → real user
     * message, or an interrupted assistant message dropped/reshaped).
     */
    fun isIncrementallyCompatible(messages: List<ChatMessage>): Boolean {
        val head = headMessageId ?: return true
        if (messages.firstOrNull()?.id != head) return false
        if (messages.size < reconciledMessageIds.size) return false
        // Full prefix match — catches same-index replacement / mid-list
        // deletion the head+count check alone cannot see.
        for (i in reconciledMessageIds.indices) {
            if (messages[i].id != reconciledMessageIds[i]) return false
        }
        return true
    }

    /**
     * Cold-open seed: hand over the canonical full build (callers run
     * [buildFlatChatItems] over the full history, including coalescing for
     * historical messages). [messageCount] anchors the incremental reconcile.
     */
    fun seed(initialRows: List<FlatChatItem>, messageCount: Int) {
        rows.clear()
        rows.addAll(initialRows)
        lastMessageIndex = messageCount - 1
        headMessageId = null // caller passes the new head on next reconcile
        segmenters.clear()
        activeAssistantIds.clear()
        // Reconcile re-derives the prefix (first reconcile after seed anchors
        // the head and snapshots the published ids).
        reconciledMessageIds.clear()
    }

    /**
     * Incremental reconcile against the latest canonical message list.
     * - Appends rows for messages beyond the last reconciled index.
     * - Reconciles the LAST message in place when it is an assistant message
     *   (live-tail text via segmenters; tool/thinking/info/error updates in
     *   place; new rows appended in first-appearance order).
     *
     * @return the refreshed snapshot (same as calling [snapshot]).
     */
    fun reconcile(messages: List<ChatMessage>): List<FlatChatItem> {
        if (messages.isEmpty()) return snapshot()
        if (lastMessageIndex < 0) {
            // No seed provided — fall back to a full canonical build.
            rows.clear()
            rows.addAll(buildFlatChatItems(messages))
            lastMessageIndex = messages.size - 1
            headMessageId = messages.firstOrNull()?.id
            reconciledMessageIds.clear()
            reconciledMessageIds.addAll(messages.map { it.id })
            seedActiveAssistantIds(messages)
            ensureLastMessageSegmented(messages)
            return snapshot()
        }

        // Anchor the head on the first incremental reconcile after seed().
        // seed() deliberately nulls it ("caller passes the new head on next
        // reconcile") but nothing ever did — so isIncrementallyCompatible()
        // always returned true and structural changes (an interrupted
        // assistant message dropped by handleUserCancelledCleanup, deletions,
        // truncation) were silently reconciled as tail appends, leaving stale
        // rows behind (e.g. a thinking placeholder whose message is gone).
        if (headMessageId == null) {
            headMessageId = messages.firstOrNull()?.id
            // First reconcile after a cold-open seed: the already-published
            // prefix is the full current history. Snapshot it so the prefix
            // check below is meaningful from the very first incremental step.
            reconciledMessageIds.clear()
            reconciledMessageIds.addAll(messages.take(lastMessageIndex + 1).map { it.id })
            seedActiveAssistantIds(messages)
        }

        // Sync queued→sent flips on already-published user rows. A message
        // published as isQueued=true (dashed bubble) keeps its id when the
        // prompt queue drains — only the flag flips. Count and head stay
        // identical, so neither the append branch nor
        // isIncrementallyCompatible() notices; the row would keep its dashed
        // styling forever.
        syncQueuedFlips(messages)

        // Sync stale live-state (RUNNING tool pills / typing / awaiting)
        // of assistant messages that were once the active turn but have since
        // become HISTORY (a new user turn was appended below them by
        // enqueuePrompt → injectQueuedPromptsAsNewTurn). Their canonical rows
        // reflect the terminal state (tool SUCCESS/FAILED/CANCELLED,
        // isStreaming=false), and the viewport froze on the old snapshot, so
        // without this pass a "thinking…"/"Running" ghost stays pinned to a
        // finished turn (the interrupted-turn leftover). Only rows belonging
        // to those specific messages are touched — everything else is left
        // untouched to preserve stable scrolling.
        syncActiveAssistantStatus(messages)

        // 1. Append rows for brand-new messages (tail), keeping neighbour
        //    lookbacks (precededByUser / resume header suppression) correct.
        //    Text rows of new assistant messages are NOT taken from the
        //    builder — they go through the segmenters inside
        //    reconcileMessage so their keys are stable mdslot keys.
        if (messages.size > lastMessageIndex + 1) {
            for (idx in lastMessageIndex + 1 until messages.size) {
                val msg = messages[idx]
                val prevRole = prevNonSystemRole(messages, idx)
                if (msg.role == "assistant") {
                    val fresh = buildNewMessageRows(msg, prevRole)
                    val nonText = fresh.filterNot { it is FlatChatItem.AssistantMarkdownBlock }
                    rows.addAll(nonText)
                    reconcileMessage(msg, prevRole)
                } else {
                    rows.addAll(buildNewMessageRows(msg, prevRole))
                }
                if (isLiveAssistant(msg)) activeAssistantIds.add(msg.id)
                reconciledMessageIds.add(msg.id)
            }
            lastMessageIndex = messages.size - 1
            pruneActiveAssistantIds(messages)
            return snapshot()
        }

        // 2. Live-tail reconcile of the last message when it is an assistant
        //    message (streaming or a freshly-finished turn).
        ensureLastMessageSegmented(messages)
        return snapshot()
    }

    /**
     * The LAST assistant message's text rows are always owned by segmenters
     * (mdslot keys) — it is the candidate live turn. Everything before it is
     * canonical history (mdblock keys, coalesced) and stays frozen.
     */
    private fun ensureLastMessageSegmented(messages: List<ChatMessage>) {
        val last = messages.lastOrNull() ?: return
        if (last.role != "assistant") return
        reconcileMessage(last, prevNonSystemRole(messages, messages.lastIndex))
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun buildNewMessageRows(
        message: ChatMessage,
        prevNonSystemRole: String?,
    ): List<FlatChatItem> {
        // NOTE: deliberately NO seedKeys passed to buildFlatChatItems. The
        // dedupe suffixing (key#2) must only guard against collisions WITHIN
        // this build; the ledger's own published keys are the merge target
        // (same message id → same keys expected), not collisions to avoid.
        val built = buildFlatChatItems(listOf(message), seedKeys = emptySet())
        val suppressHeader = message.role == "assistant" && prevNonSystemRole == "assistant"
        return built.mapNotNull { row ->
            when {
                row is FlatChatItem.AssistantHeader && suppressHeader -> null
                row is FlatChatItem.UserBubble && prevNonSystemRole == "user" && !row.precededByUser ->
                    // UserBubble is a plain class (no copy) — reconstruct.
                    FlatChatItem.UserBubble(row.message, precededByUser = true)
                else -> row
            }
        }
    }

    private fun prevNonSystemRole(messages: List<ChatMessage>, index: Int): String? {
        for (i in index - 1 downTo 0) {
            if (messages[i].role != "system") return messages[i].role
        }
        return null
    }

    /**
     * In-place update of already-published [FlatChatItem.UserBubble] rows whose
     * message's queued state flipped (enqueuePrompt publishes isQueued=true,
     * drainQueuedPrompts flips it to false on the same message id). Cheap: only
     * runs when user bubbles exist and compares one boolean per row.
     */
    private fun syncQueuedFlips(messages: List<ChatMessage>) {
        if (rows.none { it is FlatChatItem.UserBubble }) return
        val freshById = messages.associateBy { it.id }
        for (i in rows.indices) {
            val row = rows[i]
            if (row is FlatChatItem.UserBubble) {
                val fresh = freshById[row.message.id] ?: continue
                if (fresh.isQueued != row.message.isQueued) {
                    rows[i] = FlatChatItem.UserBubble(fresh, row.precededByUser)
                }
            }
        }
    }

    /**
     * Harvest the set of assistant messages that are currently "live" in the
     * UI (streaming / awaiting model response / any RUNNING tool) so that
     * [syncActiveAssistantStatus] keeps them updated even once they stop being
     * the last message (a queued prompt appended a new user turn below them).
     * Runs on seed / the first reconcile after a cold-open seed.
     */
    private fun seedActiveAssistantIds(messages: List<ChatMessage>) {
        activeAssistantIds.clear()
        for (msg in messages) {
            if (msg.role != "assistant") continue
            if (isLiveAssistant(msg)) activeAssistantIds.add(msg.id)
        }
    }

    /**
     * Drop ids of messages that moved to history and whose headline states are
     * terminal. Kept tiny so the status-sync pass stays O(active rows).
     */
    private fun pruneActiveAssistantIds(messages: List<ChatMessage>) {
        if (activeAssistantIds.isEmpty()) return
        val byId = messages.associateBy { it.id }
        activeAssistantIds.removeAll { id ->
            val msg = byId[id]
            msg == null || !isLiveAssistant(msg) || !hasLiveRowsOwnedBy(msg.id)
        }
    }

    private fun isLiveAssistant(msg: ChatMessage): Boolean =
        msg.isStreaming || msg.isAwaitingModelResponse ||
            msg.toolBlocks.any { it.toolStatus == ToolBlockStatus.STREAMING ||
                it.toolStatus == ToolBlockStatus.PENDING ||
                it.toolStatus == ToolBlockStatus.RUNNING }

    private fun hasLiveRowsOwnedBy(messageId: String): Boolean =
        rows.any {
            it.owningMessageId() == messageId &&
                (it is FlatChatItem.AssistantTyping ||
                    (it is FlatChatItem.AssistantToolRunGroup && it.isRunning))
        }

    /**
     * In-place sync of stale live-state rows (thinking placeholder, RUNNING
     * tool pills) of assistant messages that became history — the
     * interrupted-turn leftover. For each such message, compare its published
     * rows against the canonical fresh rows of the SAME message and replace
     * on the same keys, WITHOUT touching rows of other messages, re-segmenting
     * published markdown, or reordering anything — so stable scrolling is
     * preserved. Rows that legitimately disappear from canonical history
     * (typing placeholder) are dropped as transient.
     */
    private fun syncActiveAssistantStatus(messages: List<ChatMessage>) {
        if (activeAssistantIds.isEmpty()) return
        val freshById = messages.associateBy { it.id }
        for (messageId in activeAssistantIds.toList()) {
            val freshMsg = freshById[messageId] ?: continue
            val start = rows.indexOfFirst { it.owningMessageId() == messageId }
            if (start < 0) continue
            val freshAll = buildNewMessageRows(freshMsg, null)
            val freshByKey = freshAll.associateBy { it.key }
            var rowsTouched = false
            for (i in start until rows.size) {
                val row = rows[i]
                if (row.owningMessageId() != messageId) break
                // Never touch frozen text rows: their segmenter-owned content
                // is authoritative. Only sync live-status rows that flip a
                // boolean/tool state (RUNNING→终态, typing→gone).
                if (row is FlatChatItem.AssistantMarkdownBlock) continue
                if (row is FlatChatItem.UserBubble) continue
                val fresh = freshByKey[row.key] ?: continue
                if (!sameLiveView(row, fresh)) {
                    rows[i] = fresh
                    rowsTouched = true
                    if (fresh is FlatChatItem.AssistantToolRunGroup && !fresh.isRunning) {
                        activeAssistantIds.remove(messageId)
                    }
                }
            }
            // If the message has fully converged (no live state), do a full
            // replacement of ALL rows owned by this message — including
            // text/markdown blocks that the live pass deliberately skips.
            // This catches the "interrupted thinking" leftover: the stream is
            // torn down, isStreaming flips to false, but the old thinking
            // markdown block stays in the ledger because the main reconcile
            // append branch didn't run (message count unchanged) and the live
            // pass skips markdown rows. Once converged, the canonical fresh
            // rows ARE the terminal truth (thinking collapsed to a stopped
            // summary, tools flipped to CANCELLED), so swap in the full set
            // regardless of whether the live pass touched anything.
            if (!isLiveAssistant(freshMsg)) {
                // Check whether the published rows actually differ from the
                // canonical ones — if they're already identical, skip.
                val publishedEnd = start + rows.subList(start, rows.size)
                    .takeWhile { it.owningMessageId() == messageId }.size
                val publishedTake = rows.subList(start, publishedEnd)
                if (publishedTake != freshAll) {
                    rows.subList(start, publishedEnd).clear()
                    rows.addAll(start, freshAll)
                }
                activeAssistantIds.remove(messageId)
            } else if (!rowsTouched) {
                // Canonical has no live rows for it anymore — drop the stale
                // typing placeholder if any.
                var removed = false
                val itr = rows.listIterator(start)
                while (itr.hasNext()) {
                    val row = itr.next()
                    if (row.owningMessageId() != messageId) break
                    if (row is FlatChatItem.AssistantTyping) {
                        itr.remove(); removed = true
                    }
                }
                if (removed) activeAssistantIds.remove(messageId)
            }
        }
    }

    /** Whether two rows that share a key differ in their live-state fields. */
    private fun sameLiveView(a: FlatChatItem, b: FlatChatItem): Boolean = when {
        a is FlatChatItem.AssistantToolRunGroup && b is FlatChatItem.AssistantToolRunGroup ->
            a.isRunning == b.isRunning && a.tools == b.tools
        else -> a == b
    }

    private fun reconcileMessage(message: ChatMessage, prevNonSystemRole: String?) {
        val start = rows.indexOfFirst { it.owningMessageId() == message.id }
        if (start < 0) {
            // Defensive: no published rows yet (shouldn't happen — new messages
            // go through the append path). Build and append.
            rows.addAll(buildNewMessageRows(message, prevNonSystemRole))
            return
        }
        val freshAll = buildNewMessageRows(message, prevNonSystemRole)
        val freshNonText = freshAll.filterNot { it is FlatChatItem.AssistantMarkdownBlock }

        // ── typing indicator retirement ──
        // The freshly-built canonical rows carry NO AssistantTyping row once
        // any visible content exists (buildFlatChatItems emits it only for
        // the `isStreaming && !hasVisibleContent` window). If the message
        // already shows content, a previously-published typing row is now a
        // redundant "thinking…" under the answer text — retire it here so it
        // never lingers through the isAwaitingModelResponse gap.
        if (freshAll.none { it is FlatChatItem.AssistantTyping }) {
            val itr = rows.listIterator(start)
            while (itr.hasNext()) {
                if (itr.next() is FlatChatItem.AssistantTyping) itr.remove()
            }
        }

        // ── text-structure reset detection (first segmenter attach / retry) ──
        val currentTextIds = message.toolBlocks
            .filter { it.kind == "text" && it.content.isNotEmpty() }
            .map { it.id }
        val publishedTextIds = rows.subList(start, rows.size)
            .filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
            .map { it.parentBlockId }
            .distinct()
        // The message's text rows are segmenter-owned iff this ledger already
        // attached a segmenter for it (see [segmentedMessages]). Until then
        // (cold-open canonical build) they are plain canonical mdblock rows
        // that must be replaced on first attach. Key prefixes are NOT a
        // reliable discriminator: AssistantMarkdownBlock.key is fixed to
        // "mdblock:..." for both canonical and segmenter-owned rows.
        val textReset = message.id !in segmentedMessages || currentTextIds != publishedTextIds
        if (textReset) {
            segmenters.remove(message.id)
            segmentedMessages.remove(message.id)
            val itr = rows.listIterator(start)
            while (itr.hasNext()) {
                if (itr.next() is FlatChatItem.AssistantMarkdownBlock) itr.remove()
            }
        }

        val oldKeys = rows.subList(start, rows.size).map { it.key }.toSet()

        // ── pass 1+2: non-text rows — update in place, append new ──
        val freshNonTextByKey = freshNonText.associateBy { it.key }
        for (i in start until rows.size) {
            freshNonTextByKey[rows[i].key]?.let { rows[i] = it }
        }
        for (row in freshNonText) {
            if (row.key !in oldKeys) rows.add(row)
        }

        // ── pass 3: text rows via per-block segmenters ──
        val joinedMarkdown = joinedMarkdownFor(message)
        val textBlocks = message.toolBlocks.filter { it.kind == "text" && it.content.isNotEmpty() }
        if (textBlocks.isNotEmpty()) {
            // Segmenter ownership begins now: subsequent ticks reuse the
            // segmenter (no text reset) until block ids change.
            segmentedMessages.add(message.id)
        }
        for (block in textBlocks) {
            val seg = segmenters.getOrPut(message.id) { mutableMapOf() }
                .getOrPut(block.id) { AppendOnlyMarkdownSegmenter() }
            val slots = seg.update(block.content, streamEnded = !message.isStreaming)
            val isLastTextBlock = block.id == textBlocks.lastOrNull()?.id
            val freshTextRows = slots.map { slot ->
                FlatChatItem.AssistantMarkdownBlock(
                    messageId = message.id,
                    parentBlockId = block.id,
                    rawText = slot.rawText,
                    blockIndex = slot.ordinal,
                    isLastBlockOfMessage = message.isStreaming && isLastTextBlock && slot.ordinal == slots.last().ordinal,
                    messageIsStreaming = message.isStreaming && isLastTextBlock,
                    messageMarkdown = joinedMarkdown,
                )
            }
            val publishedTextKeys = rows.subList(start, rows.size)
                .filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
                .map { it.key }
                .toSet()
            val freshTextByKey = freshTextRows.associateBy { it.key }
            for (i in start until rows.size) {
                val row = rows[i]
                if (row is FlatChatItem.AssistantMarkdownBlock) {
                    freshTextByKey[row.key]?.let { rows[i] = it }
                }
            }
            for (row in freshTextRows) {
                if (row.key !in publishedTextKeys) rows.add(row)
            }
        }

        // ── pass 4: drop transient rows that no longer appear ──
        val freshKeys = freshAll.mapTo(HashSet()) { it.key }
        val itr = rows.listIterator(start)
        while (itr.hasNext()) {
            val row = itr.next()
            if (row.key in freshKeys) continue
            when (row) {
                // Text rows are fully owned by segmenters — never touched here.
                is FlatChatItem.AssistantMarkdownBlock -> Unit
                // Transient tail rows may disappear (typing → first content,
                // error → retry). Documented exception to append-only.
                is FlatChatItem.AssistantTyping,
                is FlatChatItem.AssistantError,
                -> itr.remove()
                else -> Unit // anything else stays — append-only invariant
            }
        }
    }

    private fun joinedMarkdownFor(message: ChatMessage): String {
        val parts = message.toolBlocks
            .filter { it.kind == "text" && it.content.isNotEmpty() }
            .joinToString("\n\n") { it.content }
        return if (parts.isNotEmpty()) parts else message.content
    }
}
