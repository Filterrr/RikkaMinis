package com.openminis.app.ui.trace

import com.openminis.app.tools.AgentTraceRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-trace-viewer] Contract tests for the trace read model: schema
 * projection, legacy (1.0) tolerance, partial-file behaviour, and the evidence
 * audit surfacing.
 *
 * Where a fixture comes from the recorder, these tests CALL the recorder rather
 * than hand-rolling JSON — the reader is exercised against the exact line shape
 * the writer produces, so the two cannot drift apart silently. Hand-written
 * lines are reserved for legacy (1.0) shapes the current recorder can no longer
 * emit, which is precisely the case the reader must tolerate.
 */
class TraceRunTest {

    /** Build a trace by driving the real recorder. */
    private fun roundTrip(block: (AgentTraceRecorder) -> Unit): String {
        val lines = mutableListOf<String>()
        val r = AgentTraceRecorder(appendLine = { lines.add(it) }, clock = { 1_000L })
        block(r)
        return lines.joinToString("\n")
    }

    private fun run(raw: String) = TraceRun.fromRaw(
        traceId = "agent-20260914-120000",
        filePath = "/data/.../agent-20260914-120000.jsonl",
        lastModifiedMs = 1_700_000_000_000L,
        sizeBytes = raw.length.toLong(),
        raw = raw,
    )

    @Test
    fun `projects identity and terminal state from a completed run`() {
        val raw = roundTrip { r ->
            r.beginRun("run-1", "sess-1", "OpenAIProvider", "hello", providerCount = 1, toolCount = 9)
            r.turnStart(1)
            r.turnEnd(1, tokensIn = 10, tokensOut = 20, finishReason = "stop", durationMs = 100)
            r.turnStart(2)
            r.turnEnd(2, tokensIn = 1, tokensOut = 2, finishReason = "stop", durationMs = 50)
            r.endRun(
                terminalState = "Succeeded",
                terminalReason = "completed_normally",
                durationMs = 200,
            )
        }
        val run = run(raw)

        assertEquals("run-1", run.runId)
        assertEquals("sess-1", run.sessionId)
        assertEquals("OpenAIProvider", run.provider)
        assertEquals("2.0", run.schemaVersion)
        assertEquals("Succeeded", run.terminalState)
        assertEquals("completed_normally", run.terminalReason)
        assertEquals(2, run.turns)
        assertEquals(200L, run.durationMs)
        assertTrue(run.hasTerminal)
        assertEquals(2, run.countOf(AgentTraceRecorder.TYPE_TURN_START))
    }

    @Test
    fun `a run with no terminal event reads as not-closed rather than succeeded`() {
        // A half-flushed file (process killed mid-run) must never claim a
        // verdict it did not record.
        val raw = roundTrip { r ->
            r.beginRun("run-2", "sess-2", "P", "prompt")
            r.turnStart(1)
        }
        val run = run(raw)
        assertFalse(run.hasTerminal)
        assertEquals("", run.terminalState)
        // Turns still reflect the run's own recorded work — the count comes
        // from turn_start events, so an interrupted run does not read as
        // "0 turns" just because its footer never landed.
        assertEquals(1, run.turns)
        assertTrue("an unclosed run is an evidence gap", run.evidenceGaps.any { it.contains("no trace_end") })
    }

    @Test
    fun `turns stay unknown when the run recorded no turn events at all`() {
        val raw = roundTrip { r -> r.beginRun("run-2b", "sess-2b", "P", "prompt") }
        val run = run(raw)
        assertEquals(-1, run.turns)
        assertFalse(run.hasTerminal)
    }

    @Test
    fun `legacy 1_0 traces default to schema version 1 0`() {
        // Hand-written 1.0 line shape: no trace_schema_version / run_id, and the
        // old `session` / `prompt` field names.
        val raw = """
            {"type":"trace_start","ts":5,"session":"legacy","provider":"P","prompt":"old"}
            {"type":"trace_end","ts":9,"normal_exit":true,"turns":1,"duration_ms":4}
        """.trimIndent()
        val run = run(raw)
        assertEquals("1.0", run.schemaVersion)
        assertEquals("legacy", run.sessionId)
        assertEquals("old", run.promptPreview)
        assertEquals(4L, run.durationMs)
        // parseWithRunContext back-fills run/session context from trace_start.
        assertEquals("legacy", run.sessionId)
    }

    @Test
    fun `malformed lines are skipped without losing the surrounding events`() {
        val raw = """
            {"type":"trace_start","ts":1,"run_id":"r","session_id":"s","prompt_preview":"p"}
            this is not json
            {"truncated
            {"type":"trace_end","ts":2,"terminal_state":"Failed","terminal_reason":"boom"}
        """.trimIndent()
        val run = run(raw)
        assertEquals(2, run.events.size)
        assertEquals("Failed", run.terminalState)
        assertEquals("boom", run.terminalReason)
    }

