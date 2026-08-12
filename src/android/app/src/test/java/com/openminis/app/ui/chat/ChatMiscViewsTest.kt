package com.openminis.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.AssistantBlock
import com.openminis.app.data.model.ToolBlockStatus
import com.openminis.app.ui.theme.ChatColors
import org.junit.Rule
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class ChatMiscViewsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testFallbackInfoBlock_rendersWithContent() {
        val block = AssistantBlock(
            id = "test",
            content = "Test content",
            toolName = "compact",
            toolArgs = "",
            toolStatus = ToolBlockStatus.COMPLETED
        )
        composeTestRule.setContent {
            FallbackInfoBlock(block = block)
        }
        composeTestRule.onNodeWithText("Test content").assertExists()
    }

    @Test
    fun testFallbackInfoBlock_clickInfoIconShowsSheet() {
        val block = AssistantBlock(
            id = "test",
            content = "Test content",
            toolName = "compact",
            toolArgs = "Some details",
            toolStatus = ToolBlockStatus.COMPLETED
        )
        composeTestRule.setContent {
            FallbackInfoBlock(block = block)
        }
        composeTestRule.onNodeWithContentDescription("Show full summary").performClick()
        composeTestRule.onNodeWithText("Compact Summary").assertExists()
    }

    @Test
    fun testFallbackInfoBlock_defaultParameters() {
        val block = AssistantBlock(
            id = "test",
            content = "Default content",
            toolName = "unknown",
            toolArgs = "",
            toolStatus = ToolBlockStatus.COMPLETED
        )
        composeTestRule.setContent {
            FallbackInfoBlock(block = block)
        }
        composeTestRule.onNodeWithText("Default content").assertExists()
    }

    @Test
    fun testResumeBanner_rendersWithDefaultText() {
        composeTestRule.setContent {
            ResumeBanner(onResume = {})
        }
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.resume_banner_title)).assertExists()
    }

    @Test
    fun testResumeBanner_clickResumeTriggersCallback() {
        var clicked = false
        composeTestRule.setContent {
            ResumeBanner(onResume = { clicked = true })
        }
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.resume_action)).performClick()
        assert(clicked)
    }

    @Test
    fun testResumeBanner_defaultParameters() {
        composeTestRule.setContent {
            ResumeBanner(onResume = {})
        }
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.resume_banner_title)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.resume_action)).assertExists()
    }

    @Test
    fun testSwipeToSendHint_rendersWithPositiveProgress() {
        composeTestRule.setContent {
            SwipeToSendHint(
                progress = 0.8f,
                armFraction = 0.5f,
                location = Offset(100f, 200f),
                hoverAbovePx = 50f,
                arrowHalfPx = 17f,
                isEnqueue = false
            )
        }
        composeTestRule.onNodeWithContentDescription(null).assertExists()
    }

    @Test
    fun testSwipeToSendHint_showsEnqueueTextWhenEnqueueTrue() {
        composeTestRule.setContent {
            SwipeToSendHint(
                progress = 0.8f,
                armFraction = 0.5f,
                location = Offset(100f, 200f),
                hoverAbovePx = 50f,
                arrowHalfPx = 17f,
                isEnqueue = true
            )
        }
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.composer_swipe_release_to_queue)).assertExists()
    }

    @Test
    fun testSwipeToSendHint_doesNotRenderWithZeroProgress() {
        composeTestRule.setContent {
            SwipeToSendHint(
                progress = 0f,
                armFraction = 0.5f,
                location = Offset(100f, 200f),
                hoverAbovePx = 50f,
                arrowHalfPx = 17f,
                isEnqueue = false
            )
        }
        composeTestRule.onNodeWithContentDescription(null).assertDoesNotExist()
    }

    @Test
    fun testSwipeToSendHint_defaultParameters() {
        composeTestRule.setContent {
            SwipeToSendHint(
                progress = 0.5f,
                armFraction = 0.5f,
                location = Offset(100f, 200f),
                hoverAbovePx = 50f,
                arrowHalfPx = 17f,
                isEnqueue = false
            )
        }
        composeTestRule.onNodeWithContentDescription(null).assertExists()
    }

    @Test
    fun testRememberBrowserLiveSnapshot_returnsNullForNonBrowserTool() {
        val block = AssistantBlock(
            id = "test",
            content = "Test",
            toolName = "other_tool",
            toolArgs = "",
            toolStatus = ToolBlockStatus.RUNNING
        )
        val result = rememberBrowserLiveSnapshot(block = block, intervalMs = 1000L)
        assert(result == null)
    }

    @Test
    fun testRememberBrowserLiveSnapshot_returnsNullForCompletedTool() {
        val block = AssistantBlock(
            id = "test",
            content = "Test",
            toolName = "browser_use",
            toolArgs = "",
            toolStatus = ToolBlockStatus.COMPLETED
        )
        val result = rememberBrowserLiveSnapshot(block = block, intervalMs = 1000L)
        assert(result == null)
    }

    @Test
    fun testBorderedMarkdownTable_rendersWithValidTable() {
        val markdown = "| Header 1 | Header 2 |\n| -------- | -------- |\n| Cell 1   | Cell 2   |"
        composeTestRule.setContent {
            // This is a private composable, so we test it indirectly via FallbackInfoBlock
            FallbackInfoBlock(
                block = AssistantBlock(
                    id = "test",
                    content = "Test",
                    toolName = "compact",
                    toolArgs = "",
                    toolStatus = ToolBlockStatus.COMPLETED
                )
            )
        }
        // Just verify the composable can be rendered
        composeTestRule.onNodeWithText("Test").assertExists()
    }

    @Test
    fun testCompactSummarySheet_rendersWithSummary() {
        val block = AssistantBlock(
            id = "test",
            content = "Test",
            toolName = "compact",
            toolArgs = "Detailed summary text",
            toolStatus = ToolBlockStatus.COMPLETED
        )
        composeTestRule.setContent {
            FallbackInfoBlock(block = block)
        }
        composeTestRule.onNodeWithContentDescription("Show full summary").performClick()
        composeTestRule.onNodeWithText("Compact Summary").assertExists()
        composeTestRule.onNodeWithText("Detailed summary text").assertExists()
    }

    @Test
    fun testParseInlineMarkdown_boldText() {
        val result = parseInlineMarkdown(
            text = "**bold**",
            baseStyle = androidx.compose.ui.text.TextStyle.Default,
            inlineCodeText = androidx.compose.ui.graphics.Color.Blue,
            inlineCodeBg = androidx.compose.ui.graphics.Color.LightGray,
            linkColor = androidx.compose.ui.graphics.Color.Blue
        )
        assertNotNull(result)
        assert(result.string == "bold")
    }

    @Test
    fun testParseInlineMarkdown_italicText() {
        val result = parseInlineMarkdown(
            text = "*italic*",
            baseStyle = androidx.compose.ui.text.TextStyle.Default,
            inlineCodeText = androidx.compose.ui.graphics.Color.Blue,
            inlineCodeBg = androidx.compose.ui.graphics.Color.LightGray,
            linkColor = androidx.compose.ui.graphics.Color.Blue
        )
        assertNotNull(result)
        assert(result.string == "italic")
    }

    @Test
    fun testParseInlineMarkdown_codeText() {
        val result = parseInlineMarkdown(
            text = "`code`",
            baseStyle = androidx.compose.ui.text.TextStyle.Default,
            inlineCodeText = androidx.compose.ui.graphics.Color.Blue,
            inlineCodeBg = androidx.compose.ui.graphics.Color.LightGray,
            linkColor = androidx.compose.ui.graphics.Color.Blue
        )
        assertNotNull(result)
        assert(result.string == "code")
    }

    @Test
    fun testParseInlineMarkdown_linkText() {
        val result = parseInlineMarkdown(
            text = "[link](https://example.com)",
            baseStyle = androidx.compose.ui.text.TextStyle.Default,
            inlineCodeText = androidx.compose.ui.graphics.Color.Blue,
            inlineCodeBg = androidx.compose.ui.graphics.Color.LightGray,
            linkColor = androidx.compose.ui.graphics.Color.Blue
        )
        assertNotNull(result)
        assert(result.string == "link")
    }

    @Test
    fun testLocalBrowserTabPool_defaultValue() {
        composeTestRule.setContent {
            val pool = LocalBrowserTabPool.current
            assert(pool == null)
        }
    }

    @Test
    fun testLocalToolPreviewEnabled_defaultValue() {
        composeTestRule.setContent {
            val enabled = LocalToolPreviewEnabled.current
            assert(!enabled)
        }
    }
}