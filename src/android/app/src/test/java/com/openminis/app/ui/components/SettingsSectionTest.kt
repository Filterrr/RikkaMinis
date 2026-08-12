package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Test

class SettingsSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun settingsSection_rendersTitle() {
        composeTestRule.setContent {
            SettingsSection(title = "General") {
                Text("Content")
            }
        }
        composeTestRule.onNodeWithText("GENERAL").assertIsDisplayed()
    }

    @Test
    fun settingsSection_rendersContent() {
        composeTestRule.setContent {
            SettingsSection(title = "Test") {
                Text("Test Content")
            }
        }
        composeTestRule.onNodeWithText("Test Content").assertIsDisplayed()
    }

    @Test
    fun settingsSection_clickableSurface() {
        var clicked = false
        composeTestRule.setContent {
            SettingsSection(title = "Click") {
                ClickableText(onClick = { clicked = true })
            }
        }
        composeTestRule.onNodeWithTag("clickable_text").performClick()
        assert(clicked)
    }

    @Test
    fun settingsSection_usesDefaultModifier() {
        composeTestRule.setContent {
            SettingsSection(title = "Default") {
                Text("Content")
            }
        }
        composeTestRule.onNodeWithText("DEFAULT").assertIsDisplayed()
    }

    @Test
    fun settingsSection_customModifier() {
        composeTestRule.setContent {
            SettingsSection(
                title = "Custom",
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Content")
            }
        }
        composeTestRule.onNodeWithText("CUSTOM").assertIsDisplayed()
    }

    @Test
    fun settingsRowDivider_renders() {
        composeTestRule.setContent {
            Column {
                SettingsRowDivider()
            }
        }
        composeTestRule.onNodeWithTag("settings_row_divider").assertIsDisplayed()
    }

    @Test
    fun settingsRowDivider_usesDefaultModifier() {
        composeTestRule.setContent {
            Column {
                SettingsRowDivider()
            }
        }
        composeTestRule.onNodeWithTag("settings_row_divider").assertIsDisplayed()
    }

    @Test
    fun settingsRowDivider_customModifier() {
        composeTestRule.setContent {
            Column {
                SettingsRowDivider(modifier = Modifier.fillMaxWidth())
            }
        }
        composeTestRule.onNodeWithTag("settings_row_divider").assertIsDisplayed()
    }
}

@Composable
private fun ClickableText(onClick: () -> Unit) {
    androidx.compose.material3.Text(
        text = "Click me",
        modifier = Modifier.fillMaxWidth().testTag("clickable_text"),
        onClick = onClick
    )
}