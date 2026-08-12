package com.openminis.app.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openminis.app.R
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingMarkdownTextTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun streamingMarkdownText_rendersContent() {
        composeTestRule.setContent {
            StreamingMarkdownText(
                content = "Hello World",
                isStreaming = false,
                modifier = Modifier.testTag("streamingMarkdownText")
            )
        }
        composeTestRule.onNodeWithTag("streamingMarkdownText").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hello World").assertIsDisplayed()
    }

    @Test
    fun streamingMarkdownText_rendersWithDefaultParameters() {
        composeTestRule.setContent {
            StreamingMarkdownText(
                content = "Test Content",
                isStreaming = false
            )
        }
        composeTestRule.onNodeWithText("Test Content").assertIsDisplayed()
    }

    @Test
    fun streamingMarkdownText_rendersWithShardId() {
        composeTestRule.setContent {
            StreamingMarkdownText(
                content = "Shard Content",
                isStreaming = false,
                shardId = TextShardId("message1", "shard1")
            )
        }
        composeTestRule.onNodeWithText("Shard Content").assertIsDisplayed()
    }

    @Test
    fun streamingMarkdownText_rendersStreamingContent() {
        composeTestRule.setContent {
            StreamingMarkdownText(
                content = "Streaming Content",
                isStreaming = true
            )
        }
        composeTestRule.onNodeWithText("Streaming Content").assertIsDisplayed()
    }

    @Test
    fun streamingMarkdownText_rendersHeading() {
        composeTestRule.setContent {
            StreamingMarkdownText(
                content = "# Heading",
                isStreaming = false
            )
        }
        composeTestRule.onNodeWithText("Heading").assertIsDisplayed()
    }

    @Test
    fun streamingMarkdownText_rendersBoldText() {
        composeTestRule.setContent {
            StreamingMarkdownText(
                content = "**Bold Text**",
                isStreaming = false
            )
        }
        composeTestRule.onNodeWithText("Bold Text").assertIsDisplayed()
    }

    @Test
    fun streamingMarkdownText_rendersItalicText() {
        composeTestRule.setContent {
            StreamingMarkdownText(
                content = "*Italic Text*",
                isStreaming = false
            )
        }
        composeTestRule.onNodeWithText("Italic Text").assertIsDisplayed()
    }

    @Test
    fun streamingMarkdownText_rendersCodeBlock() {
        composeTestRule.setContent {
            StreamingMarkdownText(
                content = "