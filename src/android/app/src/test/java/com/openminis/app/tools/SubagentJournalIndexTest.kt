package com.openminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-journal-index] The journal's index.
 *
 * The journal is the RECOVERY route: when the envelope's body was
 * truncated, the parent is told to `file_read` this file. But the
 * journal was a raw dump — a measured 283 KB / 5058 lines for one
 * 40-turn research run — so the parent paid tens of thousands of
 * tokens to open it and then had to find the conclusion inside it by
 * eye.
 *
 * The index turns "read the whole thing" into "read a dozen lines,
 * then go straight to the section you need".
 */
class SubagentJournalIndexTest {

    private fun run(
        steps: List<SubagentRunRegistry.Step> = emptyList(),
        segments: List<SubagentRunRegistry.Segment> = emptyList(),
    ) = SubagentRunRegistry.Run(
        id = "run-1",
        sessionId = "s1",
        skillId = "general-agent",
        skillName = "General Sub Agent",
        title = "",
        query = "investigate the thing",
        startedAtMs = 1_700_000_000_000,
        maxTurns = 40,
        status = SubagentRunRegistry.RunStatus.SUCCESS,
        segments = segments,
        steps = steps,
        blockId = "b1",
    )

    private fun step(
        status: SubagentRunRegistry.ToolStepStatus,
        output: String = "",
    ) = SubagentRunRegistry.Step(
        id = "step-${status.name}",
        turn = 1,
        toolName = "shell_execute",
        toolTitle = "",
        status = status,
        output = output,
        durationMs = 12,
    )

    private fun render(
        run: SubagentRunRegistry.Run = run(),
        resultText: String = "",
        turns: Int = 3,
        artifacts: List<String> = emptyList(),
    ): String = SubagentJournalIndex.render(
        run = run,
        terminal = "SUCCESS",
        resultText = resultText,
        turns = turns,
        artifacts = artifacts,
    )

    // ── the index exists and is near the top ────────────────────────────

    /**
     * The parent reads the top of a file first. An index at the BOTTOM
     * is an index the parent never sees before it starts reading the
     * dump.
     */
    @Test
    fun `the index comes before the task and the transcript`() {
        val text = render()
        val indexIdx = text.indexOf("## Index")
        val taskIdx = text.indexOf("## Task")
        val reportIdx = text.indexOf("## Report")

        assertTrue("the index must be rendered: $text", indexIdx >= 0)
        assertTrue("the task must be rendered: $text", taskIdx >= 0)
        assertTrue("the report must be rendered: $text", reportIdx >= 0)
        assertTrue("the index must precede the task", indexIdx < taskIdx)
        assertTrue("the index must precede the report", indexIdx < reportIdx)
    }

    // ── what the index says ─────────────────────────────────────────────

    @Test
    fun `the index counts the tool calls`() {
        val text = render(
            run = run(
                steps = listOf(
                    step(SubagentRunRegistry.ToolStepStatus.SUCCESS),
                    step(SubagentRunRegistry.ToolStepStatus.SUCCESS),
                    step(SubagentRunRegistry.ToolStepStatus.FAILED),
                ),
            ),
        )
        assertTrue("the tool calls must be counted: $text", text.contains("- tool calls: 3"))
        assertTrue("the failures must be counted: $text", text.contains("2 ok / 1 failed"))
    }

    @Test
    fun `the index says when no tool call ran`() {
        val text = render()
        assertTrue("the absence must be stated: $text", text.contains("no tool calls ran"))
    }

    @Test
    fun `the index names the deliverables`() {
        val text = render(artifacts = listOf("/var/minis/workspace/subagents/run-1/report.md"))
        assertTrue("the artifacts must be named: $text", text.contains("/var/minis/workspace/subagents/run-1/report.md"))
    }

    /**
     * The declared artifacts — the paths the report names, reconciled
     * against disk — are deliverables too, and a shell-written file never
     * enters the runtime's write log.
     */
    @Test
    fun `the index names the declared artifacts as well`() {
        val text = render(
            resultText = """
                summary: the bottom line
                status: done
                artifacts: /var/minis/workspace/out/shell-written.md
            """.trimIndent(),
        )
        assertTrue("the declared artifact must be named: $text", text.contains("/var/minis/workspace/out/shell-written.md"))
    }

    @Test
    fun `the index counts the findings and says whether gaps were declared`() {
        val text = render(
            resultText = """
                summary: the bottom line
                status: done
                key_findings:
                - one (anchor: a)
                - two (anchor: b)
                gaps: pagination was not exercised
            """.trimIndent(),
        )
        assertTrue("the findings must be counted: $text", text.contains("- key findings: 2"))
        assertTrue("the gaps must be noted: $text", text.contains("- gaps: declared"))
    }

    @Test
    fun `the index says when there are no findings and no gaps`() {
        val text = render(resultText = "just some prose")
        assertTrue("the findings must be counted: $text", text.contains("- key findings: 0"))
        assertTrue("the gaps must be noted: $text", text.contains("- gaps: none declared"))
    }

    /** The parent must be told WHERE to look, not just what happened. */
    @Test
    fun `the index names the sections`() {
        val text = render()
        assertTrue("the sections must be named: $text", text.contains("## Report"))
        assertTrue("the transcript must be named: $text", text.contains("## Transcript"))
    }

    /**
     * The index is only useful if the journal ACTUALLY WRITES it.
     *
     * The index lives in its own object because [SubagentRunJournal]
     * depends on `PRootKernel` — an Android class — and therefore
     * cannot be compiled or unit-tested on the JVM. That means the
     * wiring between the two can only be checked by a source grep.
     *
     * This is the same lesson the repository keeps relearning: a pure
     * concern extracted from an untestable file is only half the work —
     * if the wiring is not pinned, the extracted function can be
     * deleted from the call site and every unit test stays green.
     */
    @Test
    fun `the journal writes the index`() {
        val journal = com.openminis.app.ProductionSources.read("tools/SubagentRunJournal.kt")
        assertTrue(
            "the journal must call the index renderer",
            journal.contains("SubagentJournalIndex.render("),
        )
        assertTrue(
            "the index must come before the task section",
            journal.indexOf("SubagentJournalIndex.render(") < journal.indexOf("appendLine(\"## Task\")"),
        )
    }
}
