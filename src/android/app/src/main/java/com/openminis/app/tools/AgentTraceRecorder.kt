package com.openminis.app.tools

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * T9: Agent execution tracing — structured JSONL trace of one `runAgentLoop`
 * invocation.
 *
 * Each run produces a sequence of typed events, one JSON object per line:
 *   trace_start → (turn_start → tool_call → tool_result → turn_end)* → trace_end
 * Error events can appear at any point.
 *
 * The recorded line is delegated to the injectable [appendLine] callback so
 * the recorder itself is pure JVM (no Android dependency) and unit-testable
 * with an in-memory sink. The host (ChatViewModel) is responsible for routing
 * lines into the session workspace file (`workspace/.traces/agent-<ts>.jsonl`),
 * which means:
 *   - the agent itself can read its own trace back via file_read (self-debug),
 *   - the file can be exported/shared as plain text without special tooling.
 *
 * All payloads are truncated to the companion caps so a long tool chain never
 * balloons the trace file. Time is injectable via [clock] so tests can run
 * deterministically.
 */
class AgentTraceRecorder(
    /** Persists one JSON line (no trailing newline). */
    private val appendLine: (line: String) -> Unit,
    /** Clock in epoch-millis (default: System.currentTimeMillis). */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // ── event types ────────────────────────────────────────────────────────
    companion object {
        const val TYPE_TRACE_START = "trace_start"
        const val TYPE_TURN_START = "turn_start"
        const val TYPE_TOOL_CALL = "tool_call"
        const val TYPE_TOOL_RESULT = "tool_result"
        const val TYPE_TURN_END = "turn_end"
        const val TYPE_TRACE_END = "trace_end"
        const val TYPE_ERROR = "error"

        // ── truncation caps ────────────────────────────────────────────────
        /** Cap on the user prompt stored in trace_start. */
        const val PROMPT_MAX_LENGTH = 300
        /** Cap on tool args JSON stored per tool_call. */
        const val ARGS_MAX_LENGTH = 500
        /** Cap on tool output stored per tool_result. */
        const val OUTPUT_MAX_LENGTH = 1500
        /** Cap on error message stored per error / trace_end. */
        const val ERROR_MAX_LENGTH = 500

        // ── query helpers (pure, no I/O) ───────────────────────────────────
        /**
         * Parse a raw JSONL trace into events. Malformed lines are skipped
         * (a partial write must never break a query), and the surviving
         * events keep their original order.
         */
        fun parse(raw: String): List<JSONObject> {
            if (raw.isBlank()) return emptyList()
            val out = ArrayList<JSONObject>()
            raw.lineSequence().forEach { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEach
                runCatching { JSONObject(t) }
                    .onSuccess { obj -> if (obj.has("type")) out.add(obj) }
            }
            return out
        }

        /** Keep only tool_call/tool_result events for [toolName]. */
        fun filterByTool(events: List<JSONObject>, toolName: String): List<JSONObject> =
            events.filter { e ->
                e.optString("tool") == toolName &&
                    (e.optString("type") == TYPE_TOOL_CALL || e.optString("type") == TYPE_TOOL_RESULT)
            }

        /** Keep only error-signalling events: explicit [TYPE_ERROR] or failed tool results. */
        fun filterErrors(events: List<JSONObject>): List<JSONObject> =
            events.filter { e ->
                when (e.optString("type")) {
                    TYPE_ERROR -> true
                    TYPE_TOOL_RESULT -> !e.optBoolean("success", true)
                    else -> false
                }
            }

        /** Render a human-readable timeline (for 复盘 / export / sharing). */
        fun renderHumanReadable(events: List<JSONObject>): String {
            val sb = StringBuilder()
            var traceStartTs = 0L
            var normalExit = true
            events.forEach { e ->
                when (e.optString("type")) {
                    TYPE_TRACE_START -> {
                        traceStartTs = e.optLong("ts")
                        sb.appendLine("# Agent Trace")
                        sb.appendLine("session: ${e.optString("session")}  provider: ${e.optString("provider")}")
                        e.optString("prompt").takeIf { it.isNotEmpty() }?.let {
                            sb.appendLine("prompt: $it")
                        }
                        sb.appendLine()
                    }
                    TYPE_TURN_START -> {
                        sb.appendLine("## turn ${e.optInt("turn", -1)}  (${fmtTime(e.optLong("ts"))})")
                    }
                    TYPE_TOOL_CALL -> {
                        sb.appendLine("  → ${e.optString("tool")}  ${e.optString("tool_id")}")
                        e.optString("args").takeIf { it.isNotEmpty() }?.let {
                            sb.appendLine("      args: $it")
                        }
                    }
                    TYPE_TOOL_RESULT -> {
                        val ok = e.optBoolean("success", true)
                        val dur = e.optLong("duration_ms", -1)
                        sb.appendLine(
                            "  ← ${e.optString("tool")} ${if (ok) "OK" else "FAIL"} " +
                                (if (dur >= 0) "(${dur}ms)" else "") +
                                " [${e.optString("output").length} chars]"
                        )
                    }
                    TYPE_TURN_END -> {
                        val parts = buildList {
                            e.optInt("tokens_in", -1).takeIf { it >= 0 }?.let { add("in=$it") }
                            e.optInt("tokens_out", -1).takeIf { it >= 0 }?.let { add("out=$it") }
                            e.optString("finish_reason").takeIf { it.isNotEmpty() }?.let { add("finish=$it") }
                            e.optLong("duration_ms", -1).takeIf { it >= 0 }?.let { add("${it}ms") }
                        }
                        sb.appendLine("  end turn ${e.optInt("turn", -1)}  ${parts.joinToString("  ")}")
                    }
                    TYPE_ERROR -> {
                        sb.appendLine("  ⚠ error (${e.optString("phase")}): ${e.optString("message")}")
                    }
                    TYPE_TRACE_END -> {
                        normalExit = e.optBoolean("normal_exit", true)
                        val dur = e.optLong("duration_ms", -1)
                        sb.appendLine()
                        sb.appendLine(
                            "turns: ${e.optInt("turns", -1)}  " +
                                (if (dur >= 0) "duration: ${dur}ms  " else "") +
                                (if (normalExit) "exit: normal" else "exit: MAX_AGENT_TURNS/error")
                        )
                        e.optString("error").takeIf { it.isNotEmpty() }?.let {
                            sb.appendLine("error: $it")
                        }
                    }
                }
            }
            if (traceStartTs > 0) {
                sb.appendLine()
                sb.appendLine("trace started: ${fmtTime(traceStartTs)}")
            }
            return sb.toString()
        }

        private fun fmtTime(ms: Long): String {
            if (ms <= 0) return "?"
            val df = SimpleDateFormat("HH:mm:ss", Locale.US)
            df.timeZone = TimeZone.getDefault()
            return df.format(Date(ms))
        }
    }

    // ── public API ─────────────────────────────────────────────────────────
    /** Run-level header: one per runAgentLoop invocation. */
    fun traceStart(sessionId: String, provider: String, prompt: String) {
        write(TYPE_TRACE_START) {
            put("session", sessionId)
            put("provider", provider)
            put("prompt", truncate(prompt, PROMPT_MAX_LENGTH))
        }
    }

    /** Start of one agent-loop iteration. */
    fun turnStart(turn: Int) {
        write(TYPE_TURN_START) { put("turn", turn) }
    }

    /** A tool call was requested by the model (arguments truncated). */
    fun toolCall(turn: Int, toolId: String, name: String, argsJson: String) {
        write(TYPE_TOOL_CALL) {
            put("turn", turn)
            put("tool_id", toolId)
            put("tool", name)
            put("args", truncate(argsJson, ARGS_MAX_LENGTH))
        }
    }

    /** Outcome of one tool execution (output truncated). */
    fun toolResult(
        turn: Int,
        toolId: String,
        name: String,
        success: Boolean,
        output: String,
        durationMs: Long,
    ) {
        write(TYPE_TOOL_RESULT) {
            put("turn", turn)
            put("tool_id", toolId)
            put("tool", name)
            put("success", success)
            put("duration_ms", durationMs)
            put("output", truncate(output, OUTPUT_MAX_LENGTH))
        }
    }

    /** End of one agent-loop iteration: token usage + finish reason + elapsed. */
    fun turnEnd(
        turn: Int,
        tokensIn: Int?,
        tokensOut: Int?,
        finishReason: String?,
        durationMs: Long,
    ) {
        write(TYPE_TURN_END) {
            put("turn", turn)
            tokensIn?.let { put("tokens_in", it) }
            tokensOut?.let { put("tokens_out", it) }
            finishReason?.takeIf { it.isNotEmpty() }?.let { put("finish_reason", it) }
            put("duration_ms", durationMs)
        }
    }

    /** An error surfaced anywhere in the loop (phase = where it happened). */
    fun error(turn: Int?, phase: String, message: String) {
        write(TYPE_ERROR) {
            turn?.let { put("turn", it) }
            put("phase", phase)
            put("message", truncate(message, ERROR_MAX_LENGTH))
        }
    }

    /** Run-level footer: how the loop exited. */
    fun traceEnd(normalExit: Boolean, turnCount: Int, durationMs: Long, error: String?) {
        write(TYPE_TRACE_END) {
            put("normal_exit", normalExit)
            put("turns", turnCount)
            put("duration_ms", durationMs)
            error?.takeIf { it.isNotEmpty() }?.let { put("error", truncate(it, ERROR_MAX_LENGTH)) }
        }
    }

    // ── internal ───────────────────────────────────────────────────────────
    private fun write(type: String, block: JSONObject.() -> Unit) {
        val obj = JSONObject()
        obj.put("type", type)
        obj.put("ts", clock())
        obj.block()
        appendLine(obj.toString())
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max) + "…"
}