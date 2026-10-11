package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [T-dedup-pill-hide]: same-turn dedupe drops
 * (ToolBlockStatus.DEDUPLICATED) must NOT be rendered as tool pills.
 *
 * Symptom: one logical todo_write produced multiple "更新任务清单" pills —
 * the real call plus N dedupe-dropped twins that flipped to DEDUPLICATED at
 * dispatch time but stayed in the block list and were rendered by
 * ToolCallRunGroup via buildFlatChatItems.
 *
 * Pure JVM — no Compose/Android classes instantiated, mirroring
 * MessageItemAggregationBaselineTest.
 */
class DedupPillFilterTest {

    private fun toolBlock(id: String, status: ToolBlockStatus, title: String = "更新任务清单") =
        AssistantBlock(
            id = id,
            kind = "tool_use",
            toolName = "todo_write",
            toolTitle = title,
            toolStatus = status,
        )

    private fun assistantMessage(blocks: List<AssistantBlock>) = ChatMessage(
        id = "assistant-1",
        role = "assistant",
        content = "",
        toolBlocks = blocks,
    )

    private fun toolRunGroupTools(items: List<FlatChatItem>): List<AssistantBlock> =
        items.filterIsInstance<FlatChatItem.AssistantToolRunGroup>().single().tools

    @Test
    fun `DEDUPLICATED blocks are excluded from the pill list`() {
        val msg = assistantMessage(listOf(
            toolBlock("t1", ToolBlockStatus.SUCCESS),
            toolBlock("t2", ToolBlockStatus.DEDUPLICATED),
            toolBlock("t3", ToolBlockStatus.DEDUPLICATED),
        ))
        val tools = toolRunGroupTools(buildFlatChatItems(listOf(msg)))
        // Only the real call survives; the two dedupe drops are hidden.
        assertEquals(listOf("t1"), tools.map { it.id })
    }

    @Test
    fun `all deduplicated message renders an empty pill list`() {
        val msg = assistantMessage(listOf(
            toolBlock("t1", ToolBlockStatus.DEDUPLICATED),
        ))
        val tools = toolRunGroupTools(buildFlatChatItems(listOf(msg)))
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `terminal and alive statuses still render normally`() {
        val msg = assistantMessage(listOf(
            toolBlock("t1", ToolBlockStatus.SUCCESS),
            toolBlock("t2", ToolBlockStatus.FAILED),
            toolBlock("t3", ToolBlockStatus.CANCELLED),
            toolBlock("t4", ToolBlockStatus.RUNNING),
            toolBlock("t5", ToolBlockStatus.PENDING),
            toolBlock("t6", ToolBlockStatus.STREAMING),
            toolBlock("t7", ToolBlockStatus.TIMEOUT),
        ))
        val tools = toolRunGroupTools(buildFlatChatItems(listOf(msg)))
        // Every non-DEDUPLICATED status keeps its pill, order preserved.
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5", "t6", "t7"), tools.map { it.id })
    }
}
