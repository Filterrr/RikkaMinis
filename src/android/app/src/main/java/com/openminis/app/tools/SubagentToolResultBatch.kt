package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [fix/subagent-parallel-results] The sub-agent's tool results must reach the
 * provider as ONE user message, the way the main loop sends them.
 *
 * THE BUG THIS CLOSES
 *
 * The runner appended one USER message PER tool call:
 *
 *     for ((call, result) in toolCalls.zip(results)) {
 *         history.add(LLMMessage(role = USER, contentParts = [ToolResult(call)]))
 *     }
 *
 * A turn with N parallel tool calls therefore produced N CONSECUTIVE user
 * messages. Two independent mechanisms then broke on that shape:
 *
 * 1. The provider-side `sanitizeToolPairing` (Anthropic / Gemini /
 *    Antigravity / the OpenAI family) answers an assistant's tool_use blocks
 *    from the IMMEDIATELY FOLLOWING user message only:
 *
 *        val next = messages.getOrNull(i + 1)
 *        val answeredIds = next?.contentParts
 *            ?.filterIsInstance<AgentContentPart.ToolResult>()
 *            ?.map { it.id }?.toSet() ?: emptySet()
 *        kept = msg.contentParts.filter { part ->
 *            part !is AgentContentPart.ToolUse || answeredIds.contains(part.id)
 *        }
 *
 *    With N separate user messages, only the FIRST result answers the
 *    assistant turn; the other N-1 tool_use blocks are stripped as orphans
 *    (and their results are stripped in turn). Measured on the real
 *    sanitizer: 3 parallel calls → 1 tool_use + 1 tool_result reach the
 *    model, 2 calls are silently dropped.
 *
 * 2. The runner's own per-turn repair pass (Pass 1) then saw the same shape
 *    and injected "Tool execution was interrupted" placeholders for the
 *    results that were no longer adjacent to their call. The model was told
 *    its tool calls had been interrupted, and never saw their output.
 *
 * The MAIN loop never hit either problem because it puts every result of a
 * turn into ONE user message (`contentParts = resultParts`) — the shape
 * `sanitizeToolPairing` documents and expects.
 *
 * THE FIX
 *
 * Merge the turn's tool results into a single user message. The wire format
 * is unchanged for every provider family:
 *
 * - OpenAI family: each ToolResult in a user message is emitted as its own
 *   `role:"tool"` message, so one user message with N results produces the
 *   same N tool messages as N user messages with one result each.
 * - Anthropic: a user message becomes a content array; N tool_result blocks
 *   in one array is the documented shape for a parallel tool turn.
 * - Gemini: a user message's parts become functionResponse parts; N of them
 *   in one user turn is the parallel shape.
 *
 * Pure + JVM-testable: no Android, no registry, no coroutines.
 */
object SubagentToolResultBatch {

    /**
     * Build the single user message that carries a turn's tool results.
     *
     * @param results the turn's tool calls paired with their results, in the
     *   order the model issued the calls. The order is preserved: providers
     *   match by id, but a stable order keeps the transcript readable.
     * @param contents the per-result text the model should see, in the SAME
     *   order as [results] (the runner folds the loop-detector's warning
     *   into it). Kept separate so this class stays free of the detector.
     * @return one USER message whose contentParts hold every ToolResult, or
     *   null when [results] is empty (nothing to send).
     */
    fun buildToolResultMessage(
        results: List<Pair<SubagentToolCall, ToolExecutionResult>>,
        contents: List<String>,
    ): LLMMessage? {
        if (results.isEmpty()) return null
        require(contents.size == results.size) {
            "contents (${contents.size}) must match results (${results.size})"
        }
        val parts = results.mapIndexed { idx, (call, result) ->
            AgentContentPart.ToolResult(
                id = call.id,
                name = call.name,
                content = contents[idx],
                isError = !result.success,
                imageData = result.imageData,
                imageMimeType = result.imageMimeType,
                imageLinuxPath = result.imageLinuxPath,
            )
        }
        // The `content` string is deliberately EMPTY. A tool_result carrier
        // duplicates its payload in `content`, and SubagentHistoryBudget
        // counts `content` ONLY for messages that carry no ToolResult part —
        // so a duplicated payload here would be counted twice against the
        // context budget. The payload lives in the ToolResult parts, which
        // every provider reads.
        return LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = parts,
        )
    }
}
