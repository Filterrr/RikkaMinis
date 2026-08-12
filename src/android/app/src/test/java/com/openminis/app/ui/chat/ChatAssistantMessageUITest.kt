package com.openminis.app.ui.chat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatAssistantMessageUITest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun assistantHeader_rendersCorrectly() {
        composeTestRule.setContent {
            AssistantHeader()
        }

        composeTestRule.onNodeWithTag("assistantHeader").assertExists()
        composeTestRule.onNodeWithText("Assistant").assertExists()
    }

    @Test
    fun assistantHeader_clickEvent() {
        composeTestRule.setContent {
            AssistantHeader(onClick = { clicked = true })
        }

        composeTestRule.onNodeWithTag("assistantHeader").performClick()
        assertTrue(clicked)
    }

    @Test
    fun assistantMessageView_rendersWithDefaultParameters() {
        composeTestRule.setContent {
            AssistantMessageView(message = ChatMessage(content = "Test message"))
        }

        composeTestRule.onNodeWithText("Test message").assertExists()
    }

    @Test
    fun assistantMessageView_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            AssistantMessageView(
                message = ChatMessage(content = "Test message"),
                onRetry = { clicked = true }
            )
        }

        composeTestRule.onNodeWithText("Test message").performClick()
        assertTrue(clicked)
    }

    @Test
    fun assistantMessageView_rendersWithEmptyContent() {
        composeTestRule.setContent {
            AssistantMessageView(message = ChatMessage(content = ""))
        }

        composeTestRule.onNodeWithTag("assistantMessageView").assertExists()
    }

    @Test
    fun boundsTrackedBlock_rendersCorrectly() {
        composeTestRule.setContent {
            BoundsTrackedBlock(
                messageId = "testMessageId",
                slotKey = "testSlotKey",
                markdown = "Test markdown",
                content = { Text("Test content") }
            )
        }

        composeTestRule.onNodeWithText("Test content").assertExists()
    }

    @Test
    fun boundsTrackedBlock_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            BoundsTrackedBlock(
                messageId = "testMessageId",
                slotKey = "testSlotKey",
                markdown = "Test markdown",
                content = { Text("Test content", modifier = Modifier.clickable { clicked = true }) }
            )
        }

        composeTestRule.onNodeWithText("Test content").performClick()
        assertTrue(clicked)
    }

    @Test
    fun boundsTrackedBlock_rendersWithDefaultParameters() {
        composeTestRule.setContent {
            BoundsTrackedBlock(
                messageId = "defaultMessageId",
                slotKey = "defaultSlotKey",
                markdown = "Default markdown",
                content = { Text("Default content") }
            )
        }

        composeTestRule.onNodeWithText("Default content").assertExists()
    }

    @Test
    fun inlineErrorBanner_rendersCorrectly() {
        composeTestRule.setContent {
            InlineErrorBanner(error = "Test error message")
        }

        composeTestRule.onNodeWithText("Test error message").assertExists()
    }

    @Test
    fun inlineErrorBanner_clickEvent() {
        var retryClicked = false
        composeTestRule.setContent {
            InlineErrorBanner(
                error = "Test error message",
                onRetry = { retryClicked = true }
            )
        }

        composeTestRule.onNodeWithText("Retry").performClick()
        assertTrue(retryClicked)
    }

    @Test
    fun inlineErrorBanner_rendersWithDefaultParameters() {
        composeTestRule.setContent {
            InlineErrorBanner(error = "Default error message")
        }

        composeTestRule.onNodeWithText("Default error message").assertExists()
    }

    @Test
    fun toolStopButton_rendersCorrectly() {
        composeTestRule.setContent {
            ToolStopButton(onStop = {})
        }

        composeTestRule.onNodeWithTag("toolStopButton").assertExists()
    }

    @Test
    fun toolStopButton_clickEvent() {
        var stopClicked = false
        composeTestRule.setContent {
            ToolStopButton(onStop = { stopClicked = true })
        }

        composeTestRule.onNodeWithTag("toolStopButton").performClick()
        assertTrue(stopClicked)
    }

    @Test
    fun toolStopButton_rendersWithDefaultParameters() {
        composeTestRule.setContent {
            ToolStopButton(onStop = {})
        }

        composeTestRule.onNodeWithTag("toolStopButton").assertExists()
    }

    @Test
    fun toolCallPill_rendersCorrectly() {
        composeTestRule.setContent {
            ToolCallPill(
                block = AssistantBlock(
                    id = "testBlockId",
                    toolName = "testTool",
                    toolTitle = "Test Tool",
                    content = "Test content",
                    toolArgs = "{}",
                    toolStatus = ToolBlockStatus.SUCCESS
                )
            )
        }

        composeTestRule.onNodeWithText("Test Tool").assertExists()
    }

    @Test
    fun toolCallPill_clickEvent() {
        var detailOpened = false
        composeTestRule.setContent {
            ToolCallPill(
                block = AssistantBlock(
                    id = "testBlockId",
                    toolName = "testTool",
                    toolTitle = "Test Tool",
                    content = "Test content",
                    toolArgs = "{}",
                    toolStatus = ToolBlockStatus.SUCCESS
                ),
                onOpenDetail = { detailOpened = true }
            )
        }

        composeTestRule.onNodeWithText("Test Tool").performClick()
        assertTrue(detailOpened)
    }

    @Test
    fun toolCallPill_rendersWithDefaultParameters() {
        composeTestRule.setContent {
            ToolCallPill(
                block = AssistantBlock(
                    id = "defaultBlockId",
                    toolName = "defaultTool",
                    toolTitle = "Default Tool",
                    content = "Default content",
                    toolArgs = "{}",
                    toolStatus = ToolBlockStatus.PENDING
                )
            )
        }

        composeTestRule.onNodeWithText("Default Tool").assertExists()
    }

    @Test
    fun toolCallRunGroup_rendersCorrectly() {
        composeTestRule.setContent {
            ToolCallRunGroup(
                group = FlatChatItem.AssistantToolRunGroup(
                    messageId = "testMessageId",
                    count = 2,
                    isRunning = false,
                    totalDurationMs = 1000,
                    tools = listOf(
                        AssistantBlock(
                            id = "block1",
                            toolName = "tool1",
                            toolTitle = "Tool 1",
                            content = "Content 1",
                            toolArgs = "{}",
                            toolStatus = ToolBlockStatus.SUCCESS
                        ),
                        AssistantBlock(
                            id = "block2",
                            toolName = "tool2",
                            toolTitle = "Tool 2",
                            content = "Content 2",
                            toolArgs = "{}",
                            toolStatus = ToolBlockStatus.SUCCESS
                        )
                    )
                )
            )
        }

        composeTestRule.onNodeWithText("2 tools").assertExists()
    }

    @Test
    fun toolCallRunGroup_clickEvent() {
        var expanded = false
        composeTestRule.setContent {
            ToolCallRunGroup(
                group = FlatChatItem.AssistantToolRunGroup(
                    messageId = "testMessageId",
                    count = 1,
                    isRunning = false,
                    totalDurationMs = 500,
                    tools = listOf(
                        AssistantBlock(
                            id = "block1",
                            toolName = "tool1",
                            toolTitle = "Tool 1",
                            content = "Content 1",
                            toolArgs = "{}",
                            toolStatus = ToolBlockStatus.SUCCESS
                        )
                    )
                ),
                onOpenDetail = { expanded = true }
            )
        }

        composeTestRule.onNodeWithText("1 tools").performClick()
        assertTrue(expanded)
    }

    @Test
    fun toolCallRunGroup_rendersWithDefaultParameters() {
        composeTestRule.setContent {
            ToolCallRunGroup(
                group = FlatChatItem.AssistantToolRunGroup(
                    messageId = "defaultMessageId",
                    count = 0,
                    isRunning = false,
                    totalDurationMs = 0,
                    tools = emptyList()
                )
            )
        }

        composeTestRule.onNodeWithText("0 tools").assertExists()
    }

    @Test
    fun thinkingBlock_rendersCorrectly() {
        composeTestRule.setContent {
            ThinkingBlock(
                block = AssistantBlock(
                    id = "thinkingBlockId",
                    toolName = "thinking",
                    toolTitle = "Thinking",
                    content = "Thinking content",
                    toolArgs = "{}",
                    toolStatus = ToolBlockStatus.SUCCESS
                ),
                isStreaming = false,
                isLast = true
            )
        }

        composeTestRule.onNodeWithText("Deep Thinking").assertExists()
    }

    @Test
    fun thinkingBlock_clickEvent() {
        var expanded = false
        composeTestRule.setContent {
            ThinkingBlock(
                block = AssistantBlock(
                    id = "thinkingBlockId",
                    toolName = "thinking",
                    toolTitle = "Thinking",
                    content = "Thinking content",
                    toolArgs = "{}",
                    toolStatus = ToolBlockStatus.SUCCESS
                ),
                isStreaming = false,
                isLast = true,
                onToggle = { expanded = !expanded }
            )
        }

        composeTestRule.onNodeWithText("Deep Thinking").performClick()
        assertTrue(expanded)
    }

    @Test
    fun thinkingBlock_rendersWithDefaultParameters() {
        composeTestRule.setContent {
            ThinkingBlock(
                block = AssistantBlock(
                    id = "defaultThinkingBlockId",
                    toolName = "thinking",
                    toolTitle = "Thinking",
                    content = "Default thinking content",
                    toolArgs = "{}",
                    toolStatus = ToolBlockStatus.PENDING
                ),
                isStreaming = false,
                isLast = false
            )
        }

        composeTestRule.onNodeWithText("Deep Thinking").assertExists()
    }

    @Test
    fun formatToolDetailsForClipboard_returnsFormattedString() {
        val block = AssistantBlock(
            id = "testBlockId",
            toolName = "testTool",
            toolTitle = "Test Tool",
            content = "Test result content",
            toolArgs = "{\"key\": \"value\"}",
            toolStatus = ToolBlockStatus.SUCCESS
        )

        val result = formatToolDetailsForClipboard(block)

        assertTrue(result.contains("## Tool Call"))
        assertTrue(result.contains("name: testTool"))
        assertTrue(result.contains("id: testBlockId"))
        assertTrue(result.contains("## Tool Result"))
        assertTrue(result.contains("Test result content"))
    }

    @Test
    fun formatToolDetailsForClipboard_handlesEmptyArgs() {
        val block = AssistantBlock(
            id = "testBlockId",
            toolName = "testTool",
            toolTitle = "Test Tool",
            content = "Test result content",
            toolArgs = "",
            toolStatus = ToolBlockStatus.SUCCESS
        )

        val result = formatToolDetailsForClipboard(block)

        assertTrue(result.contains("(none)"))
    }

    @Test
    fun formatToolDetailsForClipboard_handlesFailedStatus() {
        val block = AssistantBlock(
            id = "testBlockId",
            toolName = "testTool",
            toolTitle = "Test Tool",
            content = "Test result content",
            toolArgs = "{\"key\": \"value\"}",
            toolStatus = ToolBlockStatus.FAILED
        )

        val result = formatToolDetailsForClipboard(block)

        assertTrue(result.contains("error"))
    }
}