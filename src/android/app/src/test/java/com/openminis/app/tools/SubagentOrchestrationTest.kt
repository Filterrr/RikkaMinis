package com.openminis.app.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-orchestration] Unit tests for the parent-side orchestration
 * layer: group/job registries, join (with timeout), wait-any racing, and
 * the cancel cascade.
 */
class SubagentOrchestrationTest {

    private fun makeJob(
        runId: String,
        detached: Boolean = true,
        skillName: String = "general-agent",
    ) = SubagentOrchestration.SubagentJob(
        runId = runId,
        skillId = skillName,
        skillName = skillName,
        detached = detached,
    )

    private fun outcome(
        runId: String,
        success: Boolean = true,
        report: String = "report-$runId",
        cancelled: Boolean = false,
        error: String? = null,
    ) = SubagentOrchestration.JobOutcome(
        runId = runId,
        success = success,
        report = report,
        skillName = "general-agent",
        cancelled = cancelled,
        error = error,
    )

    // ── Registries ───────────────────────────────────────────────────────

    @Test
    fun `attachRunToGroup creates group lazily and dedupes members`() {
        val regs = SubagentOrchestration.Registries()
        regs.attachRunToGroup("g1", "run-1")
        regs.attachRunToGroup("g1", "run-2")
        regs.attachRunToGroup("g1", "run-1") // idempotent

        val group = regs.getGroup("g1")
        assertNotNull(group)
        assertEquals(listOf("run-1", "run-2"), group!!.runIds)
    }

    @Test
    fun `groupsSnapshot is newest first`() {
        val regs = SubagentOrchestration.Registries()
        regs.attachRunToGroup("g1", "run-1")
        regs.attachRunToGroup("g2", "run-2")
        assertEquals(listOf("g2", "g1"), regs.groupsSnapshot().map { it.id })
    }

    @Test
    fun `group history is bounded`() {
        val regs = SubagentOrchestration.Registries()
        for (i in 1..(regs.MAX_RETAINED_GROUPS + 5)) {
            regs.attachRunToGroup("g$i", "run-$i")
        }
        assertEquals(regs.MAX_RETAINED_GROUPS, regs.groupsSnapshot().size)
        assertNull(regs.getGroup("g1")) // oldest evicted
        assertNotNull(regs.getGroup("g${regs.MAX_RETAINED_GROUPS + 5}"))
    }

    @Test
    fun `putJob never evicts the job being added and prefers completed`() {
        val regs = SubagentOrchestration.Registries()
        val live = makeJob("live")
        regs.putJob(live) // stays pending forever
        for (i in 1..(regs.MAX_RETAINED_JOBS + 3)) {
            val j = makeJob("done-$i")
            regs.putJob(j)
            j.deferred.complete(outcome(j.runId)) // evictable
        }
        // The live job must have survived the eviction sweep.
        assertNotNull(regs.getJob("live"))
    }

    // ── join ─────────────────────────────────────────────────────────────

    @Test
    fun `joinAll returns completed outcomes immediately`() = runBlocking {
        val a = makeJob("a").apply { deferred.complete(outcome("a")) }
        val b = makeJob("b").apply { deferred.complete(outcome("b", success = false, error = "boom")) }
        val outcomes = withTimeout(5_000) {
            SubagentOrchestration.joinAll(listOf(a, b), timeoutMs = 5_000)
        }
        assertEquals(2, outcomes.size)
        assertTrue(outcomes[0].success)
        assertFalse(outcomes[1].success)
        assertEquals("boom", outcomes[1].error)
    }

    @Test
    fun `joinAll waits for late completion`() = runBlocking {
        val a = makeJob("a")
        val results = async {
            SubagentOrchestration.joinAll(listOf(a), timeoutMs = 5_000)
        }
        delay(150)
        a.deferred.complete(outcome("a", report = "late-report"))
        val outcomes = results.await()
        assertEquals("late-report", outcomes.single().report)
    }

    @Test
    fun `joinAll surfaces timeout but leaves deferred pending`() = runBlocking {
        val a = makeJob("a")
        val outcomes = withTimeout(10_000) {
            SubagentOrchestration.joinAll(listOf(a), timeoutMs = 200)
        }
        assertEquals(1, outcomes.size)
        assertFalse(outcomes[0].success)
        assertTrue(outcomes[0].error!!.contains("join timed out"))
        // The run itself is NOT cancelled by the join timeout.
        assertFalse(a.deferred.isCompleted)
        // A later join can still collect it.
        a.deferred.complete(outcome("a"))
        val later = SubagentOrchestration.joinAll(listOf(a), timeoutMs = 1_000)
        assertTrue(later.single().success)
    }

    // ── wait_any ─────────────────────────────────────────────────────────

    @Test
    fun `waitAny resolves with first completion`() = runBlocking {
        val slow = makeJob("slow")
        val fast = makeJob("fast")
        val race = async {
            SubagentOrchestration.waitAny(listOf(slow, fast), timeoutMs = 5_000, successOnly = false)
        }
        delay(100)
        fast.deferred.complete(outcome("fast", report = "fast-wins"))
        val winner = race.await()
        assertNotNull(winner)
        assertEquals("fast", winner!!.runId)
        assertEquals("fast-wins", winner.report)
    }

