package com.openminis.app.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.openminis.app.data.repository.MemoryRepository
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class MemoryDetailScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @BeforeEach
    fun setUp() {
        // Setup common test data if needed
    }

    @Test
    fun memoryFileViewerBody_rendersContent() {
        composeTestRule.setContent {
            MemoryFileViewerBody(
                initialContent = "Test file content",
                isEditing = false,
                editedContent = "",
                onEditedContentChange = {},
                showSavedToast = false
            )
        }

        composeTestRule.onNodeWithText("Test file content").assertIsDisplayed()
    }

    @Test
    fun memoryFileViewerBody_rendersEmptyPlaceholder() {
        composeTestRule.setContent {
            MemoryFileViewerBody(
                initialContent = "",
                isEditing = false,
                editedContent = "",
                onEditedContentChange = {},
                showSavedToast = false
            )
        }

        composeTestRule.onNodeWithText("(empty)").assertIsDisplayed()
    }

    @Test
    fun memoryFileViewerBody_editingMode_acceptsTextInput() {
        composeTestRule.setContent {
            var content by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            MemoryFileViewerBody(
                initialContent = "Original",
                isEditing = true,
                editedContent = content,
                onEditedContentChange = { content = it },
                showSavedToast = false
            )
        }

        composeTestRule.onNodeWithText("").performTextInput("New content")
        composeTestRule.onNodeWithText("New content").assertIsDisplayed()
    }

    @Test
    fun memoryFileViewerBody_showsSavedToast() {
        composeTestRule.setContent {
            MemoryFileViewerBody(
                initialContent = "Content",
                isEditing = false,
                editedContent = "",
                onEditedContentChange = {},
                showSavedToast = true
            )
        }

        composeTestRule.onNodeWithText("Saved").assertIsDisplayed()
    }

    @Test
    fun savedToast_defaultModifier_renders() {
        composeTestRule.setContent {
            SavedToast()
        }

        composeTestRule.onNodeWithText("Saved").assertIsDisplayed()
    }

    @Test
    fun memoryWriteDetailBody_rendersWrittenContent() {
        val record = MemoryToolRecord(
            writtenContent = "Written test content",
            output = "Tool output",
            keywords = null
        )

        composeTestRule.setContent {
            MemoryWriteDetailBody(
                record = record,
                isEditing = false,
                editedContent = "",
                onEditedContentChange = {},
                showSavedToast = false
            )
        }

        composeTestRule.onNodeWithText("Written test content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tool output").assertIsDisplayed()
    }

    @Test
    fun memoryWriteDetailBody_rendersOnlyOutput_whenNoWrittenContent() {
        val record = MemoryToolRecord(
            writtenContent = null,
            output = "Tool output only",
            keywords = null
        )

        composeTestRule.setContent {
            MemoryWriteDetailBody(
                record = record,
                isEditing = false,
                editedContent = "",
                onEditedContentChange = {},
                showSavedToast = false
            )
        }

        composeTestRule.onNodeWithText("Tool output only").assertIsDisplayed()
        composeTestRule.onNodeWithText("Written Content").assertDoesNotExist()
    }

    @Test
    fun memoryWriteDetailBody_editingMode_works() {
        val record = MemoryToolRecord(
            writtenContent = "Original",
            output = "Output",
            keywords = null
        )

        composeTestRule.setContent {
            var content by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            MemoryWriteDetailBody(
                record = record,
                isEditing = true,
                editedContent = content,
                onEditedContentChange = { content = it },
                showSavedToast = false
            )
        }

        composeTestRule.onNodeWithText("").performTextInput("Edited content")
        composeTestRule.onNodeWithText("Edited content").assertIsDisplayed()
    }

    @Test
    fun memoryGetDetailBody_rendersOutput() {
        val record = MemoryToolRecord(
            writtenContent = null,
            output = "Get tool output",
            keywords = null
        )

        composeTestRule.setContent {
            MemoryGetDetailBody(record = record)
        }

        composeTestRule.onNodeWithText("Get tool output").assertIsDisplayed()
    }

    @Test
    fun memoryGetDetailBody_rendersKeywords() {
        val record = MemoryToolRecord(
            writtenContent = null,
            output = "Output",
            keywords = "test keyword"
        )

        composeTestRule.setContent {
            MemoryGetDetailBody(record = record)
        }

        composeTestRule.onNodeWithText("test keyword").assertIsDisplayed()
    }

    @Test
    fun revokeConfirmDialog_confirmButton_click() {
        var confirmed = false
        var dismissed = false

        composeTestRule.setContent {
            RevokeConfirmDialog(
                onConfirm = { confirmed = true },
                onDismiss = { dismissed = true }
            )
        }

        composeTestRule.onNodeWithText("Revoke").performClick()
        assert(confirmed)
        assert(!dismissed)
    }

    @Test
    fun revokeConfirmDialog_dismissButton_click() {
        var confirmed = false
        var dismissed = false

        composeTestRule.setContent {
            RevokeConfirmDialog(
                onConfirm = { confirmed = true },
                onDismiss = { dismissed = true }
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        assert(dismissed)
        assert(!confirmed)
    }

    @Test
    fun mutationResultDialog_success_showsMessage() {
        val result = MemoryRepository.EntryMutationResult.Success("2024-01-01")

        composeTestRule.setContent {
            MutationResultDialog(
                result = result,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Entry removed for 2024-01-01").assertIsDisplayed()
    }

    @Test
    fun mutationResultDialog_notFound_showsMessage() {
        val result = MemoryRepository.EntryMutationResult.NotFound

        composeTestRule.setContent {
            MutationResultDialog(
                result = result,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Entry not found").assertIsDisplayed()
    }

    @Test
    fun mutationResultDialog_ioError_showsMessage() {
        val result = MemoryRepository.EntryMutationResult.IOError("IO error occurred")

        composeTestRule.setContent {
            MutationResultDialog(
                result = result,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Error: IO error occurred").assertIsDisplayed()
    }

    @Test
    fun mutationResultDialog_confirmButton_click() {
        var dismissed = false
        val result = MemoryRepository.EntryMutationResult.Success("2024-01-01")

        composeTestRule.setContent {
            MutationResultDialog(
                result = result,
                onDismiss = { dismissed = true }
            )
        }

        composeTestRule.onNodeWithText("OK").performClick()
        assert(dismissed)
    }
}