package com.openminis.app.ui.trace

import com.openminis.app.tools.AgentTraceRecorder
import org.json.JSONObject

/**
 * [T-android-trace-viewer] Read-side model for the Agent Trace screens.
 *
 * The recorder (`AgentTraceRecorder`) has been writing schema-2.0 JSONL to
 * `workspace/.traces/agent-<ts>.jsonl` since T6/T9, and the whole stability
 * effort (failure matrix F01–F14, `docs/stability/trace-schema-v2.md`) is
 * verified against those files — but nothing in the app ever READ one. The
 * only way to answer "why did this run fail?" was to ask the agent to cat the
 * file, which is exactly the audience this data was never meant to serve.
 *
 * This type is the projection that view needs: a run condenses into
 * identity + terminal state + counters, and the event stream stays available
 * for the drill-down. Parsing is pure (no Android, no I/O), so it is
 * JVM-testable and safe to call on a background dispatcher.
 */
data class TraceRun(
    /** File name without the `.jsonl` extension — the stable per-run handle. */
    val traceId: String,
    /** Absolute host path, for sharing / opening in the file browser. */
    val filePath: String,
    /** Epoch millis of the file's last write, for display ordering. */
    val lastModifiedMs: Long,
    /** Byte size on disk. */
    val sizeBytes: Long,
    /** Run id from `trace_start` (empty for a 1.0 trace without one). */
    val runId: String,
    /** Session id from `trace_start`. */
    val sessionId: String,
    /** Provider label recorded at run start. */
    val provider: String,
    /** Schema version — "1.0" when the field is absent (legacy trace). */
    val schemaVersion: String,
    /** Prompt preview, already truncated + redacted by the recorder. */
    val promptPreview: String,
    /** Terminal state from `trace_end` ("Succeeded" / "Failed" / …), or "". */
    val terminalState: String,
    /** Terminal reason from `trace_end`, or "". */
    val terminalReason: String,
    /** Wall-clock duration reported by `trace_end`, or -1 when absent. */
    val durationMs: Long,
    /**
     * Turn count. The 1.0 footer writes `turns`; the 2.0 `endRun` does not, so
     * when the field is absent the count is derived from the run's own
     * `turn_start` events rather than reported as unknown — the events ARE the
     * ground truth, the footer was only ever a summary of them.
     */
    val turns: Int,
    /** Every event, in file order. Drives the drill-down list. */
    val events: List<JSONObject>,
) {
    /** True when the run closed with a terminal event at all. */
    val hasTerminal: Boolean get() = terminalState.isNotBlank()

    /**
     * Evidence gaps computed by the recorder's own auditor
     * ([AgentTraceRecorder.auditEvidenceGaps]) — i.e. what this trace CANNOT
     * prove. Empty means the trace is complete enough to answer "why did this
     * run end, and were its resources released?".
     */
    val evidenceGaps: List<String> get() = AgentTraceRecorder.auditEvidenceGaps(events)

    /** True when every resource lease acquired during the run was released. */
    val leasesClean: Boolean get() = AgentTraceRecorder.terminalLeaseCleanup(events)

    /** Count of events of [type]. */
    fun countOf(type: String): Int = events.count { it.optString("type") == type }

    /** Errors surfaced by the run: explicit `error` events + failed tool results. */
    val errors: List<JSONObject> get() = AgentTraceRecorder.filterErrors(events)

    /** Budget consumes/refusals — the "why did it stop early" evidence. */
    val budgetEvents: List<JSONObject> get() = AgentTraceRecorder.filterBudgetEvents(events)

    /** Resource acquire/release pairs. */
    val resourceEvents: List<JSONObject> get() = AgentTraceRecorder.filterResourceEvents(events)

    /** A human-readable timeline, reused verbatim from the recorder. */
    val humanReadable: String get() = AgentTraceRecorder.renderHumanReadable(events)

    companion object {
        /** Extension of trace files; also the files the viewer lists. */
        const val TRACE_EXTENSION = ".jsonl"

        /**
         * Build a [TraceRun] from a trace file's raw JSONL text. Malformed
         * lines are skipped by the recorder's parser (a partial write must
         * never break the view), so a half-flushed file still renders.
         */
        fun fromRaw(
            traceId: String,
            filePath: String,
            lastModifiedMs: Long,
            sizeBytes: Long,
            raw: String,
        ): TraceRun {
            val events = AgentTraceRecorder.parseWithRunContext(raw)
            val start = events.firstOrNull { it.optString("type") == AgentTraceRecorder.TYPE_TRACE_START }
            val end = events.lastOrNull { it.optString("type") == AgentTraceRecorder.TYPE_TRACE_END }

            return TraceRun(
                traceId = traceId,
                filePath = filePath,
                lastModifiedMs = lastModifiedMs,
                sizeBytes = sizeBytes,
                runId = start?.optString("run_id").orEmpty(),
                // 2.0 writes `session_id`; 1.0 wrote `session`. Accept both, in
                // the same precedence the recorder's own renderer uses.
                sessionId = start?.optString("session_id").takeUnless { it.isNullOrEmpty() }
                    ?: start?.optString("session").orEmpty(),
                provider = start?.optString("provider").orEmpty(),
                // Absent version == a 1.0 trace written before the 2.0 schema
                // landed; never fabricate a version the file did not carry.
                schemaVersion = start?.optString("trace_schema_version")
                    .takeUnless { it.isNullOrEmpty() } ?: "1.0",
                promptPreview = start?.optString("prompt_preview").takeUnless { it.isNullOrEmpty() }
                    ?: start?.optString("prompt").orEmpty(),
                terminalState = end?.optString("terminal_state").orEmpty(),
                terminalReason = end?.optString("terminal_reason").orEmpty(),
                durationMs = end?.optLong("duration_ms", -1L) ?: -1L,
                // Prefer the footer's summary; fall back to counting the run's
                // own turn_start events (the 2.0 `endRun` footer omits `turns`).
                turns = end?.optInt("turns", -1)?.takeIf { it >= 0 }
                    ?: events.count { it.optString("type") == AgentTraceRecorder.TYPE_TURN_START }
                        .takeIf { it > 0 }
                    ?: -1,
                events = events,
            )
        }
    }
}

