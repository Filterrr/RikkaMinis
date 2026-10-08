package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * Defense-in-depth sanitizers for outbound provider payloads (D-class in the
 * request-construction error audit, 2026-08-14).
 *
 * Upstream (ChatViewModel.effectiveAgentHistory + compact slicing + per-turn
 * sanitizeAgentHistoryMessages) is the FIRST line of defense; these are the
 * LAST line, applied at serialization time so a broken history can never reach
 * the wire as a deterministic 400 — even when a caller bypasses the upstream
 * layers (synthesized requests, sub-agent flows, fallback paths).
 *
 * All functions are pure JVM (no Android dependencies) and operate on copies —
 * the caller's stored history is never mutated.
 */

/**
 * Cap a requested `max_tokens` into [1, ceiling] where `ceiling` is the
 * model's claimed output ceiling (min of the model's own maxOutputTokens-or-
 * provider-default and the shared 128K global cap).
 *
 * Upstream `dynamicMaxTokens()` already produces an in-range value; this
 * guards out-of-band callers (sub-agent frontmatter budgets, synthesized
 * requests) that bypass it. An over-range `max_tokens` is a deterministic 400
 * on every provider family; a non-positive value is equally rejected.
 */
fun clampOutboundMaxTokens(requested: Int, ceiling: Int): Int {
    val safeCeiling = ceiling.coerceIn(1, GLOBAL_MAX_OUTPUT_CEILING)
    return requested.coerceIn(1, safeCeiling)
}

/** Shared 128K cap for outbound max_tokens — mirrors ChatViewModel.GLOBAL_MAX_TOKENS_CEILING. */
const val GLOBAL_MAX_OUTPUT_CEILING = 128_000

/** Clamp temperature into the standard [0, max] range (OpenAI/Gemini: 2, Anthropic: 1). */
fun clampOutboundTemperature(value: Double, max: Double = 2.0): Double =
    value.coerceIn(0.0, max)

/**
 * Sanitize tool-use/tool-result pairing in an outbound message list.
 *
 * Pass 1 — orphan tool_use: an assistant message's [AgentContentPart.ToolUse]
 *   that is NOT answered by a matching [AgentContentPart.ToolResult] in the
 *   IMMEDIATELY following user message is dropped (e.g. history truncated
 *   mid-tool-turn, user interrupted, or a compact slice cut between a tool_use
 *   and its result). Anthropic / OpenAI / Gemini all reject an unanswered tool
 *   call with a deterministic 400.
 *
 * Pass 2 — orphan tool_result: a user message's [AgentContentPart.ToolResult]
 *   whose id does not match a [AgentContentPart.ToolUse] in the most recent
 *   assistant message is dropped. Anthropic rejects with 400 (`unexpected
 *   tool_use_id ... no corresponding tool_use block`); OpenAI rejects a
 *   `role:"tool"` message with an unknown `tool_call_id`; Gemini rejects a
 *   `functionResponse` with no preceding `functionCall`.
 *
 * Pass 3 — drop messages left empty after stripping. An empty content array is
 *   itself a 400 on every provider family.
 *
 * Pairing is by [AgentContentPart.ToolUse.id] / [AgentContentPart.ToolResult.id],
 * which are session-local and consistent across providers (Gemini's wire
 * protocol matches functionCall↔functionResponse by name, but the local ids
 * round-trip the same pair, so id matching is equivalent here).
 *
 * @param log invoked with a human-readable description each time a block is
 *   stripped; callers wire this to android.util.Log.
 */
