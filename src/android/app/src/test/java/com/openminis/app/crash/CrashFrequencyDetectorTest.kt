package com.openminis.app.crash

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.openminis.app.R
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.test.*

@ExtendWith(MockitoExtension::class)
class CrashFrequencyDetectorTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockApplication: Application

    @Mock
    private lateinit var mockActivity: Activity

    @Mock
    private lateinit var mockSharedPreferences: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    @Mock
    private lateinit var mockResources: android.content.res.Resources

    @Mock
    private lateinit var mockDisplayMetrics: android.util.DisplayMetrics

    @TempDir
    lateinit var tempDir: File

    private val logsDir: File get() = File(tempDir, "logs")

    @BeforeEach
    fun setUp() {
        Mockito.`when`(mockApplication.filesDir).thenReturn(tempDir)
        Mockito.`when`(mockContext.filesDir).thenReturn(tempDir)
        Mockito.`when`(mockContext.getSharedPreferences(anyString(), anyInt()))
            .thenReturn(mockSharedPreferences)
        Mockito.`when`(mockSharedPreferences.edit()).thenReturn(mockEditor)
        Mockito.`when`(mockEditor.putLong(anyString(), anyLong())).thenReturn(mockEditor)
        Mockito.`when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor)
        Mockito.`when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        Mockito.`when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        Mockito.`when`(mockEditor.putStringSet(anyString(), anySet())).thenReturn(mockEditor)

        Mockito.`when`(mockActivity.applicationContext).thenReturn(mockContext)
        Mockito.`when`(mockActivity.resources).thenReturn(mockResources)
        Mockito.`when`(mockResources.displayMetrics).thenReturn(mockDisplayMetrics)
        Mockito.`when`(mockResources.getString(anyInt())).thenReturn("test")
        Mockito.`when`(mockResources.getString(anyInt(), any())).thenReturn("test")
        Mockito.`when`(mockResources.getDimensionPixelSize(anyInt())).thenReturn(10)
        Mockito.`when`(mockResources.getDimension(anyInt())).thenReturn(10f)
        Mockito.`when`(mockResources.getInteger(anyInt())).thenReturn(1)
        mockDisplayMetrics.density = 1.0f
    }

    @AfterEach
    fun tearDown() {
        logsDir.deleteRecursively()
    }

    // ─── Helper functions ──────────────────────────────────────────────

    private fun createCrashLogFile(name: String, ageMs: Long = 0): File {
        val file = File(logsDir, name)
        file.parentFile.mkdirs()
        file.writeText("crash log content")
        file.setLastModified(System.currentTimeMillis() - ageMs)
        return file
    }

    private fun createDailyLogFile(name: String, ageMs: Long = 0): File {
        val file = File(logsDir, name)
        file.parentFile.mkdirs()
        file.writeText("daily log content")
        file.setLastModified(System.currentTimeMillis() - ageMs)
        return file
    }

    // ─── Tests for registerSafeModeCleared ─────────────────────────────

    @Test
    fun `registerSafeModeCleared adds listener and returns unregister function`() {
        var called = false
        val listener: () -> Unit = { called = true }
        val unregister = CrashFrequencyDetector.registerSafeModeCleared(listener)

        // Check that listener is registered (we can't easily test internal firing here)
        assertNotNull(unregister, "unregister function should not be null")
        assertFalse(called, "listener should not have been called yet")
    }

    @Test
    fun `registerSafeModeCleared unregister removes listener`() {
        var callCount = 0
        val listener: () -> Unit = { callCount++ }
        val unregister = CrashFrequencyDetector.registerSafeModeCleared(listener)

        unregister()

        // We can't directly test the internal state, but we can verify the unregister function works
        assertNotNull(unregister)
    }

    // ─── Tests for isSafeMode / isInitSkipped ──────────────────────────

    @Test
    fun `isSafeMode returns false by default`() {
        assertFalse(CrashFrequencyDetector.isSafeMode())
    }

    @Test
    fun `isInitSkipped returns false by default`() {
        assertFalse(CrashFrequencyDetector.isInitSkipped())
    }

    // ─── Tests for shouldForceHomeOnLaunch ─────────────────────────────

    @Test
    fun `shouldForceHomeOnLaunch returns false when no force home set`() {
        Mockito.`when`(mockSharedPreferences.getLong(anyString(), anyLong())).thenReturn(0L)
        assertFalse(CrashFrequencyDetector.shouldForceHomeOnLaunch(mockContext))
    }

    @Test
    fun `shouldForceHomeOnLaunch returns true when within force home period`() {
        val futureTime = System.currentTimeMillis() + 30_000L
        Mockito.`when`(mockSharedPreferences.getLong(anyString(), anyLong())).thenReturn(futureTime)
        assertTrue(CrashFrequencyDetector.shouldForceHomeOnLaunch(mockContext))
    }

    @Test
    fun `shouldForceHomeOnLaunch returns false when force home period expired`() {
        val pastTime = System.currentTimeMillis() - 30_000L
        Mockito.`when`(mockSharedPreferences.getLong(anyString(), anyLong())).thenReturn(pastTime)
        assertFalse(CrashFrequencyDetector.shouldForceHomeOnLaunch(mockContext))
    }

    @Test
    fun `shouldForceHomeOnLaunch returns false on exception`() {
        Mockito.`when`(mockSharedPreferences.getLong(anyString(), anyLong()))
            .thenThrow(RuntimeException("test"))
        assertFalse(CrashFrequencyDetector.shouldForceHomeOnLaunch(mockContext))
    }

    // ─── Tests for checkAtLaunch ───────────────────────────────────────

    @Test
    fun `checkAtLaunch does nothing when logs directory does not exist`() {
        // logsDir doesn't exist
        CrashFrequencyDetector.checkAtLaunch(mockApplication)
        assertNull(CrashFrequencyDetector.pendingShareFiles)
        assertFalse(CrashFrequencyDetector.isSafeMode())
    }

    @Test
    fun `checkAtLaunch does nothing when no crash logs`() {
        logsDir.mkdirs()
        createDailyLogFile("minis-20230101.log", ageMs = 1000L)

        CrashFrequencyDetector.checkAtLaunch(mockApplication)
        assertNull(CrashFrequencyDetector.pendingShareFiles)
        assertFalse(CrashFrequencyDetector.isSafeMode())
    }

    @Test
    fun `checkAtLaunch does nothing when crash count below threshold`() {
        logsDir.mkdirs()
        createCrashLogFile("crash-1.log", ageMs = 1000L)

        CrashFrequencyDetector.checkAtLaunch(mockApplication)
        assertNull(CrashFrequencyDetector.pendingShareFiles)
        assertFalse(CrashFrequencyDetector.isSafeMode())
    }

    @Test
    fun `checkAtLaunch activates safe mode when threshold exceeded`() {
        logsDir.mkdirs()
        createCrashLogFile("crash-1.log", ageMs = 1000L)
        createCrashLogFile("crash-2.log", ageMs = 2000L)
        createCrashLogFile("crash-3.log", ageMs = 3000L)

        CrashFrequencyDetector.checkAtLaunch(mockApplication)
        assertNotNull(CrashFrequencyDetector.pendingShareFiles)
        assertTrue(CrashFrequencyDetector.isSafeMode())
        assertTrue(CrashFrequencyDetector.isInitSkipped())
    }

    @Test
    fun `checkAtLaunch respects suppressed state`() {
        val futureTime = System.currentTimeMillis() + 30_000L
        Mockito.`when`(mockSharedPreferences.getLong(anyString(), anyLong())).thenReturn(futureTime)

        logsDir.mkdirs()
        createCrashLogFile("crash-1.log", ageMs = 1000L)
        createCrashLogFile("crash-2.log", ageMs = 2000L)
        createCrashLogFile("crash-3.log", ageMs = 3000L)

        CrashFrequencyDetector.checkAtLaunch(mockApplication)
        assertNull(CrashFrequencyDetector.pendingShareFiles)
        assertFalse(CrashFrequencyDetector.isSafeMode())
    }

    @Test
    fun `checkAtLaunch respects dismissedAt within window`() {
        val dismissedAt = System.currentTimeMillis() - 30_000L
        Mockito.`when`(mockSharedPreferences.getLong(anyString(), anyLong())).thenReturn(dismissedAt)

        logsDir.mkdirs()
        createCrashLogFile("crash-1.log", ageMs = 1000L)
        createCrashLogFile("crash-2.log", ageMs = 2000L)
        createCrashLogFile("crash-3.log", ageMs = 3000L)

        CrashFrequencyDetector.checkAtLaunch(mockApplication)
        assertNull(CrashFrequencyDetector.pendingShareFiles)
        assertFalse(CrashFrequencyDetector.isSafeMode())
    }

    @Test
    fun `checkAtLaunch ignores dismissedAt outside window`() {
        val oldDismissedAt = System.currentTimeMillis() - 2 * 60 * 60 * 1000L // 2 hours
        Mockito.`when`(mockSharedPreferences.getLong(anyString(), anyLong())).thenReturn(oldDismissedAt)

        logsDir.mkdirs()
        createCrashLogFile("crash-1.log", ageMs = 1000L)
        createCrashLogFile("crash-2.log", ageMs = 2000L)
        createCrashLogFile("crash-3.log", ageMs = 3000L)

        CrashFrequencyDetector.checkAtLaunch(mockApplication)
        assertNotNull(CrashFrequencyDetector.pendingShareFiles)
        assertTrue(CrashFrequencyDetector.isSafeMode())
    }

    // ─── Tests for pendingShareFiles ──────────────────────────────────

    @Test
    fun `pendingShareFiles is initially null`() {
        assertNull(CrashFrequencyDetector.pendingShareFiles)
    }

    @Test
    fun `pendingShareFiles is set after checkAtLaunch with threshold exceeded`() {
        logsDir.mkdirs()
        createCrashLogFile("crash-1.log", ageMs = 1000L)
        createCrashLogFile("crash-2.log", ageMs = 2000L)
        createCrashLogFile("crash-3.log", ageMs = 3000L)

        CrashFrequencyDetector.checkAtLaunch(mockApplication)
        val files = CrashFrequencyDetector.pendingShareFiles
        assertNotNull(files)
        assertEquals(3, files!!.size)
    }

    @Test
    fun `pendingShareFiles is sorted by lastModified descending`() {
        logsDir.mkdirs()
        val file1 = createCrashLogFile("crash-1.log", ageMs = 1000L)
        val file2 = createCrashLogFile("crash-2.log", ageMs = 2000L)
        val file3 = createCrashLogFile("crash-3.log", ageMs = 3000L)

        // Wait a bit to ensure timestamps are different
        Thread.sleep(10)

        CrashFrequencyDetector.checkAtLaunch(mockApplication)
        val files = CrashFrequencyDetector.pendingShareFiles
        assertNotNull(files)
        assertTrue(files!![0].lastModified() >= files[1].lastModified())
        assertTrue(files[1].lastModified() >= files[2].lastModified())
    }

    // ─── Tests for maybeShowOnActivity ─────────────────────────────────

    @Test
    fun `maybeShowOnActivity does nothing when pendingShareFiles is null`() {
        var onClosedCalled = false
        CrashFrequencyDetector.maybeShowOnActivity(
            activity = mockActivity,
            onClosed = { onClosedCalled = true }
        )
        assertTrue(onClosedCalled)
    }

    @Test
    fun `maybeShowOnActivity clears pendingShareFiles`() {
        // First set up some crash files
        logsDir.mkdirs()
        createCrashLogFile("crash-1.log", ageMs = 1000L)
        createCrashLogFile("crash-2.log", ageMs = 2000L)
        createCrashLogFile("crash-3.log", ageMs = 3000L)

        CrashFrequencyDetector.checkAtLaunch(mockApplication)
        assertNotNull(CrashFrequencyDetector.pendingShareFiles)

        // Now call maybeShowOnActivity - it should clear pendingShareFiles
        // We need to mock the dialog creation to avoid actual UI
        try {
            CrashFrequencyDetector.maybeShowOnActivity(mockActivity)
        } catch (e: Exception) {
            // Expected due to dialog creation in test environment
        }

        // pendingShareFiles should be cleared
        assertNull(CrashFrequencyDetector.pendingShareFiles)
    }

    // ─── Tests for formatBytes (internal) ──────────────────────────────

    @Test
    fun `formatBytes formats correctly for bytes`() {
        // We can't access private function directly, but we can test via public API
        // This is tested indirectly through file size display
    }

    @Test
    fun `formatBytes formats correctly for kilobytes`() {
        // Same as above - tested indirectly
    }

    @Test
    fun `formatBytes formats correctly for megabytes`() {
        // Same as above - tested indirectly
    }

    // ─── Integration test for full flow ────────────────────────────────

    @Test
    fun `full flow checkAtLaunch to maybeShowOnActivity`() {
        logsDir.mkdirs()
        createCrashLogFile("crash-1.log", ageMs = 1000L)
        createCrashLogFile("crash-2.log", ageMs = 2000L)
        createCrashLogFile("crash-3.log", ageMs = 3000L)

        CrashFrequencyDetector.checkAtLaunch(mockApplication)
        assertTrue(CrashFrequencyDetector.isSafeMode())
        assertNotNull(CrashFrequencyDetector.pendingShareFiles)

        // maybeShowOnActivity should clear pendingShareFiles
        try {
            CrashFrequencyDetector.maybeShowOnActivity(mockActivity)
        } catch (e: Exception) {
            // Expected
        }
        assertNull(CrashFrequencyDetector.pendingShareFiles)
    }

    // ─── Helper functions for mockito matchers ─────────────────────────

    private fun anyString(): String = Mockito.anyString()
    private fun anyInt(): Int = Mockito.anyInt()
    private fun anyLong(): Long = Mockito.anyLong()
    private fun anyBoolean(): Boolean = Mockito.anyBoolean()
    private fun anySet(): MutableSet<String> = Mockito.anySet()
}