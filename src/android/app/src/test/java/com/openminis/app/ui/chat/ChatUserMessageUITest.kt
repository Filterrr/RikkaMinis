package com.openminis.app.ui.chat

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Rule
import org.junit.jupiter.api.Test

class ChatUserMessageUITest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testMessage = ChatMessage(
        id = "test-message-1",
        content = "Hello, this is a test message",
        role = "user",
        isQueued = false,
        imageUris = emptyList(),
        attachmentUris = emptyList(),
        attachmentNames = emptyList(),
        timestamp = 0L
    )

    @Test
    fun userMessageBubble_rendersMessageContent() {
        composeTestRule.setContent {
            UserMessageBubble(message = testMessage)
        }

        composeTestRule.onNodeWithText("Hello, this is a test message")
            .assertIsDisplayed()
    }

    @Test
    fun userMessageBubble_rendersWithDefaultParameters() {
        composeTestRule.setContent {
            UserMessageBubble(message = testMessage)
        }

        composeTestRule.onNodeWithText("Hello, this is a test message")
            .assertIsDisplayed()
    }

    @Test
    fun userMessageBubble_longPressShowsCopyMenu() {
        var copyCalled = false
        composeTestRule.setContent {
            UserMessageBubble(
                message = testMessage,
                onCopy = { copyCalled = true }
            )
        }

        composeTestRule.onNodeWithText("Hello, this is a test message")
            .performTouchInput { longClick() }

        composeTestRule.onNodeWithText("Copy")
            .assertIsDisplayed()
            .performClick()

        assert(copyCalled)
    }

    @Test
    fun userMessageBubble_retryMenuVisibleWhenOnRetryProvided() {
        var retryCalled = false
        composeTestRule.setContent {
            UserMessageBubble(
                message = testMessage,
                onRetry = { retryCalled = true }
            )
        }

        composeTestRule.onNodeWithText("Hello, this is a test message")
            .performTouchInput { longClick() }

        composeTestRule.onNodeWithText("Retry")
            .assertIsDisplayed()
            .performClick()

        assert(retryCalled)
    }

    @Test
    fun userMessageBubble_editMenuVisibleWhenOnEditProvided() {
        var editCalled = false
        composeTestRule.setContent {
            UserMessageBubble(
                message = testMessage,
                onEdit = { editCalled = true }
            )
        }

        composeTestRule.onNodeWithText("Hello, this is a test message")
            .performTouchInput { longClick() }

        composeTestRule.onNodeWithText("Edit")
            .assertIsDisplayed()
            .performClick()

        assert(editCalled)
    }

    @Test
    fun userMessageBubble_queuedMessageShowsWithdrawButton() {
        val queuedMessage = testMessage.copy(isQueued = true)
        var withdrawCalled = false

        composeTestRule.setContent {
            UserMessageBubble(
                message = queuedMessage,
                onWithdraw = { withdrawCalled = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Withdraw queued message")
            .assertIsDisplayed()
            .performClick()

        assert(withdrawCalled)
    }

    @Test
    fun userMessageBubble_withAttachments_rendersImages() {
        val messageWithImage = testMessage.copy(
            imageUris = listOf(Uri.parse("content://test/image1.jpg")),
            attachmentNames = listOf("image1.jpg")
        )

        composeTestRule.setContent {
            UserMessageBubble(message = messageWithImage)
        }

        composeTestRule.onNodeWithContentDescription("Image attachment")
            .assertIsDisplayed()
    }

    @Test
    fun userMessageBubble_withFileAttachments_rendersFileTiles() {
        val messageWithFile = testMessage.copy(
            imageUris = emptyList(),
            attachmentUris = listOf(Uri.parse("content://test/document.pdf")),
            attachmentNames = listOf("document.pdf")
        )

        composeTestRule.setContent {
            UserMessageBubble(message = messageWithFile)
        }

        composeTestRule.onNodeWithText("document.pdf")
            .assertIsDisplayed()
    }

    @Test
    fun userAttachmentList_rendersImageAttachments() {
        val imageUris = listOf(
            Uri.parse("content://test/image1.jpg"),
            Uri.parse("content://test/image2.jpg")
        )

        composeTestRule.setContent {
            UserAttachmentList(
                imageUris = imageUris,
                allFileNames = listOf("image1.jpg", "image2.jpg")
            )
        }

        composeTestRule.onNodeWithContentDescription("Image attachment")
            .assertIsDisplayed()
    }

    @Test
    fun userAttachmentList_rendersFileAttachments() {
        val nonImageUris = listOf(Uri.parse("content://test/document.pdf"))
        val fileNames = listOf("document.pdf")

        composeTestRule.setContent {
            UserAttachmentList(
                imageUris = emptyList(),
                allFileNames = fileNames,
                nonImageUris = nonImageUris
            )
        }

        composeTestRule.onNodeWithText("document.pdf")
            .assertIsDisplayed()
    }

    @Test
    fun userAttachmentList_clickOnFileAttachmentTriggersPreview() {
        var previewCalled = false
        val nonImageUris = listOf(Uri.parse("content://test/document.pdf"))
        val fileNames = listOf("document.pdf")

        composeTestRule.setContent {
            UserAttachmentList(
                imageUris = emptyList(),
                allFileNames = fileNames,
                nonImageUris = nonImageUris,
                onPreviewFile = { _, _ -> previewCalled = true }
            )
        }

        composeTestRule.onNodeWithText("document.pdf")
            .performClick()

        assert(previewCalled)
    }

    @Test
    fun userMessageBubble_imageAttachmentClickOpensGallery() {
        val messageWithImage = testMessage.copy(
            imageUris = listOf(Uri.parse("content://test/image1.jpg")),
            attachmentNames = listOf("image1.jpg")
        )

        composeTestRule.setContent {
            UserMessageBubble(message = messageWithImage)
        }

        composeTestRule.onNodeWithContentDescription("Image attachment")
            .performClick()

        composeTestRule.onNodeWithContentDescription("Close")
            .assertIsDisplayed()
    }

    @Test
    fun userMessageBubble_withMultipleImages_showsPagerCounter() {
        val messageWithImages = testMessage.copy(
            imageUris = listOf(
                Uri.parse("content://test/image1.jpg"),
                Uri.parse("content://test/image2.jpg")
            ),
            attachmentNames = listOf("image1.jpg", "image2.jpg")
        )

        composeTestRule.setContent {
            UserMessageBubble(message = messageWithImages)
        }

        composeTestRule.onNodeWithContentDescription("Image attachment")
            .performClick()

        composeTestRule.onNodeWithText("1 / 2")
            .assertIsDisplayed()
    }
}