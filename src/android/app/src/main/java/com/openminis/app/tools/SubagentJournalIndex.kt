package com.openminis.app.tools

/**
 * [feat/subagent-journal-index] The journal's machine-readable index.
 *
 * WHY
 * ---
 * A journal is the RECOVERY route: when the envelope's body was
 * truncated, the parent is told to `file_read` this file. But the
 * journal was a raw dump — a measured 283 KB / 5058 lines for one
 * 40-turn research run — so the parent paid tens of thousands of
 * tokens to open it and then had to FIND the conclusion inside it by
 * eye.
 *
 * The index turns "read the whole thing" into "read a dozen lines,
 * then go straight to the section you need". It says what the run did,
 * what it produced, and where each part is.
 *
 * WHY IT IS ITS OWN OBJECT
 * -------------------------
 * [SubagentRunJournal] depends on `PRootKernel` — an Android class —
 * so it cannot be compiled or unit-tested on the JVM. The index does
 * not need any of that: it is a function of the run's own fields.
 *
 * That is the same lesson this repository keeps relearning: a pure
 * concern hidden inside an Android-dependent file is a pure concern
 * nobody can test. Extracting it makes the index's wording a local
 * test's business instead of CI's.
 */
object SubagentJournalIndex {

    /**
     * Render the index.
     *
     * @param run the run — its id, skill, turn budget, steps and segments.
     * @param terminal the terminal status word.
     * @param resultText the delivered report text; parsed for the
     *   declared artifacts, the findings count and the gaps.
     * @param turns the turns executed.
     * @param artifacts the runtime's write log.
     */
    fun render(
        run: SubagentRunRegistry.Run,
        terminal: String,
        resultText: String,
        turns: Int,
        artifacts: List<String>,
    ): String = buildString {
        val ok = run.steps.count { it.status == SubagentRunRegistry.ToolStepStatus.SUCCESS }
        val failed = run.steps.count { it.status == SubagentRunRegistry.ToolStepStatus.FAILED }
        val running = run.steps.count { it.status == SubagentRunRegistry.ToolStepStatus.RUNNING }
        val parsed = if (resultText.isNotBlank()) {
            SubagentReportContract.parse(resultText)
        } else {
            null
        }

        appendLine("## Index")
        appendLine()
        appendLine("- skill: ${run.skillId}")
        appendLine("- turns: $turns/${run.maxTurns}")
        appendLine("- terminal status: $terminal")
        appendLine(
            buildString {
                append("- tool calls: ${run.steps.size}")
                when {
                    run.steps.isEmpty() -> append(" (no tool calls ran)")
                    failed > 0 && running > 0 -> append(" ($ok ok / $failed failed / $running running)")
                    failed > 0 -> append(" ($ok ok / $failed failed)")
                    running > 0 -> append(" ($ok ok / $running running)")
                    else -> append(" ($ok ok)")
                }
            },
        )
        val declared = parsed?.artifacts.orEmpty()
        val allArtifacts = (artifacts + declared).distinct()
        appendLine(
            if (allArtifacts.isEmpty()) "- artifacts: none"
            else "- artifacts: ${allArtifacts.size}\n" +
                allArtifacts.joinToString("\n") { "  - $it" },
        )
        appendLine("- key findings: ${parsed?.findings?.size ?: 0}")
        appendLine("- gaps: ${if (parsed?.gaps.isNullOrBlank()) "none declared" else "declared"}")
        appendLine(
            "- report: " + when {
                resultText.isBlank() -> "none (no text output produced)"
                else -> "${resultText.length} chars"
            },
        )
        appendLine(
            "- sections: `## Task` / `## Transcript` / `## Report` / `## Steps` " +
                "(read `## Report` first — it holds the conclusions; " +
                "`## Transcript` holds the full turn-by-turn record)",
        )
    }
}
