package com.openminis.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class SectionDesignTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testSectionHeader_rendersText() {
        composeTestRule.setContent {
            SectionHeader(text = "Test Header")
        }
        composeTestRule.onNodeWithText("Test Header").assertIsDisplayed()
    }

    @Test
    fun testSectionHeader_clickable() {
        var clicked = false
        composeTestRule.setContent {
            SectionHeader(
                text = "Clickable Header",
                modifier = Modifier.clickable { clicked = true }
            )
        }
        composeTestRule.onNodeWithText("Clickable Header").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testSectionHeader_defaultParameters() {
        composeTestRule.setContent {
            SectionHeader(text = "Default Params")
        }
        composeTestRule.onNodeWithText("Default Params").assertIsDisplayed()
    }

    @Test
    fun testSectionFooter_rendersText() {
        composeTestRule.setContent {
            SectionFooter(text = "Test Footer")
        }
        composeTestRule.onNodeWithText("Test Footer").assertIsDisplayed()
    }

    @Test
    fun testSectionFooter_clickable() {
        var clicked = false
        composeTestRule.setContent {
            SectionFooter(
                text = "Clickable Footer",
                modifier = Modifier.clickable { clicked = true }
            )
        }
        composeTestRule.onNodeWithText("Clickable Footer").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testSectionFooter_defaultParameters() {
        composeTestRule.setContent {
            SectionFooter(text = "Default Params")
        }
        composeTestRule.onNodeWithText("Default Params").assertIsDisplayed()
    }

    @Test
    fun testSectionCard_rendersContent() {
        composeTestRule.setContent {
            SectionCard {
                Text("Card Content")
            }
        }
        composeTestRule.onNodeWithText("Card Content").assertIsDisplayed()
    }

    @Test
    fun testSectionCard_clickable() {
        var clicked = false
        composeTestRule.setContent {
            SectionCard(
                modifier = Modifier.clickable { clicked = true }
            ) {
                Text("Clickable Card")
            }
        }
        composeTestRule.onNodeWithText("Clickable Card").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testSectionCard_defaultParameters() {
        composeTestRule.setContent {
            SectionCard {
                Text("Default Card")
            }
        }
        composeTestRule.onNodeWithText("Default Card").assertIsDisplayed()
    }

    @Test
    fun testSectionDivider_renders() {
        composeTestRule.setContent {
            SectionDivider()
        }
        // Divider is a horizontal line; we can check it exists via its semantics
        composeTestRule.onNodeWithTag("SectionDivider").assertExists()
    }

    @Test
    fun testRowLabel_rendersText() {
        composeTestRule.setContent {
            RowLabel(text = "Test Label")
        }
        composeTestRule.onNodeWithText("Test Label").assertIsDisplayed()
    }

    @Test
    fun testRowLabel_clickable() {
        var clicked = false
        composeTestRule.setContent {
            RowLabel(
                text = "Clickable Label",
                modifier = Modifier.clickable { clicked = true }
            )
        }
        composeTestRule.onNodeWithText("Clickable Label").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testRowLabel_defaultParameters() {
        composeTestRule.setContent {
            RowLabel(text = "Default Params")
        }
        composeTestRule.onNodeWithText("Default Params").assertIsDisplayed()
    }
}