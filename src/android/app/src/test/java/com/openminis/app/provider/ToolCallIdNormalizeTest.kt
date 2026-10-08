package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-11148-id-mismatch] Pins normalizeToolPairing — the wire-level
 * id pass that closes the Volcano Ark 11148 "tool calls and tool results do
 * not match" failure: orphan pairs stripped first, duplicate tool ids renamed
 * one-to-one, Responses-API "|"-combined ids de-mangled, and each
 * tool_result renamed to exactly the id its paired tool_use received.
 */
class ToolCallIdNormalizeTest {

    // ─── helpers ───────────────────────────────────────────────────────────

    private fun user(text: String, parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.USER, content = text, contentParts = parts)

    private fun assistant(text: String, parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text, contentParts = parts)

    private fun toolUse(id: String, name: String = "shell_execute") =
        AgentContentPart.ToolUse(id = id, name = name, input = JSONObject())

    private fun toolResult(id: String, name: String = "shell_execute", content: String = "ok") =
        AgentContentPart.ToolResult(id = id, name = name, content = content)

    private fun useIds(msgs: List<LLMMessage>) =
        msgs.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolUse>()
            .map { it.id }

    private fun resultIds(msgs: List<LLMMessage>) =
        msgs.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>()
            .map { it.id }

    // ─── 1. clean history passes through untouched ─────────────────────────

    @Test
    fun `clean paired history is returned unchanged`() {
        val msgs = listOf(
            user("do it"),
            assistant("ok", listOf(toolUse("call_abc"))),
            user("", listOf(toolResult("call_abc"))),
        )
        val out = normalizeToolPairing(msgs)
        assertEquals(msgs, out)
    }

    // ─── 2. duplicate id renamed on BOTH halves of the second pair ─────────

    @Test
    fun `duplicate tool id renamed identically on use and result`() {
        val msgs = listOf(
            assistant("one", listOf(toolUse("call_dup"))),
            user("", listOf(toolResult("call_dup", content = "first"))),
            assistant("two", listOf(toolUse("call_dup"))),
            user("", listOf(toolResult("call_dup", content = "second"))),
        )
        val out = normalizeToolPairing(msgs)
        assertEquals(listOf("call_dup", "call_dup-2"), useIds(out))
        assertEquals(listOf("call_dup", "call_dup-2"), resultIds(out))
    }

    @Test
    fun `two calls sharing a raw id get FIFO-matched results`() {
        val msgs = listOf(
            assistant("a", listOf(toolUse("call_d"))),
            user("", listOf(toolResult("call_d", content = "1"))),
            assistant("b", listOf(toolUse("call_d"), toolUse("call_e"))),
            user("", listOf(toolResult("call_d", content = "2"), toolResult("call_e", content = "3"))),
        )
        val out = normalizeToolPairing(msgs)
        assertEquals(listOf("call_d", "call_d-2", "call_e"), useIds(out))
        assertEquals(listOf("call_d", "call_d-2", "call_e"), resultIds(out))
    }

    // ─── 3. Responses-API combined id de-mangled on BOTH halves ────────────

    @Test
    fun `responses combined id is hashed identically on use and result`() {
        val combined = "call_abc123|fc_def456"
        val msgs = listOf(
            assistant("hi", listOf(toolUse(combined))),
            user("", listOf(toolResult(combined))),
        )
        val out = normalizeToolPairing(msgs)
        val uses = useIds(out)
        val results = resultIds(out)
        assertEquals(1, uses.size)
        assertEquals(1, results.size)
        assertEquals(uses[0], results[0])
        assertTrue("no separator may leak to the wire", !uses[0].contains('|'))
        assertTrue(uses[0].length <= 64)
        assertTrue(uses[0].startsWith("call_"))
    }

    // ─── 4. oversized ids capped deterministically ─────────────────────────

    @Test
    fun `oversized id replaced by deterministic call_ hash`() {
        val longId = "call_" + "a".repeat(80)
        val msgs = listOf(
            assistant("x", listOf(toolUse(longId))),
            user("", listOf(toolResult(longId))),
        )
        val out = normalizeToolPairing(msgs)
        val id = useIds(out)[0]
        assertEquals(id, resultIds(out)[0])
        assertTrue(id.length <= 64)
        assertTrue(id.startsWith("call_"))
        // Determinism: same raw id → same capped id on a second pass.
        val again = normalizeToolPairing(out)
        assertEquals(id, useIds(again)[0])
    }

    // ─── 5. orphan strip runs FIRST, renamed pairs stay clean ──────────────

    @Test
    fun `orphan use stripped and remaining pairs keep clean ids`() {
        val msgs = listOf(
            assistant("a", listOf(toolUse("call_orphan"), toolUse("call_keep"))),
            user("", listOf(toolResult("call_keep"))),
        )
        val out = normalizeToolPairing(msgs)
        assertEquals(listOf("call_keep"), useIds(out))
        assertEquals(listOf("call_keep"), resultIds(out))
    }

    // ─── 6. idempotence across re-serialized history ───────────────────────

    @Test
    fun `normalization is idempotent - second pass changes nothing`() {
        val msgs = listOf(
            assistant("one", listOf(toolUse("call_dup"))),
            user("", listOf(toolResult("call_dup"))),
            assistant("two", listOf(toolUse("call_dup"))),
            user("", listOf(toolResult("call_dup"))),
        )
        val once = normalizeToolPairing(msgs)
        val twice = normalizeToolPairing(once)
        assertEquals(once, twice)
    }

    // ─── 7. no-tool history untouched ──────────────────────────────────────

    @Test
    fun `history without tool parts is untouched`() {
        val msgs = listOf(
            user("hello", listOf(AgentContentPart.Text("hello"))),
            assistant("plain reply"),
        )
        assertEquals(msgs, normalizeToolPairing(msgs))
    }
}