fun sanitizeToolPairing(
    messages: List<LLMMessage>,
    log: (String) -> Unit = {},
): List<LLMMessage> {
    val result = ArrayList<LLMMessage>(messages.size)
    // Tool-use ids from the most recent assistant message that are still
    // awaiting their tool_result answer.
    var liveToolUseIds: Set<String> = emptySet()

    for (i in messages.indices) {
        val msg = messages[i]
        when (msg.role) {
            LLMMessage.Role.ASSISTANT -> {
                val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                var kept = msg.contentParts
                if (toolUses.isNotEmpty()) {
                    // The answer to this assistant's tool calls must arrive in
                    // the immediately following user message.
                    val next = messages.getOrNull(i + 1)
                    val answeredIds = if (next != null && next.role == LLMMessage.Role.USER) {
                        next.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                            .map { it.id }
                            .toSet()
                    } else {
                        emptySet()
                    }
                    kept = msg.contentParts.filter { part ->
                        part !is AgentContentPart.ToolUse || answeredIds.contains(part.id)
                    }
                    if (kept.size != msg.contentParts.size) {
                        val dropped = msg.contentParts.size - kept.size
                        log("Stripped $dropped orphan tool_use block(s) from outbound payload (idx=$i)")
                    }
                }
                // Only tool calls that survived pass 1 can be answered later.
                liveToolUseIds = kept.filterIsInstance<AgentContentPart.ToolUse>()
                    .map { it.id }
                    .toSet()
                result.add(if (kept === msg.contentParts) msg else msg.copy(contentParts = kept))
            }
            LLMMessage.Role.USER -> {
                val original = msg.contentParts
                if (original.isNotEmpty()) {
                    val kept = original.filter { part ->
                        if (part is AgentContentPart.ToolResult) {
                            liveToolUseIds.contains(part.id)
                        } else true
                    }
                    if (kept.size != original.size) {
                        val dropped = original.size - kept.size
                        log("Stripped $dropped orphan tool_result block(s) from outbound payload (idx=$i)")
                        result.add(msg.copy(contentParts = kept))
                    } else {
                        result.add(msg)
                    }
                } else {
                    result.add(msg)
                }
                // A later user turn can't answer an earlier assistant's calls.
                liveToolUseIds = emptySet()
            }
        }
    }

    // Drop messages that became empty (only orphan tool parts). Kept messages
    // still have either non-empty contentParts or a non-empty `content`
    // string (string-only messages never had parts to strip in the first
    // place).
    // Note: this filter is intentionally NOT in the sanitizer — callers
    // (AnthropicProvider) apply it themselves because GeminiProvider has a
    // test that sends pristine-empty messages (empty USER text) which must
    // NOT be dropped (Gemini's serializer replaces "" with " ").
    return result
}

/**
 * [fix/subagent-11148-id-mismatch] Normalize EVERY tool_use / tool_result id
 * in an outbound message list so the wire pairing is id-exact.
 *
 * WHY THIS EXISTS: server-issued tool_call ids routinely collide with ids the
 * client minted (or an earlier server turn minted) elsewhere in the same
 * conversation, and several OpenAI-compatible gateways (Volcano Ark being the
 * trigger for error 11148 "tool calls and tool results do not match") do NOT
 * match a role:"tool" reply to its assistant tool_call positionally — they
 * build an id→result map for the whole request. Two shapes break that map:
 *
 *   1. Duplicate ids. Ark is observed to reject or mis-map the SECOND
 *      tool_call whose id repeats an earlier one, even when
 *      call/result/answer stay positionally correct. OpenAI Chat Completions
 *      rejects duplicates outright with 400 "Duplicate value for tool_call_id".
 *      (OpenAIProvider already renames duplicates on the Chat path via
 *      globallyDedupeToolCallIds — but only after its own builder ran, and
 *      the Responses path never saw the same pass.)
 *   2. The Responses-API combined id "call_…|fc_…" replayed on a Chat
 *      Completions request. capChatToolCallId keeps the pre-'|' half for the
 *      assistant tool_call, but the old tool-message branch forwarded
 *      tr.id VERBATIM — so the assistant announced id "call_abc" while the
 *      following role:"tool" message carried tool_call_id "call_abc|fc_def".
 *      A positional matcher tolerates that; an id-map matcher reports the
 *      result as missing and the call as unanswered → 11148 / 400.
 *
 * Strategy — deterministic ONE-TO-ONE renames, applied to tool_use and
 * tool_result parts TOGETHER so each pair keeps matching, and every call id
 * stays unique within the request:
 *
 *   - duplicate raw id → "raw-2", "raw-3", … (same convention the existing
 *     OpenAI Chat dedupe pass uses, so history produced by an older build
 *     renames identically)
 *   - an id that is NOT valid as-is on a plain chat-completions wire
 *     (longer than 64 chars, or containing the Responses-API '|' separator)
 *     → "call_" + 24 hex chars of SHA-256("v2|<raw id>")
 *
 * Idempotence matters: the SAME raw id must always map to the SAME normalized
 * id (hash names and -N suffixes are both deterministic), so a history
 * re-sent on the next request renames to the same values and the model's
 * learned id references stay stable. Pure + JVM-testable; operates on a copy.
 *
 * Callers that already rename duplicates downstream (OpenAIProvider's Chat
 * builder) stay correct: after this pass no id collides, so their counters
 * never fire — the pass here is the single authority.
 */
