package com.openminis.app.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-delivery] Tests for the push-vs-pull delivery contract: a
 * detached run's result must reach the parent EXACTLY ONCE — either pulled by
 * join/wait or pushed as a wake-up turn, never both, and never neither.
 */
class SubagentDeliveryTest {

    private fun job(detached: Boolean = true) = SubagentOrchestration.SubagentJob(
        runId = "r1", skillId = "general-agent", skillName = "general-agent", detached = detached,
    )

    private fun outcome(report: String = "found 3 regressions") =
        SubagentOrchestration.JobOutcome(
            runId = "r1", success = true, report = report, skillName = "general-agent",
        )

    private fun run(
        status: SubagentRunRegistry.RunStatus = SubagentRunRegistry.RunStatus.SUCCESS,
        tokensIn: Int = -1,
        tokensOut: Int = -1,
        modelLabel: String = "",
        turn: Int = 4,
    ) = SubagentRunRegistry.Run(
        id = "r1", blockId = "b1", skillId = "general-agent", skillName = "general-agent",
        query = "do it", title = "Do it", status = status, turn = turn, maxTurns = 12,
        tokensIn = tokensIn, tokensOut = tokensOut, modelLabel = modelLabel,
    )

    // ── awaiter accounting ───────────────────────────────────────────────

    @Test
    fun `a fresh job has no awaiters`() {
        assertFalse(job().hasAwaiters())
    }

    @Test
    fun `awaiting marks interest for the whole block and releases after`() = runBlocking {
        val j = job()
        var seenInside = false
        SubagentOrchestration.awaiting(listOf(j)) {
            seenInside = j.hasAwaiters()
            Unit
        }
        assertTrue("interest must be visible while blocked", seenInside)
        assertFalse("interest must be released on return", j.hasAwaiters())
    }

    @Test
    fun `awaiting releases even when the block throws`() = runBlocking {
        val j = job()
        val before = j.hasAwaiters()
        runCatching {
            SubagentOrchestration.awaiting(listOf(j)) { throw IllegalStateException("boom") }
        }
        assertFalse(before)
        assertFalse("a timed-out or failed join must not suppress wake-ups forever", j.hasAwaiters())
    }

    @Test
    fun `nested joins on one job keep interest alive until the innermost exits`() = runBlocking {
        val j = job()
        SubagentOrchestration.awaiting(listOf(j)) {
            SubagentOrchestration.awaiting(listOf(j)) {
                assertTrue(j.hasAwaiters())
            }
            assertTrue("outer awaiter still parked", j.hasAwaiters())
        }
        assertFalse(j.hasAwaiters())
    }

    @Test
    fun `two parents parked on one batch release independently`() = runBlocking {
        val a = job()
        val b = job()
        SubagentOrchestration.awaiting(listOf(a, b)) {
            assertTrue(a.hasAwaiters() && b.hasAwaiters())
        }
        assertFalse(a.hasAwaiters() || b.hasAwaiters())
    }

    // ── wake prompt rendering ────────────────────────────────────────────

    @Test
    fun `wake prompt is framed as an event and names the run`() {
        val text = buildWakePrompt(outcome(), run())
        assertTrue(text.startsWith("[Sub-agent 'general-agent' finished in the background"))
        assertTrue(text.contains("run_id: r1"))
        assertTrue(text.contains("found 3 regressions"))
    }

    @Test
    fun `wake prompt omits cost lines the provider never reported`() {
        val text = buildWakePrompt(outcome(), run())
        assertFalse("-1 must never surface as a token count", text.contains("tokens: in=-1"))
        assertFalse(text.contains("model:"))
    }

    @Test
    fun `wake prompt surfaces model and tokens once known`() {
        val text = buildWakePrompt(
            outcome(),
            run(tokensIn = 1200, tokensOut = 340, modelLabel = "GPT-5 mini"),
        )
        assertTrue(text.contains("model: GPT-5 mini"))
        assertTrue(text.contains("tokens: in=1200 out=340"))
        assertTrue(text.contains("turns: 4/12"))
    }

