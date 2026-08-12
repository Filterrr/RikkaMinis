package com.openminis.app.sandbox.offload

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.RepeatMode
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.*
import org.mockito.kotlin.*
import java.util.Calendar

class AlarmOffloadHandlerTest {

    private lateinit var context: Context
    private lateinit var handler: AlarmOffloadHandler

    @BeforeEach
    fun setUp() {
        context = mock(Context::class.java)
        handler = spy(AlarmOffloadHandler(context))
    }

    @Test
    fun `handle with help flag returns success with help text`() {
        val request = NativeOffloadRequest(listOf("android-alarm", "-h"))
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("Usage:"))
    }

    @Test
    fun `handle with no positional args returns exit code 2`() {
        val request = NativeOffloadRequest(listOf("android-alarm"))
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
    }

    @Test
    fun `handle with set subcommand calls handleSet`() {
        val request = NativeOffloadRequest(listOf("android-alarm", "set", "--time", "07:30"))
        doReturn(NativeOffloadResult(0, "success")).`when`(handler).handleSet(any())
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        verify(handler).handleSet(any())
    }

    @Test
    fun `handle with timer subcommand calls handleTimer`() {
        val request = NativeOffloadRequest(listOf("android-alarm", "timer", "--duration", "300"))
        doReturn(NativeOffloadResult(0, "success")).`when`(handler).handleTimer(any())
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        verify(handler).handleTimer(any())
    }

    @Test
    fun `handle with open subcommand calls handleOpen`() {
        val request = NativeOffloadRequest(listOf("android-alarm", "open"))
        doReturn(NativeOffloadResult(0, "success")).`when`(handler).handleOpen(any())
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        verify(handler).handleOpen(any())
    }

    @Test
    fun `handle with unknown subcommand returns error`() {
        val request = NativeOffloadRequest(listOf("android-alarm", "unknown"))
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("unknown subcommand"))
    }

    @Test
    fun `handle with SecurityException returns exit code 77`() {
        val request = NativeOffloadRequest(listOf("android-alarm", "set"))
        doThrow(SecurityException("denied")).`when`(handler).handleSet(any())
        val result = handler.handle(request)
        assertEquals(77, result.exitCode)
        assertTrue(result.output.contains("exact_alarm_denied"))
    }

    @Test
    fun `handle with generic exception returns exit code 1`() {
        val request = NativeOffloadRequest(listOf("android-alarm", "set"))
        doThrow(RuntimeException("failed")).`when`(handler).handleSet(any())
        val result = handler.handle(request)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("alarm_failed"))
    }

    @Test
    fun `handleSet with missing time returns error`() {
        val args = OffloadArgs(listOf("set"))
        val result = handler.handleSet(args)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("--time"))
    }

    @Test
    fun `handleSet with invalid time returns error`() {
        val args = OffloadArgs(listOf("set", "invalid"))
        val result = handler.handleSet(args)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("invalid time"))
    }

    @Test
    fun `handleSet with invalid repeat returns error`() {
        val args = OffloadArgs(listOf("set", "--time", "07:30", "--repeat", "INVALID"))
        val result = handler.handleSet(args)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("invalid --repeat"))
    }

    @Test
    fun `handleSet successfully schedules alarm`() {
        val args = OffloadArgs(listOf("set", "--time", "07:30", "--label", "Test", "--repeat", "DAILY"))
        doReturn(null).`when`(handler).scheduleViaSystemClock(any(), any(), any(), any())
        val result = handler.handleSet(args)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("system_alarm"))
        assertTrue(result.output.contains("DAILY"))
    }

    @Test
    fun `handleSet when system clock unavailable returns error`() {
        val args = OffloadArgs(listOf("set", "--time", "07:30"))
        doReturn("Clock unavailable").`when`(handler).scheduleViaSystemClock(any(), any(), any(), any())
        val result = handler.handleSet(args)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("system_clock_unavailable"))
    }

    @Test
    fun `scheduleViaSystemClock returns null on success`() {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
        whenever(context.startActivity(any(Intent::class.java))).thenAnswer { }
        val result = handler.scheduleViaSystemClock("Test", 7, 30, RepeatMode.ONCE)
        assertNull(result)
        verify(context).startActivity(any(Intent::class.java))
    }

    @Test
    fun `scheduleViaSystemClock returns error on ActivityNotFoundException`() {
        whenever(context.startActivity(any(Intent::class.java))).thenThrow(ActivityNotFoundException())
        val result = handler.scheduleViaSystemClock("Test", 7, 30, RepeatMode.ONCE)
        assertNotNull(result)
        assertTrue(result!!.contains("No system Clock app"))
    }

    @Test
    fun `scheduleViaSystemClock returns error on SecurityException`() {
        whenever(context.startActivity(any(Intent::class.java))).thenThrow(SecurityException("denied"))
        val result = handler.scheduleViaSystemClock("Test", 7, 30, RepeatMode.ONCE)
        assertNotNull(result)
        assertTrue(result!!.contains("SET_ALARM permission"))
    }

    @Test
    fun `scheduleViaSystemClock returns error on generic exception`() {
        whenever(context.startActivity(any(Intent::class.java))).thenThrow(RuntimeException("boom"))
        val result = handler.scheduleViaSystemClock("Test", 7, 30, RepeatMode.ONCE)
        assertNotNull(result)
        assertTrue(result!!.contains("System Clock dispatch failed"))
    }

    @Test
    fun `handleTimer with missing duration returns error`() {
        val args = OffloadArgs(listOf("timer"))
        val result = handler.handleTimer(args)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("--duration"))
    }

    @Test
    fun `handleTimer with invalid duration returns error`() {
        val args = OffloadArgs(listOf("timer", "--duration", "invalid"))
        val result = handler.handleTimer(args)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("invalid duration"))
    }

    @Test
    fun `handleTimer with zero duration returns error`() {
        val args = OffloadArgs(listOf("timer", "--duration", "0"))
        val result = handler.handleTimer(args)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("positive"))
    }

    @Test
    fun `handleTimer successfully starts timer`() {
        val args = OffloadArgs(listOf("timer", "--duration", "300", "--label", "Tea"))
        doReturn(null).`when`(handler).startSystemTimer(any(), any())
        val result = handler.handleTimer(args)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("system_alarm"))
        assertTrue(result.output.contains("300"))
    }

    @Test
    fun `handleTimer when system clock unavailable returns error`() {
        val args = OffloadArgs(listOf("timer", "--duration", "300"))
        doReturn("Timer unavailable").`when`(handler).startSystemTimer(any(), any())
        val result = handler.handleTimer(args)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("system_clock_unavailable"))
    }

    @Test
    fun `startSystemTimer returns null on success`() {
        whenever(context.startActivity(any(Intent::class.java))).thenAnswer { }
        val result = handler.startSystemTimer("Test", 300)
        assertNull(result)
        verify(context).startActivity(any(Intent::class.java))
    }

    @Test
    fun `startSystemTimer returns error on ActivityNotFoundException`() {
        whenever(context.startActivity(any(Intent::class.java))).thenThrow(ActivityNotFoundException())
        val result = handler.startSystemTimer("Test", 300)
        assertNotNull(result)
        assertTrue(result!!.contains("No system Clock app"))
    }

    @Test
    fun `startSystemTimer returns error on SecurityException`() {
        whenever(context.startActivity(any(Intent::class.java))).thenThrow(SecurityException("denied"))
        val result = handler.startSystemTimer("Test", 300)
        assertNotNull(result)
        assertTrue(result!!.contains("SET_ALARM permission"))
    }

    @Test
    fun `startSystemTimer returns error on generic exception`() {
        whenever(context.startActivity(any(Intent::class.java))).thenThrow(RuntimeException("boom"))
        val result = handler.startSystemTimer("Test", 300)
        assertNotNull(result)
        assertTrue(result!!.contains("System Clock dispatch failed"))
    }

    @Test
    fun `handleOpen successfully launches clock`() {
        val args = OffloadArgs(listOf("open"))
        whenever(context.startActivity(any(Intent::class.java))).thenAnswer { }
        val result = handler.handleOpen(args)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("opened"))
    }

    @Test
    fun `handleOpen returns error on ActivityNotFoundException`() {
        val args = OffloadArgs(listOf("open"))
        whenever(context.startActivity(any(Intent::class.java))).thenThrow(ActivityNotFoundException())
        val result = handler.handleOpen(args)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("no_clock_app"))
    }

    @Test
    fun `handleOpen returns error on generic exception`() {
        val args = OffloadArgs(listOf("open"))
        whenever(context.startActivity(any(Intent::class.java))).thenThrow(RuntimeException("boom"))
        val result = handler.handleOpen(args)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("open_failed"))
    }

    @Test
    fun `parseHHMM with valid time returns pair`() {
        val result = handler.parseHHMM("07:30")
        assertEquals(7 to 30, result)
    }

    @Test
    fun `parseHHMM with invalid format returns null`() {
        assertNull(handler.parseHHMM("invalid"))
        assertNull(handler.parseHHMM("7:30"))
        assertNull(handler.parseHHMM("07:30:00"))
        assertNull(handler.parseHHMM("25:30"))
        assertNull(handler.parseHHMM("07:60"))
    }

    @Test
    fun `parseTimeArg with HHMM format returns pair`() {
        val result = handler.parseTimeArg("07:30")
        assertEquals(7 to 30, result)
    }

    @Test
    fun `parseTimeArg with ISO format returns pair`() {
        val result = handler.parseTimeArg("2026-02-25T14:00")
        assertEquals(14 to 0, result)
    }

    @Test
    fun `parseTimeArg with invalid format returns null`() {
        assertNull(handler.parseTimeArg("invalid"))
    }

    @Test
    fun `parseDuration with seconds returns seconds`() {
        assertEquals(300, handler.parseDuration("300"))
    }

    @Test
    fun `parseDuration with shorthand returns seconds`() {
        assertEquals(30, handler.parseDuration("30s"))
        assertEquals(300, handler.parseDuration("5m"))
        assertEquals(3600, handler.parseDuration("1h"))
        assertEquals(172800, handler.parseDuration("2d"))
    }

    @Test
    fun `parseDuration with invalid input returns null`() {
        assertNull(handler.parseDuration(""))
        assertNull(handler.parseDuration("invalid"))
        assertNull(handler.parseDuration("5x"))
    }

    @Test
    fun `formatIso returns formatted date`() {
        val date = Calendar.getInstance().apply {
            set(2026, 1, 25, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
        val result = handler.formatIso(date)
        assertTrue(result.startsWith("2026-02-25T14:30:00"))
    }

    @Test
    fun `buildSetEnvelope creates valid JSON`() {
        val result = handler.buildSetEnvelope("Test", 7, 30, RepeatMode.DAILY)
        assertEquals("Test", result.getString("label"))
        assertEquals(7, result.getInt("hour"))
        assertEquals(30, result.getInt("minute"))
        assertEquals("DAILY", result.getString("repeat"))
        assertTrue(result.has("time"))
        assertTrue(result.has("view_url"))
        assertTrue(result.has("hint"))
    }

    @Test
    fun `buildTimerEnvelope creates valid JSON`() {
        val result = handler.buildTimerEnvelope("Test", 300)
        assertEquals("Test", result.getString("label"))
        assertEquals(300, result.getInt("duration_seconds"))
        assertTrue(result.has("fires_at"))
        assertTrue(result.has("view_url"))
        assertTrue(result.has("hint"))
    }

    @Test
    fun `emitEnvelope returns success result`() {
        val data = JSONObject().put("test", true)
        val args = OffloadArgs(listOf("set"))
        val result = handler.emitEnvelope("set", data, args)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("android-alarm"))
        assertTrue(result.output.contains("set"))
    }

    @Test
    fun `wrap with error text returns exit code 1`() {
        val args = OffloadArgs(listOf("set"))
        val result = handler.wrap("Error: something", args)
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `wrap with non-error text returns exit code 0`() {
        val args = OffloadArgs(listOf("set"))
        val result = handler.wrap("Success", args)
        assertEquals(0, result.exitCode)
    }
}