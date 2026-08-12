package com.openminis.app.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.KeyboardType
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SectionTextFieldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sectionTextField_renders() {
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "Test",
                    onValueChange = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Test").assertExists()
    }

    @Test
    fun sectionTextField_rendersPlaceholder() {
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "",
                    onValueChange = {},
                    placeholder = "Enter text"
                )
            }
        }
        composeTestRule.onNodeWithText("Enter text").assertExists()
    }

    @Test
    fun sectionTextField_clickTriggersFocus() {
        var clicked = false
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "",
                    onValueChange = { clicked = true },
                    placeholder = "Click me"
                )
            }
        }
        composeTestRule.onNodeWithText("Click me").performClick()
        // Click on the TextField should allow text input
        composeTestRule.onNodeWithText("Click me").performTextInput("A")
        assert(clicked)
    }

    @Test
    fun sectionTextField_textInputUpdatesValue() {
        var currentValue = ""
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = currentValue,
                    onValueChange = { currentValue = it }
                )
            }
        }
        composeTestRule.onNodeWithTag("SectionTextField").performTextReplacement("Hello")
        assert(currentValue == "Hello")
    }

    @Test
    fun sectionTextField_disabledState() {
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "Disabled",
                    onValueChange = {},
                    enabled = false
                )
            }
        }
        composeTestRule.onNodeWithText("Disabled").assertExists()
        // Verify that the text field is not interactable
        composeTestRule.onNodeWithText("Disabled").performTextReplacement("New")
        composeTestRule.onNodeWithText("Disabled").assertExists()
    }

    @Test
    fun sectionTextField_readOnlyState() {
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "Read Only",
                    onValueChange = {},
                    readOnly = true
                )
            }
        }
        composeTestRule.onNodeWithText("Read Only").assertExists()
        // Verify that the text field is not interactable
        composeTestRule.onNodeWithText("Read Only").performTextReplacement("New")
        composeTestRule.onNodeWithText("Read Only").assertExists()
    }

    @Test
    fun sectionTextField_singleLineDefault() {
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "Single Line",
                    onValueChange = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Single Line").assertExists()
    }

    @Test
    fun sectionTextField_maxLinesMultiLine() {
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "Line 1\nLine 2",
                    onValueChange = {},
                    singleLine = false,
                    maxLines = 2
                )
            }
        }
        composeTestRule.onNodeWithText("Line 1\nLine 2").assertExists()
    }

    @Test
    fun sectionTextField_isErrorState() {
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "Error",
                    onValueChange = {},
                    isError = true
                )
            }
        }
        composeTestRule.onNodeWithText("Error").assertExists()
    }

    @Test
    fun sectionTextField_withTrailingIcon() {
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "Icon",
                    onValueChange = {},
                    trailingIcon = { androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Info,
                        contentDescription = "Info"
                    ) }
                )
            }
        }
        composeTestRule.onNodeWithText("Icon").assertExists()
    }

    @Test
    fun sectionTextField_withKeyboardOptions() {
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "",
                    onValueChange = {},
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
        composeTestRule.onNodeWithTag("SectionTextField").assertExists()
    }

    @Test
    fun sectionTextField_defaultParameters() {
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "Default",
                    onValueChange = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Default").assertExists()
    }

    @Test
    fun sectionTextField_customModifier() {
        composeTestRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "Modifier",
                    onValueChange = {},
                    modifier = androidx.compose.ui.Modifier.testTag("CustomModifier")
                )
            }
        }
        composeTestRule.onNodeWithTag("CustomModifier").assertExists()
    }
}