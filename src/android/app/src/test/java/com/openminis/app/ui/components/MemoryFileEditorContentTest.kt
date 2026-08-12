package com.openminis.app.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryFileEditorContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testMemoryFileEditorContent_rendersWithDefaultParameters() {
        composeTestRule.setContent {
            MemoryFileEditorContent(
                value = "Hello, World!",
                onValueChange = {},
                errorMessage = null,
            )
        }

        composeTestRule.onNodeWithText("Hello, World!").assertIsDisplayed()
    }

    @Test
    fun testMemoryFileEditorContent_rendersWithError() {
        composeTestRule.setContent {
            MemoryFileEditorContent(
                value = "Test value",
                onValueChange = {},
                errorMessage = "Error occurred",
            )
        }

        composeTestRule.onNodeWithText("Error occurred").assertIsDisplayed()
    }

    @Test
    fun testMemoryFileEditorContent_clickEvent() {
        var inputText = ""

        composeTestRule.setContent {
            MemoryFileEditorContent(
                value = inputText,
                onValueChange = { inputText = it },
                errorMessage = null,
            )
        }

        composeTestRule.onNodeWithText("").performTextInput("New text")

        assert(inputText == "New text")
    }

    @Test
    fun testMemoryFileEditorContent_errorNotDisplayedWhenNull() {
        composeTestRule.setContent {
            MemoryFileEditorContent(
                value = "Test",
                onValueChange = {},
                errorMessage = null,
            )
        }

        composeTestRule.onNodeWithText("Error").assertIsNotDisplayed()
    }

    @Test
    fun testMemoryFileEditorContent_rendersWithCustomModifier() {
        composeTestRule.setContent {
            MemoryFileEditorContent(
                value = "Test value",
                onValueChange = {},
                errorMessage = null,
                modifier = androidx.compose.ui.Modifier.padding(10.dp),
            )
        }

        composeTestRule.onNodeWithText("Test value").assertIsDisplayed()
    }
}