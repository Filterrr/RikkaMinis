package com.openminis.app.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class LargeContentGuardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @BeforeEach
    fun setUp() {
        // Setup common test state if needed
    }

    @Test
    fun `LargeContentBadge renders with preview text`() {
        val content = "A".repeat(10_000)
        var expanded = false
        composeTestRule.setContent {
            LargeContentBadge(
                content = content,
                stableKey = "test-key-1",
                onExpand = { expanded = true }
            )
        }
        composeTestRule.onNodeWithText(content.take(LARGE_MESSAGE_PREVIEW_CHARS) + "…").assertIsDisplayed()
    }

    @Test
    fun `LargeContentBadge expand button triggers onExpand`() {
        val content = "A".repeat(10_000)
        var expanded = false
        composeTestRule.setContent {
            LargeContentBadge(
                content = content,
                stableKey = "test-key-2",
                onExpand = { expanded = true }
            )
        }
        composeTestRule.onNodeWithText("展开").performClick()
        assert(expanded)
    }

    @Test
    fun `LargeContentBadge export button clickable`() {
        val content = "A".repeat(10_000)
        var expanded = false
        composeTestRule.setContent {
            LargeContentBadge(
                content = content,
                stableKey = "test-key-3",
                onExpand = { expanded = true }
            )
        }
        composeTestRule.onNodeWithText("导出").assertIsDisplayed().performClick()
    }

    @Test
    fun `LargeContentBadge displays size label`() {
        val content = "A".repeat(10_000)
        var expanded = false
        composeTestRule.setContent {
            LargeContentBadge(
                content = content,
                stableKey = "test-key-4",
                onExpand = { expanded = true }
            )
        }
        composeTestRule.onNodeWithText("10.0 KB").assertIsDisplayed()
    }

    @Test
    fun `LargeContentGuard renders content when not collapsed`() {
        composeTestRule.setContent {
            LargeContentGuard(
                content = "Hello World",
                isStreaming = false,
                stableKey = "test-key-5",
                renderer = { androidx.compose.material3.Text("Rendered Content", modifier = androidx.compose.ui.Modifier.testTag("renderer")) }
            )
        }
        composeTestRule.onNodeWithTag("renderer").assertIsDisplayed()
    }

    @Test
    fun `LargeContentGuard collapses large content`() {
        val content = "A".repeat(33_000)
        composeTestRule.setContent {
            LargeContentGuard(
                content = content,
                isStreaming = false,
                stableKey = "test-key-6",
                renderer = { androidx.compose.material3.Text("Rendered Content") }
            )
        }
        composeTestRule.onNodeWithText("展开").assertIsDisplayed()
    }

    @Test
    fun `LargeContentGuard expands on button click`() {
        val content = "A".repeat(33_000)
        composeTestRule.setContent {
            LargeContentGuard(
                content = content,
                isStreaming = false,
                stableKey = "test-key-7",
                renderer = { androidx.compose.material3.Text("Rendered Content", modifier = androidx.compose.ui.Modifier.testTag("renderer")) }
            )
        }
        composeTestRule.onNodeWithText("展开").performClick()
        composeTestRule.onNodeWithTag("renderer").assertIsDisplayed()
    }

    @Test
    fun `LargeContentGuard shows degraded notice when streaming long content`() {
        val content = "A".repeat(10_000)
        composeTestRule.setContent {
            LargeContentGuard(
                content = content,
                isStreaming = true,
                stableKey = "test-key-8",
                renderer = { androidx.compose.material3.Text("Rendered Content") }
            )
        }
        composeTestRule.onNodeWithText("流式输出内容过长，已截断显示最后部分").assertIsDisplayed()
    }

    @Test
    fun `LargeContentGuard renders short content during streaming`() {
        val content = "Short content"
        composeTestRule.setContent {
            LargeContentGuard(
                content = content,
                isStreaming = true,
                stableKey = "test-key-9",
                renderer = { androidx.compose.material3.Text("Rendered Content", modifier = androidx.compose.ui.Modifier.testTag("renderer")) }
            )
        }
        composeTestRule.onNodeWithTag("renderer").assertIsDisplayed()
    }

    @Test
    fun `LargeContentGuard default parameters render`() {
        val content = "A".repeat(33_000)
        composeTestRule.setContent {
            LargeContentGuard(
                content = content,
                isStreaming = false,
                stableKey = "test-key-10",
                renderer = { androidx.compose.material3.Text("Rendered Content") }
            )
        }
        composeTestRule.onNodeWithText("展开").assertIsDisplayed()
    }
}