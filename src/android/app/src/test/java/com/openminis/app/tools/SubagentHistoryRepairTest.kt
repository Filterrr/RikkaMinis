package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-phantom-bridge] Pins the role-alternation repair the sub-agent
 * loop runs at every turn boundary.
 *
 * The regression: the pass fired on `history.last().role == USER`, which the
 * ordinary assistant(tool_use) → user(tool_result) cycle satisfies on EVERY
 * tool-executing turn. It therefore appended a fabricated "you were
 * interrupted by a new user message" assistant turn after each turn, and the
 * model spent turns (and in one observed run, the whole run) reasoning about a
 * user message that never existed.
 */
class SubagentHistoryRepairTest {

    private val bridge = "BRIDGE"

    private fun user(text: String) = LLMMessage(LLMMessage.Role.USER, text)
    private fun assistant(text: String) = LLMMessage(LLMMessage.Role.ASSISTANT, text)

    private fun toolResultCarrier(id: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(
            AgentContentPart.ToolResult(id = id, name = "shell_execute", content = "out"),
        ),
    )

    private fun toolUseRound(id: String) = assistant("working").copy(
        contentParts = listOf(
            AgentContentPart.ToolUse(id = id, name = "shell_execute", input = org.json.JSONObject()),
        ),
    )

    // ─── 1. the normal cycle must NOT be touched ───────────────────────────

    @Test
    fun `ordinary tool cycle needs no bridge`() {
        // user(task) → assistant(tool_use) → user(tool_result)
        // The tail is USER, which is exactly what tripped the old predicate.
        val history = mutableListOf(
            user("TASK"),
            toolUseRound("t1"),
            toolResultCarrier("t1"),
        )
        assertFalse(SubagentHistoryRepair.needsBridge(history))
        assertEquals(0, SubagentHistoryRepair.applyBridges(history, bridge))
        assertEquals(3, history.size)
    }

    @Test
    fun `multi-turn tool cycle accumulates no bridges`() {
        val history = mutableListOf(user("TASK"))
        repeat(5) { i ->
            history.add(toolUseRound("t$i"))
            history.add(toolResultCarrier("t$i"))
        }
        assertEquals(0, SubagentHistoryRepair.applyBridges(history, bridge))
        // The whole point: 1 task + 5 rounds stays 11 messages, not 11 + a
        // phantom interruption notice per turn.
        assertEquals(11, history.size)
        assertEquals(0, history.count { it.role == LLMMessage.Role.ASSISTANT && it.content.isEmpty() && it.contentParts.any { p -> p is AgentContentPart.Text && p.text == bridge } })
    }

    // ─── 2. genuine alternation violations are still repaired ──────────────

    @Test
    fun `consecutive user messages get one bridge`() {
        val history = mutableListOf(user("one"), user("two"))
        assertEquals(listOf(1), SubagentHistoryRepair.bridgeIndices(history))
        assertEquals(1, SubagentHistoryRepair.applyBridges(history, bridge))
        assertEquals(3, history.size)
        assertEquals(LLMMessage.Role.ASSISTANT, history[1].role)
        assertEquals("BRIDGE", (history[1].contentParts.single() as AgentContentPart.Text).text)
        assertFalse(SubagentHistoryRepair.needsBridge(history))
    }

    @Test
    fun `mid-history violation is repaired too`() {
        // A trim boundary can leave two USERs anywhere, not only at the tail.
        val history = mutableListOf(
            user("TASK"),
            assistant("a"),
            user("u1"),
            user("u2"),
            assistant("b"),
        )
        assertEquals(listOf(3), SubagentHistoryRepair.bridgeIndices(history))
        assertEquals(1, SubagentHistoryRepair.applyBridges(history, bridge))
        assertEquals(6, history.size)
        assertEquals(LLMMessage.Role.ASSISTANT, history[3].role)
        // Order preserved around the insertion.
        assertEquals("u1", history[2].content)
        assertEquals("u2", history[4].content)
    }

    @Test
    fun `three consecutive users get two bridges`() {
        val history = mutableListOf(user("1"), user("2"), user("3"))
        assertEquals(listOf(1, 2), SubagentHistoryRepair.bridgeIndices(history))
        assertEquals(2, SubagentHistoryRepair.applyBridges(history, bridge))
        assertFalse(SubagentHistoryRepair.needsBridge(history))
        assertEquals(5, history.size)
    }

    // ─── 3. idempotence — the repair cannot snowball ───────────────────────

    @Test
    fun `apply twice adds nothing the second time`() {
        val history = mutableListOf(user("1"), user("2"))
        assertEquals(1, SubagentHistoryRepair.applyBridges(history, bridge))
        assertEquals(0, SubagentHistoryRepair.applyBridges(history, bridge))
        assertEquals(3, history.size)
    }

    @Test
    fun `empty and single-message histories are no-ops`() {
        val empty = mutableListOf<LLMMessage>()
        assertEquals(0, SubagentHistoryRepair.applyBridges(empty, bridge))
        val single = mutableListOf(user("only"))
        assertEquals(0, SubagentHistoryRepair.applyBridges(single, bridge))
        assertEquals(1, single.size)
    }

    // ─── 4. production wiring guard ────────────────────────────────────────

    @Test
    fun `runner no longer branches on a USER tail alone`() {
        // The old code was `history.lastOrNull()?.role == LLMMessage.Role.USER`
        // immediately followed by appending the bridge. Guard against a
        // reintroduction of that exact predicate.
        val src = com.openminis.app.ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "the repair pass must delegate to SubagentHistoryRepair",
            src.contains("SubagentHistoryRepair.applyBridges"),
        )
        assertFalse(
            "the old tail-only predicate must not come back",
            src.contains("history.lastOrNull()?.role == LLMMessage.Role.USER"),
        )
    }
}
