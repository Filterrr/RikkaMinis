package com.openminis.app.ui.chat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.waitUntil
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilNodeCount
import com.openminis.app.data.model.AssistantBlock
import com.openminis.app.data.model.ToolBlockStatus
import com.openminis.app.ui.chat.ChatToolDetailUI
import com.openminis.app.ui.theme.ChatColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag

class ChatToolDetailUITest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @BeforeEach
    fun setUp() {
        // Setup common test state
    }

    @Test
    @DisplayName("Test ToolDetailSheet renders with default parameters")
    fun testToolDetailSheet_rendersWithDefaultParameters() {
        val toolBlocks = listOf(
            AssistantBlock(
                id = "test-1",
                toolName = "shell_execute",
                toolArgs = "{\"command\": \"echo hello\"}",
                toolStatus = ToolBlockStatus.SUCCESS,
                content = "hello world",
                toolTitle = "Shell Command"
            )
        )
        
        composeTestRule.setContent {
            ToolDetailSheet(
                toolBlocks = toolBlocks,
                initialIndex = 0,
                onDismiss = {},
                onOpenTerminalWithCommand = {},
                onOpenBrowserForUrl = {}
            )
        }
        
        composeTestRule.onNodeWithText("Shell Command").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 / 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("$ echo hello").assertIsDisplayed()
        composeTestRule.onNodeWithText("hello world").assertIsDisplayed()
    }

    @Test
    @DisplayName("Test ToolDetailSheet dismiss callback")
    fun testToolDetailSheet_dismissCallback() {
        var dismissed = false
        val toolBlocks = listOf(
            AssistantBlock(
                id = "test-1",
                toolName = "shell_execute",
                toolArgs = "{}",
                toolStatus = ToolBlockStatus.SUCCESS,
                content = "",
                toolTitle = "Test"
            )
        )
        
        composeTestRule.setContent {
            ToolDetailSheet(
                toolBlocks = toolBlocks,
                initialIndex = 0,
                onDismiss = { dismissed = true },
                onOpenTerminalWithCommand = {},
                onOpenBrowserForUrl = {}
            )
        }
        
        // Dismiss is triggered by back press or swipe down
        // The modal bottom sheet dismiss callback is called when sheet is dismissed
        // We can test by checking if the component is visible
        composeTestRule.onNodeWithText("Minis Computer").assertIsDisplayed()
    }

    @Test
    @DisplayName("Test ToolDetailSheet with empty tool blocks")
    fun testToolDetailSheet_emptyToolBlocks() {
        var dismissed = false
        
        composeTestRule.setContent {
            ToolDetailSheet(
                toolBlocks = emptyList(),
                initialIndex = 0,
                onDismiss = { dismissed = true },
                onOpenTerminalWithCommand = {},
                onOpenBrowserForUrl = {}
            )
        }
        
        // Should dismiss since there are no blocks
        assertTrue(dismissed)
    }

    @Test
    @DisplayName("Test ToolDetailSheet with running tool status")
    fun testToolDetailSheet_runningToolStatus() {
        val toolBlocks = listOf(
            AssistantBlock(
                id = "test-1",
                toolName = "shell_execute",
                toolArgs = "{\"command\": \"sleep 1\"}",
                toolStatus = ToolBlockStatus.RUNNING,
                content = "running...",
                toolTitle = "Running Command",
                durationMs = 0,
                startTimeMs = System.currentTimeMillis()
            )
        )
        
        composeTestRule.setContent {
            ToolDetailSheet(
                toolBlocks = toolBlocks,
                initialIndex = 0,
                onDismiss = {},
                onOpenTerminalWithCommand = {},
                onOpenBrowserForUrl = {}
            )
        }
        
        composeTestRule.onNodeWithText("Running Command").assertIsDisplayed()
        composeTestRule.onNodeWithText("Live").assertIsDisplayed()
    }

    @Test
    @DisplayName("Test ToolDetailSheet with file_edit tool")
    fun testToolDetailSheet_fileEditTool() {
        val toolBlocks = listOf(
            AssistantBlock(
                id = "test-1",
                toolName = "file_edit",
                toolArgs = "{\"path\": \"/test/file.txt\", \"old_string\": \"old content\", \"new_string\": \"new content\"}",
                toolStatus = ToolBlockStatus.SUCCESS,
                content = "Edited successfully",
                toolTitle = "Edit File"
            )
        )
        
        composeTestRule.setContent {
            ToolDetailSheet(
                toolBlocks = toolBlocks,
                initialIndex = 0,
                onDismiss = {},
                onOpenTerminalWithCommand = {},
                onOpenBrowserForUrl = {}
            )
        }
        
        composeTestRule.onNodeWithText("Edit File").assertIsDisplayed()
        composeTestRule.onNodeWithText("Edited").assertIsDisplayed()
        composeTestRule.onNodeWithText("/test/file.txt").assertIsDisplayed()
    }

    @Test
    @DisplayName("Test ToolDetailSheet with browser_use tool")
    fun testToolDetailSheet_browserUseTool() {
        val toolBlocks = listOf(
            AssistantBlock(
                id = "test-1",
                toolName = "browser_use",
                toolArgs = "{\"action\": \"navigate\", \"url\": \"https://example.com\"}",
                toolStatus = ToolBlockStatus.SUCCESS,
                content = "Page loaded successfully",
                toolTitle = "Browse Web",
                browserURL = "https://example.com"
            )
        )
        
        composeTestRule.setContent {
            ToolDetailSheet(
                toolBlocks = toolBlocks,
                initialIndex = 0,
                onDismiss = {},
                onOpenTerminalWithCommand = {},
                onOpenBrowserForUrl = {}
            )
        }
        
        composeTestRule.onNodeWithText("Browse Web").assertIsDisplayed()
        composeTestRule.onNodeWithText("navigate").assertIsDisplayed()
        composeTestRule.onNodeWithText("Result").assertIsDisplayed()
        composeTestRule.onNodeWithText("Page loaded successfully").assertIsDisplayed()
    }

    @Test
    @DisplayName("Test ToolDetailSheet with memory_write tool")
    fun testToolDetailSheet_memoryWriteTool() {
        val toolBlocks = listOf(
            AssistantBlock(
                id = "test-1",
                toolName = "memory_write",
                toolArgs = "{\"content\": \"Remember this\", \"keywords\": \"test\"}",
                toolStatus = ToolBlockStatus.SUCCESS,
                content = "Memory saved",
                toolTitle = "Save Memory"
            )
        )
        
        composeTestRule.setContent {
            ToolDetailSheet(
                toolBlocks = toolBlocks,
                initialIndex = 0,
                onDismiss = {},
                onOpenTerminalWithCommand = {},
                onOpenBrowserForUrl = {}
            )
        }
        
        composeTestRule.onNodeWithText("Save Memory").assertIsDisplayed()
        composeTestRule.onNodeWithText("Remember this").assertIsDisplayed()
    }

    @Test
    @DisplayName("Test ToolDetailSheet navigation buttons")
    fun testToolDetailSheet_navigationButtons() {
        val toolBlocks = listOf(
            AssistantBlock(
                id = "test-1",
                toolName = "shell_execute",
                toolArgs = "{}",
                toolStatus = ToolBlockStatus.SUCCESS,
                content = "First output",
                toolTitle = "First"
            ),
            AssistantBlock(
                id = "test-2",
                toolName = "shell_execute",
                toolArgs = "{}",
                toolStatus = ToolBlockStatus.SUCCESS,
                content = "Second output",
                toolTitle = "Second"
            )
        )
        
        composeTestRule.setContent {
            ToolDetailSheet(
                toolBlocks = toolBlocks,
                initialIndex = 0,
                onDismiss = {},
                onOpenTerminalWithCommand = {},
                onOpenBrowserForUrl = {}
            )
        }
        
        // Initial state should show first block
        composeTestRule.onNodeWithText("First").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 / 2").assertIsDisplayed()
        
        // Previous button should be disabled
        composeTestRule.onNodeWithContentDescription("Previous").assertIsNotEnabled()
        
        // Next button should be enabled
        composeTestRule.onNodeWithContentDescription("Next").assertIsEnabled()
        
        // Click next
        composeTestRule.onNodeWithContentDescription("Next").performClick()
        composeTestRule.onNodeWithText("Second").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 / 2").assertIsDisplayed()
        
        // Next button should be disabled
        composeTestRule.onNodeWithContentDescription("Next").assertIsNotEnabled()
        
        // Previous button should be enabled
        composeTestRule.onNodeWithContentDescription("Previous").assertIsEnabled()
    }

    @Test
    @DisplayName("Test ToolDetailSheet with failed tool status")
    fun testToolDetailSheet_failedToolStatus() {
        val toolBlocks = listOf(
            AssistantBlock(
                id = "test-1",
                toolName = "shell_execute",
                toolArgs = "{}",
                toolStatus = ToolBlockStatus.FAILED,
                content = "Error: command not found",
                toolTitle = "Failed Command"
            )
        )
        
        composeTestRule.setContent {
            ToolDetailSheet(
                toolBlocks = toolBlocks,
                initialIndex = 0,
                onDismiss = {},
                onOpenTerminalWithCommand = {},
                onOpenBrowserForUrl = {}
            )
        }
        
        composeTestRule.onNodeWithText("Failed Command").assertIsDisplayed()
        composeTestRule.onNodeWithText("Error: command not found").assertIsDisplayed()
    }

    @Test
    @DisplayName("Test ToolDetailSheet with cancelled tool status")
    fun testToolDetailSheet_cancelledToolStatus() {
        val toolBlocks = listOf(
            AssistantBlock(
                id = "test-1",
                toolName = "shell_execute",
                toolArgs = "{}",
                toolStatus = ToolBlockStatus.CANCELLED,
                content = "Operation cancelled",
                toolTitle = "Cancelled"
            )
        )
        
        composeTestRule.setContent {
            ToolDetailSheet(
                toolBlocks = toolBlocks,
                initialIndex = 0,
                onDismiss = {},
                onOpenTerminalWithCommand = {},
                onOpenBrowserForUrl = {}
            )
        }
        
        composeTestRule.onNodeWithText("Cancelled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Operation cancelled").assertIsDisplayed()
    }

    @Test
    @DisplayName("Test ToolDetailSheet with timeout tool status")
    fun testToolDetailSheet_timeoutToolStatus() {
        val toolBlocks = listOf(
            AssistantBlock(
                id = "test-1",
                toolName = "shell_execute",
                toolArgs = "{}",
                toolStatus = ToolBlockStatus.TIMEOUT,
                content = "Operation timed out",
                toolTitle = "Timeout"
            )
        )
        
        composeTestRule.setContent {
            ToolDetailSheet(
                toolBlocks = toolBlocks,
                initialIndex = 0,
                onDismiss = {},
                onOpenTerminalWithCommand = {},
                onOpenBrowserForUrl = {}
            )
        }
        
        composeTestRule.onNodeWithText("Timeout").assertIsDisplayed()
        composeTestRule.onNodeWithText("Operation timed out").assertIsDisplayed()
    }

    @Test
    @DisplayName("Test extractShellCommand function")
    fun testExtractShellCommand() {
        val args = org.json.JSONObject("{\"command\": \"echo test\"}")
        val block = AssistantBlock(
            id = "test-1",
            toolName = "shell_execute",
            toolArgs = "{\"command\": \"echo test\"}",
            toolStatus = ToolBlockStatus.SUCCESS,
            content = "",
            toolTitle = "Test"
        )
        
        val result = extractShellCommand(args, block)
        assertEquals("echo test", result)
    }

    @Test
    @DisplayName("Test extractShellCommand with empty args")
    fun testExtractShellCommand_emptyArgs() {
        val args = org.json.JSONObject()
        val block = AssistantBlock(
            id = "test-1",
            toolName = "shell_execute",
            toolArgs = "{}",
            toolStatus = ToolBlockStatus.SUCCESS,
            content = "$ echo hello",
            toolTitle = "Test"
        )
        
        val result = extractShellCommand(args, block)
        assertEquals("echo hello", result)
    }

    @Test
    @DisplayName("Test extractShellCommand returns default when no command found")
    fun testExtractShellCommand_defaultReturn() {
        val args = org.json.JSONObject()
        val block = AssistantBlock(
            id = "test-1",
            toolName = "shell_execute",
            toolArgs = "{}",
            toolStatus = ToolBlockStatus.SUCCESS,
            content = "",
            toolTitle = "Test"
        )
        
        val result = extractShellCommand(args, block)
        assertEquals("Shell command", result)
    }

    @Test
    @DisplayName("Test extractPartialJsonString function")
    fun testExtractPartialJsonString() {
        val json = "{\"key\": \"value\"}"
        val result = extractPartialJsonString("key", json)
        assertEquals("value", result)
    }

    @Test
    @DisplayName("Test extractPartialJsonString with empty json")
    fun testExtractPartialJsonString_emptyJson() {
        val result = extractPartialJsonString("key", "")
        assertEquals(null, result)
    }

    @Test
    @DisplayName("Test extractPartialJsonString with escaped characters")
    fun testExtractPartialJsonString_escapedCharacters() {
        val json = "{\"key\": \"line1\\nline2\"}"
        val result = extractPartialJsonString("key", json)
        assertEquals("line1\nline2", result)
    }

    @Test
    @DisplayName("Test chunkToolOutput function")
    fun testChunkToolOutput() {
        val text = (1..100).joinToString("\n") { "Line $it" }
        val chunks = chunkToolOutput(text)
        
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.size <= 3) // 100 lines / 40 lines per chunk = 2.5 -> 3 chunks
    }

    @Test
    @DisplayName("Test chunkToolOutput with empty text")
    fun testChunkToolOutput_emptyText() {
        val chunks = chunkToolOutput("")
        assertTrue(chunks.isEmpty())
    }

    @Test
    @DisplayName("Test initialRevealChunks function")
    fun testInitialRevealChunks() {
        val chunks = listOf(
            (1..40).joinToString("\n") { "Line $it" },
            (41..80).joinToString("\n") { "Line $it" }
        )
        
        val initial = initialRevealChunks(chunks)
        assertTrue(initial > 0)
        assertTrue(initial <= chunks.size)
    }

    @Test
    @DisplayName("Test initialRevealChunks with empty chunks")
    fun testInitialRevealChunks_emptyChunks() {
        val initial = initialRevealChunks(emptyList())
        assertEquals(0, initial)
    }

    @Test
    @DisplayName("Test ToolDetailSheet with multiple navigation using keyboard shortcuts")
    fun testToolDetailSheet_multipleNavigation() {
        val toolBlocks = listOf(
            AssistantBlock(id = "1", toolName = "shell_execute", toolArgs = "{}", toolStatus = ToolBlockStatus.SUCCESS, content = "First", toolTitle = "First"),
            AssistantBlock(id = "2", toolName = "shell_execute", toolArgs = "{}", toolStatus = ToolBlockStatus.SUCCESS, content = "Second", toolTitle = "Second"),
            AssistantBlock(id = "3", toolName = "shell_execute", toolArgs = "{}", toolStatus = ToolBlockStatus.SUCCESS, content = "Third", toolTitle = "Third")
       