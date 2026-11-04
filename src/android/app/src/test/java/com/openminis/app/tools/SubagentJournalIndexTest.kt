package com.openminis.app.tools

import org.junit.Assert.assertEquals
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

    /** Renders the FINISHED document (header + index + sections). */
    private fun document(
        run: SubagentRunRegistry.Run = run(),
        resultText: String = "",
        turns: Int = 3,
        artifacts: List<String> = emptyList(),
    ): String = SubagentJournalIndex.assemble(
        header = "# Sub-agent run: test\n\n",
        sections = listOf(
            "Task" to run.query,
            "Report" to if (resultText.isBlank()) "(no text output produced)" else resultText,
            "Transcript" to "(transcript)",
            "Steps" to "(steps)",
        ),
    ) { spans ->
        SubagentJournalIndex.render(
            run = run,
            terminal = "SUCCESS",
            resultText = resultText,
            turns = turns,
            artifacts = artifacts,
            spans = spans,
        )
    }

    /**
     * The parent reads the top of a file first. An index at the BOTTOM
     * is an index the parent never sees before it starts reading the
     * dump.
     */
    @Test
    fun `the index comes before the task and the transcript`() {
        val text = document()
        val indexIdx = text.indexOf("## Index")
        val taskIdx = text.indexOf("## Task")
        val reportIdx = text.indexOf("## Report")
        val transcriptIdx = text.indexOf("## Transcript")

        assertTrue("the index must be rendered: $text", indexIdx >= 0)
        assertTrue("the task must be rendered: $text", taskIdx >= 0)
        assertTrue("the report must be rendered: $text", reportIdx >= 0)
        assertTrue("the index must precede the task", indexIdx < taskIdx)
        assertTrue("the index must precede the report", indexIdx < reportIdx)
        // [fix/subagent-journal-navigability] The report comes BEFORE the
        // transcript: the transcript is 100x larger and is only evidence.
        assertTrue(
            "the report must precede the transcript: $text",
            reportIdx < transcriptIdx,
        )
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
        assertTrue("the report must be named: $text", text.contains("Report"))
        assertTrue("the transcript must be named: $text", text.contains("Transcript"))
        assertTrue("the task must be named: $text", text.contains("Task"))
        assertTrue("the steps must be named: $text", text.contains("Steps"))
    }

    /**
     * [fix/subagent-journal-navigability] The index must give a LOCATION, not
     * just a name.
     *
     * The first version of this index listed the section names and said "read
     * `## Report` first" — advice the parent could only act on by opening the
     * whole file and searching for the heading. That is a table of contents
     * without page numbers.
     */
    @Test
    fun `the index gives a line range and a size per section`() {
        val text = document(
            run = run(steps = listOf(step(SubagentRunRegistry.ToolStepStatus.SUCCESS))),
            resultText = "summary: the bottom line\nstatus: done",
        )

        assertTrue("a line range must be given: $text", text.contains("L1"))
        assertTrue("a size must be given: $text", text.contains("KB") || text.contains(" B"))
        assertTrue(
            "the report must be marked as the first thing to read: $text",
            text.contains("read this first"),
        )
        assertTrue(
            "the transcript must be marked as optional: $text",
            text.contains("skip by default"),
        )
    }

    /**
     * The index's HEIGHT must not depend on the numbers it reports.
     *
     * This is the property the numbering rests on: the index is part of the
     * file whose line numbers it prints, so a variable-height index would
     * shift every offset it reports — the more it had to say, the wronger it
     * would be.
     */
    @Test
    fun `the index block is the same height with and without spans`() {
        val noSpans = render()
        val withSpans = SubagentJournalIndex.render(
            run = run(steps = listOf(step(SubagentRunRegistry.ToolStepStatus.SUCCESS))),
            terminal = "SUCCESS",
            resultText = "summary: x\nstatus: done",
            turns = 9,
            artifacts = listOf("/a/b.md", "/c/d.md"),
            spans = listOf(
                SubagentJournalIndex.SectionSpan("Task", 12, 14, 40),
                SubagentJournalIndex.SectionSpan("Report", 15, 20, 800),
                SubagentJournalIndex.SectionSpan("Transcript", 21, 400, 190_000, elided = true),
                SubagentJournalIndex.SectionSpan("Steps", 401, 500, 12_000),
            ),
        )
        assertTrue(
            "the address column must never be missing: $noSpans",
            noSpans.lines().filter { it.contains("L") }.size >= 4,
        )
        assertTrue(
            "the elided flag must be visible: $withSpans",
            withSpans.contains("SHORTENED to fit"),
        )
    }

    // ── the layout owns the numbering ───────────────────────────────────

    /**
     * [fix/subagent-journal-navigability] The offsets the index prints must be
     * the lines the sections ACTUALLY start on.
     *
     * `assemble` is in the pure object precisely so this can be asserted: in
     * `SubagentRunJournal` (which imports `PRootKernel`) it could not be
     * compiled on the JVM, and a numbering bug would be invisible until a
     * parent read the wrong lines.
     */
    @Test
    fun `the assembled document reports the true line numbers`() {
        val header = "---\nheader\n\n"
        val sections = listOf(
            "Task" to "the assignment\n",
            "Report" to "summary: everything is fine\n",
            "Transcript" to "turn 1 text\n",
            "Steps" to "step one\n",
        )
        val doc = SubagentJournalIndex.assemble(header, sections) { spans ->
            SubagentJournalIndex.render(
                run = run(steps = listOf(step(SubagentRunRegistry.ToolStepStatus.SUCCESS))),
                terminal = "SUCCESS",
                resultText = "summary: everything is fine\n",
                turns = 1,
                artifacts = emptyList(),
                spans = spans,
            )
        }

        val lines = doc.split('\n')
        // For every section, the line number the index claims must be the line
        // the heading is actually on.
        for ((name, _) in sections) {
            val match = Regex("^\\s+${name}\\s+L(\\d+)-L(\\d+)", RegexOption.MULTILINE).find(doc)
            assertTrue("the index must address `$name`: $doc", match != null)
            val claimed = match!!.groupValues[1].toInt()
            val actual = lines.indexOfFirst { it == "## $name" } + 1
            assertEquals(
                "the index must point at the real heading line for `$name`",
                actual,
                claimed,
            )
        }
    }

    /** A section with no content still gets a stable, addressable line. */
    @Test
    fun `an empty section is still addressable`() {
        val doc = SubagentJournalIndex.assemble("h\n", listOf("Report" to "")) { spans ->
            SubagentJournalIndex.render(
                run = run(), terminal = "SUCCESS", resultText = "", turns = 1,
                artifacts = emptyList(), spans = spans,
            )
        }
        val lines = doc.split('\n')
        val match = Regex("^\\s+Report\\s+L(\\d+)-L(\\d+)", RegexOption.MULTILINE).find(doc)
        assertTrue("the section must still be addressed: $doc", match != null)
        assertEquals(
            "the heading line must be the claim",
            lines.indexOfFirst { it == "## Report" } + 1,
            match!!.groupValues[1].toInt(),
        )
    }

    /**
     * The index is only useful if the journal ACTUALLY WRITES it.
     *
     * The index lives in its own object because [SubagentRunJournal]
     * depends on `PRootKernel` — an Android class — and therefore
     * cannot be compiled or unit-tested on the JVM. That means the
     * wiring between the two can only be checked by a source grep.
     *
     * [fix/subagent-journal-navigability] The journal no longer emits the
     * section headings itself — `assemble` does — so the old guard (index
     * call site before `appendLine("## Task")`) pinned a shape that no longer
     * exists. It now pins the contract that matters: the journal hands its
     * sections to the layout pass and passes the spans through.
     */
    @Test
    fun `the journal hands its sections to the layout pass`() {
        val journal = com.openminis.app.ProductionSources.read("tools/SubagentRunJournal.kt")
        assertTrue(
            "the journal must delegate the layout to the index object",
            journal.contains("SubagentJournalIndex.assemble("),
        )
        assertTrue(
            "the layout call must be given a span-aware index renderer",
            journal.contains("spans = spans"),
        )
        assertTrue(
            "the section order must put Report before Transcript",
            journal.indexOf("\"Report\" to") < journal.indexOf("\"Transcript\" to"),
        )
        assertTrue(
            "the section order must put Report before Steps",
            journal.indexOf("\"Report\" to") < journal.indexOf("\"Steps\" to"),
        )
    }
}
