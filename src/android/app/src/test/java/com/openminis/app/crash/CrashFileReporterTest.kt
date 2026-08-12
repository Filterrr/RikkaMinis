package com.openminis.app.crash

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.ReportField
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashFileReporterTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `CrashFileSender send should create log file with crash data`() {
        val sender = CrashFileSender()
        val crashData = createTestCrashReportData()
        
        sender.send(context, crashData)
        
        val logDir = File(context.filesDir, "logs")
        assertTrue(logDir.exists())
        
        val files = logDir.listFiles()
        assertNotNull(files)
        assertTrue(files!!.isNotEmpty())
        
        val logFile = files.first()
        val content = logFile.readText()
        
        assertTrue(content.contains("=== Minis Java/Kotlin Crash ==="))
        assertTrue(content.contains("Time:"))
        assertTrue(content.contains("Version: 1.0 (100)"))
        assertTrue(content.contains("Android: 13 (33)"))
        assertTrue(content.contains("Device: Pixel 7 (Google)"))
        assertTrue(content.contains("--- Stack Trace ---"))
        assertTrue(content.contains("java.lang.NullPointerException"))
        assertTrue(content.contains("--- Logcat (last 200 lines) ---"))
        assertTrue(content.contains("Test logcat line"))
    }

    @Test
    fun `CrashFileSender send should handle null logcat gracefully`() {
        val sender = CrashFileSender()
        val crashData = createTestCrashReportData(includeLogcat = false)
        
        sender.send(context, crashData)
        
        val logDir = File(context.filesDir, "logs")
        val files = logDir.listFiles()
        val logFile = files!!.first()
        val content = logFile.readText()
        
        assertTrue(content.contains("--- Stack Trace ---"))
        assertTrue(!content.contains("--- Logcat"))
    }

    @Test
    fun `CrashFileSenderFactory create should return CrashFileSender instance`() {
        val factory = CrashFileSenderFactory()
        val config = CoreConfiguration.Builder(context).build()
        
        val sender = factory.create(context, config)
        
        assertTrue(sender is CrashFileSender)
    }

    @Test
    fun `CrashFileSenderFactory enabled should always return true`() {
        val factory = CrashFileSenderFactory()
        val config = CoreConfiguration.Builder(context).build()
        
        assertEquals(true, factory.enabled(config))
    }

    @Test
    fun `CrashFileSender send should create file with timestamp pattern`() {
        val sender = CrashFileSender()
        val crashData = createTestCrashReportData()
        
        sender.send(context, crashData)
        
        val logDir = File(context.filesDir, "logs")
        val files = logDir.listFiles()
        val fileName = files!!.first().name
        
        val pattern = Regex("crash-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.log")
        assertTrue(pattern.matches(fileName))
    }

    @Test
    fun `CrashFileSender send should include all required crash fields`() {
        val sender = CrashFileSender()
        val crashData = createTestCrashReportData()
        
        sender.send(context, crashData)
        
        val logDir = File(context.filesDir, "logs")
        val files = logDir.listFiles()
        val content = files!!.first().readText()
        
        assertTrue(content.contains("APP_VERSION_NAME"))
        assertTrue(content.contains("APP_VERSION_CODE"))
        assertTrue(content.contains("ANDROID_VERSION"))
        assertTrue(content.contains("BUILD"))
        assertTrue(content.contains("PHONE_MODEL"))
        assertTrue(content.contains("BRAND"))
        assertTrue(content.contains("STACK_TRACE"))
    }

    @Test
    fun `CrashFileSender send should handle missing optional fields`() {
        val sender = CrashFileSender()
        val crashData = CrashReportData()
        crashData.put(ReportField.STACK_TRACE, "Test stack trace")
        
        sender.send(context, crashData)
        
        val logDir = File(context.filesDir, "logs")
        val files = logDir.listFiles()
        val content = files!!.first().readText()
        
        assertTrue(content.contains("Version: null (null)"))
        assertTrue(content.contains("Android: null (null)"))
        assertTrue(content.contains("Device: null (null)"))
    }

    @Test
    fun `Composable test - verify crash report display renders correctly`() {
        composeTestRule.setContent {
            CrashReportDisplay(
                crashMessage = "Test crash message",
                stackTrace = "java.lang.NullPointerException\n    at TestClass.testMethod()"
            )
        }
        
        composeTestRule.onNodeWithText("Test crash message").assertIsDisplayed()
        composeTestRule.onNodeWithText("java.lang.NullPointerException\n    at TestClass.testMethod()").assertIsDisplayed()
    }

    @Test
    fun `Composable test - verify crash report display with default parameters`() {
        composeTestRule.setContent {
            CrashReportDisplay()
        }
        
        composeTestRule.onNodeWithText("No crash report available").assertIsDisplayed()
        composeTestRule.onNodeWithText("No stack trace provided").assertIsDisplayed()
    }

    @Test
    fun `Composable test - verify crash report display click event`() {
        var clicked = false
        composeTestRule.setContent {
            CrashReportDisplay(
                crashMessage = "Test crash",
                onDismiss = { clicked = true }
            )
        }
        
        composeTestRule.onNodeWithText("Dismiss").performClick()
        assertTrue(clicked)
    }

    @Test
    fun `Composable test - verify crash report display with all optional parameters`() {
        composeTestRule.setContent {
            CrashReportDisplay(
                crashMessage = "Custom crash",
                stackTrace = "Custom stack trace",
                timestamp = "2024-01-01 12:00:00",
                appVersion = "2.0",
                androidVersion = "14",
                deviceModel = "Pixel 8",
                onDismiss = {}
            )
        }
        
        composeTestRule.onNodeWithText("Custom crash").assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom stack trace").assertIsDisplayed()
        composeTestRule.onNodeWithText("2024-01-01 12:00:00").assertIsDisplayed()
        composeTestRule.onNodeWithText("2.0").assertIsDisplayed()
        composeTestRule.onNodeWithText("14").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pixel 8").assertIsDisplayed()
    }

    private fun createTestCrashReportData(includeLogcat: Boolean = true): CrashReportData {
        val data = CrashReportData()
        data.put(ReportField.APP_VERSION_NAME, "1.0")
        data.put(ReportField.APP_VERSION_CODE, "100")
        data.put(ReportField.ANDROID_VERSION, "13")
        data.put(ReportField.BUILD, "33")
        data.put(ReportField.PHONE_MODEL, "Pixel 7")
        data.put(ReportField.BRAND, "Google")
        data.put(ReportField.STACK_TRACE, "java.lang.NullPointerException\n    at TestClass.testMethod()")
        if (includeLogcat) {
            data.put(ReportField.LOGCAT, "Test logcat line")
        }
        return data
    }
}

// Test helper composable functions (assuming these exist in the source)
@androidx.compose.runtime.Composable
fun CrashReportDisplay(
    crashMessage: String = "No crash report available",
    stackTrace: String = "No stack trace provided",
    timestamp: String? = null,
    appVersion: String? = null,
    androidVersion: String? = null,
    deviceModel: String? = null,
    onDismiss: () -> Unit = {}
) {
    androidx.compose.material3.Text(crashMessage)
    androidx.compose.material3.Text(stackTrace)
    if (timestamp != null) {
        androidx.compose.material3.Text(timestamp)
    }
    if (appVersion != null) {
        androidx.compose.material3.Text(appVersion)
    }
    if (androidVersion != null) {
        androidx.compose.material3.Text(androidVersion)
    }
    if (deviceModel != null) {
        androidx.compose.material3.Text(deviceModel)
    }
    androidx.compose.material3.Button(onClick = onDismiss) {
        androidx.compose.material3.Text("Dismiss")
    }
}