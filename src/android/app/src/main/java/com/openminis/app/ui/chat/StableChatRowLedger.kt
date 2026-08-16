package com.openminis.app.ui.chat

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

    /** Current published rows. Treat the result as immutable. */
    fun snapshot(): List<FlatChatItem> = rows.toList()

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
    }

    /**
     * Whether [messages] can be reconciled incrementally against the current
     * ledger state. False when the message list was structurally changed in
     * a way the append-only model cannot follow: a new head (load-older,
     * compact, deletion) or a truncated tail. Callers should then do a full
     * [seed] rebuild instead.
     */
    fun isIncrementallyCompatible(messages: List<ChatMessage>): Boolean {
        val head = headMessageId ?: return true
        if (messages.firstOrNull()?.id != head) return false
        return messages.size >= lastMessageIndex + 1
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
            ensureLastMessageSegmented(messages)
            return snapshot()
        }

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
            }
            lastMessageIndex = messages.size - 1
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
