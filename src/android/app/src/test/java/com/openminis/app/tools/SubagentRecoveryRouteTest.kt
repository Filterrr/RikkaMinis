package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-journal-navigability] + [fix/subagent-result-head-truncation] +
 * [fix/subagent-artifact-size]
 *
 * The recovery route exists so the parent can go and GET the content. Four
 * things decide whether that trip is cheap or pointless:
 *
 *  1. can the pointer say WHERE in the file the answer is, not just that a
 *     file exists;
 *  2. is the answer at the TOP of the file or behind the evidence dump;
 *  3. does the registry keep a mid-run snapshot's HEAD (where the contract puts
 *     the summary) or only its tail;
 *  4. do the deliverables announce their size, so the parent knows whether to
 *     read one or query it.
 */
class SubagentRecoveryRouteTest {

    private fun span(name: String, start: Int, end: Int, bytes: Int) =
        SubagentJournalIndex.SectionSpan(name, start, end, bytes)

    private fun facts(
        journalPath: String? = "/var/minis/workspace/.subagent/subagent-3.md",
        spans: List<SubagentJournalIndex.SectionSpan>? = null,
        artifacts: List<String> = emptyList(),
        sizes: Map<String, Int> = emptyMap(),
    ) = SubagentResultEnvelope.Facts(
        runId = "subagent-3",
        skillName = "general-agent",
        outcome = SubagentRunAssessment.Outcome.COMPLETED,
        reportSource = SubagentRunAssessment.ReportSource.MODEL,
        executedToolCalls = 9,
        turns = 20,
        maxTurns = 75,
        report = "summary: fine\nstatus: done",
        parsed = SubagentReportContract.parse("summary: fine\nstatus: done"),
        artifacts = artifacts,
        artifactBytes = sizes,
        journalPath = journalPath,
        journalSpans = spans,
    )

    // ── 1. the pointer names a line range ───────────────────────────────

