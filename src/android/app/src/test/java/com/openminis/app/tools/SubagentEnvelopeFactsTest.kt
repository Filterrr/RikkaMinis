package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-envelope-facts] The envelope's facts must reach EVERY
 * delivery path.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The sub-agent workstream shipped one envelope and four paths, and the
 * envelope renders fields the paths have to SUPPLY:
 *
 *     turns: 20/75
 *     cost: model: glm-5.2, tokens: in=1200 out=340
 *     === run subagent-3 (general-agent) CANCELLED ===
 *
 * The inline path had all of it. The join and wait_any paths passed
 * `snapshot = null`, so `turns` rendered 0 and the cost line rendered "not
 * reported by the provider" — for runs whose numbers the registry had known
 * the whole time. Every unit test stayed green: they all built `Facts`
 * directly, which is exactly the layer that was missing the data.
 *
 * So these tests go through the PRODUCTION fact builder
 * ([envelopeFacts]) and assert what the PARENT would actually read.
 */
class SubagentEnvelopeFactsTest {

    private fun outcome(
        runId: String = "subagent-3",
        report: String = "summary: all good\nstatus: done\nkey_findings:\n- a (x.md)\ngaps: none",
        cancelled: Boolean = false,
        parsed: SubagentReportContract.ParsedReport? = SubagentReportContract.parse(report),
    ) = SubagentOrchestration.JobOutcome(
        runId = runId,
        success = true,
        report = report,
        skillName = "general-agent",
        journalPath = "/var/minis/workspace/.subagent/$runId.md",
        cancelled = cancelled,
        outcome = if (cancelled) {
            SubagentRunAssessment.Outcome.FAILED
        } else {
            SubagentRunAssessment.Outcome.COMPLETED
        },
        reportSource = SubagentRunAssessment.ReportSource.MODEL,
        executedToolCalls = 12,
        // [fix/subagent-envelope-facts] The facts the renderer needs travel
        // WITH the outcome — this is the fix, so the fixture has to carry them.
        turns = 20,
        maxTurns = 75,
        parsed = parsed,
    )

    // ── turns ────────────────────────────────────────────────────────────

    /**
     * The join / wait_any surfaces have no registry snapshot in hand — the
     * outcome must carry the turn count itself, or the parent reads
     * "turns: 0" for a run that spent twenty.
     */
    @Test
    fun `the outcome carries the turn count when there is no snapshot`() {
        val facts = envelopeFacts(outcome(), snapshot = null)

        assertEquals(20, facts.turns)
        assertEquals(75, facts.maxTurns)
    }

    @Test
    fun `a rendered join envelope shows the real turn budget`() {
        val text = SubagentResultEnvelope.render(
            envelopeFacts(outcome(), snapshot = null),
            SubagentResultEnvelope.Framing.JOIN,
            bodyMaxChars = JOIN_REPORT_MAX_CHARS,
        )

        assertTrue("the turn pair must be rendered: $text", text.contains("turns: 20/75"))
        assertFalse(
            "a run that spent 20 turns must never render `turns: 0`: $text",
            text.contains("turns: 0\n"),
        )
    }

    /**
     * The snapshot stays a FALLBACK: a run whose outcome predates the facts
     * (or a detached outcome assembled from a pruned row) still renders what
     * the registry knows.
     */
    @Test
    fun `the registry snapshot is the fallback for the turn count`() {
        val registry = SubagentRunRegistry()
        val run = registry.register("b1", "general-agent", "general-agent", "q", "t", 40)
        registry.turnStarted(run.id, 7)

        val facts = envelopeFacts(
            outcome(runId = run.id).copy(turns = 0, maxTurns = 0),
            registry.runs.value.firstOrNull { it.id == run.id },
        )

        assertEquals(7, facts.turns)
        assertEquals(40, facts.maxTurns)
    }

    // ── cost ─────────────────────────────────────────────────────────────

