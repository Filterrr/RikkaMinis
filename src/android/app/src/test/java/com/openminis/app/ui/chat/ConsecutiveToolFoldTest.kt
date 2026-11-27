package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-consecutive-tool-fold] 连续工具轮的折叠契约。
 *
 * agent 跑五轮工具会渲染成五张卡片，聊天框被壳子撑满。规则只有一条：
 * **相邻**且中间没有正文/思考隔开时才合并；隔开了就是两段不同的工作。
 */
class ConsecutiveToolFoldTest {

    private fun tool(id: String, status: ToolBlockStatus = ToolBlockStatus.SUCCESS) =
        AssistantBlock(
            id = id,
            kind = "tool_use",
            toolName = "shell_execute",
            toolStatus = status,
        )

    private fun toolMessage(messageId: String, toolId: String) = ChatMessage(
        id = messageId,
        role = "assistant",
        content = "",
        toolBlocks = listOf(tool(toolId)),
    )

    private fun textMessage(messageId: String, text: String) = ChatMessage(
        id = messageId,
        role = "assistant",
        content = text,
        toolBlocks = listOf(AssistantBlock(id = "$messageId-t", kind = "text", content = text)),
    )

    private fun groups(items: List<FlatChatItem>) =
        items.filterIsInstance<FlatChatItem.AssistantToolRunGroup>()

    @Test
    fun `adjacent tool-only messages fold into one card`() {
        val items = buildFlatChatItems(
            listOf(
                toolMessage("m1", "t1"),
                toolMessage("m2", "t2"),
                toolMessage("m3", "t3"),
            ),
        )
        val folded = groups(items)
        assertEquals(1, folded.size)
        assertEquals(listOf("t1", "t2", "t3"), folded.single().tools.map { it.id })
        // The representative id must be the FIRST one: fork / rerun anchor to
        // the start of the run, not to its tail.
        assertEquals("m1", folded.single().messageId)
    }

    @Test
    fun `a text reply between runs keeps them separate`() {
        val items = buildFlatChatItems(
            listOf(
                toolMessage("m1", "t1"),
                textMessage("m2", "here is what I found"),
                toolMessage("m3", "t2"),
            ),
        )
        assertEquals(2, groups(items).size)
    }

    @Test
    fun `one live tool keeps the folded card running`() {
        val items = buildFlatChatItems(
            listOf(
                toolMessage("m1", "t1"),
                ChatMessage(
                    id = "m2",
                    role = "assistant",
                    content = "",
                    toolBlocks = listOf(tool("t2", ToolBlockStatus.RUNNING)),
                    isStreaming = true,
                ),
            ),
        )
        val folded = groups(items).single()
        assertEquals(2, folded.tools.size)
        assertEquals(true, folded.isRunning)
    }

    @Test
    fun `a single tool message stays one group`() {
        val items = buildFlatChatItems(listOf(toolMessage("m1", "t1")))
        assertEquals(1, groups(items).size)
        assertEquals(1, groups(items).single().tools.size)
    }
}