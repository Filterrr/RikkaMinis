package com.openminis.app.data

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.Rule

class FastModePrefsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FastModePrefs.prime(context)
        FastModePrefs.setEnabled(context, false)
    }

    @AfterEach
    fun tearDown() {
        FastModePrefs.setEnabled(context, false)
    }

    @Test
    fun `prime should initialize cached value from SharedPreferences`() {
        FastModePrefs.setEnabled(context, true)
        FastModePrefs.prime(context)
        assertTrue(FastModePrefs.isEnabled())
    }

    @Test
    fun `isEnabled should return false by default after prime`() {
        assertFalse(FastModePrefs.isEnabled())
    }

    @Test
    fun `setEnabled should update cached value and persist to SharedPreferences`() {
        FastModePrefs.setEnabled(context, true)
        assertTrue(FastModePrefs.isEnabled())

        FastModePrefs.prime(context)
        assertTrue(FastModePrefs.isEnabled())
    }

    @Test
    fun `setEnabled should set to false`() {
        FastModePrefs.setEnabled(context, true)
        FastModePrefs.setEnabled(context, false)
        assertFalse(FastModePrefs.isEnabled())
    }

    @Test
    fun `multiple calls to setEnabled should work correctly`() {
        FastModePrefs.setEnabled(context, true)
        FastModePrefs.setEnabled(context, false)
        FastModePrefs.setEnabled(context, true)
        FastModePrefs.setEnabled(context, false)
        assertFalse(FastModePrefs.isEnabled())
    }

    @Test
    fun `prime should work with different context instances`() {
        FastModePrefs.setEnabled(context, true)
        val newContext = ApplicationProvider.getApplicationContext<Context>()
        FastModePrefs.prime(newContext)
        assertTrue(FastModePrefs.isEnabled())
    }
}