    /**
     * "file_read it for the complete transcript" told the parent the file was
     * big and not where the answer was, so the sensible reaction was to skip
     * it. The pointer now names the two ranges that matter and what each costs.
     */
    @Test
    fun `the pointer names the conclusion range`() {
        val text = SubagentResultEnvelope.render(
            facts(spans = listOf(
                span("Task", 20, 21, 80),
                span("Report", 23, 31, 1_800),
                span("Transcript", 33, 240, 195_000),
                span("Steps", 242, 300, 12_000),
            )),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertTrue("the report range must be given: $text", text.contains("conclusions: L23-L31"))
        assertTrue(
            "the parent must be told how to read exactly that range: $text",
            text.contains("offset=23"),
        )
        assertTrue("the line count must be given: $text", text.contains("lines=9"))
    }

    @Test
    fun `the pointer marks the transcript as optional`() {
        val text = SubagentResultEnvelope.render(
            facts(spans = listOf(
                span("Report", 23, 31, 1_800),
                span("Transcript", 33, 240, 195_000),
            )),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertTrue("the evidence range must be named: $text", text.contains("evidence: L33-L240"))
        assertTrue(
            "the parent must be told it is optional: $text",
            text.contains("only if you must verify"),
        )
    }

    /** Without a layout the pointer degrades — it must not invent offsets. */
    @Test
    fun `a pointer without a layout invents no offsets`() {
        val text = SubagentResultEnvelope.render(facts(spans = null), SubagentResultEnvelope.Framing.INLINE)

        assertTrue(
            "the path must still be named: $text",
            text.contains("Full report: /var/minis/workspace/.subagent/subagent-3.md"),
        )
        assertFalse("no offsets may be fabricated: $text", text.contains("offset="))
    }

    // ── 2. the conclusions come before the evidence ─────────────────────

    private fun runForIndex() = SubagentRunRegistry.Run(
        id = "subagent-3", sessionId = "s", skillId = "general-agent",
        skillName = "G", title = "", query = "the assignment",
        startedAtMs = 0, maxTurns = 75,
        status = SubagentRunRegistry.RunStatus.SUCCESS, blockId = "b",
    )

    /**
     * The journal used to be `Task -> Transcript (190 KB) -> Report (2 KB)`, so
     * every reader wanting the answer walked through the largest section first.
     */
    @Test
    fun `the report section precedes the transcript`() {
        val doc = SubagentJournalIndex.assemble(
            header = "h\n",
            sections = listOf(
                "Task" to "the assignment",
                "Report" to "summary: the answer",
                "Transcript" to "turn 1",
                "Steps" to "step 1",
            ),
        ) { spans ->
            SubagentJournalIndex.render(
                run = runForIndex(), terminal = "SUCCESS",
                resultText = "summary: the answer", turns = 3,
                artifacts = emptyList(), spans = spans,
            )
        }

        val reportIdx = doc.indexOf("## Report")
        val transcriptIdx = doc.indexOf("## Transcript")
        val stepsIdx = doc.indexOf("## Steps")
        assertTrue("the report must be before the transcript: $doc", reportIdx < transcriptIdx)
        assertTrue("the report must be before the steps: $doc", reportIdx < stepsIdx)
        assertTrue("the report must come after the task: $doc", doc.indexOf("## Task") < reportIdx)
    }

    // ── 3. the mid-run snapshot keeps its head ──────────────────────────

    /**
     * A cancelled / crashed / join-timed-out run delivers the registry's
     * streamed snapshot. The old bound was `takeLast(24k)`, which deleted the
     * HEAD — and the contract puts `summary:` at the head precisely so it
     * survives truncation.
     */
    @Test
    fun `a bounded snapshot keeps the summary and the closing turns`() {
        val head = "summary: the answer is 42\nstatus: done\nkey_findings:\n- it is 42 (a.kt:1)\n"
        val middle = "narration ".repeat(5000)
        val tail = "\nfinal note: done\n"

        val bounded = boundResultText(head + middle + tail)

        assertTrue(
            "the summary must survive: ${bounded.take(80)}",
            bounded.contains("summary: the answer is 42"),
        )
        assertTrue("the closing turns must survive", bounded.contains("final note: done"))
        assertTrue("the gap must be announced", bounded.contains("characters elided here"))
        assertTrue(
            "the bound must be respected",
            bounded.length <= SubagentRunRegistry.MAX_RESULT_TEXT_CHARS + 400,
        )
    }

    /** A short text is untouched — the bound must not add noise. */
    @Test
    fun `a short snapshot is unchanged`() {
        val text = "summary: fine\nstatus: done"
        assertEquals(text, boundResultText(text))
    }

    // ── 4. deliverables announce their size ─────────────────────────────

    @Test
    fun `an artifact names its size and what to do with it`() {
        val text = SubagentResultEnvelope.render(
            facts(
                artifacts = listOf("/r/report.md", "/r/data.csv"),
                sizes = mapOf(
                    "/r/report.md" to 12_000,
                    "/r/data.csv" to 480 * 1024,
                ),
            ),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertTrue("the small file must be marked readable: $text", text.contains("read it"))
        assertTrue(
            "the large file must be marked as query-only: $text",
            text.contains("query it, do not read it whole"),
        )
        assertTrue("both paths must be named: $text", text.contains("/r/report.md"))
        assertTrue(text.contains("/r/data.csv"))
    }

    /**
     * A path nobody measured renders BARE. An invented "0 B" about a file the
     * runtime never stat'ed would be a fabricated measurement — the same class
     * of lie as "cost: 0" for a provider that reported nothing.
     */
    @Test
    fun `an unmeasured artifact is named without a size`() {
        val text = SubagentResultEnvelope.render(
            facts(artifacts = listOf("/r/shell-written.md"), sizes = emptyMap()),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertTrue("the path must still be named: $text", text.contains("/r/shell-written.md"))
        assertFalse("no size may be invented: $text", text.contains("0 B"))
        assertFalse("no advice may be invented: $text", text.contains("read it)"))
    }

    /** The sizing seam reports -1 rather than guessing. */
    @Test
    fun `the sizing seam returns minus one for a missing file`() {
        assertEquals(-1, SubagentArtifactSize.of("/var/minis/definitely/not/here.md"))
    }

    /** ...and the real size for a real file. */
    @Test
    fun `the sizing seam measures a real file`() {
        val tmp = java.io.File.createTempFile("subagent-size", ".md")
        try {
            tmp.writeText("1234567890")
            assertEquals(10, SubagentArtifactSize.of(tmp.absolutePath))
        } finally {
            tmp.delete()
        }
    }
}
