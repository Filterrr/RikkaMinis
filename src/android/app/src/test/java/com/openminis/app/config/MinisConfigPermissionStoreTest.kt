package com.openminis.app.config

import android.content.Context
import android.content.SharedPreferences
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class MinisConfigPermissionStoreTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockSharedPreferences: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    @Test
    fun `init should load default value when prefs do not contain key`() {
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockSharedPreferences)
        whenever(mockSharedPreferences.contains(MinisConfigPermissionStore.KEY)).thenReturn(false)

        MinisConfigPermissionStore.init(mockContext)

        assertEquals(true, MinisConfigPermissionStore.isEnabled)
        assertEquals(true, MinisConfigPermissionStore.enabled.value)
    }

    @Test
    fun `init should load stored value when prefs contain key`() {
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockSharedPreferences)
        whenever(mockSharedPreferences.contains(MinisConfigPermissionStore.KEY)).thenReturn(true)
        whenever(mockSharedPreferences.getBoolean(MinisConfigPermissionStore.KEY, true)).thenReturn(false)

        MinisConfigPermissionStore.init(mockContext)

        assertEquals(false, MinisConfigPermissionStore.isEnabled)
        assertEquals(false, MinisConfigPermissionStore.enabled.value)
    }

    @Test
    fun `init should not reinitialize when prefs already set`() {
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockSharedPreferences)
        whenever(mockSharedPreferences.contains(MinisConfigPermissionStore.KEY)).thenReturn(false)

        MinisConfigPermissionStore.init(mockContext)
        MinisConfigPermissionStore.init(mockContext)

        assertEquals(true, MinisConfigPermissionStore.isEnabled)
    }

    @Test
    fun `setEnabled should update preferences and state flow`() {
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockSharedPreferences)
        whenever(mockSharedPreferences.contains(MinisConfigPermissionStore.KEY)).thenReturn(false)
        whenever(mockSharedPreferences.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putBoolean(any(), any())).thenReturn(mockEditor)

        MinisConfigPermissionStore.init(mockContext)
        MinisConfigPermissionStore.setEnabled(false)

        assertEquals(false, MinisConfigPermissionStore.isEnabled)
        assertEquals(false, MinisConfigPermissionStore.enabled.value)
        verify(mockEditor).putBoolean(MinisConfigPermissionStore.KEY, false)
        verify(mockEditor).apply()
    }

    @Test
    fun `setEnabled should update state flow even when prefs is null`() {
        // Reset prefs to null
        MinisConfigPermissionStore.setEnabled(true)

        assertEquals(true, MinisConfigPermissionStore.isEnabled)
        assertEquals(true, MinisConfigPermissionStore.enabled.value)
    }

    @Test
    fun `enabled state flow should reflect changes`() {
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockSharedPreferences)
        whenever(mockSharedPreferences.contains(MinisConfigPermissionStore.KEY)).thenReturn(false)
        whenever(mockSharedPreferences.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putBoolean(any(), any())).thenReturn(mockEditor)

        MinisConfigPermissionStore.init(mockContext)
        assertEquals(true, MinisConfigPermissionStore.enabled.value)

        MinisConfigPermissionStore.setEnabled(false)
        assertEquals(false, MinisConfigPermissionStore.enabled.value)

        MinisConfigPermissionStore.setEnabled(true)
        assertEquals(true, MinisConfigPermissionStore.enabled.value)
    }
}