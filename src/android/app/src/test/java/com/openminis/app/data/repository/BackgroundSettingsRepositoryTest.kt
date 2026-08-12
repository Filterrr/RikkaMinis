package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

class BackgroundSettingsRepositoryTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var repository: BackgroundSettingsRepository

    @BeforeEach
    fun setUp() {
        mockContext = mockk()
        mockPrefs = mockk()
        mockEditor = mockk(relaxed = true)
        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSharedPreferences("background_settings", Context.MODE_PRIVATE) } returns mockPrefs
        // Default prefs values
        every { mockPrefs.getBoolean("taskNotificationsEnabled", true) } returns true
        every { mockPrefs.getBoolean("backgroundOverlayEnabled", false) } returns false
        every { mockPrefs.getBoolean("dynamicIslandEnabled", false) } returns false
        every { mockPrefs.getInt("backgroundOverlayX", -1) } returns -1
        every { mockPrefs.getInt("backgroundOverlayY", -1) } returns -1
        // Mock edit()
        every { mockPrefs.edit() } returns mockEditor
        repository = BackgroundSettingsRepository(mockContext)
    }

    @Test
    fun `test taskNotificationsEnabled initial value`() = runBlocking {
        assertEquals(true, repository.taskNotificationsEnabled.first())
    }

    @Test
    fun `test setTaskNotificationsEnabled updates StateFlow and SharedPreferences`() = runBlocking {
        val newValue = false
        repository.setTaskNotificationsEnabled(newValue)
        assertEquals(newValue, repository.taskNotificationsEnabled.first())
        verify { mockEditor.putBoolean("taskNotificationsEnabled", newValue) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `test backgroundOverlayEnabled initial value`() = runBlocking {
        assertEquals(false, repository.backgroundOverlayEnabled.first())
    }

    @Test
    fun `test setBackgroundOverlayEnabled updates StateFlow and SharedPreferences`() = runBlocking {
        val newValue = true
        repository.setBackgroundOverlayEnabled(newValue)
        assertEquals(newValue, repository.backgroundOverlayEnabled.first())
        verify { mockEditor.putBoolean("backgroundOverlayEnabled", newValue) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `test dynamicIslandEnabled initial value`() = runBlocking {
        assertEquals(false, repository.dynamicIslandEnabled.first())
    }

    @Test
    fun `test setDynamicIslandEnabled updates StateFlow and SharedPreferences`() = runBlocking {
        val newValue = true
        repository.setDynamicIslandEnabled(newValue)
        assertEquals(newValue, repository.dynamicIslandEnabled.first())
        verify { mockEditor.putBoolean("dynamicIslandEnabled", newValue) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `test getOverlayX returns default value`() {
        assertEquals(-1, repository.getOverlayX())
        verify { mockPrefs.getInt("backgroundOverlayX", -1) }
    }

    @Test
    fun `test getOverlayY returns default value`() {
        assertEquals(-1, repository.getOverlayY())
        verify { mockPrefs.getInt("backgroundOverlayY", -1) }
    }

    @Test
    fun `test setOverlayPosition stores values and verifies editor calls`() {
        repository.setOverlayPosition(100, 200)
        verify { mockEditor.putInt("backgroundOverlayX", 100) }
        verify { mockEditor.putInt("backgroundOverlayY", 200) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `test setOverlayPosition updates getOverlayX and getOverlayY`() {
        // Override mock to return new values after set
        every { mockPrefs.getInt("backgroundOverlayX", -1) } returns 100
        every { mockPrefs.getInt("backgroundOverlayY", -1) } returns 200
        repository.setOverlayPosition(100, 200)
        assertAll(
            { assertEquals(100, repository.getOverlayX()) },
            { assertEquals(200, repository.getOverlayY()) }
        )
    }
}