    @Test
    fun `evidence gaps are surfaced for a run that never released its lease`() {
        val raw = roundTrip { r ->
            r.beginRun("run-3", "sess-3", "P", "prompt")
            r.resourceAcquire(AgentTraceRecorder.RESOURCE_SESSION_SLOT, "slot-1", "lease-1")
            // Killed before release and before any terminal event.
        }
        val run = run(raw)
        assertTrue("a dangling lease must be reported", run.evidenceGaps.any { it.contains("lease-1") })
        assertTrue("an unclosed run must be reported", run.evidenceGaps.any { it.contains("no trace_end") })
        assertFalse(run.leasesClean)
    }

    @Test
    fun `a fully closed run reports no evidence gaps`() {
        val raw = roundTrip { r ->
            r.beginRun("run-4", "sess-4", "P", "prompt")
            r.resourceAcquire(AgentTraceRecorder.RESOURCE_SESSION_SLOT, "slot-1", "lease-1")
            r.resourceRelease(
                AgentTraceRecorder.RESOURCE_SESSION_SLOT, "slot-1", "lease-1",
                AgentTraceRecorder.RELEASED_NORMAL,
            )
            r.endRun(terminalState = "Succeeded", durationMs = 10)
        }
        val run = run(raw)
        assertEquals(emptyList<String>(), run.evidenceGaps)
        assertTrue(run.leasesClean)
    }

    @Test
    fun `budget refusals and terminal reason survive the projection`() {
        val raw = roundTrip { r ->
            r.beginRun("run-5", "sess-5", "P", "prompt")
            r.budgetRefuse(
                AgentTraceRecorder.DIMENSION_TOOL_CALLS,
                requested = 1,
                remaining = 0,
                reason = AgentTraceRecorder.REFUSE_BUDGET_EXHAUSTED,
            )
            r.endRun(
                terminalState = "Interrupted",
                terminalReason = "deadline_reached",
                durationMs = 50,
            )
        }
        val run = run(raw)
        assertEquals(1, run.budgetEvents.size)
        assertEquals("Interrupted", run.terminalState)
        assertEquals("deadline_reached", run.terminalReason)
    }

    @Test
    fun `errors collect explicit error events and failed tool results`() {
        val raw = roundTrip { r ->
            r.beginRun("run-6", "sess-6", "P", "prompt")
            r.error(turn = 1, phase = "tool", message = "boom")
            r.toolResult(turn = 1, toolId = "t1", name = "shell_execute", success = false, output = "fail", durationMs = 5)
            r.toolResult(turn = 1, toolId = "t2", name = "file_read", success = true, output = "ok", durationMs = 2)
            r.endRun(terminalState = "Failed", durationMs = 10)
        }
        val run = run(raw)
        assertEquals("one error event + one failed tool result", 2, run.errors.size)
    }

    // ── timeline projection ────────────────────────────────────────────────

    @Test
    fun `timeline offsets are relative to run start and ordered`() {
        val raw = """
            {"type":"trace_start","ts":1000,"run_id":"r","session_id":"s"}
            {"type":"turn_start","ts":1100,"turn":1}
            {"type":"tool_call","ts":1250,"turn":1,"tool_id":"t1","tool":"shell_execute","args":"{}"}
            {"type":"trace_end","ts":2000,"terminal_state":"Succeeded"}
        """.trimIndent()
        val lines = TraceTimeline.lines(run(raw))

        assertEquals(listOf(0L, 100L, 250L, 1000L), lines.map { it.offsetMs })
        assertEquals(
            listOf(
                TraceEventLine.Kind.START,
                TraceEventLine.Kind.TURN,
                TraceEventLine.Kind.TOOL,
                TraceEventLine.Kind.END,
            ),
            lines.map { it.kind },
        )
        assertTrue(lines[2].summary.contains("shell_execute"))
    }

    @Test
    fun `timeline renders a failed tool result as FAIL`() {
        val raw = roundTrip { r ->
            r.beginRun("run-7", "sess-7", "P", "prompt")
            r.toolResult(turn = 1, toolId = "t1", name = "shell_execute", success = false, output = "x", durationMs = 7)
            r.endRun(terminalState = "Succeeded", durationMs = 9)
        }
        val lines = TraceTimeline.lines(run(raw))
        val toolLine = lines.first { it.kind == TraceEventLine.Kind.TOOL }
        assertTrue(toolLine.summary.contains("FAIL"))
        assertTrue(toolLine.summary.contains("shell_execute"))
    }

    @Test
    fun `timeline never fabricates a phase for an error that lacks one`() {
        // An error event with no `phase` must not render an empty "( )".
        val raw = """
            {"type":"trace_start","ts":1,"run_id":"r","session_id":"s"}
            {"type":"error","ts":2,"message":"boom"}
            {"type":"trace_end","ts":3,"terminal_state":"Failed"}
        """.trimIndent()
        val lines = TraceTimeline.lines(run(raw))
        val error = lines.first { it.kind == TraceEventLine.Kind.ERROR }
        assertTrue(error.summary.contains("boom"))
        assertFalse("no empty phase parentheses", error.summary.contains("()"))
    }

    @Test
    fun `timeline offsets are negative when the run start timestamp is missing`() {
        // Without trace_start there is no clock to offset against — the UI
        // shows a placeholder rather than inventing an origin.
        val raw = """{"type":"turn_start","ts":500,"turn":1}"""
        val lines = TraceTimeline.lines(run(raw))
        assertEquals(-1L, lines.single().offsetMs)
    }
}
