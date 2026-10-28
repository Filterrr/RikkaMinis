package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-report-summary] The report contract, PARSED.
 *
 * Before this object existed the runtime consumed exactly one field of
 * the sub-agent's report contract — `status`, and only from the last ten
 * lines. `key_findings`, `gaps` and `artifacts` reached the parent as
 * prose, and `summary` did not exist as a field at all.
 *
 * These tests pin the PARSER's behaviour, including the places where it
 * must NOT be clever. A parser that guesses is worse than no parser: a
 * field it invents is a fact the parent will quote.
 */
class SubagentReportContractTest {

    @Test
    fun `a well-formed report yields every field`() {
        val parsed = SubagentReportContract.parse(
            """
            summary: The API returns 404 for missing repos; the rate limit is 60/min.
            status: done
            key_findings:
            - 404 is returned, not 403 (anchor: curl -s https://api/repos/x)
            - the limit is per-IP (anchor: docs/limits.md:12)
            gaps: pagination was not exercised
            artifacts: /var/minis/workspace/subagents/run-1/report.md
            """.trimIndent(),
        )

        assertEquals(
            "The API returns 404 for missing repos; the rate limit is 60/min.",
            parsed.summary,
        )
        assertEquals("done", parsed.status)
        assertEquals(2, parsed.findings.size)
        assertTrue(parsed.findings[0].contains("404 is returned"))
        assertEquals("pagination was not exercised", parsed.gaps)
        assertEquals(1, parsed.artifacts.size)
    }

    // ── the field grammar ────────────────────────────────────────────────

    /**
     * A bullet belongs to the PREVIOUS field's body. If a bullet could
     * start a field, then a `key_findings:` entry containing a colon
     * ("- the api: returns 404") would silently become a field and cut
     * the real field's body in half.
     */
    @Test
    fun `a list bullet does not start a field`() {
        val parsed = SubagentReportContract.parse(
            """
            key_findings:
            - the api: returns 404
            - the limit: 60/min
            gaps: none
            """.trimIndent(),
        )

        assertEquals(2, parsed.findings.size)
        assertTrue("the bullet body must stay whole", parsed.findings[0].contains("the api: returns 404"))
        assertNull("a bullet must not become the status", parsed.status)
    }

    @Test
    fun `an indented line is body, not a field`() {
        val parsed = SubagentReportContract.parse(
            """
            summary: first line
              continuation: still the summary
            """.trimIndent(),
        )

        assertEquals("first line continuation: still the summary", parsed.summary)
    }

    /** The existing status parser accepts the Chinese labels; this one must agree. */
    @Test
    fun `the chinese labels are accepted`() {
        val parsed = SubagentReportContract.parse(
            """
            摘要: 找到了两个版本
            状态: partial
            关键发现:
            - 第三个供应商不可达
            """.trimIndent(),
        )

        assertEquals("找到了两个版本", parsed.summary)
        assertEquals("partial", parsed.status)
        assertEquals(1, parsed.findings.size)
    }

    /**
     * `status` is read by this object's own reader, which reports the
     * FULL vocabulary ("done" included). The existing
     * [SubagentSkill.parseReportStatus] answers a different question
     * ("does the model want the parent warned?") and returns null for
     * "done" on purpose. This test pins that the two agree on the tokens
     * they BOTH answer for — "partial" and "failed" — so the two
     * readers cannot drift apart on what a token means.
     */
    @Test
    fun `the status matches the existing status parser`() {
        val partial = "prose\n\nstatus: partial\nkey_findings: x (a)\ngaps: y\nartifacts: none"
        assertEquals(
            SubagentSkill.parseReportStatus(partial),
            SubagentReportContract.parse(partial).status,
        )

        val failed = "status: failed (no provider context)\nkey_findings: none"
        assertEquals(
            SubagentSkill.parseReportStatus(failed),
            SubagentReportContract.parse(failed).status,
        )
    }

    /** "done" is a declaration, and this reader reports it. */
    @Test
    fun `a done status is reported as done`() {
        val parsed = SubagentReportContract.parse(
            """
            summary: all good
            status: done
            key_findings:
            - it worked
            """.trimIndent(),
        )
        assertEquals("done", parsed.status)
    }

    // ── the summary fallback ─────────────────────────────────────────────

    /**
     * The fallback is what rescues a skill that has not learned the new
     * field yet: the first key finding becomes the summary. Without it,
     * every existing sub-agent would deliver a summary-less report until
     * the skills were re-read.
     */
    @Test
    fun `a missing summary falls back to the first finding`() {
        val parsed = SubagentReportContract.parse(
            """
            status: done
            key_findings:
            - the limit is 60/min (anchor: docs/limits.md)
            - pagination was not exercised
            """.trimIndent(),
        )

        assertEquals("the limit is 60/min (anchor: docs/limits.md)", parsed.summary)
        assertEquals(
            "a derived summary must be marked as derived",
            SubagentReportContract.SummarySource.KEY_FINDINGS,
            parsed.summarySource,
        )
    }

    /** With no summary and no findings there is nothing to fall back to. */
    @Test
    fun `a report with neither summary nor findings has no summary`() {
        val parsed = SubagentReportContract.parse(
            """
            status: done
            gaps: nothing found
            """.trimIndent(),
        )

        assertNull(parsed.summary)
        assertTrue(parsed.findings.isEmpty())
        assertTrue("absence must be visible", !parsed.summaryAvailable)
    }

    // ── honesty ──────────────────────────────────────────────────────────

    /**
     * Prose with no contract fields is not a contract. The parser must
     * not manufacture one — a summary it invented would be quoted by the
     * parent as the model's own conclusion.
     */
    @Test
    fun `prose without fields yields no fields`() {
        val parsed = SubagentReportContract.parse("I looked around and found some things.")

        assertNull(parsed.summary)
        assertNull(parsed.status)
        assertTrue(parsed.findings.isEmpty())
        assertNull(parsed.gaps)
        assertTrue(parsed.artifacts.isEmpty())
    }

    @Test
    fun `a blank report yields no fields`() {
        val parsed = SubagentReportContract.parse("   \n  \n")
        assertNull(parsed.summary)
        assertTrue(parsed.findings.isEmpty())
    }

    /**
     * The source flag distinguishes "the model wrote a summary" from "the
     * runtime derived one" — a parent that quotes a derived summary as
     * the model's own words is misquoting it.
     */
    @Test
    fun `an authored summary is marked as authored`() {
        val authored = SubagentReportContract.parse(
            """
            summary: mine
            key_findings:
            - a finding
            """.trimIndent(),
        )
        assertTrue(authored.summaryIsModelAuthored)
        assertEquals(SubagentReportContract.SummarySource.MODEL, authored.summarySource)
    }
}
