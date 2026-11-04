package com.openminis.app.tools

import android.content.Context
import com.openminis.app.sandbox.PRootKernel

/**
 * [T-subagent-durability] Journal for sub-agent terminal reports.
 *
 * Persists a run's terminal state to
 * `/var/minis/workspace/.subagent/<runId>.md` (session sandbox path,
 * resolved via PRootKernel like the ERRORS.md learnings journal).
 *
 * Why: the spawn_agent tool_result is NOT durable on interruption — a
 * user cancel (or process death) discards the runner's return value and
 * the framework persists only a synthetic CANCELLED_MARKER. The journal
 * is the recovery anchor: the parent agent (or the user) can always
 * `file_read` it to retrieve the partial/final report.
 *
 * [fix/subagent-journal-navigability] SECTION ORDER puts the conclusions
 * FIRST. The order used to be
 *
 *     Task -> Transcript (190 KB) -> Report (2 KB) -> Steps
 *
 * which forced every reader who wanted the answer to walk through the
 * largest section in the file to reach the smallest one. The journal is a
 * recovery route, and the thing being recovered is the REPORT. Evidence
 * follows it now:
 *
 *     Index (line map) -> Task -> Report -> Transcript -> Steps
 *
 * This file supplies CONTENT (redacted); [SubagentJournalIndex.assemble] owns
 * the LAYOUT. That split is deliberate: the numbering is the part most easily
 * got wrong and this file imports `PRootKernel`, so it cannot be compiled or
 * asserted on the JVM.
 *
 * Every failure is swallowed — journaling must never break the run.
 */
object SubagentRunJournal {

    /** Sandbox path prefix the journal writes under. */
    const val DIR = "/var/minis/workspace/.subagent"

    /**
     * [fix/subagent-journal-navigability] Per-section output budgets.
     *
     * The journal had NO bound at all: a 40-turn run measured 283 KB, and the
     * 75-turn budget shipped in #90 makes half a megabyte the expected size of
     * a long research run. That is paid by whoever opens the recovery file —
     * which is the parent agent doing exactly what the envelope told it to.
     *
     * The bounds are generous (they only bite on pathological turns) but
     * finite, and a cut is announced in place: a journal that silently dropped
     * half a transcript while calling itself "the durable record" would be the
     * same lie the truncation markers exist to prevent.
     */
    const val MAX_SEGMENT_CHARS = 4_000
    const val MAX_STEP_OUTPUT_CHARS = 4_000
    const val MAX_TRANSCRIPT_CHARS = 200_000
    const val MAX_STEPS_CHARS = 200_000

    /**
     * Persist one terminal record. Returns the sandbox Linux path of the
     * journal file, or null when resolution/persistence failed (swallowed).
     */
    fun write(
        run: SubagentRunRegistry.Run,
        terminal: String,
        resultText: String,
        turns: Int,
        error: String? = null,
        artifacts: List<String> = emptyList(),
        context: Context? = null,
    ): String? = runCatching {
        val sandboxPath = "$DIR/${run.id}.md"
        val file = if (context != null) {
            PRootKernel.resolveSessionHostPath(run.sessionId, sandboxPath, context) ?: return null
        } else {
            java.io.File(sandboxPath)
        }
        file.parentFile?.mkdirs()
        file.writeText(render(run, terminal, resultText, turns, error, artifacts))
        sandboxPath
    }.getOrNull()

    /**
     * [fix/subagent-journal-redaction] Render the markdown journal body with
     * every env-var secret masked.
     *
     * The journal is durable and `file_read`-able long after the run ends,
     * so it must not become the resting place of a secret the shell path
     * already scrubbed. Every free-text field goes through
     * [SubagentJournalRedaction]; the structural fields (ids, skill name,
     * terminal status, artifact paths) are left alone because they are
     * runtime-generated and carry no user data.
     *
     * [secretValues] is injectable so the rule is unit-testable on the plain
     * JVM without an Android Context.
     */
    fun render(
        run: SubagentRunRegistry.Run,
        terminal: String,
        resultText: String,
        turns: Int,
        error: String? = null,
        artifacts: List<String> = emptyList(),
        secretValues: Collection<String> = SubagentJournalRedaction.currentSecretValues(),
    ): String {
        val scrub: (String) -> String = { SubagentJournalRedaction.redact(it, secretValues) }

        val header = buildString {
            appendLine("# Sub-agent run: ${run.title.ifBlank { run.skillName }}")
            appendLine()
            appendLine("- run id: ${run.id}")
            appendLine("- skill: ${run.skillId}")
            appendLine("- terminal status: $terminal")
            appendLine("- turns: $turns/${run.maxTurns}")
            appendLine("- started: ${java.time.Instant.ofEpochMilli(run.startedAtMs)}")
            error?.let { appendLine("- note: ${scrub(it)}") }
            if (artifacts.isNotEmpty()) {
                appendLine("- artifacts: ${artifacts.joinToString(", ")}")
            }
            appendLine()
        }

        val sections = listOf(
            "Task" to scrub(run.query),
            "Report" to if (resultText.isBlank()) "(no text output produced)" else scrub(resultText),
            "Transcript" to renderTranscript(run, scrub),
            "Steps" to renderSteps(run, scrub),
        )

        return SubagentJournalIndex.assemble(header, sections) { spans ->
            SubagentJournalIndex.render(run, terminal, resultText, turns, artifacts, spans = spans)
        }
    }

