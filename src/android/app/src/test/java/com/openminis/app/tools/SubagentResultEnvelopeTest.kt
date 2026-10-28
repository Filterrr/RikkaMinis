package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-result-envelope] ONE renderer for a sub-agent's result.
 *
 * The property that matters: the four delivery paths must show the
 * parent the SAME facts in the SAME order, so a parent that learned the
 * shape on the inline path can read the wake / join / wait_any output
 * without re-learning it.
 *
 * These tests pin the envelope's LAYERS - the envelope, then the
 * contract, then the body, then the pointers - because the layer order
 * is the whole point: the actionable part must come before the part that
 * may be cut.
 */
class SubagentResultEnvelopeTest {

    private fun facts(
        outcome: SubagentRunAssessment.Outcome = SubagentRunAssessment.Outcome.COMPLETED,
        reportSource: SubagentRunAssessment.ReportSource = SubagentRunAssessment.ReportSource.MODEL,
        report: String = "the report body",
        parsed: SubagentReportContract.ParsedReport? = null,
        artifacts: List<String> = emptyList(),
        journalPath: String? = "/var/minis/workspace/.subagent/run-1.md",
        error: String? = null,
        declaredStatus: String? = null,
        modelLabel: String = "GPT-5 mini",
        tokensIn: Int = 1200,
        tokensOut: Int = 340,
        turns: Int = 4,
        maxTurns: Int = 12,
        executedToolCalls: Int = 7,
    ) = SubagentResultEnvelope.Facts(
        runId = "run-1",
        skillName = "general-agent",
        outcome = outcome,
        reportSource = reportSource,
        executedToolCalls = executedToolCalls,
        turns = turns,
        maxTurns = maxTurns,
        modelLabel = modelLabel,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        report = report,
        parsed = parsed,
        artifacts = artifacts,
        journalPath = journalPath,
        error = error,
        declaredStatus = declaredStatus,
    )

    private fun parsedReport() = SubagentReportContract.parse(
        """
        summary: The API returns 404 for missing repos.
        status: done
        key_findings:
        - 404 not 403 (anchor: curl -s https://api/repos/x)
        gaps: pagination was not exercised
        """.trimIndent(),
    )

    // ── the layer order ─────────────────────────────────────────────────

    /**
     * The order IS the contract. The summary answers the parent's
     * question, so it must come BEFORE the body - otherwise a
     * truncation of the body eats the one part that was worth reading.
     */
    @Test
    fun `the contract comes before the body`() {
        val text = SubagentResultEnvelope.render(
            facts(parsed = parsedReport()),
            SubagentResultEnvelope.Framing.INLINE,
        )

        val summaryIdx = text.indexOf("summary: The API returns 404")
        val bodyIdx = text.indexOf("---")
        assertTrue("the summary must be rendered: $text", summaryIdx >= 0)
        assertTrue("the body must be rendered: $text\n---\n", bodyIdx >= 0)
        assertTrue("the summary must precede the body", summaryIdx < bodyIdx)
    }

    /** The envelope (the facts) comes before the contract. */
    @Test
    fun `the envelope comes before the contract`() {
        val text = SubagentResultEnvelope.render(
            facts(parsed = parsedReport()),
            SubagentResultEnvelope.Framing.INLINE,
        )

        val costIdx = text.indexOf("cost:")
        val summaryIdx = text.indexOf("summary:")
        assertTrue("the cost line must be rendered: $text", costIdx >= 0)
        assertTrue("the summary must be rendered: $text", summaryIdx >= 0)
        assertTrue("the envelope must precede the contract", costIdx < summaryIdx)
    }

    /** The pointers come last - they are the recovery route. */
    @Test
    fun `the pointers come after the body`() {
        val text = SubagentResultEnvelope.render(
            facts(report = "the report body"),
            SubagentResultEnvelope.Framing.INLINE,
        )

        val bodyIdx = text.indexOf("---")
        val journalIdx = text.indexOf("/var/minis/workspace/.subagent/run-1.md")
        assertTrue("the journal must be named: $text", journalIdx >= 0)
        assertTrue("the journal must come after the body", bodyIdx < journalIdx)
    }

    // ── the envelope layer ──────────────────────────────────────────────