    @Test
    fun `the cost line names the model and the tokens on the join path`() {
        val facts = envelopeFacts(
            outcome().copy(modelLabel = "glm-5.2", tokensIn = 1200, tokensOut = 340),
            snapshot = null,
        )
        val text = SubagentResultEnvelope.render(
            facts,
            SubagentResultEnvelope.Framing.JOIN,
            bodyMaxChars = JOIN_REPORT_MAX_CHARS,
        )

        assertTrue("the model must be named: $text", text.contains("model: glm-5.2"))
        assertTrue("the tokens must be named: $text", text.contains("tokens: in=1200 out=340"))
        assertFalse(
            "a measured cost must not be reported as unknown: $text",
            text.contains("cost: not reported by the provider"),
        )
    }

    /** An unreported cost is still "unknown", never a fabricated zero. */
    @Test
    fun `an unreported cost stays unknown`() {
        val facts = envelopeFacts(outcome(), snapshot = null)
        assertEquals(-1, facts.tokensIn)
        assertEquals(-1, facts.tokensOut)
        val text = SubagentResultEnvelope.render(
            facts, SubagentResultEnvelope.Framing.WAKE, bodyMaxChars = 4000,
        )
        assertTrue(text.contains("cost: not reported by the provider"))
    }

    // ── runtime status ───────────────────────────────────────────────────

    /**
     * A cancelled run is CANCELLED on every path.
     *
     * The old code folded `cancelled` into Outcome.FAILED and ALSO hardcoded
     * `runtimeStatus = null` on wait_any, so the one word the parent uses to
     * choose between "re-spawn" and "the user stopped it" was unreachable.
     */
    @Test
    fun `a cancelled run is announced as cancelled on join and wait_any`() {
        val facts = envelopeFacts(outcome(cancelled = true), snapshot = null)

        val join = SubagentResultEnvelope.render(
            facts, SubagentResultEnvelope.Framing.JOIN, bodyMaxChars = JOIN_REPORT_MAX_CHARS,
        )
        val waitAny = SubagentResultEnvelope.render(
            facts, SubagentResultEnvelope.Framing.WAIT_ANY, bodyMaxChars = JOIN_REPORT_MAX_CHARS,
        )

        assertTrue("the join must say CANCELLED: $join", join.startsWith("=== run subagent-3 (general-agent) CANCELLED ==="))
        assertTrue("the race must say CANCELLED: $waitAny", waitAny.contains("CANCELLED"))
        assertTrue(
            "a user cancel must be announced as a cancel, not a crash: $join",
            join.contains("[cancelled] the run was stopped before it finished"),
        )
        assertFalse(
            "a user cancel is not a crash: $join",
            join.contains("[failed]"),
        )
    }

    /** The runtime status travels with the outcome even without a snapshot. */
    @Test
    fun `the runtime status survives without a snapshot`() {
        val facts = envelopeFacts(
            outcome().copy(runtimeStatus = "TIMED_OUT"),
            snapshot = null,
        )
        val text = SubagentResultEnvelope.render(
            facts, SubagentResultEnvelope.Framing.JOIN, bodyMaxChars = JOIN_REPORT_MAX_CHARS,
        )
        assertTrue("a timed-out run must say so: $text", text.contains("TIMED_OUT"))
    }

    // ── parsed contract on the non-success paths ─────────────────────────

    /**
     * [fix/subagent-parsed-on-every-path] Every terminal write parses the
     * delivered contract — not only the SUCCESS one.
     *
     * A timed-out run's partial report is the ONLY thing the parent gets, and
     * the 75-turn budget makes timing out the expected end of a long run.
     * Before this, the registry stored no parsed report on that path, so the
     * detached surfaces announced "summary: not reported" over a report whose
     * summary was written in plain sight.
     */
    @Test
    fun `a terminal write parses the report on every terminal status`() {
        val report = "summary: did half the work\nstatus: partial\nkey_findings:\n- half done (a.md)\ngaps: the rest"

        val terminalStatuses = listOf(
            SubagentRunRegistry.RunStatus.SUCCESS,
            SubagentRunRegistry.RunStatus.FAILED,
            SubagentRunRegistry.RunStatus.CANCELLED,
            SubagentRunRegistry.RunStatus.TIMED_OUT,
        )
        terminalStatuses.forEach { status ->
            val registry = SubagentRunRegistry()
            val run = registry.register("b1", "general-agent", "general-agent", "q", "t", 75)

            registry.finishIfActive(
                run.id, status,
                resultText = report,
                reportSource = SubagentRunAssessment.ReportSource.PARTIAL,
            )

            val parsed = registry.runs.value.single().parsedReport
            assertTrue(
                "$status must keep the parsed summary: got $parsed",
                parsed?.summary == "did half the work",
            )
        }
    }

