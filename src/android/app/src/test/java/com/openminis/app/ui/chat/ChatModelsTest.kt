package com.openminis.app.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatModelsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testChatMessage_defaultParameters() {
        val message = ChatMessage(
            id = "1",
            role = "user",
            content = "Hello"
        )
        composeTestRule.setContent {
            ChatMessageComposable(chatMessage = message)
        }
        composeTestRule.onNodeWithText("Hello").assertIsDisplayed()
    }

    @Test
    fun testChatMessage_clickEvent() {
        var clicked = false
        val message = ChatMessage(
            id = "2",
            role = "assistant",
            content = "Click me"
        )
        composeTestRule.setContent {
            ChatMessageComposable(
                chatMessage = message,
                onClick = { clicked = true }
            )
        }
        composeTestRule.onNodeWithText("Click me").performClick()
        assert(clicked)
    }

    @Test
    fun testChatMessage_withStreaming() {
        val message = ChatMessage(
            id = "3",
            role = "assistant",
            content = "Streaming...",
            isStreaming = true
        )
        composeTestRule.setContent {
            ChatMessageComposable(chatMessage = message)
        }
        composeTestRule.onNodeWithText("Streaming...").assertIsDisplayed()
    }

    @Test
    fun testChatMessage_withError() {
        val message = ChatMessage(
            id = "4",
            role = "assistant",
            content = "Error occurred",
            error = "Something went wrong"
        )
        composeTestRule.setContent {
            ChatMessageComposable(chatMessage = message)
        }
        composeTestRule.onNodeWithText("Error occurred").assertIsDisplayed()
    }

    @Test
    fun testChatMessage_withImageUris() {
        val message = ChatMessage(
            id = "5",
            role = "user",
            content = "Image message",
            imageUris = listOf(Uri.parse("file://test.png"))
        )
        composeTestRule.setContent {
            ChatMessageComposable(chatMessage = message)
        }
        composeTestRule.onNodeWithText("Image message").assertIsDisplayed()
    }

    @Test
    fun testChatMessage_withToolBlocks() {
        val message = ChatMessage(
            id = "6",
            role = "assistant",
            content = "Tool message",
            toolBlocks = listOf(
                AssistantBlock(
                    id = "tool1",
                    kind = "tool",
                    toolTitle = "Test Tool"
                )
            )
        )
        composeTestRule.setContent {
            ChatMessageComposable(chatMessage = message)
        }
        composeTestRule.onNodeWithText("Tool message").assertIsDisplayed()
    }

    @Test
    fun testAssistantBlock_defaultParameters() {
        val block = AssistantBlock(
            id = "block1",
            kind = "text",
            content = "Block content"
        )
        composeTestRule.setContent {
            AssistantBlockComposable(block = block)
        }
        composeTestRule.onNodeWithText("Block content").assertIsDisplayed()
    }

    @Test
    fun testAssistantBlock_clickEvent() {
        var clicked = false
        val block = AssistantBlock(
            id = "block2",
            kind = "text",
            content = "Clickable block"
        )
        composeTestRule.setContent {
            AssistantBlockComposable(
                block = block,
                onClick = { clicked = true }
            )
        }
        composeTestRule.onNodeWithText("Clickable block").performClick()
        assert(clicked)
    }

    @Test
    fun testAssistantBlock_withToolStatus() {
        val block = AssistantBlock(
            id = "block3",
            kind = "tool",
            content = "Tool block",
            toolStatus = ToolBlockStatus.RUNNING,
            toolTitle = "Running Tool"
        )
        composeTestRule.setContent {
            AssistantBlockComposable(block = block)
        }
        composeTestRule.onNodeWithText("Tool block").assertIsDisplayed()
    }

    @Test
    fun testAssistantBlock_withBrowserURL() {
        val block = AssistantBlock(
            id = "block4",
            kind = "browser",
            content = "Browser block",
            browserURL = "https://example.com"
        )
        composeTestRule.setContent {
            AssistantBlockComposable(block = block)
        }
        composeTestRule.onNodeWithText("Browser block").assertIsDisplayed()
    }

    @Test
    fun testAssistantBlock_withImageFilePath() {
        val block = AssistantBlock(
            id = "block5",
            kind = "image",
            content = "Image block",
            imageFilePath = "/path/to/image.png"
        )
        composeTestRule.setContent {
            AssistantBlockComposable(block = block)
        }
        composeTestRule.onNodeWithText("Image block").assertIsDisplayed()
    }

    @Test
    fun testQueuedPrompt_defaultParameters() {
        val prompt = QueuedPrompt(
            id = "prompt1",
            text = "Test prompt"
        )
        composeTestRule.setContent {
            QueuedPromptComposable(prompt = prompt)
        }
        composeTestRule.onNodeWithText("Test prompt").assertIsDisplayed()
    }

    @Test
    fun testQueuedPrompt_clickEvent() {
        var clicked = false
        val prompt = QueuedPrompt(
            id = "prompt2",
            text = "Clickable prompt"
        )
        composeTestRule.setContent {
            QueuedPromptComposable(
                prompt = prompt,
                onClick = { clicked = true }
            )
        }
        composeTestRule.onNodeWithText("Clickable prompt").performClick()
        assert(clicked)
    }

    @Test
    fun testQueuedPrompt_withAttachments() {
        val prompt = QueuedPrompt(
            id = "prompt3",
            text = "Prompt with attachments",
            attachments = listOf(
                InputAttachment(name = "file1.txt", uri = Uri.parse("file://test.txt"))
            )
        )
        composeTestRule.setContent {
            QueuedPromptComposable(prompt = prompt)
        }
        composeTestRule.onNodeWithText("Prompt with attachments").assertIsDisplayed()
    }

    @Test
    fun testSlashCommand_defaultParameters() {
        val command = SlashCommand(
            id = "cmd1",
            icon = Icons.Default.Compress,
            title = "Test Command",
            subtitle = "Test subtitle"
        )
        composeTestRule.setContent {
            SlashCommandComposable(command = command)
        }
        composeTestRule.onNodeWithText("Test Command").assertIsDisplayed()
    }

    @Test
    fun testSlashCommand_clickEvent() {
        var clicked = false
        val command = SlashCommand(
            id = "cmd2",
            icon = Icons.Default.Delete,
            title = "Clickable Command",
            subtitle = "Click me"
        )
        composeTestRule.setContent {
            SlashCommandComposable(
                command = command,
                onClick = { clicked = true }
            )
        }
        composeTestRule.onNodeWithText("Clickable Command").performClick()
        assert(clicked)
    }

    @Test
    fun testSlashCommand_withSkill() {
        val command = SlashCommand(
            id = "cmd3",
            icon = Icons.Default.Lightbulb,
            title = "Skill Command",
            subtitle = "Skill subtitle",
            isSkill = true
        )
        composeTestRule.setContent {
            SlashCommandComposable(command = command)
        }
        composeTestRule.onNodeWithText("Skill Command").assertIsDisplayed()
    }

    @Test
    fun testSlashCommand_withMcp() {
        val command = SlashCommand(
            id = "cmd4",
            icon = Icons.Default.Psychology,
            title = "MCP Command",
            subtitle = "MCP subtitle",
            isMcp = true
        )
        composeTestRule.setContent {
            SlashCommandComposable(command = command)
        }
        composeTestRule.onNodeWithText("MCP Command").assertIsDisplayed()
    }

    @Test
    fun testStreamingDelta_defaultParameters() {
        val delta = StreamingDelta(
            content = "Delta content",
            toolBlocks = emptyList(),
            isAwaitingModelResponse = false
        )
        composeTestRule.setContent {
            StreamingDeltaComposable(delta = delta)
        }
        composeTestRule.onNodeWithText("Delta content").assertIsDisplayed()
    }

    @Test
    fun testStreamingDelta_withToolBlocks() {
        val delta = StreamingDelta(
            content = "Delta with tools",
            toolBlocks = listOf(
                AssistantBlock(
                    id = "deltaTool1",
                    kind = "tool",
                    toolTitle = "Delta Tool"
                )
            ),
            isAwaitingModelResponse = true
        )
        composeTestRule.setContent {
            StreamingDeltaComposable(delta = delta)
        }
        composeTestRule.onNodeWithText("Delta with tools").assertIsDisplayed()
    }

    @Test
    fun testStreamingDelta_clickEvent() {
        var clicked = false
        val delta = StreamingDelta(
            content = "Clickable delta",
            toolBlocks = emptyList(),
            isAwaitingModelResponse = false
        )
        composeTestRule.setContent {
            StreamingDeltaComposable(
                delta = delta,
                onClick = { clicked = true }
            )
        }
        composeTestRule.onNodeWithText("Clickable delta").performClick()
        assert(clicked)
    }

    @Test
    fun testToolBlockStatus_enumValues() {
        val statuses = ToolBlockStatus.values()
        assert(statuses.contains(ToolBlockStatus.STREAMING))
        assert(statuses.contains(ToolBlockStatus.PENDING))
        assert(statuses.contains(ToolBlockStatus.RUNNING))
        assert(statuses.contains(ToolBlockStatus.SUCCESS))
        assert(statuses.contains(ToolBlockStatus.FAILED))
        assert(statuses.contains(ToolBlockStatus.CANCELLED))
        assert(statuses.contains(ToolBlockStatus.TIMEOUT))
    }

    @Test
    fun testChatMessage_isInternalBridge() {
        val bridgeMessage = ChatMessage(
            id = "bridge1",
            role = "assistant",
            content = "(Interrupted mid-task by a new user message. Decide based on the new " +
                "message and overall context whether the prior task should continue — do " +
                "not forget or abandon it unless the user explicitly says to stop, or the " +
                "new message makes clear it is no longer needed.)"
        )
        assert(bridgeMessage.isInternalBridge)
    }

    @Test
    fun testChatMessage_isNotInternalBridge() {
        val normalMessage = ChatMessage(
            id = "normal1",
            role = "assistant",
            content = "Normal message"
        )
        assert(!normalMessage.isInternalBridge)
    }

    @Test
    fun testChatMessage_sourceDbIds() {
        val message = ChatMessage(
            id = "db1",
            role = "user",
            content = "DB message",
            sourceDbIds = listOf("db1", "db2")
        )
        composeTestRule.setContent {
            ChatMessageComposable(chatMessage = message)
        }
        composeTestRule.onNodeWithText("DB message").assertIsDisplayed()
    }

    @Test
    fun testAssistantBlock_withDuration() {
        val block = AssistantBlock(
            id = "durationBlock",
            kind = "tool",
            content = "Duration block",
            durationMs = 5000L,
            startTimeMs = 1000L
        )
        composeTestRule.setContent {
            AssistantBlockComposable(block = block)
        }
        composeTestRule.onNodeWithText("Duration block").assertIsDisplayed()
    }
}