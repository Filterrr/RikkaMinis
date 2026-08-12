package com.openminis.app.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SectionDropdownTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testSectionDropdown_rendersWithDefaultParameters() {
        val items = listOf("Option A", "Option B", "Option C")
        composeTestRule.setContent {
            SectionDropdown(
                selected = items[0],
                items = items,
                onSelect = {},
            )
        }
        composeTestRule.onNodeWithText("Option A").assertExists()
    }

    @Test
    fun testSectionDropdown_rendersSelectedItem() {
        val items = listOf("Option A", "Option B", "Option C")
        composeTestRule.setContent {
            SectionDropdown(
                selected = items[1],
                items = items,
                onSelect = {},
            )
        }
        composeTestRule.onNodeWithText("Option B").assertExists()
    }

    @Test
    fun testSectionDropdown_clickOpensDropdown() {
        val items = listOf("Option A", "Option B", "Option C")
        composeTestRule.setContent {
            SectionDropdown(
                selected = items[0],
                items = items,
                onSelect = {},
            )
        }
        composeTestRule.onNodeWithText("Option A").performClick()
        composeTestRule.onNodeWithText("Option B").assertExists()
        composeTestRule.onNodeWithText("Option C").assertExists()
    }

    @Test
    fun testSectionDropdown_selectItem() {
        var selectedItem = "Option A"
        val items = listOf("Option A", "Option B", "Option C")
        composeTestRule.setContent {
            SectionDropdown(
                selected = selectedItem,
                items = items,
                onSelect = { selectedItem = it },
            )
        }
        composeTestRule.onNodeWithText("Option A").performClick()
        composeTestRule.onNodeWithText("Option B").performClick()
        composeTestRule.onNodeWithText("Option B").assertExists()
    }

    @Test
    fun testSectionDropdown_disabledDoesNotOpen() {
        var selectedItem = "Option A"
        val items = listOf("Option A", "Option B", "Option C")
        composeTestRule.setContent {
            SectionDropdown(
                selected = selectedItem,
                items = items,
                onSelect = { selectedItem = it },
                enabled = false,
            )
        }
        composeTestRule.onNodeWithText("Option A").performClick()
        composeTestRule.onNodeWithText("Option B").assertDoesNotExist()
    }

    @Test
    fun testSectionDropdown_rendersWithCustomLabel() {
        data class CustomItem(val id: Int, val name: String)
        val items = listOf(CustomItem(1, "First"), CustomItem(2, "Second"))
        composeTestRule.setContent {
            SectionDropdown(
                selected = items[0],
                items = items,
                onSelect = {},
                itemLabel = { it.name },
            )
        }
        composeTestRule.onNodeWithText("First").assertExists()
    }

    @Test
    fun testSectionDropdown_customLabelSelectItem() {
        data class CustomItem(val id: Int, val name: String)
        var selectedItem = CustomItem(1, "First")
        val items = listOf(CustomItem(1, "First"), CustomItem(2, "Second"))
        composeTestRule.setContent {
            SectionDropdown(
                selected = selectedItem,
                items = items,
                onSelect = { selectedItem = it },
                itemLabel = { it.name },
            )
        }
        composeTestRule.onNodeWithText("First").performClick()
        composeTestRule.onNodeWithText("Second").performClick()
        composeTestRule.onNodeWithText("Second").assertExists()
    }
}