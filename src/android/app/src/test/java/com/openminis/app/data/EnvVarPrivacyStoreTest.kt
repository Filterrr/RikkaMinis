package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnvVarPrivacyStoreTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EnvVarPrivacyStore.init(context)
    }

    @Composable
    fun TestComposable() {
        val enabled by EnvVarPrivacyStore.enabled.collectAsState()
        Column {
            Text(text = if (enabled) "Enabled" else "Disabled")
            Switch(
                checked = enabled,
                onCheckedChange = { EnvVarPrivacyStore.setEnabled(it) }
            )
        }
    }

    @Test
    fun testInitialState() {
        EnvVarPrivacyStore.setEnabled(true)
        composeTestRule.setContent {
            TestComposable()
        }
        composeTestRule.onNodeWithText("Enabled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Switch").assertIsOn()
    }

    @Test
    fun testToggleSwitch() {
        EnvVarPrivacyStore.setEnabled(true)
        composeTestRule.setContent {
            TestComposable()
        }
        composeTestRule.onNodeWithText("Switch").performClick()
        composeTestRule.onNodeWithText("Disabled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Switch").assertIsOff()
        assertFalse(EnvVarPrivacyStore.isEnabled)
    }

    @Test
    fun testDefaultEnabled() {
        val sharedPrefs = context.getSharedPreferences("envvar_privacy", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().apply()
        EnvVarPrivacyStore.init(context)
        assertTrue(EnvVarPrivacyStore.isEnabled)
    }

    @Test
    fun testSetEnabledTrue() {
        EnvVarPrivacyStore.setEnabled(true)
        assertTrue(EnvVarPrivacyStore.isEnabled)
        val flowValue = runBlocking { EnvVarPrivacyStore.enabled.first() }
        assertTrue(flowValue)
    }

    @Test
    fun testSetEnabledFalse() {
        EnvVarPrivacyStore.setEnabled(false)
        assertFalse(EnvVarPrivacyStore.isEnabled)
        val flowValue = runBlocking { EnvVarPrivacyStore.enabled.first() }
        assertFalse(flowValue)
    }

    @Test
    fun testPrefsPersistence() {
        EnvVarPrivacyStore.setEnabled(true)
        val sharedPrefs = context.getSharedPreferences("envvar_privacy", Context.MODE_PRIVATE)
        assertTrue(sharedPrefs.getBoolean("privacy_mode_enabled", true))

        EnvVarPrivacyStore.setEnabled(false)
        assertFalse(sharedPrefs.getBoolean("privacy_mode_enabled", true))
    }

    @Test
    fun testInitWithExistingPrefs() {
        val sharedPrefs = context.getSharedPreferences("envvar_privacy", Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("privacy_mode_enabled", false).apply()
        EnvVarPrivacyStore.init(context)
        assertFalse(EnvVarPrivacyStore.isEnabled)
    }

    @Test
    fun testInitCalledMultipleTimes() {
        EnvVarPrivacyStore.setEnabled(true)
        EnvVarPrivacyStore.init(context)
        assertTrue(EnvVarPrivacyStore.isEnabled)
    }

    @Test
    fun testRenderWithDefaultParameters() {
        composeTestRule.setContent {
            TestComposable()
        }
        composeTestRule.onNodeWithText("Enabled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Switch").assertIsOn()
    }

    @Test
    fun testEnabledStateFlow() {
        EnvVarPrivacyStore.setEnabled(true)
        val flowValue = runBlocking { EnvVarPrivacyStore.enabled.first() }
        assertTrue(flowValue)
        assertEquals(EnvVarPrivacyStore.isEnabled, flowValue)
    }
}