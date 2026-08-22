package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TF-A] Tests for the optional run/seq correlation fields on the :modelservice
 * JSONL streaming protocol. Guarantees decode stays backwards-compatible: lines
 * WITHOUT run/seq decode exactly as before, and lines WITH them expose the
 * correlation via [ChatStreamJsonl.decodeLine] without changing [ChatStreamJsonl.decode]
 * semantics.
 */
class ChatStreamJsonlCorrelationTest {

    @Test
    fun `decode of plain lines keeps runId null and seq minus one`() {
        val line = ChatStreamJsonl.encode(com.openminis.app.data.model.LLMStreamChunk.Text("hello"))
        val decoded = ChatStreamJsonl.decodeLine(line)
        assertEquals(com.openminis.app.data.model.LLMStreamChunk.Text("hello"), decoded.chunk)
        assertNull(decoded.runId)
        assertEquals(-1, decoded.seq)
    }

    @Test
    fun `decode of line with run and seq exposes correlation`() {
        val line = """{"t":"text","v":"hi","run":"run-123","seq":7}"""
        val decoded = ChatStreamJsonl.decodeLine(line)
        assertEquals(com.openminis.app.data.model.LLMStreamChunk.Text("hi"), decoded.chunk)
        assertEquals("run-123", decoded.runId)
        assertEquals(7, decoded.seq)
    }

    @Test
    fun `encodeWithCorrelation injects fields only when provided`() {
        val plain = ChatStreamJsonl.encodeWithCorrelation(com.openminis.app.data.model.LLMStreamChunk.Started)
        assertTrue("no run/seq when not provided", !plain.contains("\"run\"") && !plain.contains("\"seq\""))

        val withRun = ChatStreamJsonl.encodeWithCorrelation(
            com.openminis.app.data.model.LLMStreamChunk.Started, runId = "run-9", seq = 1,
        )
        assertTrue("run injected", withRun.contains("\"run\":\"run-9\""))
        assertTrue("seq injected", withRun.contains("\"seq\":1"))
    }

    @Test
    fun `decode with malformed line returns null chunk and defaults`() {
        val decoded = ChatStreamJsonl.decodeLine("not-json{")
        assertNull(decoded.chunk)
        assertNull(decoded.runId)
        assertEquals(-1, decoded.seq)
    }

    @Test
    fun `decodeLine parses correlated line and round-trips through encodeWithCorrelation`() {
        val chunk = com.openminis.app.data.model.LLMStreamChunk.Text("correlated text")
        val line = ChatStreamJsonl.encodeWithCorrelation(chunk, runId = "run-42", seq = 3)
        val decoded = ChatStreamJsonl.decodeLine(line)
        assertEquals(chunk, decoded.chunk)
        assertEquals("run-42", decoded.runId)
        assertEquals(3, decoded.seq)
    }
}