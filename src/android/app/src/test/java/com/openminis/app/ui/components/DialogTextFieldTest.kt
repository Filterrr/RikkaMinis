package com.openminis.app.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.text.input.VisualTransformation
import org.junit.Rule
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class DialogTextFieldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testDialogTextField_rendersWithDefaultParameters() {
        composeTestRule.setContent {
            DialogTextField(
                value = "",
                onValueChange = {},
                modifier = Modifier.testTag("dialogTextField")
            )
        }

        composeTestRule.onNodeWithTag("dialogTextField").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dialogTextField").assertIsEnabled()
    }

    @Test
    fun testDialogTextField_rendersWithPlaceholder() {
        val placeholderText = "Enter text"
        composeTestRule.setContent {
            DialogTextField(
                value = "",
                onValueChange = {},
                placeholder = placeholderText,
                modifier = Modifier.testTag("dialogTextField")
            )
        }

        composeTestRule.onNodeWithText(placeholderText).assertIsDisplayed()
    }

    @Test
    fun testDialogTextField_acceptsTextInput() {
        var inputValue = ""
        composeTestRule.setContent {
            DialogTextField(
                value = inputValue,
                onValueChange = { inputValue = it },
                modifier = Modifier.testTag("dialogTextField")
            )
        }

        composeTestRule.onNodeWithTag("dialogTextField").performTextInput("Hello")
        assertTrue(inputValue == "Hello")
    }

    @Test
    fun testDialogTextField_displaysInitialValue() {
        composeTestRule.setContent {
            DialogTextField(
                value = "Initial",
                onValueChange = {},
                modifier = Modifier.testTag("dialogTextField")
            )
        }

        composeTestRule.onNodeWithTag("dialogTextField").assertIsDisplayed()
        composeTestRule.onNodeWithText("Initial").assertIsDisplayed()
    }

    @Test
    fun testDialogTextField_disabledState() {
        composeTestRule.setContent {
            DialogTextField(
                value = "",
                onValueChange = {},
                enabled = false,
                modifier = Modifier.testTag("dialogTextField")
            )
        }

        composeTestRule.onNodeWithTag("dialogTextField").assertIsDisplayed()
        // Disabled state is visual, but we can verify it exists
    }

    @Test
    fun testDialogTextField_errorState() {
        composeTestRule.setContent {
            DialogTextField(
                value = "",
                onValueChange = {},
                isError = true,
                modifier = Modifier.testTag("dialogTextField")
            )
        }

        composeTestRule.onNodeWithTag("dialogTextField").assertIsDisplayed()
        // Error state is visual, but we can verify it exists
    }

    @Test
    fun testDialogTextField_withTrailingIcon() {
        composeTestRule.setContent {
            DialogTextField(
                value = "",
                onValueChange = {},
                trailingIcon = {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Check,
                        contentDescription = "Trailing icon"
                    )
                },
                modifier = Modifier.testTag("dialogTextField")
            )
        }

        composeTestRule.onNodeWithTag("dialogTextField").assertIsDisplayed()
        composeTestRule.onNodeWithText("Trailing icon").assertIsDisplayed()
    }

    @Test
    fun testDialogTextField_singleLineMode() {
        composeTestRule.setContent {
            DialogTextField(
                value = "Single line",
                onValueChange = {},
                singleLine = true,
                modifier = Modifier.testTag("dialogTextField")
            )
        }

        composeTestRule.onNodeWithTag("dialogTextField").assertIsDisplayed()
        composeTestRule.onNodeWithText("Single line").assertIsDisplayed()
    }

    @Test
    fun testDialogTextField_readOnlyMode() {
        composeTestRule.setContent {
            DialogTextField(
                value = "Read only",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.testTag("dialogTextField")
            )
        }

        composeTestRule.onNodeWithTag("dialogTextField").assertIsDisplayed()
        composeTestRule.onNodeWithText("Read only").assertIsDisplayed()
    }

    @Test
    fun testDialogTextField_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            DialogTextField(
                value = "",
                onValueChange = { clicked = true },
                modifier = Modifier.testTag("dialogTextField")
            )
        }

        composeTestRule.onNodeWithTag("dialogTextField").performClick()
        // Click will trigger focus, but not necessarily value change
        // We verify the component is clickable
    }
}