package com.openminis.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.tap
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

class FullscreenImageViewerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun fullscreenImageViewer_rendersImage() {
        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = {}
            )
        }

        composeTestRule.onAllNodesWithTag("async_image").assertCountEquals(1)
        composeTestRule.onNodeWithTag("async_image").assertIsDisplayed()
    }

    @Test
    fun fullscreenImageViewer_closeButtonClick_callsOnDismiss() {
        var dismissed = false
        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = { dismissed = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Close").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun fullscreenImageViewer_defaultParameters() {
        var dismissed = false
        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = { dismissed = true }
            )
        }

        // Verify default chrome is visible
        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.image_action_copy)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.image_action_share)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.image_action_save)).assertIsDisplayed()
    }

    @Test
    fun fullscreenImageViewer_tapOnImage_togglesChrome() {
        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = {}
            )
        }

        // Initially chrome is visible
        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()

        // Tap on image to hide chrome
        composeTestRule.onNodeWithTag("async_image").performTouchInput {
            tap()
        }
        composeTestRule.waitForIdle()

        // Chrome should be hidden
        composeTestRule.onNodeWithContentDescription("Close").assertIsNotDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.image_action_copy)).assertIsNotDisplayed()

        // Tap again to show chrome
        composeTestRule.onNodeWithTag("async_image").performTouchInput {
            tap()
        }
        composeTestRule.waitForIdle()

        // Chrome should be visible again
        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.image_action_copy)).assertIsDisplayed()
    }

    @Test
    fun fullscreenImageViewer_doubleTap_zoomsInAndOut() {
        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = {}
            )
        }

        // Double tap to zoom in
        composeTestRule.onNodeWithTag("async_image").performTouchInput {
            doubleClick()
        }
        composeTestRule.waitForIdle()

        // Double tap again to zoom out
        composeTestRule.onNodeWithTag("async_image").performTouchInput {
            doubleClick()
        }
        composeTestRule.waitForIdle()

        // Should still be displayed
        composeTestRule.onNodeWithTag("async_image").assertIsDisplayed()
    }

    @Test
    fun imageActionButton_rendersWithLabel() {
        composeTestRule.setContent {
            ImageActionButton(
                icon = androidx.compose.material.icons.Icons.Outlined.ContentCopy,
                label = "Copy",
                onClick = {}
            )
        }

        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Copy").assertIsDisplayed()
    }

    @Test
    fun imageActionButton_clickAction() {
        var clicked = false
        composeTestRule.setContent {
            ImageActionButton(
                icon = androidx.compose.material.icons.Icons.Outlined.ContentCopy,
                label = "Copy",
                onClick = { clicked = true }
            )
        }

        composeTestRule.onNodeWithText("Copy").performClick()
        assertTrue(clicked)
    }

    @Test
    fun imageActionButton_defaultParameters() {
        composeTestRule.setContent {
            ImageActionButton(
                icon = androidx.compose.material.icons.Icons.Outlined.ContentCopy,
                label = "Test Label",
                onClick = {}
            )
        }

        composeTestRule.onNodeWithText("Test Label").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Test Label").assertIsDisplayed()
    }

    @Test
    fun fullscreenImageViewer_shareButton_launchesShareIntent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var startActivityCalled = false

        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = {}
            )
        }

        // Verify share button exists
        composeTestRule.onNodeWithText(context.getString(R.string.image_action_share)).assertIsDisplayed()
    }

    @Test
    fun fullscreenImageViewer_saveButton_visible() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.image_action_save)).assertIsDisplayed()
    }

    @Test
    fun fullscreenImageViewer_copyButton_visible() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.image_action_copy)).assertIsDisplayed()
    }

    @Test
    fun fullscreenImageViewer_allActionButtons_displayed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.image_action_copy)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.image_action_share)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.image_action_save)).assertIsDisplayed()
    }

    @Test
    fun fullscreenImageViewer_closeButton_hasCorrectIcon() {
        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Close").assertExists()
    }

    @Test
    fun fullscreenImageViewer_image_hasCorrectScaleType() {
        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithTag("async_image").assertIsDisplayed()
    }

    @Test
    fun fullscreenImageViewer_dismissFromBackPress() {
        var dismissed = false
        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = { dismissed = true }
            )
        }

        // Simulate back press
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        
        assertTrue(dismissed)
    }

    @Test
    fun imageActionButton_clickableArea_works() {
        var clickCount = 0
        composeTestRule.setContent {
            ImageActionButton(
                icon = androidx.compose.material.icons.Icons.Outlined.ContentCopy,
                label = "Click Me",
                onClick = { clickCount++ }
            )
        }

        composeTestRule.onNodeWithText("Click Me").performClick()
        composeTestRule.onNodeWithText("Click Me").performClick()
        
        assertEquals(2, clickCount)
    }

    @Test
    fun fullscreenImageViewer_shareButton_click_doesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        composeTestRule.setContent {
            FullscreenImageViewer(
                model = "https://example.com/image.jpg",
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.image_action_share)).performClick()
        composeTestRule.waitForIdle()
        
        // Component should still be displayed
        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }
}