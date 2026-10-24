package com.openminis.app.tools

/**
 * [fix/subagent-step-output-parity] ONE rule for how a tool's output is
 * clipped for a step preview, shared by the main loop and the sub-agent.
 *
 * The defect this fixes: the sub-agent clipped EVERY tool's output with
 * `lines().takeLast(60)`. That rule is right for `shell_execute` (long
 * stdout streams where the tail is what matters) and wrong for every other
 * tool, because the FIRST line carries the metadata:
 *
 *     [path | 1234 bytes | 300 lines | showing 1-200 of 300]
 *     [path | 42 bytes | binary file — cannot display contents]
 *
 * A `file_read` of 300 lines lost that banner entirely — the parent (and
 * the journal, which is the parent's recovery route when a detached run has
 * to be inspected) saw a wall of text with no indication of WHICH file it
 * came from or that it had been truncated. A `file_write` confirmation, a
 * `memory_*` structured header, a `browser_use` URL banner — all
 * head-clipped the same way.
 *
 * The main loop already learned this (T263) and gated the trim to
 * `shell_execute`. The sub-agent never got the same fix, so the two loops
 * had drifted apart on the same tool's output. This object is the shared
 * rule both use now, so they cannot drift again.
 *
 * The rule:
 *  - `shell_execute` -> keep the TAIL (the interesting end of a stream).
 *  - every other tool -> keep the HEAD (the metadata banner and the start
 *    of the payload).
 *
 * This is a PREVIEW cap. It does NOT change what the model receives — the
 * model gets the full output in both loops. It only changes what the step
 * list, the journal and the tool block show.
 */
object SubagentStepOutputTrim {

    /** Tail size for `shell_execute` — matches the main loop's 80. */
    const val MAX_SHELL_TAIL_LINES = 80

    /** Head size for every other tool — matches the sub-agent's old 60. */
    const val MAX_HEAD_LINES = 60

    /**
     * Marker appended when a preview had to be clipped. Without it the
     * reader cannot tell a complete short output from a clipped long one —
     * which is exactly the ambiguity that made the old head-clip dangerous.
     */
    const val TRUNCATION_MARKER = "…[preview truncated]"

    /**
     * Clip [output] for a step preview.
     *
     * @param toolName the tool that produced the output.
     * @param output the raw tool output.
     * @param maxLines the line budget; defaults to the per-tool budget.
     *   Pass [Int.MAX_VALUE] to keep the output whole (the main loop does
     *   this for non-shell tools, whose output is already bounded upstream).
     */
    fun trim(toolName: String, output: String, maxLines: Int = maxLinesFor(toolName)): String {
        if (output.isEmpty() || maxLines <= 0) return output
        val lines = output.lines()
        if (lines.size <= maxLines) return output
        val kept = if (isShell(toolName)) {
            lines.takeLast(maxLines)
        } else {
            lines.take(maxLines)
        }
        return kept.joinToString("\n") + "\n" + TRUNCATION_MARKER
    }

    /** The line budget for [toolName]. */
    fun maxLinesFor(toolName: String): Int =
        if (isShell(toolName)) MAX_SHELL_TAIL_LINES else MAX_HEAD_LINES

    /**
     * True for the shell tool. The main loop keys on the literal name
     * `shell_execute`; the sub-agent uses the same tool name.
     */
    fun isShell(toolName: String): Boolean = toolName == "shell_execute"
}
