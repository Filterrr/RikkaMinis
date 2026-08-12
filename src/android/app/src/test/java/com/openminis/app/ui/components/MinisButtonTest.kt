package com.openminis.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.MinisSmallOutlinedButton
import com.openminis.app.ui.components.MinisSmallTextButton
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

class MinisButtonTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun minisButton_renders() {
        composeTestRule.setContent {
            MinisButton(onClick = {}) {
                androidx.compose.material3.Text("Test Button")
            }
        }
        composeTestRule.onNodeWithTag("MinisButton").assertIsDisplayed()
    }

    @Test
    fun minisButton_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            MinisButton(onClick = { clicked = true }) {
                androidx.compose.material3.Text("Click Me")
            }
        }
        composeTestRule.onNodeWithTag("MinisButton").performClick()
        assertTrue(clicked)
    }

    @Test
    fun minisButton_defaultParameters() {
        composeTestRule.setContent {
            MinisButton(onClick = {}) {
                androidx.compose.material3.Text("Default")
            }
        }
        composeTestRule.onNodeWithTag("MinisButton").assertIsDisplayed()
    }

    @Test
    fun minisOutlinedButton_renders() {
        composeTestRule.setContent {
            MinisOutlinedButton(onClick = {}) {
                androidx.compose.material3.Text("Outlined")
            }
        }
        composeTestRule.onNodeWithTag("MinisOutlinedButton").assertIsDisplayed()
    }

    @Test
    fun minisOutlinedButton_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            MinisOutlinedButton(onClick = { clicked = true }) {
                androidx.compose.material3.Text("Click")
            }
        }
        composeTestRule.onNodeWithTag("MinisOutlinedButton").performClick()
        assertTrue(clicked)
    }

    @Test
    fun minisOutlinedButton_defaultParameters() {
        composeTestRule.setContent {
            MinisOutlinedButton(onClick = {}) {
                androidx.compose.material3.Text("Default")
            }
        }
        composeTestRule.onNodeWithTag("MinisOutlinedButton").assertIsDisplayed()
    }

    @Test
    fun minisTextButton_renders() {
        composeTestRule.setContent {
            MinisTextButton(onClick = {}) {
                androidx.compose.material3.Text("Text")
            }
        }
        composeTestRule.onNodeWithTag("MinisTextButton").assertIsDisplayed()
    }

    @Test
    fun minisTextButton_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            MinisTextButton(onClick = { clicked = true }) {
                androidx.compose.material3.Text("Click")
            }
        }
        composeTestRule.onNodeWithTag("MinisTextButton").performClick()
        assertTrue(clicked)
    }

    @Test
    fun minisTextButton_defaultParameters() {
        composeTestRule.setContent {
            MinisTextButton(onClick = {}) {
                androidx.compose.material3.Text("Default")
            }
        }
        composeTestRule.onNodeWithTag("MinisTextButton").assertIsDisplayed()
    }

    @Test
    fun minisSmallButton_renders() {
        composeTestRule.setContent {
            MinisSmallButton(onClick = {}) {
                androidx.compose.material3.Text("Small")
            }
        }
        composeTestRule.onNodeWithTag("MinisSmallButton").assertIsDisplayed()
    }

    @Test
    fun minisSmallButton_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            MinisSmallButton(onClick = { clicked = true }) {
                androidx.compose.material3.Text("Click")
            }
        }
        composeTestRule.onNodeWithTag("MinisSmallButton").performClick()
        assertTrue(clicked)
    }

    @Test
    fun minisSmallButton_defaultParameters() {
        composeTestRule.setContent {
            MinisSmallButton(onClick = {}) {
                androidx.compose.material3.Text("Default")
            }
        }
        composeTestRule.onNodeWithTag("MinisSmallButton").assertIsDisplayed()
    }

    @Test
    fun minisSmallOutlinedButton_renders() {
        composeTestRule.setContent {
            MinisSmallOutlinedButton(onClick = {}) {
                androidx.compose.material3.Text("Small Outlined")
            }
        }
        composeTestRule.onNodeWithTag("MinisSmallOutlinedButton").assertIsDisplayed()
    }

    @Test
    fun minisSmallOutlinedButton_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            MinisSmallOutlinedButton(onClick = { clicked = true }) {
                androidx.compose.material3.Text("Click")
            }
        }
        composeTestRule.onNodeWithTag("MinisSmallOutlinedButton").performClick()
        assertTrue(clicked)
    }

    @Test
    fun minisSmallOutlinedButton_defaultParameters() {
        composeTestRule.setContent {
            MinisSmallOutlinedButton(onClick = {}) {
                androidx.compose.material3.Text("Default")
            }
        }
        composeTestRule.onNodeWithTag("MinisSmallOutlinedButton").assertIsDisplayed()
    }

    @Test
    fun minisSmallTextButton_renders() {
        composeTestRule.setContent {
            MinisSmallTextButton(onClick = {}) {
                androidx.compose.material3.Text("Small Text")
            }
        }
        composeTestRule.onNodeWithTag("MinisSmallTextButton").assertIsDisplayed()
    }

    @Test
    fun minisSmallTextButton_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            MinisSmallTextButton(onClick = { clicked = true }) {
                androidx.compose.material3.Text("Click")
            }
        }
        composeTestRule.onNodeWithTag("MinisSmallTextButton").performClick()
        assertTrue(clicked)
    }

    @Test
    fun minisSmallTextButton_defaultParameters() {
        composeTestRule.setContent {
            MinisSmallTextButton(onClick = {}) {
                androidx.compose.material3.Text("Default")
            }
        }
        composeTestRule.onNodeWithTag("MinisSmallTextButton").assertIsDisplayed()
    }
}