    @Test
    fun `timed out status renders as timed out with its error`() {
        val o = SubagentOrchestration.JobOutcome(
            runId = "r1", success = false, report = "partial notes",
            skillName = "general-agent", error = "exceeded the 600-second run budget",
        )
        val text = buildWakePrompt(o, run(status = SubagentRunRegistry.RunStatus.TIMED_OUT))
        assertTrue(text.contains("status TIMED_OUT"))
        assertTrue(text.contains("exceeded the 600-second run budget"))
        assertTrue("partial findings must still be delivered", text.contains("partial notes"))
    }

    @Test
    fun `oversized report is truncated and pointed at the journal`() {
        val huge = "x".repeat(9000)
        val o = SubagentOrchestration.JobOutcome(
            runId = "r1", success = true, report = huge,
            skillName = "general-agent", journalPath = "/var/minis/workspace/.subagent/r1.md",
        )
        val text = buildWakePrompt(o, run())
        assertTrue("report body must be capped", text.length < huge.length)
        assertTrue(text.contains("…[truncated]"))
        assertTrue("the full text needs an anchor", text.contains("/var/minis/workspace/.subagent/r1.md"))
    }

    @Test
    fun `empty report still renders a usable event line`() {
        val o = SubagentOrchestration.JobOutcome(
            runId = "r1", success = true, report = "", skillName = "general-agent",
        )
        val text = buildWakePrompt(o, run())
        assertTrue(text.startsWith("[Sub-agent"))
        assertFalse(text.contains("null"))
    }

    // ── deferred exactly-once, which the push path relies on ────────────

    @Test
    fun `only the first completer wins the deferred`() {
        val j = job()
        val first = j.deferred.complete(outcome("first"))
        val second = j.deferred.complete(outcome("second"))
        assertTrue(first)
        assertFalse("a second delivery must be rejected", second)
    }

    @Test
    fun `a blocked join wakes when the run completes and interest then clears`() = runBlocking {
        val j = job()
        val joiner = async {
            SubagentOrchestration.awaiting(listOf(j)) {
                SubagentOrchestration.joinAll(listOf(j), timeoutMs = 5_000L)
            }
        }
        delay(50)
        assertTrue("join parked here must count as interest", j.hasAwaiters())
        // Run finishes WHILE the parent is blocked → push path must stay quiet.
        j.deferred.complete(outcome("done while joined"))
        val outcomes = joiner.await()
        assertEquals(1, outcomes.size)
        assertEquals("done while joined", outcomes.first().report)
        assertFalse("after the join returns, later runs may push again", j.hasAwaiters())
    }

    @Test
    fun `a timed out join stops suppressing so the eventual push can fire`() = runBlocking {
        val j = job()
        val outcomes = SubagentOrchestration.awaiting(listOf(j)) {
            SubagentOrchestration.joinAll(listOf(j), timeoutMs = 120L)
        }
        assertTrue("join must have timed out", outcomes.first().error.orEmpty().contains("timed out"))
        assertFalse("interest released — the push path is live again", j.hasAwaiters())
    }

    // ── usage accumulation ───────────────────────────────────────────────

    @Test
    fun `usage accumulates across turns and promotes unknown to known`() {
        val registry = SubagentRunRegistry()
        val r = registry.register("b", "s", "s", "q", "t", 12)
        val unknown = registry.runs.value.first()
        assertEquals(-1, unknown.tokensIn)

        registry.addUsage(r.id, inputTokens = 1000, outputTokens = 200)
        registry.addUsage(r.id, inputTokens = 1500, outputTokens = 350)
        val after = registry.runs.value.first()
        assertEquals(2500, after.tokensIn)
        assertEquals(550, after.tokensOut)
    }

    @Test
    fun `a zero usage report leaves the run unknown`() {
        val registry = SubagentRunRegistry()
        val r = registry.register("b", "s", "s", "q", "t", 12)
        registry.addUsage(r.id, inputTokens = 0, outputTokens = 0)
        val after = registry.runs.value.first()
        assertEquals("0 must not masquerade as a real measurement", -1, after.tokensIn)
    }

    @Test
    fun `timed out is terminal and not active`() {
        assertFalse(run(status = SubagentRunRegistry.RunStatus.TIMED_OUT).isActive)
    }
}