/** One-line summary of an event, used by the drill-down list. */
data class TraceEventLine(
    val index: Int,
    val type: String,
    /** Icon-less category bucket, so the UI can colour rows consistently. */
    val kind: Kind,
    /** Short human sentence. */
    val summary: String,
    /** Relative timestamp in ms from run start, or -1 when unknown. */
    val offsetMs: Long,
) {
    enum class Kind { START, TURN, TOOL, BUDGET, RESOURCE, RETRY, PERSIST, ERROR, END, OTHER }
}

/**
 * Flatten events into render lines. Kept here (rather than in the composable)
 * so the exact wording is unit-testable and so the same text can be reused by
 * an export without touching Compose.
 */
object TraceTimeline {

    fun lines(run: TraceRun): List<TraceEventLine> {
        val startTs = run.events.firstOrNull { it.optString("type") == AgentTraceRecorder.TYPE_TRACE_START }
            ?.optLong("ts") ?: 0L
        return run.events.mapIndexed { index, e ->
            val ts = e.optLong("ts", 0L)
            TraceEventLine(
                index = index,
                type = e.optString("type"),
                kind = kindOf(e.optString("type")),
                summary = summarize(e),
                offsetMs = if (startTs > 0 && ts > 0) ts - startTs else -1L,
            )
        }
    }

