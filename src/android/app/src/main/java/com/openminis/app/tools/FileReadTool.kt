package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

object FileReadTool {
    const val NAME = "file_read"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file from the Linux filesystem. Faster than shell_execute for reading files — no shell overhead. Returns file content with metadata. Rejects binary files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read Python script contents', 'Check system configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to read (e.g. /var/minis/workspace/data.csv)"),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). Ignored when direction is 'tail'."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (default: 15000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "offset", "lines", "direction", "max_length"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)
            val offset = args.optInt("offset", 1).coerceAtLeast(1)
            // T-FILEREAD-CAP: hard upper bound on returned content length.
            // Pre-cap, the agent could ask for `max_length=1_000_000` and we
            // would happily inline a 400 KB base64 image into a tool_result —
            // which then renders as a single user-message bubble and locks
            // up Compose's StaticLayout / LineBreaker for tens of seconds
            // (see HangDetector report for session
            // e84882d7-2087-47f8-9300-ff2c897fe0b4: 820 KB partsJson, 43 s
            // hang in nComputeLineBreaks). Cap at 80 KB regardless of
            // requested value; the truncation tail below tells the agent the
            // full file size so it can paginate with offset/lines if needed.
            // iOS mirrors this cap in AIChatViewModel.executeFileRead.
            val MAX_LENGTH_HARD_CAP = 80_000
            val maxLength = args.optInt("max_length", 15000).coerceAtMost(MAX_LENGTH_HARD_CAP)
            val direction = args.optString("direction", "head")

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // T123: per-session resolver — see FileWriteTool for rationale.
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            if (file.isDirectory) {
                return ToolExecutionResult("Error: Path is a directory: $path", false, toolTitle = toolTitle)
            }

            val size = file.length()

            // Binary detection: check first 8192 bytes for null bytes
            val isBinary = file.inputStream().use { input ->
                val buf = ByteArray(minOf(8192, size.toInt()))
                val read = input.read(buf)
                if (read > 0) buf.take(read).any { it == 0.toByte() } else false
            }

            if (isBinary) {
                return ToolExecutionResult(
                    "[$path | $size bytes | binary file — cannot display contents]",
                    true, toolTitle = toolTitle
                )
            }

            // [P3-negatives] A non-positive `lines` used to reach subList()
            // with fromIndex > toIndex and surfaced as a cryptic
            // "Error reading file: fromIndex > toIndex".
            val requestedLines = if (args.has("lines")) args.optInt("lines") else null
            if (requestedLines != null && requestedLines <= 0) {
                return ToolExecutionResult("Error: 'lines' must be a positive integer", false, toolTitle = toolTitle)
            }

            // [P2-oom] Stream the file instead of file.readLines(): the old
            // path materialized EVERY line before slicing the window, so a
            // multi-hundred-MB log OOM-killed the app even though the reply
            // is capped at MAX_LENGTH_HARD_CAP. One streaming pass counts
            // lines and retains only the selected window — head skips to
            // offset and stops collecting once the char budget is spent;
            // tail keeps a ring buffer of the last `lines` entries bounded
            // by the same budget. Peak memory is proportional to the reply,
            // not the file.
            var totalLines = 0
            var windowChars = 0L
            var cutShort = false
            val window = ArrayDeque<String>()

            file.bufferedReader().useLines { lineSeq ->
                for (line in lineSeq) {
                    totalLines++
                    // A single line longer than the whole reply budget can
                    // never contribute more than maxLength chars — truncate
                    // on ingest so one pathological line can't blow memory.
                    val stored = if (line.length > maxLength) {
                        cutShort = true
                        line.take(maxLength)
                    } else {
                        line
                    }
                    if (direction == "tail") {
                        if (requestedLines == null) {
                            // tail without a count selected the whole file and
                            // was then truncated from the front — equivalent to
                            // the first maxLength chars of the file.
                            if (windowChars <= maxLength) {
                                window.addLast(stored)
                                windowChars += stored.length + 1L
                            } else {
                                cutShort = true
                            }
                        } else {
                            window.addLast(stored)
                            windowChars += stored.length + 1L
                            while (window.size > requestedLines) {
                                val evicted = window.removeFirst()
                                windowChars -= evicted.length + 1L
                            }
                            while (windowChars > maxLength && window.size > 1) {
                                val evicted = window.removeFirst()
                                windowChars -= evicted.length + 1L
                                cutShort = true
                            }
                        }
                    } else {
                        val withinLineLimit = requestedLines == null || window.size < requestedLines
                        if (totalLines >= offset && withinLineLimit) {
                            if (windowChars > maxLength) {
                                cutShort = true
                            } else {
                                window.addLast(stored)
                                windowChars += stored.length + 1L
                            }
                        }
                    }
                }
            }

            // Logical selection (what the header reports) — computed from the
            // counts, independent of the char-budget trimming above.
            val logicalSelected = if (direction == "tail") {
                minOf(requestedLines ?: totalLines, totalLines)
            } else {
                minOf(requestedLines ?: totalLines, (totalLines - offset + 1).coerceAtLeast(0))
            }
            val showStart = if (direction == "tail") totalLines - logicalSelected + 1 else offset
            val showEnd = showStart + logicalSelected - 1
            val rangeText = if (logicalSelected > 0) {
                "showing $showStart-$showEnd of $totalLines"
            } else {
                "showing 0 of $totalLines"
            }

            var content = window.joinToString("\n")
            if (content.length > maxLength || cutShort) {
                content = content.take(maxLength) + "\n... (truncated)"
            }

            val header = "[$path | $size bytes | $totalLines lines | $rangeText]"
            ToolExecutionResult("$header\n$content", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error reading file: ${e.message}", false)
        }
    }
}
