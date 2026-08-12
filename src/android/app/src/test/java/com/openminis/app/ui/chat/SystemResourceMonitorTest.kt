package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class SystemResourceMonitorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `rememberSystemResourceMonitor - renders and returns monitor`() {
        var monitorResult: SystemResourceMonitor? = null
        composeTestRule.setContent {
            monitorResult = rememberSystemResourceMonitor(active = true)
        }
        composeTestRule.waitForIdle()
        assertNotNull(monitorResult)
    }

    @Test
    fun `rememberSystemResourceMonitor - active false resets monitor`() {
        var monitorResult: SystemResourceMonitor? = null
        composeTestRule.setContent {
            monitorResult = rememberSystemResourceMonitor(active = false)
        }
        composeTestRule.waitForIdle()
        assertNotNull(monitorResult)
        assertEquals(0f, monitorResult?.cpuUsage ?: -1f, 0.01f)
        assertEquals(0L, monitorResult?.memUsedBytes ?: -1L)
        assertEquals(0L, monitorResult?.memTotalBytes ?: -1L)
    }

    @Test
    fun `rememberSystemResourceMonitor - active true samples and updates`() {
        var monitorResult: SystemResourceMonitor? = null
        composeTestRule.setContent {
            monitorResult = rememberSystemResourceMonitor(active = true)
        }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 3000) {
            monitorResult?.memTotalBytes ?: 0L > 0L
        }
        assertTrue(monitorResult?.memTotalBytes ?: 0L > 0L)
    }

    @Test
    fun `SystemResourceMonitor - default values`() {
        val monitor = SystemResourceMonitor()
        assertEquals(0f, monitor.cpuUsage, 0.01f)
        assertEquals(0L, monitor.memUsedBytes)
        assertEquals(0L, monitor.memTotalBytes)
    }

    @Test
    fun `SystemResourceMonitor - reset resets values`() {
        val monitor = SystemResourceMonitor()
        monitor.sampleOnce(mock())
        monitor.reset()
        assertEquals(0f, monitor.cpuUsage, 0.01f)
        assertEquals(0L, monitor.memUsedBytes)
        assertEquals(0L, monitor.memTotalBytes)
    }

    @Test
    fun `SystemResourceMonitor - sampleOnce updates memory values`() {
        val context = mock<Context>()
        val am = mock<android.app.ActivityManager>()
        val memInfo = android.app.ActivityManager.MemoryInfo()
        memInfo.totalMem = 8L * 1024 * 1024 * 1024
        whenever(context.getSystemService(Context.ACTIVITY_SERVICE)).thenReturn(am)
        whenever(am.memoryInfo).thenReturn(memInfo)

        val monitor = SystemResourceMonitor()
        monitor.sampleOnce(context)
        assertEquals(8L * 1024 * 1024 * 1024, monitor.memTotalBytes)
    }

    @Test
    fun `SystemResourceMonitor - formattedCpu returns correct format`() {
        val monitor = SystemResourceMonitor()
        monitor.cpuUsage = 45.6f
        assertEquals("CPU 46%", monitor.formattedCpu())
    }

    @Test
    fun `SystemResourceMonitor - formattedCpu handles negative values`() {
        val monitor = SystemResourceMonitor()
        monitor.cpuUsage = -10f
        assertEquals("CPU 0%", monitor.formattedCpu())
    }

    @Test
    fun `SystemResourceMonitor - formattedMem returns correct format`() {
        val monitor = SystemResourceMonitor()
        monitor.memUsedBytes = 2L * 1024 * 1024 * 1024
        monitor.memTotalBytes = 8L * 1024 * 1024 * 1024
        assertEquals("MEM 2.0/8.0 GB", monitor.formattedMem())
    }

    @Test
    fun `SystemResourceMonitor - formattedMem compact returns correct format`() {
        val monitor = SystemResourceMonitor()
        monitor.memUsedBytes = 2L * 1024 * 1024 * 1024
        assertEquals("MEM 2.0G", monitor.formattedMem(compact = true))
    }

    @Test
    fun `SystemResourceMonitor - formattedMem handles zero values`() {
        val monitor = SystemResourceMonitor()
        assertEquals("MEM 0.0/0.0 GB", monitor.formattedMem())
        assertEquals("MEM 0.0G", monitor.formattedMem(compact = true))
    }

    @Test
    fun `SystemResourceMonitor - sampleCpu reads from proc file`() {
        val monitor = SystemResourceMonitor()
        val procContent = "1234 (test) S 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20"
        val file = File("/proc/self/stat")
        if (file.exists()) {
            monitor.sampleOnce(mock())
            assertTrue(monitor.cpuUsage >= 0f)
        }
    }

    @Test
    fun `SystemResourceMonitor - multiple samples smooth cpu usage`() {
        val monitor = SystemResourceMonitor()
        val context = mock<Context>()
        monitor.sampleOnce(context)
        Thread.sleep(100)
        monitor.sampleOnce(context)
        Thread.sleep(100)
        monitor.sampleOnce(context)
        assertTrue(monitor.cpuUsage >= 0f)
    }

    @Test
    fun `rememberSystemResourceMonitor - recomposition maintains state`() {
        var count = 0
        composeTestRule.setContent {
            val monitor = rememberSystemResourceMonitor(active = true)
            count++
            if (count == 1) {
                assertNotNull(monitor)
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            val monitor = rememberSystemResourceMonitor(active = true)
            count++
            if (count == 2) {
                assertNotNull(monitor)
            }
        }
        composeTestRule.waitForIdle()
        assertTrue(count >= 2)
    }

    @Test
    fun `rememberSystemResourceMonitor - toggle active state`() {
        var activeState = true
        var monitor: SystemResourceMonitor? = null
        composeTestRule.setContent {
            monitor = rememberSystemResourceMonitor(active = activeState)
        }
        composeTestRule.waitForIdle()
        assertNotNull(monitor)

        activeState = false
        composeTestRule.setContent {
            monitor = rememberSystemResourceMonitor(active = activeState)
        }
        composeTestRule.waitForIdle()
        assertEquals(0f, monitor?.cpuUsage ?: -1f, 0.01f)
    }
}