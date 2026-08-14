package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-slice-tool-pairing] Locks the tool_use/tool_result pairing repair
 * that runs on the compacted-slice result of effectiveAgentHistory.
 *
 * The compact slice (walkBack cap / preAnchor prune / postAnchor splice) can
 * split a tool round across a boundary, leaving an orphan tool_result whose
 * tool_use was cut off — the API rejects the request with 400 "Messages with
 * role 'tool' must be a response to a preceding message with 'tool_calls'".
 * sanitizeAgentHistoryMessages must repair ANY list of messages (full history
 * OR compacted slice) so no outgoing request carries a dangling tool message.
 */
class ChatViewModelSanitizeTest {

    private fun userMsg(content: String = "", parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.USER, content = content, contentParts = parts)

    private fun assistantMsg(content: String = "", parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = content, contentParts = parts)

    private fun toolUse(id: String, name: String = "shell_execute") =
        AgentContentPart.ToolUse(id, name, org.json.JSONObject())

    private fun toolResult(id: String, name: String = "shell_execute") =
        AgentContentPart.ToolResult(id, name, "output of $id", isError = false)

    @Test
    fun `paired tool round passes through unchanged`() {
        val msgs = mutableListOf(
            userMsg("check files"),
            assistantMsg(parts = listOf(toolUse("call_1"))),
            userMsg(parts = listOf(toolResult("call_1"))),
            assistantMsg("done"),
        )
        sanitizeAgentHistoryMessages(msgs)
        assertEquals(4, msgs.size)
        assertEquals(1, msgs[2].contentParts.count { it is AgentContentPart.ToolResult })
    }

    @Test
    fun `orphan tool_result without tool_use is removed`() {
        // The exact shape a compact-slice boundary produces: the tool_result
        // survived the slice but its assistant tool_use was cut off above.
        val msgs = mutableListOf(
            userMsg("continue"),
            userMsg(parts = listOf(toolResult("call_orphan"))), // role=USER, but orphan
            assistantMsg("answer"),
        )
        sanitizeAgentHistoryMessages(msgs)
        assertFalse(
            "orphan tool_result must be removed",
            msgs.any { it.contentParts.any { p -> p is AgentContentPart.ToolResult } },
        )
        assertFalse(
            "empty tool-result-only user message must be dropped",
            msgs.any { it.role == LLMMessage.Role.USER && it.content.isBlank() && it.contentParts.isEmpty() },
        )
    }

    @Test
    fun `orphan tool_use gets placeholder tool_result injected`() {
        val msgs = mutableListOf(
            userMsg("do it"),
            assistantMsg(parts = listOf(toolUse("call_lost"))),
            userMsg("next"),
        )
        sanitizeAgentHistoryMessages(msgs)
        // After the assistant tool_use there must be a user message with a
        // matching placeholder result — inserted between them.
        val asstIdx = msgs.indexOfFirst { it.role == LLMMessage.Role.ASSISTANT }
        val next = msgs[asstIdx + 1]
        assertEquals(LLMMessage.Role.USER, next.role)
        val results = next.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
        assertEquals(1, results.size)
        assertEquals("call_lost", results[0].id)
        assertTrue(results[0].isError)
    }

    @Test
    fun `mixed paired and orphan results - only orphan removed`() {
        val msgs = mutableListOf(
            userMsg("go"),
            assistantMsg(parts = listOf(toolUse("call_good"), toolUse("call_bad"))),
            userMsg(parts = listOf(toolResult("call_good"))), // call_bad result missing
            assistantMsg("done"),
            userMsg(parts = listOf(toolResult("call_ghost"))), // no tool_use anywhere
        )
        sanitizeAgentHistoryMessages(msgs)
        // call_bad gets a placeholder appended to the existing result message
        val resultMsg = msgs[2]
        val resultIds = resultMsg.contentParts.filterIsInstance<AgentContentPart.ToolResult>().map { it.id }
        assertEquals(setOf("call_good", "call_bad"), resultIds.toSet())
        // call_ghost orphan removed entirely
        assertFalse(msgs.any { it.contentParts.any { p -> p is AgentContentPart.ToolResult && p.id == "call_ghost" } })
    }

    @Test
    fun `compact slice opening with orphan result is fully repaired`() {
        // Simulates effectiveAgentHistory slice: walkBack cap stopped on a
        // tool_result user message while its tool_use was cut off — plus the
        // summary user turn at the head.
        val msgs = mutableListOf(
            userMsg("<context-summary>…"),
            userMsg(parts = listOf(toolResult("call_cut"))), // orphan, use was cut
            assistantMsg("continuing"),
        )
        sanitizeAgentHistoryMessages(msgs)
        // No tool_result may survive without its tool_use.
        assertFalse(msgs.any { it.contentParts.any { p -> p is AgentContentPart.ToolResult } })
        assertEquals(
            "only summary + assistant should remain",
            2, msgs.size,
        )
    }
}
