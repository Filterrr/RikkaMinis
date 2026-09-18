package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-subagent-context-budget] Turn-granular context trimming for the
 * sub-agent's own model loop.
 *
 * Why this exists: the main loop guards its history with offload +
 * `trimContextHistoryWindow`, but [SubagentRunner] builds its own
 * `history: MutableList<LLMMessage>` that only ever grows. A long research
 * run (`general-agent` ships `max_turns: 48`) therefore hits the provider's
 * context wall and fails on a stream error instead of degrading gracefully.
 *
 * Shape of a sub-agent history (differs from the main chat!):
 *
 *     [0] USER     the task — ONE message, never re-sent
 *     [1] ASSISTANT  model output + tool_use parts
 *     [2] USER     tool_result carrier (NOT a real user prompt)
 *     [3] ASSISTANT  ...
 *     [4] USER     tool_result carrier
 *
 * So "turn" here means ONE ASSISTANT round plus the tool_result carriers that
 * follow it — the main loop's "starts at a real user prompt" rule would never
 * fire in this shape, because there is exactly one real user prompt at index 0.
 *
 * Invariants:
 *  - **Never split a tool_use / tool_result pair** — the unit of dropping is a
 *    whole assistant round, so the model never sees an orphan result.
 *  - **Index 0 (the task) is never dropped.** It is the only copy of the
 *    assignment; losing it turns the run into an unguided loop.
 *  - **The [keepRecentTurns] newest rounds always survive** — that region
 *    holds the active state of the task.
 *  - Trim to 95% of the window so a char-based under-estimate doesn't cross
 *    the real cap on the very request we're about to send.
 *  - Elision is announced to the model by appending a marker line to the task
 *    message, so it knows its earlier work was summarized away rather than
 *    never done.
 *
 * Estimation is deliberately crude (chars / 3.5); the API-reported
 * `latestContextTokens` wins when the caller has it, mirroring the main loop.
 */
object SubagentHistoryBudget {

    /** Headroom factor — see class doc. */
    private const val BUDGET_PERCENT = 95

    /** Rough chars-per-token, mirroring the main loop's estimator. */
    private const val CHARS_PER_TOKEN = 3.5

    /** Flat cost per inline image so a screenshot-heavy run still trims. */
    private const val IMAGE_TOKEN_GUESS = 1000

    /** Marker line appended to the task message when earlier rounds are dropped. */
    internal const val ELISION_PREFIX = "[earlier sub-agent rounds elided:"

    data class TrimResult(
        val messages: List<LLMMessage>,
        val droppedMessages: Int,
        val estimatedTokens: Int,
    ) {
        val didTrim: Boolean get() = droppedMessages > 0
    }

    /**
     * Return a history that fits [contextWindowTokens] (<= 0 disables
     * trimming). [apiContextTokens] is the last API-reported context size for
     * this run, 0 when unknown — when present it is trusted over the local
     * estimate.
     */
    fun trim(
        history: List<LLMMessage>,
        contextWindowTokens: Int,
        apiContextTokens: Int = 0,
        keepRecentTurns: Int = 4
    ): TrimResult {
        val estimate = estimateTokens(history)
        if (contextWindowTokens <= 0 || history.size < 3) {
            return TrimResult(history, 0, estimate)
        }
        val budget = contextWindowTokens.toLong() * BUDGET_PERCENT / 100
        val current = if (apiContextTokens > 0) apiContextTokens.toLong() else estimate.toLong()
        if (current <= budget) return TrimResult(history, 0, current.toInt())

        // Round start indices, ascending: every ASSISTANT message opens one.
        val roundStarts = history.indices.filter { history[it].role == LLMMessage.Role.ASSISTANT }
        if (roundStarts.isEmpty()) return TrimResult(history, 0, estimate)

        // [keepRecentTurns] is a FLOOR, not a target: we never keep fewer than
        // that many newest rounds even when the result still busts the window
        // (same semantics as the main loop's MIN_CONTEXT_TURNS_TO_KEEP —
        // over-shrinking would leave the model without the active task state,
        // which is worse than a context error the provider reports loudly).
        val keepFrom = (roundStarts.lastIndex - keepRecentTurns + 1).coerceAtLeast(0)
        val firstRound = roundStarts[keepFrom]
        val dropped = firstRound - 1
        if (dropped <= 0) return TrimResult(history, 0, estimate)

        val rebuilt = listOf(withElisionNote(history[0], dropped)) + history.subList(firstRound, history.size)
        return TrimResult(rebuilt, dropped, estimateTokens(rebuilt))
    }

    /** Index of the first message of the [keepTurns]-th round counting back. */
    internal fun roundBoundaryFromEnd(history: List<LLMMessage>, keepTurns: Int): Int {
        val starts = history.indices.filter { history[it].role == LLMMessage.Role.ASSISTANT }
        if (keepTurns <= 0 || starts.isEmpty()) return history.size
        val idx = starts.lastIndex - keepTurns + 1
        return if (idx < 0) 0 else starts[idx]
    }