    @Test
    fun `the envelope names the run, the model, the tokens and the turns`() {
        val text = SubagentResultEnvelope.render(
            facts(),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertTrue("the skill must be named: $text", text.contains("general-agent"))
        assertTrue("the model must be named: $text", text.contains("model: GPT-5 mini"))
        assertTrue("the tokens must be named: $text", text.contains("tokens: in=1200 out=340"))
        assertTrue("the turns must be named: $text", text.contains("turns: 4/12"))
    }

    /**
     * A provider that never reports usage must render as "not reported",
     * NEVER as 0 - a zero cost claim is a measurement the runtime does
     * not have.
     */
    @Test
    fun `an unreported cost is not reported, never zero`() {
        val text = SubagentResultEnvelope.render(
            facts(tokensIn = -1, tokensOut = -1, modelLabel = ""),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertFalse("-1 must never surface as a token count: $text", text.contains("in=-1"))
        assertFalse("an unknown cost must not be rendered as zero: $text", text.contains("tokens: in=0"))
        assertTrue("the absence must be stated: $text", text.contains("cost: not reported by the provider"))
    }

    // ── the contract layer ──────────────────────────────────────────────

    @Test
    fun `the contract layer carries the summary, the findings, the gaps and the artifacts`() {
        val text = SubagentResultEnvelope.render(
            facts(parsed = parsedReport(), artifacts = listOf("/a.md")),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertTrue("the summary must be rendered: $text", text.contains("summary: The API returns 404"))
        assertTrue("the gaps must be rendered: $text", text.contains("gaps: pagination was not exercised"))
        assertTrue("the artifacts must be rendered: $text", text.contains("artifacts: /a.md"))
    }

    /**
     * [feat/subagent-truncation-envelope] The findings — the
     * conclusions WITH their evidence anchors — must be rendered.
     *
     * The envelope's documentation promised `findings` from the first
     * PR, and the code never rendered them: they were parsed, stored,
     * and then dropped at the exact moment they were supposed to reach
     * the parent. A parent that gets the summary and the gaps but not
     * the findings gets the bottom line without the proof.
     *
     * This assertion is what makes that regression impossible to
     * reintroduce silently — the negative verification caught the gap.
     */
    @Test
    fun `the findings are rendered with their anchors`() {
        val text = SubagentResultEnvelope.render(
            facts(parsed = parsedReport()),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertTrue("the findings must be rendered: $text", text.contains("findings:"))
        assertTrue(
            "the finding must be rendered with its anchor: $text",
            text.contains("404 not 403 (anchor: curl -s https://api/repos/x)"),
        )
    }

    /**
     * A report with no summary says so. Silence would read as "there is
     * nothing to say", which is a different claim from "the sub-agent
     * did not write one".
     */
    @Test
    fun `a report with no summary says so`() {
        val text = SubagentResultEnvelope.render(
            facts(parsed = null),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertTrue("the absence must be stated: $text", text.contains("summary: not reported"))
    }

    /**
     * A DERIVED summary is labelled. The parent must be able to tell the
     * model's own bottom line from the runtime's guess at one.
     */
    @Test
    fun `a derived summary is labelled as derived`() {
        val derived = SubagentReportContract.parse(
            """
            status: done
            key_findings:
            - the limit is 60/min (anchor: docs/limits.md)
            """.trimIndent(),
        )
        val text = SubagentResultEnvelope.render(
            facts(parsed = derived),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertTrue("a derived summary must say so: $text", text.contains("derived from the first key finding"))
    }

    // ── the body layer ──────────────────────────────────────────────────

    /**
     * The inline path used to have NO budget. It has one now, and it is
     * the same as the join path's.
     */
    @Test
    fun `the inline body is bounded`() {
        val huge = "x".repeat(50_000)
        val text = SubagentResultEnvelope.render(
            facts(report = huge),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertTrue("the body must be capped", text.length < huge.length)
        assertTrue("the cut must be announced: $text", text.contains("truncated"))
    }

    /**
     * A body that is not a complete account must say so. A parent that
     * reads a partial body as a complete one treats a half-finished
     * run's conclusions as final.
     */
    @Test
    fun `a partial body is marked as partial`() {
        val text = SubagentResultEnvelope.render(
            facts(
                outcome = SubagentRunAssessment.Outcome.TIMED_OUT,
                reportSource = SubagentRunAssessment.ReportSource.PARTIAL,
                report = "half-done notes",
            ),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertTrue("a partial body must be marked: $text", text.contains("Partial output"))
    }

    @Test
    fun `a complete body is not marked as partial`() {
        val text = SubagentResultEnvelope.render(
            facts(report = "the whole thing"),
            SubagentResultEnvelope.Framing.INLINE,
        )

        assertFalse("a complete body must not be marked partial: $text", text.contains("Partial output"))
    }

    // ── the framings ────────────────────────────────────────────────────

    /**
     * The framing differs per path; the CONTENT does not. This test pins
     * the property that makes the four paths learnable: same facts,
     * same order, different first line.
     */
    @Test
    fun `every framing carries the same facts`() {
        val framings = listOf(
            SubagentResultEnvelope.Framing.INLINE,
            SubagentResultEnvelope.Framing.EARLY_READOUT,
            SubagentResultEnvelope.Framing.WAKE,
            SubagentResultEnvelope.Framing.JOIN,
            SubagentResultEnvelope.Framing.WAIT_ANY,
        )

        for (framing in framings) {
            val text = SubagentResultEnvelope.render(
                facts(parsed = parsedReport(), artifacts = listOf("/a.md")),
                framing,
            )
            assertTrue("$framing must carry the model: $text", text.contains("model: GPT-5 mini"))
            assertTrue("$framing must carry the summary: $text", text.contains("summary: The API returns 404"))
            assertTrue("$framing must carry the gaps: $text", text.contains("gaps: pagination was not exercised"))
            assertTrue("$framing must carry the artifacts: $text", text.contains("artifacts: /a.md"))
            assertTrue("$framing must carry the body section: $text", text.contains("---"))
            assertTrue("$framing must carry the journal: $text", text.contains("/var/minis/workspace/.subagent/run-1.md"))
        }
    }

    /**
     * The wake-up must name the run: the parent's next move is usually
     * join_subagents(run_ids=...) or cancel_subagents, and without the
     * id it cannot name the run it is talking about.
     */
    @Test
    fun `the wake framing names the run id`() {
        val text = SubagentResultEnvelope.render(
            facts(),
            SubagentResultEnvelope.Framing.WAKE,
        )
        assertTrue("the wake must name the run: $text", text.contains("run_id: run-1"))
    }

    /** An early readout is a PEEK, not a finished task. */
    @Test
    fun `the early readout framing says it is an early readout`() {
        val text = SubagentResultEnvelope.render(
            facts(),
            SubagentResultEnvelope.Framing.EARLY_READOUT,
        )
        assertTrue("the early readout must say so: $text", text.contains("first-turn readout"))
    }
}
