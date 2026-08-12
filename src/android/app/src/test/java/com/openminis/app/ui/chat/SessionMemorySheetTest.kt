package com.openminis.app.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openminis.app.data.repository.MemoryRepository
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock

class SessionMemorySheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val memoryRepository: MemoryRepository = mock()

    private val toolRecords = listOf(
        MemoryToolRecord(
            title = "Test Record",
            isWrite = true,
            preview = "Test preview",
            output = "Test output",
            writtenContent = "Test written content"
        ),
        MemoryToolRecord(
            title = "Get Record",
            isWrite = false,
            preview = "Get preview",
            output = "Get output"
        )
    )

    @Test
    fun sessionMemorySheet_rendersWithDefaultParameters() {
        composeTestRule.setContent {
            SessionMemorySheet(
                memoryRepository = memoryRepository,
                toolRecords = emptyList(),
                onDismiss = {},
                onRevokeRecord = { mock() },
                onSaveRecord = { _, _ -> mock() }
            )
        }

        composeTestRule.onNodeWithText("Session Memory").assertIsDisplayed()
    }

    @Test
    fun sessionMemorySheet_rendersToolRecords() {
        composeTestRule.setContent {
            SessionMemorySheet(
                memoryRepository = memoryRepository,
                toolRecords = toolRecords,
                onDismiss = {},
                onRevokeRecord = { mock() },
                onSaveRecord = { _, _ -> mock() }
            )
        }

        composeTestRule.onNodeWithText("Test Record").assertIsDisplayed()
        composeTestRule.onNodeWithText("Get Record").assertIsDisplayed()
    }

    @Test
    fun sessionMemorySheet_clickOnWriteRecord_opensWriteMode() {
        composeTestRule.setContent {
            SessionMemorySheet(
                memoryRepository = memoryRepository,
                toolRecords = toolRecords,
                onDismiss = {},
                onRevokeRecord = { mock() },
                onSaveRecord = { _, _ -> mock() }
            )
        }

        composeTestRule.onNodeWithText("Test Record").performClick()
        
        // Verify edit and save buttons are displayed
        composeTestRule.onNodeWithContentDescription("Edit").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun sessionMemorySheet_clickOnGetRecord_opensGetMode() {
        composeTestRule.setContent {
            SessionMemorySheet(
                memoryRepository = memoryRepository,
                toolRecords = toolRecords,
                onDismiss = {},
                onRevokeRecord = { mock() },
                onSaveRecord = { _, _ -> mock() }
            )
        }

        composeTestRule.onNodeWithText("Get Record").performClick()
        
        // Verify no edit button for get mode
        composeTestRule.onNodeWithContentDescription("Edit").assertDoesNotExist()
    }

    @Test
    fun sessionMemorySheet_clickBackButton_returnsToList() {
        composeTestRule.setContent {
            SessionMemorySheet(
                memoryRepository = memoryRepository,
                toolRecords = toolRecords,
                onDismiss = {},
                onRevokeRecord = { mock() },
                onSaveRecord = { _, _ -> mock() }
            )
        }

        composeTestRule.onNodeWithText("Test Record").performClick()
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        
        composeTestRule.onNodeWithText("Session Memory").assertIsDisplayed()
    }

    @Test
    fun sessionMemorySheet_clickAutoFile_opensFileMode() {
        Mockito.`when`(memoryRepository.readFile("SOUL.md")).thenReturn("Test content")
        
        composeTestRule.setContent {
            SessionMemorySheet(
                memoryRepository = memoryRepository,
                toolRecords = emptyList(),
                onDismiss = {},
                onRevokeRecord = { mock() },
                onSaveRecord = { _, _ -> mock() }
            )
        }

        composeTestRule.onNodeWithText("SOUL.md").performClick()
        
        composeTestRule.onNodeWithText("SOUL.md").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").assertIsDisplayed()
    }

    @Test
    fun sessionMemorySheet_editAndSaveFile() {
        Mockito.`when`(memoryRepository.readFile("SOUL.md")).thenReturn("Test content")
        
        composeTestRule.setContent {
            SessionMemorySheet(
                memoryRepository = memoryRepository,
                toolRecords = emptyList(),
                onDismiss = {},
                onRevokeRecord = { mock() },
                onSaveRecord = { _, _ -> mock() }
            )
        }

        composeTestRule.onNodeWithText("SOUL.md").performClick()
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.onNodeWithText("Saved").assertIsDisplayed()
    }

    @Test
    fun sessionMemorySheet_revokeRecord_showsConfirmDialog() {
        composeTestRule.setContent {
            SessionMemorySheet(
                memoryRepository = memoryRepository,
                toolRecords = toolRecords,
                onDismiss = {},
                onRevokeRecord = { mock() },
                onSaveRecord = { _, _ -> mock() }
            )
        }

        composeTestRule.onNodeWithText("Test Record").performClick()
        composeTestRule.onNodeWithContentDescription("Revoke").performClick()
        
        composeTestRule.onNodeWithText("Confirm").assertIsDisplayed()
    }
}