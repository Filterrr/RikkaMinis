package com.openminis.app.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MinisAlertDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun alertDialog_rendersWithDefaultParameters() {
        var confirmClicked = false
        var dismissClicked = false

        composeTestRule.setContent {
            MinisAlertDialog(
                onDismissRequest = { dismissClicked = true },
                title = "Test Title",
                confirmText = "OK",
                onConfirm = { confirmClicked = true },
            )
        }

        composeTestRule.onNodeWithText("Test Title").assertExists()
        composeTestRule.onNodeWithText("OK").assertExists()
        composeTestRule.onNodeWithText("Cancel").assertExists()
        
        assertFalse(confirmClicked)
        assertFalse(dismissClicked)
    }

    @Test
    fun alertDialog_rendersWithAllParameters() {
        composeTestRule.setContent {
            MinisAlertDialog(
                onDismissRequest = {},
                title = "Full Dialog",
                text = "This is a message",
                confirmText = "Confirm",
                onConfirm = {},
                dismissText = "Close",
                isDestructive = true,
            )
        }

        composeTestRule.onNodeWithText("Full Dialog").assertExists()
        composeTestRule.onNodeWithText("This is a message").assertExists()
        composeTestRule.onNodeWithText("Confirm").assertExists()
        composeTestRule.onNodeWithText("Close").assertExists()
    }

    @Test
    fun alertDialog_confirmButton_clickEvent() {
        var confirmClicked = false

        composeTestRule.setContent {
            MinisAlertDialog(
                onDismissRequest = {},
                title = "Confirm Test",
                confirmText = "Yes",
                onConfirm = { confirmClicked = true },
            )
        }

        composeTestRule.onNodeWithText("Yes").performClick()
        assertTrue(confirmClicked)
    }

    @Test
    fun alertDialog_dismissButton_clickEvent() {
        var dismissClicked = false

        composeTestRule.setContent {
            MinisAlertDialog(
                onDismissRequest = {},
                title = "Dismiss Test",
                confirmText = "OK",
                onConfirm = {},
                dismissText = "No",
                onDismiss = { dismissClicked = true },
            )
        }

        composeTestRule.onNodeWithText("No").performClick()
        assertTrue(dismissClicked)
    }

    @Test
    fun alertDialog_onDismissRequest_clickEvent() {
        var dismissRequestCalled = false

        composeTestRule.setContent {
            MinisAlertDialog(
                onDismissRequest = { dismissRequestCalled = true },
                title = "Dismiss Request Test",
                confirmText = "OK",
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissRequestCalled)
    }

    @Test
    fun alertDialog_destructiveConfirmButton_rendersCorrectly() {
        composeTestRule.setContent {
            MinisAlertDialog(
                onDismissRequest = {},
                title = "Destructive Test",
                confirmText = "Delete",
                onConfirm = {},
                isDestructive = true,
            )
        }

        composeTestRule.onNodeWithText("Delete").assertExists()
        composeTestRule.onNodeWithText("Cancel").assertExists()
    }
}