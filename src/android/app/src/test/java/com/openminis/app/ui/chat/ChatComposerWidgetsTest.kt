package com.openminis.app.ui.chat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.theme.ChatColors
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.InputAttachment
import com.openminis.app.ui.chat.ToolBlockStatus
import com.openminis.app.ui.chat.toolAccentColor
import com.openminis.app.ui.chat.ToolCheckColor
import com.openminis.app.ui.chat.ToolErrorColor
import com.openminis.app.ui.chat.ToolCancelColor
import com.openminis.app.ui.chat.LocalToolPreviewEnabled
import com.openminis.app.ui.chat.rememberSystemResourceMonitor
import com.openminis.app.ui.chat.rememberBrowserLiveSnapshot
import com.openminis.app.ui.chat.extractShellCommand
import com.openminis.app.ui.chat.extractPartialJsonString
import com.openminis.app.ui.chat.toolIconFor
import com.openminis.app.ui.chat.ToolMemoryAccent
import org.junit.jupiter.api.Assertions.assertTrue

class ChatComposerWidgetsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun attachmentChip_renders() {
        val attachment = InputAttachment(
            uri = android.net.Uri.parse("content://test/file.txt"),
            fileName = "test.txt",
            mimeType = "text/plain",
            fileSize = 100L,
            isImage = false
        )
        var removed = false
        composeTestRule.setContent {
            AttachmentChip(
                attachment = attachment,
                onRemove = { removed = true },
                onClick = {},
                onLongClick = {}
            )
        }
        composeTestRule.onNodeWithText("test.txt").assertExists()
        composeTestRule.onNodeWithContentDescription("Remove").performClick()
        assertTrue(removed)
    }

    @Test
    fun attachmentChip_rendersImage() {
        val attachment = InputAttachment(
            uri = android.net.Uri.parse("content://test/image.jpg"),
            fileName = "image.jpg",
            mimeType = "image/jpeg",
            fileSize = 200L,
            isImage = true
        )
        composeTestRule.setContent {
            AttachmentChip(
                attachment = attachment,
                onRemove = {},
                onClick = {},
                onLongClick = null
            )
        }
        composeTestRule.onNodeWithContentDescription("image.jpg").assertExists()
    }

    @Test
    fun attachmentChip_clickTriggersOnClick() {
        val attachment = InputAttachment(
            uri = android.net.Uri.parse("content://test/file.txt"),
            fileName = "test.txt",
            mimeType = "text/plain",
            fileSize = 100L,
            isImage = false
        )
        var clicked = false
        composeTestRule.setContent {
            AttachmentChip(
                attachment = attachment,
                onRemove = {},
                onClick = { clicked = true },
                onLongClick = null
            )
        }
        composeTestRule.onNodeWithText("test.txt").performClick()
        assertTrue(clicked)
    }

    @Test
    fun inputCircleButton_renders() {
        composeTestRule.setContent {
            InputCircleButton(
                onClick = {},
                content = { Text("A") }
            )
        }
        composeTestRule.onNodeWithText("A").assertExists()
    }

    @Test
    fun inputCircleButton_clickTriggersOnClick() {
        var clicked = false
        composeTestRule.setContent {
            InputCircleButton(
                onClick = { clicked = true },
                content = { Icon(Icons.Default.Close, contentDescription = "Icon") }
            )
        }
        composeTestRule.onNodeWithContentDescription("Icon").performClick()
        assertTrue(clicked)
    }

    @Test
    fun inputCircleButton_defaultParameters() {
        composeTestRule.setContent {
            InputCircleButton(
                onClick = {},
                content = { Text("Default") }
            )
        }
        composeTestRule.onNodeWithText("Default").assertExists()
    }

    @Test
    fun floatingToolStatusBar_rendersWithRunningBlock() {
        val blocks = listOf(
            AssistantBlock(
                id = "1",
                toolName = "shell_execute",
                toolStatus = ToolBlockStatus.RUNNING,
                toolArgs = "{}",
                content = "running",
                toolTitle = "Shell",
                imageFilePath = null
            )
        )
        composeTestRule.setContent {
            FloatingToolStatusBar(
                toolBlocks = blocks,
                onStop = null,
                onOpenDetail = {},
                onOpenTerminalWithCommand = {}
            )
        }
        composeTestRule.onNodeWithText("Shell").assertExists()
        composeTestRule.onNodeWithTag("CircularProgressIndicator").assertExists()
    }

    @Test
    fun floatingToolStatusBar_rendersWithSuccessBlock() {
        val blocks = listOf(
            AssistantBlock(
                id = "1",
                toolName = "file_read",
                toolStatus = ToolBlockStatus.SUCCESS,
                toolArgs = "{}",
                content = "success",
                toolTitle = "File Read",
                imageFilePath = null
            )
        )
        composeTestRule.setContent {
            FloatingToolStatusBar(
                toolBlocks = blocks,
                onStop = null,
                onOpenDetail = {},
                onOpenTerminalWithCommand = {}
            )
        }
        composeTestRule.onNodeWithText("File Read").assertExists()
        composeTestRule.onNodeWithContentDescription("CheckCircle").assertExists()
    }

    @Test
    fun floatingToolStatusBar_rendersWithFailedBlock() {
        val blocks = listOf(
            AssistantBlock(
                id = "1",
                toolName = "file_write",
                toolStatus = ToolBlockStatus.FAILED,
                toolArgs = "{}",
                content = "failed",
                toolTitle = "Write",
                imageFilePath = null
            )
        )
        composeTestRule.setContent {
            FloatingToolStatusBar(
                toolBlocks = blocks,
                onStop = null,
                onOpenDetail = {},
                onOpenTerminalWithCommand = {}
            )
        }
        composeTestRule.onNodeWithText("Write").assertExists()
        composeTestRule.onNodeWithContentDescription("Error").assertExists()
    }

    @Test
    fun floatingToolStatusBar_rendersWithCancelledBlock() {
        val blocks = listOf(
            AssistantBlock(
                id = "1",
                toolName = "browser_use",
                toolStatus = ToolBlockStatus.CANCELLED,
                toolArgs = "{}",
                content = "cancelled",
                toolTitle = "Browser",
                imageFilePath = null
            )
        )
        composeTestRule.setContent {
            FloatingToolStatusBar(
                toolBlocks = blocks,
                onStop = null,
                onOpenDetail = {},
                onOpenTerminalWithCommand = {}
            )
        }
        composeTestRule.onNodeWithText("Browser").assertExists()
        composeTestRule.onNodeWithContentDescription("Close").assertExists()
    }

    @Test
    fun floatingToolStatusBar_navigationBetweenBlocks() {
        val blocks = listOf(
            AssistantBlock(
                id = "1",
                toolName = "tool1",
                toolStatus = ToolBlockStatus.SUCCESS,
                toolArgs = "{}",
                content = "first",
                toolTitle = "First",
                imageFilePath = null
            ),
            AssistantBlock(
                id = "2",
                toolName = "tool2",
                toolStatus = ToolBlockStatus.SUCCESS,
                toolArgs = "{}",
                content = "second",
                toolTitle = "Second",
                imageFilePath = null
            )
        )
        composeTestRule.setContent {
            FloatingToolStatusBar(
                toolBlocks = blocks,
                onStop = null,
                onOpenDetail = {},
                onOpenTerminalWithCommand = {}
            )
        }
        composeTestRule.onNodeWithText("1/2").assertExists()
        composeTestRule.onNodeWithContentDescription("Next").performClick()
        composeTestRule.onNodeWithText("2/2").assertExists()
        composeTestRule.onNodeWithContentDescription("Previous").performClick()
        composeTestRule.onNodeWithText("1/2").assertExists()
    }

    @Test
    fun floatingToolStatusBar_defaultParameters() {
        val blocks = listOf(
            AssistantBlock(
                id = "1",
                toolName = "default",
                toolStatus = ToolBlockStatus.SUCCESS,
                toolArgs = "{}",
                content = "default",
                toolTitle = "Default",
                imageFilePath = null
            )
        )
        composeTestRule.setContent {
            FloatingToolStatusBar(
                toolBlocks = blocks,
                onStop = null,
                onOpenDetail = {},
                onOpenTerminalWithCommand = {}
            )
        }
        composeTestRule.onNodeWithText("Default").assertExists()
    }

    @Test
    fun thinkingLevelPicker_renders() {
        val levels = listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)
        var selected = ThinkingLevel.OFF
        composeTestRule.setContent {
            ThinkingLevelPicker(
                current = ThinkingLevel.LOW,
                availableLevels = levels,
                onSelect = { selected = it }
            )
        }
        composeTestRule.onNodeWithText("Off").assertExists()
        composeTestRule.onNodeWithText("Low").assertExists()
        composeTestRule.onNodeWithText("Medium").assertExists()
        composeTestRule.onNodeWithText("High").assertExists()
    }

    @Test
    fun thinkingLevelPicker_clickSelectsLevel() {
        val levels = listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM)
        var selected = ThinkingLevel.OFF
        composeTestRule.setContent {
            ThinkingLevelPicker(
                current = ThinkingLevel.OFF,
                availableLevels = levels,
                onSelect = { selected = it }
            )
        }
        composeTestRule.onNodeWithText("Low").performClick()
        assertTrue(selected == ThinkingLevel.LOW)
    }

    @Test
    fun thinkingLevelPicker_clickOnActiveTogglesOff() {
        val levels = listOf(ThinkingLevel.OFF, ThinkingLevel.LOW)
        var selected = ThinkingLevel.LOW
        composeTestRule.setContent {
            ThinkingLevelPicker(
                current = ThinkingLevel.LOW,
                availableLevels = levels,
                onSelect = { selected = it }
            )
        }
        composeTestRule.onNodeWithText("Low").performClick()
        assertTrue(selected == ThinkingLevel.OFF)
    }

    @Test
    fun thinkingLevelPicker_defaultParameters() {
        val levels = listOf(ThinkingLevel.OFF, ThinkingLevel.LOW)
        composeTestRule.setContent {
            ThinkingLevelPicker(
                current = ThinkingLevel.OFF,
                availableLevels = levels,
                onSelect = {}
            )
        }
        composeTestRule.onNodeWithText("Off").assertExists()
        composeTestRule.onNodeWithText("Low").assertExists()
    }
}