    /** Attach an elision note to the task message so the model knows work was dropped. */
    private fun withElisionNote(task: LLMMessage, droppedMessages: Int): LLMMessage {
        if (droppedMessages <= 0) return task
        if (task.content.contains(ELISION_PREFIX)) return task
        val note = "\n\n$ELISION_PREFIX $droppedMessages earlier messages were trimmed to fit the " +
            "context window. Anything you already learned from them is no longer visible — " +
            "re-derive only what the current task still needs."
        return task.copy(content = task.content + note)
    }

    fun estimateTokens(messages: List<LLMMessage>): Int {
        var chars = 0
        for (msg in messages) {
            // A tool_result carrier duplicates its payload in BOTH `content`
            // (the "Result of <tool> (<id>):\n..." rendering the runner writes)
            // and the ToolResult part. Counting both would double every tool
            // output — exactly the messages that dominate a sub-agent history.
            val carriesToolResult = msg.contentParts.any { it is AgentContentPart.ToolResult }
            if (!carriesToolResult) chars += msg.content.length
            for (part in msg.contentParts) {
                chars += when (part) {
                    is AgentContentPart.Text -> part.text.length
                    is AgentContentPart.ToolUse -> part.input.toString().length
                    is AgentContentPart.ToolResult ->
                        // [T-subagent-vision] A tool_result can now carry an
                        // image (read_image / browser screenshot) whose BYTES
                        // dominate the part. Counting only `content` would let
                        // a screenshot-heavy run sail past the trimming
                        // threshold and die on the provider's real context
                        // limit instead of trimming gracefully — the exact
                        // failure this module exists to prevent. Estimated as a
                        // flat per-image cost, matching [IMAGE_TOKEN_GUESS]:
                        // the wire cost of an image is bounded by its
                        // dimensions after provider-side compression, not by
                        // its encoded byte length.
                        part.content.length + if (part.imageData != null) IMAGE_TOKEN_GUESS else 0
                    is AgentContentPart.ImageData -> IMAGE_TOKEN_GUESS
                }
            }
        }
        return (chars / CHARS_PER_TOKEN).toInt()
    }

    /**
     * [T-subagent-vision] Drop image BYTES from every tool_result that is NOT
     * among the [keepRecentTurns] newest rounds, replacing each with a text
     * placeholder that names its [AgentContentPart.ToolResult.imageLinuxPath]
     * so it stays re-fetchable with `read_image`.
     *
     * Why bytes need their own pass while text has [trim]: the two have
     * different blast radii. Dropping a text round is recoverable by
     * re-deriving; a dropped screenshot needs one cheap tool call, but an
     * UNBOUNDED history of them pins every decoded bitmap in the JVM heap for
     * the run's lifetime — the main loop learned this the hard way (a
     * 50-screenshot turn sequence ≈ 50–75 MB) and mirrors request-level
     * elision back into history for exactly this reason (see
     * ChatViewModel's [fix/history-bytes-offload]).
     *
     * The newest rounds keep their pixels: that region is the live task state,
     * and yanking an image the model just looked at would break the very
     * reasoning the image was fetched for. Returns the rebuilt history and how
     * many images were elided.
     */
    fun elideStaleImages(
        history: List<LLMMessage>,
        keepRecentTurns: Int = 4,
    ): Pair<List<LLMMessage>, Int> {
        if (history.isEmpty()) return history to 0
        val keepFrom = roundBoundaryFromEnd(history, keepRecentTurns)
        var elided = 0
        val rebuilt = history.mapIndexed { idx, msg ->
            if (idx >= keepFrom) return@mapIndexed msg
            if (msg.contentParts.none { it is AgentContentPart.ToolResult && it.imageData != null }) {
                return@mapIndexed msg
            }
            val parts = msg.contentParts.map { part ->
                if (part is AgentContentPart.ToolResult && part.imageData != null) {
                    elided++
                    part.copy(
                        content = part.content + "\n" + elidedImageNote(part.imageLinuxPath),
                        imageData = null,
                        imageMimeType = null,
                    )
                } else {
                    part
                }
            }
            msg.copy(contentParts = parts)
        }
        return rebuilt to elided
    }

    /**
     * Placeholder left in place of elided image bytes. Names the recovery
     * route — without a path the model can only guess what it was shown, and
     * a guess presented as a finding is the failure mode this subsystem's
     * report hygiene exists to prevent.
     */
    internal fun elidedImageNote(linuxPath: String?): String =
        if (linuxPath.isNullOrBlank()) {
            "[image dropped from older context to bound memory. Bytes are no longer addressable; " +
                "re-run the tool (read_image / browser_use screenshot) if you need to see it again.]"
        } else {
            "[image dropped from older context to bound memory. Original at $linuxPath — " +
                "re-read it with read_image if you need to see it again.]"
        }
}
