package com.openminis.app.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.Rule
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinisMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testMinisMenu_rendersWhenExpanded() {
        composeTestRule.setContent {
            val expanded = remember { mutableStateOf(true) }
            MinisMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
                content = {
                    Text("Test Item", modifier = Modifier.testTag("menuItem"))
                }
            )
        }
        composeTestRule.onNodeWithTag("menuItem").assertIsDisplayed()
    }

    @Test
    fun testMinisMenu_doesNotRenderWhenNotExpanded() {
        composeTestRule.setContent {
            val expanded = remember { mutableStateOf(false) }
            MinisMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
                content = {
                    Text("Test Item", modifier = Modifier.testTag("menuItem"))
                }
            )
        }
        composeTestRule.onNodeWithTag("menuItem").assertDoesNotExist()
    }

    @Test
    fun testMinisMenu_clickEventTriggersOnDismissRequest() {
        val dismissRequested = mutableStateOf(false)
        composeTestRule.setContent {
            val expanded = remember { mutableStateOf(true) }
            MinisMenu(
                expanded = expanded.value,
                onDismissRequest = { dismissRequested.value = true },
                content = {
                    Text("Test Item", modifier = Modifier.testTag("menuItem"))
                }
            )
        }
        composeTestRule.onNodeWithTag("menuItem").performClick()
        assertTrue(dismissRequested.value)
    }

    @Test
    fun testMinisMenu_defaultParameters() {
        composeTestRule.setContent {
            val expanded = remember { mutableStateOf(true) }
            MinisMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
                content = {
                    Text("Default Menu", modifier = Modifier.testTag("defaultMenu"))
                }
            )
        }
        composeTestRule.onNodeWithTag("defaultMenu").assertIsDisplayed()
    }

    @Test
    fun testMinisMenu_alignEndParameter() {
        composeTestRule.setContent {
            val expanded = remember { mutableStateOf(true) }
            MinisMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
                alignEnd = true,
                content = {
                    Text("Aligned End", modifier = Modifier.testTag("alignedEndMenu"))
                }
            )
        }
        composeTestRule.onNodeWithTag("alignedEndMenu").assertIsDisplayed()
    }

    @Test
    fun testMinisMenu_withCustomOffset() {
        composeTestRule.setContent {
            val expanded = remember { mutableStateOf(true) }
            MinisMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
                offset = DpOffset(10.dp, 20.dp),
                content = {
                    Text("Offset Menu", modifier = Modifier.testTag("offsetMenu"))
                }
            )
        }
        composeTestRule.onNodeWithTag("offsetMenu").assertIsDisplayed()
    }

    @Test
    fun testMinisMenuDivider_renders() {
        composeTestRule.setContent {
            Box {
                MinisMenuDivider(modifier = Modifier.testTag("menuDivider"))
            }
        }
        composeTestRule.onNodeWithTag("menuDivider").assertIsDisplayed()
    }

    @Test
    fun testMinisMenuDivider_defaultParameters() {
        composeTestRule.setContent {
            Box {
                MinisMenuDivider(modifier = Modifier.testTag("defaultDivider"))
            }
        }
        composeTestRule.onNodeWithTag("defaultDivider").assertIsDisplayed()
    }
}