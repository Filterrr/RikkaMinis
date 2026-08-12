package com.openminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9: unit tests for [AgentTraceRecorder] — event emission, truncation,
 * JSONL parsing tolerance, and the query/render helpers. Pure JVM; the
 * sink is captured in memory and the clock is injected for determinism.
 */
class AgentTraceRecorderTest {

    private class Sink {
        val lines = mutableListOf<String>()
        val raw: String get() = lines.joinToString("\n")
    }

    private fun recorder(sink: Sink, clock: () -> Long = { 0L }) =
        AgentTraceRecorder(appendLine = { sink.lines.add(it) }, clock = clock)

    // ── event emission ─────────────────────────────────────────────────────

    @Test
    fun `traceStart writes type session provider and prompt`() {
        val sink = Sink()
        val r = recorder(sink)
        r.traceStart("sess-1", "OpenAIProvider", "hello agent")
        val e = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_TRACE_START, e.getString("type"))
        assertEquals("sess-1", e.getString("session"))
        assertEquals("OpenAIProvider", e.getString("provider"))
        assertEquals("hello agent", e.getString("prompt"))
        assertEquals(0L, e.getLong("ts"))
    }

    @Test
    fun `traceStart truncates long prompt`() {
        val sink = Sink()
        val r = recorder(sink)
        val longPrompt = "x".repeat(AgentTraceRecorder.PROMPT_MAX_LENGTH + 100)
        r.traceStart("s", "P", longPrompt)
        val e = JSONObject(sink.lines[0])
        val stored = e.getString("prompt")
        assertTrue(stored.length <= AgentTraceRecorder.PROMPT_MAX_LENGTH + 1) // + ellipsis
        assertTrue(stored.endsWith("…"))
    }

    @Test
    fun `turnStart and turnEnd carry turn tokens and finish reason`() {
        val sink = Sink()
        val r = recorder(sink)
        r.turnStart(3)
        r.turnEnd(3, tokensIn = 120, tokensOut = 45, finishReason = "stop", durationMs = 500L)
        val start = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_TURN_START, start.getString("type"))
        assertEquals(3, start.getInt("turn"))
        val end = JSONObject(sink.lines[1])
        assertEquals(AgentTraceRecorder.TYPE_TURN_END, end.getString("type"))
        assertEquals(3, end.getInt("turn"))
        assertEquals(120, end.getInt("tokens_in"))
        assertEquals(45, end.getInt("tokens_out"))
        assertEquals("stop", end.getString("finish_reason"))
        assertEquals(500L, end.getLong("duration_ms"))
    }

    @Test
    fun `turnEnd omits absent token fields`() {
        val sink = Sink()
        val r = recorder(sink)
        r.turnEnd(0, tokensIn = null, tokensOut = null, finishReason = null, durationMs = 10L)
        val e = JSONObject(sink.lines[0])
        assertFalse(e.has("tokens_in"))
        assertFalse(e.has("tokens_out"))
        assertFalse(e.has("finish_reason"))
        assertEquals(10L, e.getLong("duration_ms"))
    }

    @Test
    fun `toolCall and toolResult round trip with ids and duration`() {
        val sink = Sink()
        val r = recorder(sink)
        r.toolCall(1, "call_abc", "file_read", """{"path":"/etc/hosts"}""")
        r.toolResult(1, "call_abc", "file_read", success = true, output = "127.0.0.1 localhost", durationMs = 42L)
        val call = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_TOOL_CALL, call.getString("type"))
        assertEquals("call_abc", call.getString("tool_id"))
        assertEquals("file_read", call.getString("tool"))
        assertEquals(1, call.getInt("turn"))
        assertTrue(call.getString("args").contains("/etc/hosts"))
        val result = JSONObject(sink.lines[1])
        assertEquals(AgentTraceRecorder.TYPE_TOOL_RESULT, result.getString("type"))
        assertTrue(result.getBoolean("success"))
        assertEquals(42L, result.getLong("duration_ms"))
        assertTrue(result.getString("output").contains("localhost"))
    }

    @Test
    fun `toolResult with failure records success false`() {
        val sink = Sink()
        val r = recorder(sink)
        r.toolResult(0, "id", "shell_execute", success = false, output = "command not found", durationMs = 7L)
        val e = JSONObject(sink.lines[0])
        assertFalse(e.getBoolean("success"))
    }

    @Test
    fun `args and output are truncated to caps`() {
        val sink = Sink()
        val r = recorder(sink)
        val longArgs = "a".repeat(AgentTraceRecorder.ARGS_MAX_LENGTH + 200)
        val longOutput = "b".repeat(AgentTraceRecorder.OUTPUT_MAX_LENGTH + 200)
        r.toolCall(0, "id", "shell_execute", longArgs)
        r.toolResult(0, "id", "shell_execute", success = true, output = longOutput, durationMs = 1L)
        val call = JSONObject(sink.lines[0])
        assertTrue(call.getString("args").length <= AgentTraceRecorder.ARGS_MAX_LENGTH + 1)
        val result = JSONObject(sink.lines[1])
        assertTrue(result.getString("output").length <= AgentTraceRecorder.OUTPUT_MAX_LENGTH + 1)
    }

    @Test
    fun `error and traceEnd write expected fields`() {
        val sink = Sink()
        val r = recorder(sink)
        r.error(turn = 2, phase = "exception", message = "boom")
        r.traceEnd(normalExit = false, turnCount = 3, durationMs = 9000L, error = "boom")
        val err = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_ERROR, err.getString("type"))
        assertEquals(2, err.getInt("turn"))
        assertEquals("exception", err.getString("phase"))
        val end = JSONObject(sink.lines[1])
        assertEquals(AgentTraceRecorder.TYPE_TRACE_END, end.getString("type"))
        assertFalse(end.getBoolean("normal_exit"))
        assertEquals(3, end.getInt("turns"))
        assertEquals(9000L, end.getLong("duration_ms"))
        assertEquals("boom", end.getString("error"))
    }

    @Test
    fun `clock is used for every event timestamp`() {
        var now = 111L
        val sink = Sink()
        val r = recorder(sink, clock = { now })
        r.turnStart(0)
        now = 222L
        r.turnStart(1)
        assertEquals(111L, JSONObject(sink.lines[0]).getLong("ts"))
        assertEquals(222L, JSONObject(sink.lines[1]).getLong("ts"))
    }

    // ── parse / query helpers ──────────────────────────────────────────────

    @Test
    fun `parse returns events in order and skips malformed lines`() {
        val sink = Sink()
        val r = recorder(sink)
        r.turnStart(0)
        r.turnStart(1)
        val malformed = sink.lines.joinToString("\n") + "\nnot-json{{{"
        val events = AgentTraceRecorder.parse(malformed)
        assertEquals(2, events.size)
        assertEquals(0, events[0].getInt("turn"))
        assertEquals(1, events[1].getInt("turn"))
    }

    @Test
    fun `parse handles blank input`() {
        assertTrue(AgentTraceRecorder.parse("").isEmpty())
        assertTrue(AgentTraceRecorder.parse("   \n\n  ").isEmpty())
    }

    @Test
    fun `filterByTool matches only the named tool events`() {
        val sink = Sink()
        val r = recorder(sink)
        r.toolCall(0, "a", "file_read", "{}")
        r.toolResult(0, "a", "file_read", true, "data", 1L)
        r.toolCall(0, "b", "shell_execute", "{}")
        r.toolResult(0, "b", "shell_execute", true, "out", 1L)
        val events = AgentTraceRecorder.parse(sink.raw)
        val fileRead = AgentTraceRecorder.filterByTool(events, "file_read")
        assertEquals(2, fileRead.size)
        assertTrue(fileRead.all { it.getString("tool") == "file_read" })
        assertEquals(0, AgentTraceRecorder.filterByTool(events, "nope").size)
    }

    @Test
    fun `filterErrors finds failed tools and explicit errors only`() {
        val sink = Sink()
        val r = recorder(sink)
        r.toolCall(0, "a", "file_read", "{}")
        r.toolResult(0, "a", "file_read", true, "data", 1L)
        r.toolResult(1, "b", "shell_execute", false, "boom", 1L)
        r.error(1, "exception", "kaboom")
        r.traceEnd(true, 2, 5L, null)
        val events = AgentTraceRecorder.parse(sink.raw)
        val errors = AgentTraceRecorder.filterErrors(events)
        assertEquals(2, errors.size)
        assertEquals(AgentTraceRecorder.TYPE_TOOL_RESULT, errors[0].getString("type"))
        assertFalse(errors[0].getBoolean("success"))
        assertEquals(AgentTraceRecorder.TYPE_ERROR, errors[1].getString("type"))
    }

    @Test
    fun `renderHumanReadable contains the key timeline facts`() {
        val sink = Sink()
        val r = recorder(sink)
        r.traceStart("sess-9", "OpenAIProvider", "summarize this")
        r.turnStart(0)
        r.toolCall(0, "id1", "file_read", """{"path":"/tmp/a.txt"}""")
        r.toolResult(0, "id1", "file_read", true, "hello world", 12L)
        r.turnEnd(0, 100, 25, "stop", 300L)
        r.traceEnd(true, 1, 300L, null)
        val text = AgentTraceRecorder.renderHumanReadable(AgentTraceRecorder.parse(sink.raw))
        assertTrue(text.contains("sess-9"))
        assertTrue(text.contains("OpenAIProvider"))
        assertTrue(text.contains("summarize this"))
        assertTrue(text.contains("turn 0"))
        assertTrue(text.contains("file_read"))
        assertTrue(text.contains("OK"))
        assertTrue(text.contains("12ms"))
        assertTrue(text.contains("in=100"))
        assertTrue(text.contains("out=25"))
        assertTrue(text.contains("exit: normal"))
    }
}