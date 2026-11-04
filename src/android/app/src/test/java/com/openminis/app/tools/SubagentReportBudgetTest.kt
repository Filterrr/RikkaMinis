package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-report-budget] Every path that hands a sub-agent's report to the
 * parent turn must respect a size budget, and a truncation must always name
 * its recovery route.
 *
 * The bug this pins: the detached wake-up path was carefully capped at 4k
 * chars while the JOIN path (and wait_any) spliced `outcome.report` verbatim —
 * and those reports come from a registry whose per-run cap is 24k, with the
 * inline path unbounded. One join of four research runs could therefore spend
 * ~96k chars of the parent's window on a single tool result.
 */
class SubagentReportBudgetTest {

    /**
     * The upper bound on the truncation marker's length.
     *
     * The marker names the cap, the original size, the dropped count
     * and the recovery route — a few hundred characters at most. The
     * budget tests must allow for the marker WITHOUT becoming a place
     * where the marker's own size is hard-coded twice.
     */
    private companion object {
        const val TRUNCATION_MARKER_MAX_CHARS = 400

        /**
         * [fix/subagent-envelope-facts] Everything the envelope renders AROUND
         * the body: the framing line, the verdict headline, the cost line, the
         * turn line, the five contract lines and the journal pointer.
         *
         * Bounded by construction (the contract fields have their own budgets
         * — see [SubagentResultEnvelope.MAX_SUMMARY_CHARS] & co), and stated
         * here so the bound is a total budget rather than "the body cap,
         * plus whatever the envelope happens to add today".
         */
        const val ENVELOPE_OVERHEAD_MAX_CHARS = 1500
    }

    private fun outcome(
        report: String,
        journalPath: String? = null,
        success: Boolean = true,
    ) = SubagentOrchestration.JobOutcome(
        runId = "run-1",
        success = success,
        report = report,
        skillName = "general-agent",
        journalPath = journalPath,
    )

    // ── the cap ──────────────────────────────────────────────────────────

    @Test
    fun `a report under the cap is delivered verbatim`() {
        val report = "short and complete"
        assertEquals(report, SubagentResultEnvelope.boundBody(report, journalPath = null, maxChars = 100))
    }

    @Test
    fun `a report at exactly the cap is untouched`() {
        val report = "x".repeat(50)
        val bounded = SubagentResultEnvelope.boundBody(report, journalPath = null, maxChars = 50)
        assertEquals(report, bounded)
        assertFalse("an exact fit is not a truncation", bounded.contains("truncated"))
    }

    @Test
    fun `an oversized report is truncated to the cap`() {
        val report = "y".repeat(500)
        val bounded = SubagentResultEnvelope.boundBody(report, journalPath = null, maxChars = 100)

        assertTrue("must be cut", bounded.length < report.length)
        assertTrue("the report body survives up to the cap", bounded.startsWith("y".repeat(100)))
        assertTrue("the cut must be announced", bounded.contains("truncated"))
    }

    /**
     * A truncation without a way back would let the parent treat a partial
     * report as the whole finding — the precise failure the report-hygiene
     * workstream exists to prevent. A journaled run names its journal.
     */
    @Test
    fun `a truncation with a journal names the journal as the recovery route`() {
        val bounded = SubagentResultEnvelope.boundBody(
            "z".repeat(500), "/var/minis/workspace/.subagent/run-1.md", maxChars = 100,
        )

        assertTrue("recovery must be actionable", bounded.contains("file_read"))
        assertTrue(bounded.contains("/var/minis/workspace/.subagent/run-1.md"))
    }

    /** Without a journal the note must still be honest about what was lost. */
    @Test
    fun `a truncation without a journal says so and suggests a fix`() {
        val bounded = SubagentResultEnvelope.boundBody("z".repeat(500), journalPath = null, maxChars = 100)

        assertTrue("must not promise a journal that does not exist", !bounded.contains("file_read"))
        assertTrue("must state the gap", bounded.contains("not journaled") || bounded.contains("re-spawn"))
    }

    // ── wait_any uses the same budget ────────────────────────────────────

    /**
     * [fix/subagent-envelope-facts] wait_any renders through the ONE envelope
     * now — `boundWaitAnyReport` had no production caller left and is gone.
     * The property it guarded (the race path must not be the one unbounded
     * sink) is pinned here against the renderer that actually runs.
     */
    @Test
    fun `wait_any winner reports obey the same cap`() {
        val facts = envelopeFacts(outcome("w".repeat(50_000)), null)
        val text = SubagentResultEnvelope.render(
            facts,
            SubagentResultEnvelope.Framing.WAIT_ANY,
            bodyMaxChars = JOIN_REPORT_MAX_CHARS,
        )

        assertTrue(
            "wait_any must not be the one unbounded sink: ${text.length} chars",
            text.length <= JOIN_REPORT_MAX_CHARS + TRUNCATION_MARKER_MAX_CHARS + ENVELOPE_OVERHEAD_MAX_CHARS,
        )
        assertTrue("the cut must be announced", text.contains("truncated"))
    }

    @Test
    fun `wait_any renders a placeholder for an empty report`() {
        val text = SubagentResultEnvelope.render(
            envelopeFacts(outcome(""), null),
            SubagentResultEnvelope.Framing.WAIT_ANY,
            bodyMaxChars = JOIN_REPORT_MAX_CHARS,
        )
        assertTrue(text.contains("no text output produced"))
    }

    @Test
    fun `the join cap is smaller than the registry per-run cap`() {
        // If the join budget ever exceeded a single run's stored text, the
        // bound would be decorative for the common case.
        assertTrue(JOIN_REPORT_MAX_CHARS < SubagentRunRegistry.MAX_RESULT_TEXT_CHARS)
    }

    // ── wake path still bounded ──────────────────────────────────────────

    @Test
    fun `the wake prompt bounds the report and names the journal`() {
        val prompt = buildWakePrompt(
            outcome = outcome("q".repeat(20_000), "/var/minis/workspace/.subagent/run-1.md"),
            snapshot = null,
            maxReportChars = 500,
        )

        assertTrue(prompt.contains("q".repeat(500)))
        assertTrue("the cut must be announced", prompt.contains("truncated"))
        // The footer already names the journal for anything truncated above.
        assertTrue(prompt.contains("Full report: /var/minis/workspace/.subagent/run-1.md"))
    }
}