fun normalizeToolCallIds(messages: List<LLMMessage>): List<LLMMessage> {
    // Occurrence count per raw id across tool_use parts — the Nth duplicate
    // tool_call gets the Nth distinct name.
    val useCounts = HashMap<String, Int>()
    // Pending (unanswered) new ids per raw id, FIFO: a tool_result takes the
    // OLDEST pending occurrence of its raw id, so each result is renamed to
    // exactly the id its paired tool_use received — even when several calls
    // share one raw id.
    val pending = HashMap<String, ArrayDeque<String>>()

    // Hash-mangle an id that cannot ride a plain chat-completions wire:
    // longer than 64 chars, or carrying the Responses-API '|' separator.
    fun baseFor(raw: String): String =
        if (raw.length <= 64 && !raw.contains('|')) raw
        else "call_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("v2|$raw".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)

    // Collect first (a part id may appear on many messages), rewrite second.
    data class Rewrite(val msgIdx: Int, val partIdx: Int, val newId: String)
    val rewrites = ArrayList<Rewrite>()
    for ((mi, msg) in messages.withIndex()) {
        msg.contentParts.forEachIndexed { pi, part ->
            val raw = when (part) {
                is AgentContentPart.ToolUse -> part.id
                is AgentContentPart.ToolResult -> part.id
                else -> null
            } ?: return@forEachIndexed
            if (raw.isEmpty()) return@forEachIndexed
            val newId: String = when (part) {
                is AgentContentPart.ToolUse -> {
                    val n = useCounts.getOrDefault(raw, 0)
                    useCounts[raw] = n + 1
                    val base = baseFor(raw)
                    val id = when {
                        n == 0 -> base
                        // The suffixed duplicate must itself stay wire-valid.
                        "$base-${n + 1}".length <= 64 && !base.contains('|') -> "$base-${n + 1}"
                        else -> baseFor("$base-${n + 1}")
                    }
                    pending.getOrPut(raw) { ArrayDeque() }.addLast(id)
                    id
                }
                else -> {
                    // tool_result: renamed to exactly what its paired call got.
                    val q = pending[raw]
                    if (q != null && q.isNotEmpty()) q.removeFirst() else raw
                }
            }
            rewrites.add(Rewrite(mi, pi, newId))
        }
    }
    if (rewrites.isEmpty()) return messages

    val touched = rewrites.groupBy { it.msgIdx }
    return messages.mapIndexed { mi, msg ->
        val rw = touched[mi] ?: return@mapIndexed msg
        val parts = msg.contentParts.toMutableList()
        for ((_, pi, newId) in rw) {
            when (val part = parts[pi]) {
                is AgentContentPart.ToolUse -> parts[pi] = part.copy(id = newId)
                is AgentContentPart.ToolResult -> parts[pi] = part.copy(id = newId)
                else -> Unit
            }
        }
        msg.copy(contentParts = parts)
    }
}

/**
 * [fix/subagent-11148-id-mismatch] Composed outbound hardening used by the
 * OpenAI-family builders: orphan-pairing strip FIRST (sanitizeToolPairing —
 * a result whose call was stripped must not influence rename counters), then
 * id normalization (normalizeToolCallIds — dedupe + de-mangle). The [log]
 * sink receives both passes' diagnostics.
 */
fun normalizeToolPairing(
    messages: List<LLMMessage>,
    log: (String) -> Unit = {},
): List<LLMMessage> =
    normalizeToolCallIds(sanitizeToolPairing(messages, log))
