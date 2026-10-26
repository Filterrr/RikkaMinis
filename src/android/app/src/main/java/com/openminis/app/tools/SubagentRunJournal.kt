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
 * Every failure is swallowed — journaling must never break the run.
 */
object SubagentRunJournal {

    /** Sandbox path prefix the journal writes under. */
    const val DIR = "/var/minis/workspace/.subagent"

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
        return buildString {
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
            appendLine("## Task")
            appendLine()
            appendLine(scrub(run.query))
            appendLine()
            // [T-subagent-chat-stream] The ORDERED transcript — narration,
            // reasoning and tool calls in the order they happened. This is what
            // actually occurred; `## Steps` below is the flat tool-only index
            // kept for readers (and tests) that expect it. Reasoning is included
            // here because the journal is the durable record of the run and the
            // page only holds a bounded tail of it.
            if (run.segments.isNotEmpty()) {
                appendLine("## Transcript")
                appendLine()
                for (segment in run.segments) {
                    when (segment) {
                        is SubagentRunRegistry.Segment.Text -> {
                            appendLine("**turn ${segment.turn} — text**")
                            appendLine()
                            appendLine(scrub(segment.content.trim()))
                            appendLine()
                        }
                        is SubagentRunRegistry.Segment.Thinking -> {
                            appendLine("**turn ${segment.turn} — thinking**")
                            appendLine()
                            appendLine(scrub(segment.content.trim()))
                            appendLine()
                        }
                        is SubagentRunRegistry.Segment.ToolCall -> {
                            appendLine(
                                "**turn ${segment.turn} — tool: " +
                                    "${segment.toolTitle.ifBlank { segment.toolName }}**",
                            )
                            appendLine()
                        }
                    }
                }
            }
            appendLine("## Report")
            appendLine()
            if (resultText.isBlank()) appendLine("(no text output produced)") else appendLine(scrub(resultText))
            appendLine()
            appendLine("## Steps")
            appendLine()
            for (step in run.steps) {
                appendLine("### [${step.status}] ${step.toolTitle.ifBlank { step.toolName }} (${step.durationMs}ms)")
                if (step.output.isNotBlank()) {
                    appendLine("```")
                    appendLine(scrub(step.output))
                    appendLine("```")
                }
                appendLine()
            }
        }
    }
}
