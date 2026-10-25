package com.openminis.app.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-verdict-consistency-v3] The verdict must survive every path
 * that hands a run's result back to the parent.
 *
 * Before this, PR #75/#77 added a rich verdict (UNSTARTED / DEGRADED /
 * PARTIAL / DECLARED_FAILED / NO_OUTPUT) but only the INLINE path consumed
 * it. The detached paths — join, wait_any, the background wake-up — read the
 * binary `success` flag, so every one of those verdicts reached the parent
 * as the same word ("SUCCESS" or "FAILED"). The parent then folded a
 * runtime digest into its plan as if it were findings.
 *
 * These tests pin the contract at the level where it broke: a verdict that
 * is not COMPLETED must never be announced as a success, on any path.
 */
class SubagentVerdictConsistencyTest {

    private fun job(runId: String) = SubagentOrchestration.SubagentJob(
        runId = runId,
        skillId = "skill",
        skillName = "skill",
        detached = true,
    )

    private fun outcome(
        runId: String,
        success: Boolean = true,
        report: String = "report",
        cancelled: Boolean = false,
        error: String? = null,
        outcome: SubagentRunAssessment.Outcome = SubagentRunAssessment.Outcome.COMPLETED,
        reportSource: SubagentRunAssessment.ReportSource =
            SubagentRunAssessment.ReportSource.MODEL,
        executedToolCalls: Int = 3,
    ) = SubagentOrchestration.JobOutcome(
        runId = runId,
        success = success,
        report = report,
        skillName = "skill",
        cancelled = cancelled,
        error = error,
        outcome = outcome,
        reportSource = reportSource,
        executedToolCalls = executedToolCalls,
    )

    // ── The reconciliation rule ──────────────────────────────────────────

    @Test
    fun `a failed outcome with the default COMPLETED verdict reconciles to FAILED`() {
        // JobOutcome's defaults are success=false + COMPLETED — the
        // contradictory pair. Reading the verdict alone would claim the
        // model wrote a report.
        assertEquals(
            SubagentRunAssessment.Outcome.FAILED,
            SubagentRunAssessment.effective(
                SubagentRunAssessment.Outcome.COMPLETED,
                success = false,
            ),
        )
    }

    @Test
    fun `a consistent verdict survives the reconciliation`() {
        for (verdict in SubagentRunAssessment.Outcome.entries) {
            if (verdict == SubagentRunAssessment.Outcome.COMPLETED) continue
            assertEquals(
                "verdict $verdict must survive the reconciliation",
                verdict,
                SubagentRunAssessment.effective(verdict, success = false),
            )
            assertEquals(
                "verdict $verdict must survive the reconciliation",
                verdict,
                SubagentRunAssessment.effective(verdict, success = true),
            )
        }
    }

    @Test
    fun `only COMPLETED is deliverable`() {
        assertTrue(SubagentRunAssessment.isDeliverable(SubagentRunAssessment.Outcome.COMPLETED))
        for (verdict in SubagentRunAssessment.Outcome.entries) {
            if (verdict == SubagentRunAssessment.Outcome.COMPLETED) continue
            assertFalse(
                "verdict $verdict must not be deliverable",
                SubagentRunAssessment.isDeliverable(verdict),
            )
        }
    }

    @Test
    fun `UNKNOWN is not deliverable and has its own label`() {
        assertFalse(SubagentRunAssessment.isDeliverable(SubagentRunAssessment.Outcome.UNKNOWN))
        assertEquals("UNKNOWN", SubagentRunAssessment.statusLabel(SubagentRunAssessment.Outcome.UNKNOWN))
    }

    // ── The delivered flag ───────────────────────────────────────────────

    @Test
    fun `a DEGRADED run is not delivered even though the loop succeeded`() {
        val o = outcome(
            "r1",
            success = true,
            outcome = SubagentRunAssessment.Outcome.DEGRADED,
            reportSource = SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
        )
        assertFalse("a runtime digest is not findings", o.delivered)
    }

