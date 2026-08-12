package com.openminis.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class ImageGalleryViewerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testItems = listOf(
        ImageGalleryItem(model = "https://example.com/image1.jpg", caption = "Test Image 1"),
        ImageGalleryItem(model = "https://example.com/image2.jpg", caption = "Test Image 2"),
        ImageGalleryItem(model = "https://example.com/image3.jpg", caption = null)
    )

    @Test
    fun imageGalleryViewer_rendersWithItems() {
        var dismissCalled = false
        composeTestRule.setContent {
            ImageGalleryViewer(
                items = testItems,
                startIndex = 0,
                onDismiss = { dismissCalled = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Image 1").assertIsDisplayed()
    }

    @Test
    fun imageGalleryViewer_rendersWithDefaultStartIndex() {
        var dismissCalled = false
        composeTestRule.setContent {
            ImageGalleryViewer(
                items = testItems,
                onDismiss = { dismissCalled = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Image 1").assertIsDisplayed()
    }

    @Test
    fun imageGalleryViewer_rendersWithCustomStartIndex() {
        var dismissCalled = false
        composeTestRule.setContent {
            ImageGalleryViewer(
                items = testItems,
                startIndex = 1,
                onDismiss = { dismissCalled = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Image 2").assertIsDisplayed()
    }

    @Test
    fun imageGalleryViewer_dismissButton_callsOnDismiss() {
        var dismissCalled = false
        composeTestRule.setContent {
            ImageGalleryViewer(
                items = testItems,
                startIndex = 0,
                onDismiss = { dismissCalled = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Close").performClick()
        assertTrue(dismissCalled)
    }

    @Test
    fun imageGalleryViewer_emptyItems_callsOnDismiss() {
        var dismissCalled = false
        composeTestRule.setContent {
            ImageGalleryViewer(
                items = emptyList(),
                onDismiss = { dismissCalled = true }
            )
        }

        assertTrue(dismissCalled)
    }

    @Test
    fun imageGalleryViewer_rendersWithNullCaption() {
        var dismissCalled = false
        composeTestRule.setContent {
            ImageGalleryViewer(
                items = listOf(testItems[2]),
                startIndex = 0,
                onDismiss = { dismissCalled = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Image 3").assertDoesNotExist()
    }

    @Test
    fun imageGalleryViewer_rendersActionButtons() {
        var dismissCalled = false
        composeTestRule.setContent {
            ImageGalleryViewer(
                items = testItems,
                startIndex = 0,
                onDismiss = { dismissCalled = true }
            )
        }

        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun imageGalleryViewer_clickActionButtons_doesNotCrash() {
        var dismissCalled = false
        composeTestRule.setContent {
            ImageGalleryViewer(
                items = testItems,
                startIndex = 0,
                onDismiss = { dismissCalled = true }
            )
        }

        composeTestRule.onNodeWithText("Copy").performClick()
        composeTestRule.onNodeWithText("Share").performClick()
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun galleryPage_rendersImage() {
        composeTestRule.setContent {
            GalleryPage(
                item = testItems[0],
                onTapChrome = {}
            )
        }

        composeTestRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun galleryPage_tapImage_triggersCallback() {
        var tapCount = 0
        composeTestRule.setContent {
            GalleryPage(
                item = testItems[0],
                onTapChrome = { tapCount++ }
            )
        }

        composeTestRule.onRoot().performClick()
        assertEquals(1, tapCount)
    }

    @Test
    fun galleryPage_doubleTapImage_doesNotCrash() {
        var tapCount = 0
        composeTestRule.setContent {
            GalleryPage(
                item = testItems[0],
                onTapChrome = { tapCount++ }
            )
        }

        composeTestRule.onRoot().performClick()
        composeTestRule.onRoot().performClick()
        composeTestRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun galleryPage_rendersWithNullCaption() {
        composeTestRule.setContent {
            GalleryPage(
                item = testItems[2],
                onTapChrome = {}
            )
        }

        composeTestRule.onRoot().assertIsDisplayed()
    }
}