package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-truncation-envelope] Truncation must cost the parent
 * the REASONING, never the CONCLUSIONS.
 *
 * The old rule was `report.take(maxChars)` - keep the head, drop the
 * tail. The contract's field order is `summary / status / key_findings /
 * gaps / artifacts`, so the head is the summary and the tail is the
 * gaps and the artifacts: the cut landed EXACTLY on the two most
 * actionable fields.
 *
 * The new rule: the envelope (facts) and the contract (summary,
 * findings, gaps, artifacts) are never cut. Only the BODY - the prose
 * that is not part of any contract field - may be cut, and the cut is
 * announced with how much was dropped and where the rest lives.
 */
class SubagentTruncationEnvelopeTest {

    private fun reportWithProse(proseLength: Int) = buildString {
        appendLine("summary: The API returns 404 for missing repos.")
        appendLine("status: done")
        appendLine("key_findings:")
        appendLine("- 404 not 403 (anchor: curl -s https://api/repos/x)")
        appendLine("- the limit is per-IP (anchor: docs/limits.md:12)")
        appendLine("gaps: pagination was not exercised")
        appendLine("artifacts: /var/minis/workspace/subagents/run-1/report.md")
        appendLine()
        append("x".repeat(proseLength))
    }

    private fun render(report: String, maxChars: Int = 6000): String {
        val parsed = SubagentReportContract.parse(report)
        return SubagentResultEnvelope.render(
            facts = SubagentResultEnvelope.Facts(
                runId = "run-1",
                skillName = "general-agent",
                outcome = SubagentRunAssessment.Outcome.COMPLETED,
                reportSource = SubagentRunAssessment.ReportSource.MODEL,
                executedToolCalls = 3,
                turns = 2,
                maxTurns = 12,
                report = report,
                parsed = parsed,
                journalPath = "/var/minis/workspace/.subagent/run-1.md",
            ),
            framing = SubagentResultEnvelope.Framing.INLINE,
            bodyMaxChars = maxChars,
        )
    }

    /**
     * THE core property: a cut body must not take the findings with it.
     *
     * This is the failure the old `take()` rule caused: a report over
     * the cap lost its gaps and its artifacts - the two fields that
     * tell the parent what was NOT done and what to read next.
     */
    @Test
    fun `a truncated body keeps the findings, the gaps and the artifacts`() {
        val text = render(reportWithProse(50_000))

        assertTrue("the body must be cut: $text", text.contains("truncated"))
        assertTrue("the summary must survive: $text", text.contains("summary: The API returns 404"))
        assertTrue("the findings must survive: $text", text.contains("404 not 403 (anchor:"))
        assertTrue("the second finding must survive: $text", text.contains("the limit is per-IP (anchor:"))
        assertTrue("the gaps must survive: $text", text.contains("gaps: pagination was not exercised"))
        assertTrue("the artifacts must survive: $text", text.contains("artifacts: /var/minis/workspace/subagents/run-1/report.md"))
    }

    /**
     * The findings must not appear TWICE - once as structure, once
     * inside the body. The parent pays for every duplicate in its
     * window.
     */
    @Test
    fun `the findings are not duplicated in the body`() {
        val text = render(reportWithProse(200))

        val occurrences = "404 not 403 (anchor:".let { needle ->
            var count = 0
            var index = text.indexOf(needle)
            while (index >= 0) {
                count++
                index = text.indexOf(needle, index + needle.length)
            }
            count
        }
        assertTrue("the findings must appear exactly once, found $occurrences\n$text", occurrences == 1)
    }

    /**
     * The prose is what the structured fields cannot carry - the
     * reasoning. It must still reach the parent when it fits.
     */
    @Test
    fun `the prose reaches the parent when it fits`() {
        val text = render("summary: short\n\nthe reasoning behind it")
        assertTrue("the prose must be rendered: $text", text.contains("the reasoning behind it"))
    }

    /** The cut must say HOW MUCH was dropped. */
    @Test
    fun `the cut says how much was dropped`() {
        val text = render(reportWithProse(50_000), maxChars = 1000)
        assertTrue("the marker must say what was kept: $text", text.contains("1000 of "))
        assertTrue("the marker must say what was dropped: $text", text.contains("chars dropped"))
    }

    /** The cut must name the recovery route. */
    @Test
    fun `the cut names the recovery route`() {
        val text = render(reportWithProse(50_000), maxChars = 1000)
        assertTrue("the cut must name the journal: $text", text.contains("file_read /var/minis/workspace/.subagent/run-1.md"))
    }

    /**
     * The marker must say that the contract was NOT cut - otherwise the
     * parent cannot tell "the body was cut" from "some of my findings
     * are missing".
     */
    @Test
    fun `the cut says the contract was not cut`() {
        val text = render(reportWithProse(50_000), maxChars = 1000)
        assertTrue("the marker must say what was NOT cut: $text", text.contains("are NOT cut"))
        // The marker must name the FOUR contract fields by name — a
        // parent that reads "something was not cut" cannot act on it; a
        // parent that reads "the summary, findings, gaps and artifacts
        // are NOT cut" knows exactly what it still has.
        assertTrue(
            "the marker must name the fields that survive: $text",
            text.contains("summary, findings, gaps and artifacts") &&
                text.contains("are NOT cut"),
        )
    }

    /**
     * A report with NO contract fields has no prose - the body IS the
     * report, and the cut applies to it as before. Otherwise a
     * runtime-assembled digest (which is not a contract) would lose its
     * entire content.
     */
    @Test
    fun `a report with no contract fields is still cut as a body`() {
        val text = render("y".repeat(50_000), maxChars = 1000)
        assertTrue("the body must be cut: $text", text.contains("truncated"))
        assertTrue("the body must be rendered: $text", text.contains("y".repeat(50)))
    }

    /**
     * The envelope layer - the facts - is never cut either. A parent
     * that lost the model / token / turn line would not know what it
     * spent.
     */
    @Test
    fun `the facts survive a truncated body`() {
        val text = render(reportWithProse(50_000), maxChars = 1000)
        assertTrue("the model must survive: $text", text.contains("turns: 2/12"))
        assertTrue("the skill must survive: $text", text.contains("general-agent"))
    }

    /** No cut, no marker. */
    @Test
    fun `a body that fits is not marked as cut`() {
        val text = render(reportWithProse(10))
        assertFalse("a body that fits must not be marked: $text", text.contains("truncated"))
    }
}