    @Test
    fun `a PARTIAL run is not delivered`() {
        val o = outcome(
            "r1",
            success = true,
            outcome = SubagentRunAssessment.Outcome.PARTIAL,
        )
        assertFalse(o.delivered)
    }

    @Test
    fun `a COMPLETED run with success is delivered`() {
        assertTrue(outcome("r1", success = true).delivered)
    }

    @Test
    fun `a cancelled run is never delivered`() {
        val o = outcome("r1", success = true, cancelled = true)
        assertFalse(o.delivered)
    }

    // ── wait_any ─────────────────────────────────────────────────────────

    @Test
    fun `waitAny successOnly skips a DEGRADED winner and waits for a delivered one`() = runBlocking {
        val degraded = job("degraded")
        val delivered = job("delivered")
        val race = async {
            SubagentOrchestration.waitAny(
                listOf(degraded, delivered),
                timeoutMs = 5_000,
                successOnly = true,
            )
        }
        delay(50)
        degraded.deferred.complete(
            outcome(
                "degraded",
                success = true,
                outcome = SubagentRunAssessment.Outcome.DEGRADED,
                reportSource = SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
            ),
        )
        delay(50)
        delivered.deferred.complete(outcome("delivered", report = "the answer"))
        val winner = race.await()
        assertNotNull(winner)
        assertEquals("delivered", winner!!.runId)
    }

    @Test
    fun `waitAny successOnly returns null when nothing was delivered`() = runBlocking {
        val a = job("a")
        val b = job("b")
        val race = async {
            SubagentOrchestration.waitAny(listOf(a, b), timeoutMs = 5_000, successOnly = true)
        }
        delay(50)
        a.deferred.complete(
            outcome(
                "a",
                success = true,
                outcome = SubagentRunAssessment.Outcome.PARTIAL,
            ),
        )
        b.deferred.complete(
            outcome(
                "b",
                success = true,
                outcome = SubagentRunAssessment.Outcome.DEGRADED,
                reportSource = SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
            ),
        )
        assertNull(race.await())
    }

    // ── joinSummary ──────────────────────────────────────────────────────

    @Test
    fun `joinSummary names a DEGRADED run instead of calling it succeeded`() {
        val group = SubagentOrchestration.SubagentGroup("g", "b", listOf("a", "b"))
        val outcomes = listOf(
            outcome("a", success = true),
            outcome(
                "b",
                success = true,
                outcome = SubagentRunAssessment.Outcome.DEGRADED,
                reportSource = SubagentRunAssessment.ReportSource.RUNTIME_DIGEST,
            ),
        )
        val summary = SubagentOrchestration.joinSummary(group, listOf(job("a"), job("b")), outcomes)
        assertTrue("summary: $summary", summary.contains("1 succeeded"))
        assertTrue("summary: $summary", summary.contains("1 degraded"))
        assertFalse(
            "a digest must not be counted as a failure — it RAN: $summary",
            summary.contains("1 failed"),
        )
    }

    @Test
    fun `joinSummary counts a crashed run as failed`() {
        val group = SubagentOrchestration.SubagentGroup("g", "b", listOf("a"))
        val outcomes = listOf(outcome("a", success = false, error = "boom"))
        val summary = SubagentOrchestration.joinSummary(group, listOf(job("a")), outcomes)
        assertTrue("summary: $summary", summary.contains("0 succeeded"))
        assertTrue("summary: $summary", summary.contains("1 failed"))
    }

    // ── The label helper ─────────────────────────────────────────────────

    @Test
    fun `every verdict has a distinct non-blank label`() {
        val labels = SubagentRunAssessment.Outcome.entries.map {
            SubagentRunAssessment.statusLabel(it)
        }
        assertTrue("labels: $labels", labels.none { it.isBlank() })
        assertEquals(
            "two verdicts share a label — the parent cannot tell them apart",
            labels.size,
            labels.distinct().size,
        )
    }

    @Test
    fun `COMPLETED is announced as SUCCESS for continuity`() {
        assertEquals(
            "SUCCESS",
            SubagentRunAssessment.statusLabel(SubagentRunAssessment.Outcome.COMPLETED),
        )
    }
}
