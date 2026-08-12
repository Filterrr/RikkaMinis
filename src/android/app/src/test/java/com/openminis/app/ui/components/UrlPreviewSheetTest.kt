package com.openminis.app.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@get:Rule
val composeTestRule = createComposeRule()

class UrlPreviewSheetTest {

    @Test
    fun urlPreviewSheet_rendersWithUrl() {
        var rendered = false
        composeTestRule.setContent {
            UrlPreviewSheet(
                url = "https://example.com",
                onDismiss = { rendered = true }
            )
        }
        
        composeTestRule.onNodeWithText("https://example.com").assertExists()
        assertTrue(rendered == false)
    }

    @Test
    fun urlPreviewSheet_dismissClick_triggerOnDismiss() {
        var dismissed = false
        composeTestRule.setContent {
            UrlPreviewSheet(
                url = "https://example.com",
                onDismiss = { dismissed = true }
            )
        }
        
        composeTestRule.onNodeWithText("https://example.com").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun urlPreviewSheet_defaultParameters() {
        var dismissCount = 0
        composeTestRule.setContent {
            UrlPreviewSheet(
                url = "https://test.com",
                onDismiss = { dismissCount++ }
            )
        }
        
        composeTestRule.onNodeWithText("https://test.com").assertExists()
        assertEquals(0, dismissCount)
    }
}