    /** A runtime digest is not a contract and must not be parsed into one. */
    @Test
    fun `a runtime digest is never parsed into a contract`() {
        val registry = SubagentRunRegistry()
        val run = registry.register("b1", "g", "g", "q", "t", 75)

        registry.finishIfActive(
            run.id, SubagentRunRegistry.RunStatus.SUCCESS,
            resultText = "summary: this text is raw tool output, not the model's report",
            reportSource = SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
        )

        assertNull(
            "a digest must not manufacture contract fields",
            registry.runs.value.single().parsedReport,
        )
    }

    /** An empty delivery has nothing to parse — absence stays absent. */
    @Test
    fun `an empty delivery keeps the parsed report absent`() {
        val registry = SubagentRunRegistry()
        val run = registry.register("b1", "g", "g", "q", "t", 75)

        registry.finishIfActive(
            run.id, SubagentRunRegistry.RunStatus.TIMED_OUT,
            reportSource = SubagentRunAssessment.ReportSource.NONE,
        )

        assertNull(registry.runs.value.single().parsedReport)
    }

    /** A parsed report stored explicitly is never overwritten by a later write. */
    @Test
    fun `an explicitly stored parsed report wins`() {
        val registry = SubagentRunRegistry()
        val run = registry.register("b1", "g", "g", "q", "t", 75)
        val explicit = SubagentReportContract.parse("summary: the real conclusion\nstatus: done")

        registry.finishIfActive(
            run.id, SubagentRunRegistry.RunStatus.SUCCESS,
            resultText = "summary: a later, different text\nstatus: done",
            reportSource = SubagentRunAssessment.ReportSource.MODEL,
        )
        registry.setParsedReport(run.id, explicit)

        assertEquals(
            "the object the runner produced is the authority",
            "the real conclusion",
            registry.runs.value.single().parsedReport?.summary,
        )
    }

    // ── the inline path names a real run ─────────────────────────────────

    /**
     * [fix/subagent-envelope-facts] The inline envelope used to print the
     * SKILL id where the run id belongs, so a parent that read "run
     * general-agent" could not name the run it was about to join or cancel —
     * and two runs of the same skill were indistinguishable.
     */
    @Test
    fun `the inline envelope names the run, not the skill`() {
        val text = SubagentResult(
            status = SubagentResult.Status.SUCCESS,
            report = "summary: done\nstatus: done",
            turns = 3,
            maxTurns = 75,
            skillId = "general-agent",
            skillName = "general-agent",
            runId = "subagent-7",
            outcome = SubagentRunAssessment.Outcome.COMPLETED,
            reportSource = SubagentRunAssessment.ReportSource.MODEL,
            executedToolCalls = 4,
        ).toPromptText()

        assertTrue("the run id must be usable: $text", text.contains("run subagent-7"))
        assertFalse(
            "the skill id must not be printed as the run id: $text",
            text.contains("run general-agent"),
        )
    }

    /** A caller with no run id falls back to the skill id rather than blank. */
    @Test
    fun `a result without a run id falls back to the skill id`() {
        val text = SubagentResult(
            status = SubagentResult.Status.SUCCESS,
            report = "summary: done\nstatus: done",
            turns = 1,
            maxTurns = 10,
            skillId = "g",
            skillName = "g",
        ).toPromptText()

        assertTrue("the header must never be blank: $text", text.contains("run g"))
    }
}