    /**
     * [fix/subagent-journal-navigability] The transcript, bounded.
     *
     * Reasoning is included: the journal is the durable record of the run and
     * the detail page only holds a bounded tail of it.
     */
    private fun renderTranscript(
        run: SubagentRunRegistry.Run,
        scrub: (String) -> String,
    ): String = buildString {
        if (run.segments.isEmpty()) {
            appendLine("(no transcript recorded)")
            return@buildString
        }
        var used = 0
        var skipped = 0
        for (segment in run.segments) {
            val rendered = when (segment) {
                is SubagentRunRegistry.Segment.Text -> buildString {
                    appendLine("**turn ${segment.turn} — text**")
                    appendLine()
                    appendLine(boundSegment("text", segment.content.trim(), scrub))
                    appendLine()
                }
                is SubagentRunRegistry.Segment.Thinking -> buildString {
                    appendLine("**turn ${segment.turn} — thinking**")
                    appendLine()
                    appendLine(boundSegment("thinking", segment.content.trim(), scrub))
                    appendLine()
                }
                is SubagentRunRegistry.Segment.ToolCall -> buildString {
                    appendLine(
                        "**turn ${segment.turn} — tool: " +
                            "${segment.toolTitle.ifBlank { segment.toolName }}**",
                    )
                    appendLine()
                }
            }
            if (used + rendered.length > MAX_TRANSCRIPT_CHARS) {
                skipped++
                continue
            }
            append(rendered)
            used += rendered.length
        }
        if (skipped > 0) {
            appendLine(
                SubagentJournalIndex.ELISION_MARKER +
                    " $skipped later segment(s) elided because the transcript budget " +
                    "($MAX_TRANSCRIPT_CHARS chars) was reached. The findings are " +
                    "unaffected; the live UI holds the tail.",
            )
        }
    }

    /** The flat tool index, bounded the same way as the transcript. */
    private fun renderSteps(
        run: SubagentRunRegistry.Run,
        scrub: (String) -> String,
    ): String = buildString {
        if (run.steps.isEmpty()) {
            appendLine("(no tool calls ran)")
            return@buildString
        }
        var used = 0
        var skipped = 0
        for (step in run.steps) {
            val rendered = buildString {
                appendLine("### [${step.status}] ${step.toolTitle.ifBlank { step.toolName }} (${step.durationMs}ms)")
                if (step.output.isNotBlank()) {
                    appendLine("```")
                    appendLine(boundSegment("output", step.output, scrub))
                    appendLine("```")
                }
                appendLine()
            }
            if (used + rendered.length > MAX_STEPS_CHARS) {
                skipped++
                continue
            }
            append(rendered)
            used += rendered.length
        }
        if (skipped > 0) {
            appendLine(
                SubagentJournalIndex.ELISION_MARKER +
                    " $skipped later step(s) elided because the steps budget " +
                    "($MAX_STEPS_CHARS chars) was reached.",
            )
        }
    }

    /**
     * One segment, bounded. The cut is announced IN PLACE — a reader who finds
     * a mid-sentence break with no marker would believe the model stopped
     * there, which is exactly the misreading the truncation markers exist to
     * prevent.
     */
    private fun boundSegment(label: String, text: String, scrub: (String) -> String): String {
        val scrubbed = scrub(text)
        if (scrubbed.length <= MAX_SEGMENT_CHARS) return scrubbed
        return scrubbed.take(MAX_SEGMENT_CHARS) +
            "\n… [$label truncated: $MAX_SEGMENT_CHARS of ${scrubbed.length} chars shown]"
    }
}
