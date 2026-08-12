package com.openminis.app.crash

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeCrashHandlerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `install should not throw when native library is not available`() {
        val logsDir = File(tempDir, "logs")
        NativeCrashHandler.install(logsDir)
        assertTrue(logsDir.exists())
    }

    @Test
    fun `install should create logs directory`() {
        val logsDir = File(tempDir, "custom_logs")
        NativeCrashHandler.install(logsDir)
        assertTrue(logsDir.exists())
    }

    @Test
    fun `install should be idempotent`() {
        val logsDir = File(tempDir, "logs")
        NativeCrashHandler.install(logsDir)
        NativeCrashHandler.install(logsDir)
        assertTrue(logsDir.exists())
    }

    @Test
    fun `install should handle multiple calls with different directories`() {
        val logsDir1 = File(tempDir, "logs1")
        val logsDir2 = File(tempDir, "logs2")
        NativeCrashHandler.install(logsDir1)
        NativeCrashHandler.install(logsDir2)
        assertTrue(logsDir1.exists())
        assertTrue(logsDir2.exists())
    }

    @Test
    fun `install should handle non-existent parent directory`() {
        val logsDir = File(tempDir, "nested/deep/logs")
        NativeCrashHandler.install(logsDir)
        assertTrue(logsDir.exists())
    }
}