    fun kindOf(type: String): TraceEventLine.Kind = when (type) {
        AgentTraceRecorder.TYPE_TRACE_START -> TraceEventLine.Kind.START
        AgentTraceRecorder.TYPE_TRACE_END -> TraceEventLine.Kind.END
        AgentTraceRecorder.TYPE_TURN_START, AgentTraceRecorder.TYPE_TURN_END -> TraceEventLine.Kind.TURN
        AgentTraceRecorder.TYPE_TOOL_CALL, AgentTraceRecorder.TYPE_TOOL_RESULT -> TraceEventLine.Kind.TOOL
        AgentTraceRecorder.TYPE_BUDGET_CONSUME, AgentTraceRecorder.TYPE_BUDGET_REFUSE -> TraceEventLine.Kind.BUDGET
        AgentTraceRecorder.TYPE_RESOURCE_ACQUIRE, AgentTraceRecorder.TYPE_RESOURCE_RELEASE -> TraceEventLine.Kind.RESOURCE
        AgentTraceRecorder.TYPE_RETRY_DECISION -> TraceEventLine.Kind.RETRY
        AgentTraceRecorder.TYPE_PERSISTENCE_RESULT -> TraceEventLine.Kind.PERSIST
        AgentTraceRecorder.TYPE_ERROR -> TraceEventLine.Kind.ERROR
        else -> TraceEventLine.Kind.OTHER
    }

    /** One compact line per event. Field-by-field mirror of the recorder's
     *  own `renderHumanReadable`, but flat enough for a list row. */
    private fun summarize(e: JSONObject): String = when (e.optString("type")) {
        AgentTraceRecorder.TYPE_TRACE_START -> "run started"
        AgentTraceRecorder.TYPE_TRACE_END ->
            "run ended: ${e.optString("terminal_state").ifEmpty { "?" }}" +
                e.optString("terminal_reason").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        AgentTraceRecorder.TYPE_TURN_START -> "turn ${e.optInt("turn", -1)}"
        AgentTraceRecorder.TYPE_TURN_END -> "turn ${e.optInt("turn", -1)} end · ${parts(e)}"
        AgentTraceRecorder.TYPE_TOOL_CALL -> "→ ${e.optString("tool")}"
        AgentTraceRecorder.TYPE_TOOL_RESULT ->
            "← ${e.optString("tool")} ${if (e.optBoolean("success", true)) "OK" else "FAIL"}" +
                e.optLong("duration_ms", -1L).takeIf { it >= 0 }?.let { " (${it}ms)" } ?: ""
        AgentTraceRecorder.TYPE_STATE_TRANSITION ->
            "${e.optString("from")} → ${e.optString("to")}"
        AgentTraceRecorder.TYPE_BUDGET_CONSUME ->
            "budget ${e.optString("dimension")} −${e.optInt("consumed", 1)}"
        AgentTraceRecorder.TYPE_BUDGET_REFUSE ->
            "budget ${e.optString("dimension")} refused (${e.optString("reason")})"
        AgentTraceRecorder.TYPE_RESOURCE_ACQUIRE ->
            "locked ${e.optString("resource_type")} ${e.optString("resource_id")}"
        AgentTraceRecorder.TYPE_RESOURCE_RELEASE ->
            "released ${e.optString("resource_type")} ${e.optString("resource_id")}"
        AgentTraceRecorder.TYPE_RETRY_DECISION ->
            "retry ${e.optString("operation_type")} → ${e.optString("outcome")}"
        AgentTraceRecorder.TYPE_PERSISTENCE_RESULT ->
            "persist ${e.optString("target")} ${if (e.optBoolean("success", true)) "OK" else "FAIL"}"
        AgentTraceRecorder.TYPE_ERROR -> {
            // `phase` is optional in the schema — render the parentheses only
            // when it is actually present.
            val phase = e.optString("phase").takeIf { it.isNotEmpty() }
            "error" + (phase?.let { " ($it)" } ?: "") + ": ${e.optString("message")}"
        }
        else -> e.optString("type")
    }

    private fun parts(e: JSONObject): String = buildList {
        e.optInt("tokens_in", -1).takeIf { it >= 0 }?.let { add("in=$it") }
        e.optInt("tokens_out", -1).takeIf { it >= 0 }?.let { add("out=$it") }
        e.optString("finish_reason").takeIf { it.isNotEmpty() }?.let { add("finish=$it") }
        e.optLong("duration_ms", -1L).takeIf { it >= 0 }?.let { add("${it}ms") }
    }.joinToString(" ")
}
