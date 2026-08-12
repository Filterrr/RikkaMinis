package com.openminis.app.config

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Test

class ConfigRegistryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testConfigRegistryRender() {
        composeTestRule.setContent {
            ConfigRegistryScreen()
        }
        composeTestRule.onNodeWithTag("config_registry_screen").assertIsDisplayed()
    }

    @Test
    fun testConfigRegistryClick() {
        var clickCount = 0
        composeTestRule.setContent {
            ConfigRegistryScreen(onClick = { clickCount++ })
        }
        composeTestRule.onNodeWithTag("config_registry_button").performClick()
        assert(clickCount == 1)
    }

    @Test
    fun testConfigRegistryDefaultParameters() {
        composeTestRule.setContent {
            ConfigRegistryScreen()
        }
        composeTestRule.onNodeWithText("Default Title").assertIsDisplayed()
    }

    @Test
    fun testConfigRegistryWithCustomTitle() {
        composeTestRule.setContent {
            ConfigRegistryScreen(title = "Custom Title")
        }
        composeTestRule.onNodeWithText("Custom Title").assertIsDisplayed()
    }

    @Test
    fun testConfigRegistryMultipleClicks() {
        var clickCount = 0
        composeTestRule.setContent {
            ConfigRegistryScreen(onClick = { clickCount++ })
        }
        composeTestRule.onNodeWithTag("config_registry_button").performClick()
        composeTestRule.onNodeWithTag("config_registry_button").performClick()
        composeTestRule.onNodeWithTag("config_registry_button").performClick()
        assert(clickCount == 3)
    }
}