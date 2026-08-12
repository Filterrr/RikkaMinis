package com.openminis.app.sandbox.offload

import com.openminis.app.offload.MediaPlayerManager
import com.openminis.app.sandbox.NativeOffloadRequest
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PlayerOffloadHandlerTest {

    private val handler = PlayerOffloadHandler()

    @BeforeEach
    fun setUp() {
        mockkObject(MediaPlayerManager)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `handle with -h flag returns help and code 0`() {
        val request = NativeOffloadRequest(listOf("android-player", "-h"))
        val result = handler.handle(request)
        assertEquals(0, result.code)
        assertTrue(result.output.contains("android-player — control audio playback sessions"))
    }

    @Test
    fun `handle with --help flag returns help and code 0`() {
        val request = NativeOffloadRequest(listOf("android-player", "--help"))
        val result = handler.handle(request)
        assertEquals(0, result.code)
        assertTrue(result.output.contains("android-player — control audio playback sessions"))
    }

    @Test
    fun `handle play without session and path returns code 2`() {
        val request = NativeOffloadRequest(listOf("android-player", "play"))
        val result = handler.handle(request)
        assertEquals(2, result.code)
        assertEquals("android-player play: need <session> <path>\n", result.output)
    }

    @Test
    fun `handle play without path returns code 2`() {
        val request = NativeOffloadRequest(listOf("android-player", "play", "session1"))
        val result = handler.handle(request)
        assertEquals(2, result.code)
        assertEquals("android-player play: need <session> <path>\n", result.output)
    }

    @Test
    fun `handle play success returns code 0`() {
        every { MediaPlayerManager.play("session1", "/path/to/audio") } returns "Playing"
        val request = NativeOffloadRequest(listOf("android-player", "play", "session1", "/path/to/audio"))
        val result = handler.handle(request)
        assertEquals(0, result.code)
        assertTrue(result.output.contains("Playing"))
    }

    @Test
    fun `handle pause without session returns code 2`() {
        val request = NativeOffloadRequest(listOf("android-player", "pause"))
        val result = handler.handle(request)
        assertEquals(2, result.code)
        assertEquals("android-player pause: need <session>\n", result.output)
    }

    @Test
    fun `handle pause success returns code 0`() {
        every { MediaPlayerManager.pause("session1") } returns "Paused"
        val request = NativeOffloadRequest(listOf("android-player", "pause", "session1"))
        val result = handler.handle(request)
        assertEquals(0, result.code)
        assertTrue(result.output.contains("Paused"))
    }

    @Test
    fun `handle resume without session returns code 2`() {
        val request = NativeOffloadRequest(listOf("android-player", "resume"))
        val result = handler.handle(request)
        assertEquals(2, result.code)
        assertEquals("android-player resume: need <session>\n", result.output)
    }

    @Test
    fun `handle resume success returns code 0`() {
        every { MediaPlayerManager.resume("session1") } returns "Resumed"
        val request = NativeOffloadRequest(listOf("android-player", "resume", "session1"))
        val result = handler.handle(request)
        assertEquals(0, result.code)
        assertTrue(result.output.contains("Resumed"))
    }

    @Test
    fun `handle seek without session returns code 2`() {
        val request = NativeOffloadRequest(listOf("android-player", "seek"))
        val result = handler.handle(request)
        assertEquals(2, result.code)
        assertEquals("android-player seek: need <session> <position_ms>\n", result.output)
    }

    @Test
    fun `handle seek without position returns code 2`() {
        val request = NativeOffloadRequest(listOf("android-player", "seek", "session1"))
        val result = handler.handle(request)
        assertEquals(2, result.code)
        assertEquals("android-player seek: need <session> <position_ms>\n", result.output)
    }

    @Test
    fun `handle seek with invalid position returns code 2`() {
        val request = NativeOffloadRequest(listOf("android-player", "seek", "session1", "invalid"))
        val result = handler.handle(request)
        assertEquals(2, result.code)
        assertEquals("android-player seek: need <session> <position_ms>\n", result.output)
    }

    @Test
    fun `handle seek success returns code 0`() {
        every { MediaPlayerManager.seek("session1", 1000) } returns "Seeking"
        val request = NativeOffloadRequest(listOf("android-player", "seek", "session1", "1000"))
        val result = handler.handle(request)
        assertEquals(0, result.code)
        assertTrue(result.output.contains("Seeking"))
    }

    @Test
    fun `handle stop without session returns code 2`() {
        val request = NativeOffloadRequest(listOf("android-player", "stop"))
        val result = handler.handle(request)
        assertEquals(2, result.code)
        assertEquals("android-player stop: need <session>\n", result.output)
    }

    @Test
    fun `handle stop success returns code 0`() {
        every { MediaPlayerManager.stop("session1") } returns "Stopped"
        val request = NativeOffloadRequest(listOf("android-player", "stop", "session1"))
        val result = handler.handle(request)
        assertEquals(0, result.code)
        assertTrue(result.output.contains("Stopped"))
    }

    @Test
    fun `handle status without session returns code 2`() {
        val request = NativeOffloadRequest(listOf("android-player", "status"))
        val result = handler.handle(request)
        assertEquals(2, result.code)
        assertEquals("android-player status: need <session>\n", result.output)
    }

    @Test
    fun `handle status success returns code 0`() {
        every { MediaPlayerManager.status("session1") } returns "Status OK"
        val request = NativeOffloadRequest(listOf("android-player", "status", "session1"))
        val result = handler.handle(request)
        assertEquals(0, result.code)
        assertTrue(result.output.contains("Status OK"))
    }

    @Test
    fun `handle status returns error string returns code 1`() {
        every { MediaPlayerManager.status("session1") } returns "Error: something went wrong"
        val request = NativeOffloadRequest(listOf("android-player", "status", "session1"))
        val result = handler.handle(request)
        assertEquals(1, result.code)
        assertTrue(result.output.contains("Error: something went wrong"))
    }

    @Test
    fun `handle list default subcommand returns code 0`() {
        every { MediaPlayerManager.listSessions() } returns "Session1\nSession2"
        val request = NativeOffloadRequest(listOf("android-player"))
        val result = handler.handle(request)
        assertEquals(0, result.code)
        assertTrue(result.output.contains("Session1"))
    }

    @Test
    fun `handle list explicit subcommand returns code 0`() {
        every { MediaPlayerManager.listSessions() } returns "Session1"
        val request = NativeOffloadRequest(listOf("android-player", "list"))
        val result = handler.handle(request)
        assertEquals(0, result.code)
        assertTrue(result.output.contains("Session1"))
    }

    @Test
    fun `handle unknown subcommand returns code 2`() {
        val request = NativeOffloadRequest(listOf("android-player", "unknown"))
        val result = handler.handle(request)
        assertEquals(2, result.code)
        assertTrue(result.output.contains("android-player: unknown subcommand 'unknown'"))
    }

    @Test
    fun `handle exception during play returns code 1`() {
        every { MediaPlayerManager.play("session1", "/path") } throws RuntimeException("Failed to play")
        val request = NativeOffloadRequest(listOf("android-player", "play", "session1", "/path"))
        val result = handler.handle(request)
        assertEquals(1, result.code)
        assertTrue(result.output.contains("player_failed"))
        assertTrue(result.output.contains("Failed to play"))
    }
}