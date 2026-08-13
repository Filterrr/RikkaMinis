package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for pure functions extracted from [ChatScreen].
 */
class ChatScreenUtilsTest {

    // ── originalMessageId ─────────────────────────────────────────────

    @Test
    fun `originalMessageId returns plain id unchanged`() {
        assertEquals("msg_abc123", originalMessageId("msg_abc123"))
    }

    @Test
    fun `originalMessageId strips numeric dedupe suffix`() {
        assertEquals("msg_abc123", originalMessageId("msg_abc123#2"))
    }

    @Test
    fun `originalMessageId strips double digit suffix`() {
        assertEquals("msg_abc123", originalMessageId("msg_abc123#42"))
    }

    @Test
    fun `originalMessageId returns empty string for empty input`() {
        assertEquals("", originalMessageId(""))
    }

    @Test
    fun `originalMessageId handles hash with no number`() {
        assertEquals("", originalMessageId("#"))
    }

    @Test
    fun `originalMessageId handles multiple hashes`() {
        assertEquals("a#b", originalMessageId("a#b#2"))
    }

    @Test
    fun `originalMessageId handles id with hash in middle`() {
        assertEquals("a#b", originalMessageId("a#b"))
    }
}