package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.sanitizeToolPairing
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-parallel-tool-results] Pins the defect this branch closes:
 * a sub-agent turn with N parallel tool calls must reach the provider as ONE
 * user message carrying N tool_results, or the results are lost.
 *
 * THE DEFECT (measured against the real production classes)
 *
 * The runner used to append one USER message per tool call. Two mechanisms
 * then broke on that shape:
 *
 * 1. `sanitizeToolPairing` (Anthropic / Gemini / Antigravity / OpenAI family)
 *    answers an assistant's tool_use blocks from the IMMEDIATELY FOLLOWING
 *    user message only. With N separate user messages only the first result
 *    answered the assistant turn; the rest were stripped from the wire.
 * 2. The runner's own Pass 1 repair then injected a fake
 *    "Tool execution was interrupted" placeholder for every result that was
 *    no longer adjacent to its call — so the model was told its tool calls
 *    had been interrupted and never saw their output.
 *
 * Reproduced with the real sanitizer:
 *   3 parallel reads → the model saw 1 real output + 2 fake interruptions.
 *
 * These tests pin the FIXED behaviour end-to-end: the batched history shape
 * survives the real sanitizer with every result intact, and the Pass 1
 * repair has nothing to repair.
 */
class SubagentParallelToolResultsTest {

    private fun part(id: String, body: String, isError: Boolean = false) =
        AgentContentPart.ToolResult(id = id, name = "file_read", content = body, isError = isError)

    /**
     * The real Pass 1 of SubagentRunner: every assistant tool_use that the
     * immediately following user message does not answer is reported (the
     * runner injects a fake "interrupted" placeholder for each).
     */
    private fun pass1InjectedIds(history: List<LLMMessage>): List<String> {
        val injected = mutableListOf<String>()
        var idx = 0
        while (idx < history.size) {
            val msg = history[idx]
            if (msg.role != LLMMessage.Role.ASSISTANT) { idx++; continue }
            val useIds = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                .map { it.id }.toSet()
            if (useIds.isEmpty()) { idx++; continue }
            val next = history.getOrNull(idx + 1)
            val nextIds = next?.contentParts?.filterIsInstance<AgentContentPart.ToolResult>()
                ?.map { it.id }?.toSet() ?: emptySet()
            injected += useIds - nextIds
            idx++
        }
        return injected
    }

    private fun wireToolResults(history: List<LLMMessage>): List<Pair<String, String>> =
        sanitizeToolPairing(history).flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>()
            .map { it.id to it.content }

    // ─── the batch builder ────────────────────────────────────────────────

    @Test
    fun `one user message carries every result of the turn`() {
        val msg = SubagentToolResultBatch.buildToolResultMessage(
            listOf(part("c1", "A1"), part("c2", "A2"), part("c3", "A3")),
        )!!
        assertEquals(LLMMessage.Role.USER, msg.role)
        assertEquals(3, msg.contentParts.size)
        assertEquals(
            listOf("c1", "c2", "c3"),
            msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>().map { it.id },
        )
    }

    @Test
    fun `the content string stays empty so the payload is not counted twice`() {
        val msg = SubagentToolResultBatch.buildToolResultMessage(listOf(part("c1", "A1")))!!
        // SubagentHistoryBudget skips `content` for tool-result carriers;
        // a duplicated payload here would be billed twice.
        assertEquals("", msg.content)
    }

    @Test
    fun `empty results produce no message`() {
        assertNull(SubagentToolResultBatch.buildToolResultMessage(emptyList()))
    }

    @Test
    fun `error results keep the error flag`() {
        val msg = SubagentToolResultBatch.buildToolResultMessage(
            listOf(part("c1", "Error: boom", isError = true)),
        )!!
        val p = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>().single()
        assertTrue(p.isError)
        assertEquals("Error: boom", p.content)
    }

    // ─── the end-to-end shape the runner now produces ─────────────────────

    private fun batchedHistory(): List<LLMMessage> = listOf(
        LLMMessage(role = LLMMessage.Role.USER, content = "task"),
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf("c1", "c2", "c3").map {
                AgentContentPart.ToolUse(id = it, name = "file_read", input = JSONObject())
            },
        ),
        SubagentToolResultBatch.buildToolResultMessage(
            listOf(part("c1", "A1"), part("c2", "A2"), part("c3", "A3")),
        )!!,
    )

    @Test
    fun `pass 1 has nothing to repair in the batched shape`() {
        assertEquals(emptyList<String>(), pass1InjectedIds(batchedHistory()))
    }

    @Test
    fun `every result survives the real pairing sanitizer`() {
        val onWire = wireToolResults(batchedHistory())
        assertEquals(3, onWire.size)
        assertEquals(listOf("A1", "A2", "A3"), onWire.map { it.second })
    }

    @Test
    fun `the batched shape needs no role-alternation bridge`() {
        // Second-order win: N consecutive USER messages were also two
        // consecutive USERs, so SubagentHistoryRepair inserted the phantom
        // "you were interrupted by a new user message" assistant turn between
        // them. The batched shape has one USER message, so there is nothing
        // to bridge.
        val history = batchedHistory()
        var consecutiveUserPairs = 0
        for (i in 1 until history.size) {
            if (history[i].role == LLMMessage.Role.USER &&
                history[i - 1].role == LLMMessage.Role.USER
            ) {
                consecutiveUserPairs++
            }
        }
        assertEquals(0, consecutiveUserPairs)
    }

    @Test
    fun `the old one-message-per-result shape loses results`() {
        // Regression anchor: this is the shape the runner used to produce.
        // If someone restores it, the assertions below fail — which is the
        // point of the negative verification.
        val legacy = mutableListOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "task"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf("c1", "c2", "c3").map {
                    AgentContentPart.ToolUse(id = it, name = "file_read", input = JSONObject())
                },
            ),
        )
        listOf("c1" to "A1", "c2" to "A2", "c3" to "A3").forEach { (id, out) ->
            legacy += LLMMessage(
                role = LLMMessage.Role.USER,
                content = "Result of file_read ($id):\n$out",
                contentParts = listOf(part(id, out)),
            )
        }
        // The legacy shape loses two of three results on the wire...
        assertEquals(1, wireToolResults(legacy).size)
        // ...and the runner's Pass 1 tells the model the other two were
        // interrupted.
        assertEquals(listOf("c2", "c3"), pass1InjectedIds(legacy))
    }
}