    @Test
    fun `waitAny successOnly skips failures and waits for success`() = runBlocking {
        val failing = makeJob("failing")
        val winning = makeJob("winning")
        val race = async {
            SubagentOrchestration.waitAny(listOf(failing, winning), timeoutMs = 5_000, successOnly = true)
        }
        delay(50)
        failing.deferred.complete(outcome("failing", success = false, error = "nope"))
        delay(50)
        winning.deferred.complete(outcome("winning", report = "winner"))
        val winner = race.await()
        assertNotNull(winner)
        assertEquals("winning", winner!!.runId)
    }

    @Test
    fun `waitAny successOnly returns null when everything fails`() = runBlocking {
        val a = makeJob("a")
        val b = makeJob("b")
        val race = async {
            SubagentOrchestration.waitAny(listOf(a, b), timeoutMs = 5_000, successOnly = true)
        }
        delay(50)
        a.deferred.complete(outcome("a", success = false, error = "x"))
        b.deferred.complete(outcome("b", success = false, error = "y"))
        assertNull(race.await())
    }

    @Test
    fun `waitAny returns null on timeout`() = runBlocking {
        val a = makeJob("a")
        val winner = SubagentOrchestration.waitAny(listOf(a), timeoutMs = 150, successOnly = false)
        assertNull(winner)
        assertFalse(a.deferred.isCompleted) // race did not cancel the run
    }

    // ── cancel cascade ───────────────────────────────────────────────────

    @Test
    fun `cancelCascade completes pending deferreds and skips finished ones`() {
        val pending = makeJob("pending")
        val finished = makeJob("finished").apply { deferred.complete(outcome("finished")) }
        val cancelled = SubagentOrchestration.cancelCascade(
            listOf(pending, finished), "reaped",
        )
        assertEquals(listOf("pending"), cancelled)
        assertTrue(pending.deferred.isCompleted)
        val outcome = kotlinx.coroutines.runBlocking { pending.deferred.await() }
        assertTrue(outcome.cancelled)
        assertFalse(outcome.success)
        assertEquals("reaped", outcome.error)
    }

    @Test
    fun `cancelCascade wakes a blocked join`() = runBlocking {
        val a = makeJob("a")
        val join = async { SubagentOrchestration.joinAll(listOf(a), timeoutMs = 30_000) }
        delay(100)
        SubagentOrchestration.cancelCascade(listOf(a), "parent cancelled it")
        val outcomes = join.await()
        assertTrue(outcomes.single().cancelled)
    }

    // ── resolution ───────────────────────────────────────────────────────

    @Test
    fun `resolveJoinTargets prefers run_ids then group then latest`() {
        val regs = SubagentOrchestration.Registries()
        regs.attachRunToGroup("g1", "run-1")
        regs.attachRunToGroup("g2", "run-2")
        val j1 = makeJob("run-1").apply { deferred.complete(outcome("run-1")) }
        val j2 = makeJob("run-2").apply { deferred.complete(outcome("run-2")) }
        regs.putJob(j1)
        regs.putJob(j2)

        // By run_ids.
        val byIds = SubagentOrchestration.resolveJoinTargets(regs, listOf("run-2"), null)
        assertEquals(listOf("run-2"), byIds!!.second.map { it.runId })

        // By group.
        val byGroup = SubagentOrchestration.resolveJoinTargets(regs, emptyList(), "g1")
        assertEquals(listOf("run-1"), byGroup!!.second.map { it.runId })

        // Latest group fallback.
        val latest = SubagentOrchestration.resolveJoinTargets(regs, emptyList(), null)
        assertEquals("g2", latest!!.first.id)

        // Nothing found.
        assertNull(SubagentOrchestration.resolveJoinTargets(regs, listOf("nope"), null))
    }

    @Test
    fun `joinSummary counts statuses`() {
        val group = SubagentOrchestration.SubagentGroup("g", "b", listOf("a", "b", "c", "d"))
        val jobs = listOf(makeJob("a"), makeJob("b"), makeJob("c"), makeJob("d"))
        val outcomes = listOf(
            outcome("a"),
            outcome("b", success = false, error = "x"),
            outcome("c", cancelled = true),
            outcome("d"),
        )
        val summary = SubagentOrchestration.joinSummary(group, jobs, outcomes)
        assertTrue(summary.contains("2 succeeded"))
        assertTrue(summary.contains("1 failed"))
        assertTrue(summary.contains("1 cancelled"))
        assertTrue(summary.contains("4 total"))
    }

    // ── run-id parsing ───────────────────────────────────────────────────

    @Test
    fun `parseRunIds handles comma space and quoted forms`() {
        assertEquals(
            listOf("subagent-1", "subagent-2"),
            SubagentOrchestrationTools.parseRunIds("subagent-1, subagent-2"),
        )
        assertEquals(
            listOf("a", "b"),
            SubagentOrchestrationTools.parseRunIds("\"a\" 'b'"),
        )
        assertTrue(SubagentOrchestrationTools.parseRunIds("").isEmpty())
        assertTrue(SubagentOrchestrationTools.parseRunIds(null).isEmpty())
    }
}
