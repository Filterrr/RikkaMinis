package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.Rule

class EnhancedCachePrefsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @BeforeEach
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("minis_enhanced_cache_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @AfterEach
    fun tearDown() {
        prefs.edit().clear().apply()
    }

    @Test
    fun `isConfirmed returns false by default`() {
        val result = EnhancedCachePrefs.isConfirmed(context)
        assertFalse(result)
    }

    @Test
    fun `isConfirmed returns true after setConfirmed`() {
        EnhancedCachePrefs.setConfirmed(context)
        val result = EnhancedCachePrefs.isConfirmed(context)
        assertTrue(result)
    }

    @Test
    fun `isConfirmed returns false after clearing prefs`() {
        EnhancedCachePrefs.setConfirmed(context)
        prefs.edit().clear().apply()
        val result = EnhancedCachePrefs.isConfirmed(context)
        assertFalse(result)
    }

    @Test
    fun `setConfirmed persists the value`() {
        EnhancedCachePrefs.setConfirmed(context)
        val newPrefs = context.getSharedPreferences("minis_enhanced_cache_prefs", Context.MODE_PRIVATE)
        val result = newPrefs.getBoolean("enhancedCacheConfirmed", false)
        assertTrue(result)
    }
}