package com.openminis.app.data

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import org.junit.Rule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MemoryGlobalPrefsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @BeforeEach
    fun setUp() {
        // Reset to default before each test
        MemoryGlobalPrefs.setGlobalEnabled(context, true)
    }

    @AfterEach
    fun tearDown() {
        // Clean up after each test
        MemoryGlobalPrefs.setGlobalEnabled(context, true)
    }

    @Test
    fun `isGlobalEnabled returns true by default`() {
        val result = MemoryGlobalPrefs.isGlobalEnabled(context)
        assertTrue(result)
    }

    @Test
    fun `setGlobalEnabled false then isGlobalEnabled returns false`() {
        MemoryGlobalPrefs.setGlobalEnabled(context, false)
        assertFalse(MemoryGlobalPrefs.isGlobalEnabled(context))
    }

    @Test
    fun `setGlobalEnabled true after false then isGlobalEnabled returns true`() {
        MemoryGlobalPrefs.setGlobalEnabled(context, false)
        MemoryGlobalPrefs.setGlobalEnabled(context, true)
        assertTrue(MemoryGlobalPrefs.isGlobalEnabled(context))
    }

    @Test
    fun `prefs file is shared across instances`() {
        val context1 = context
        val context2 = context

        MemoryGlobalPrefs.setGlobalEnabled(context1, false)
        assertFalse(MemoryGlobalPrefs.isGlobalEnabled(context2))
    }

    @Test
    fun `renders global enabled state composable`() {
        var enabledState by mutableStateOf(MemoryGlobalPrefs.isGlobalEnabled(context))

        composeTestRule.setContent {
            Button(onClick = {
                enabledState = !enabledState
                MemoryGlobalPrefs.setGlobalEnabled(context, enabledState)
            }) {
                Text(if (enabledState) "Enabled" else "Disabled")
            }
        }

        composeTestRule.onNodeWithText("Enabled").assertExists()
        composeTestRule.onNodeWithText("Disabled").assertDoesNotExist()
    }

    @Test
    fun `clicking toggle changes state and persists`() {
        var enabledState by mutableStateOf(MemoryGlobalPrefs.isGlobalEnabled(context))

        composeTestRule.setContent {
            Button(onClick = {
                enabledState = !enabledState
                MemoryGlobalPrefs.setGlobalEnabled(context, enabledState)
            }) {
                Text(if (enabledState) "Enabled" else "Disabled")
            }
        }

        // Initially enabled
        composeTestRule.onNodeWithText("Enabled").assertExists()
        
        // Click to disable
        composeTestRule.onNodeWithText("Enabled").performClick()
        composeTestRule.onNodeWithText("Disabled").assertExists()
        assertFalse(MemoryGlobalPrefs.isGlobalEnabled(context))

        // Click to enable again
        composeTestRule.onNodeWithText("Disabled").performClick()
        composeTestRule.onNodeWithText("Enabled").assertExists()
        assertTrue(MemoryGlobalPrefs.isGlobalEnabled(context))
    }

    @Test
    fun `composable with default parameter renders correctly`() {
        var enabledState by mutableStateOf(MemoryGlobalPrefs.isGlobalEnabled(context))

        composeTestRule.setContent {
            GlobalToggleComposable(
                enabled = enabledState,
                onToggle = {
                    enabledState = !enabledState
                    MemoryGlobalPrefs.setGlobalEnabled(context, enabledState)
                }
            )
        }

        composeTestRule.onNodeWithText("Global Memory: Enabled").assertExists()
    }

    @Test
    fun `composable click handler works with default parameter`() {
        var enabledState by mutableStateOf(MemoryGlobalPrefs.isGlobalEnabled(context))

        composeTestRule.setContent {
            GlobalToggleComposable(
                enabled = enabledState,
                onToggle = {
                    enabledState = !enabledState
                    MemoryGlobalPrefs.setGlobalEnabled(context, enabledState)
                }
            )
        }

        composeTestRule.onNodeWithText("Global Memory: Enabled").performClick()
        composeTestRule.onNodeWithText("Global Memory: Disabled").assertExists()
        assertFalse(MemoryGlobalPrefs.isGlobalEnabled(context))
    }

    @Composable
    private fun GlobalToggleComposable(
        enabled: Boolean,
        onToggle: () -> Unit
    ) {
        Button(onClick = onToggle) {
            Text(if (enabled) "Global Memory: Enabled" else "Global Memory: Disabled")
        }
    }
}