package com.openminis.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-orchestration-fix] Regression suite for the spawn WIRING that
 * shipped broken: the orchestration group registry was never populated in
 * production, so every group-addressed join / wait_any / cancel — and the
 * "most recent batch" default — failed with "no matching runs" while the
 * spawn tool result advertised a group_id that could never resolve.
 *
 * The pre-existing `SubagentOrchestrationTest` could not catch this: it
 * called `attachRunToGroup` ITSELF and then asserted the lookup worked, which
 * proves the lookup works given a populated registry and says nothing about
 * whether the SPAWN path populates it. These tests drive the seam the runner
 * actually calls ([SubagentOrchestration.registerSpawn] /
 * [SubagentOrchestration.attachReusedRun]) and assert the end-to-end
 * consequence: a group id produced by a spawn is RESOLVABLE by join.
 */
class SubagentWiringTest {

    private fun registries() = SubagentOrchestration.Registries()

    private fun job(runId: String, detached: Boolean = true) = SubagentOrchestration.SubagentJob(
        runId = runId,
        skillId = "general-agent",
        skillName = "general-agent",
        detached = detached,
    )

    // ── the reported bug ─────────────────────────────────────────────────

    /**
     * THE regression: after a spawn registers, `resolveJoinTargets` must find
     * the run BY GROUP ID. Before the fix the group map stayed empty, so this
     * returned null and the tool answered "No matching sub-agent runs found".
     */
    @Test
    fun `a spawned run is resolvable by its group id`() {
        val regs = registries()
        SubagentOrchestration.registerSpawn(regs, job("subagent-1"), groupId = "sgroup-1")

        val target = SubagentOrchestration.resolveJoinTargets(regs, emptyList(), "sgroup-1")

        assertNotNull("group-addressed join must resolve after a spawn", target)
        assertEquals("sgroup-1", target!!.first.id)
        assertEquals(listOf("subagent-1"), target.second.map { it.runId })
    }

    /**
     * The "omit both → join the MOST RECENT batch" default is documented in
     * the tool schema and was equally dead: it reads the newest group from the
     * same registry. This pins the default path.
     */
    @Test
    fun `the default most-recent-batch join resolves after a spawn`() {
        val regs = registries()
        SubagentOrchestration.registerSpawn(regs, job("subagent-1"), groupId = "sgroup-1")
        SubagentOrchestration.registerSpawn(regs, job("subagent-2"), groupId = "sgroup-2")

        val target = SubagentOrchestration.resolveJoinTargets(regs, emptyList(), null)

        assertNotNull("default join must resolve to the newest batch", target)
        assertEquals("sgroup-2", target!!.first.id)
    }

    /**
     * A multi-spawn batch: every member must be reachable by the SAME group
     * id, in spawn order — that ordering is what the result zip relies on to
     * attribute reports to tasks.
     */
    @Test
    fun `a batch accumulates every member under one group id`() {
        val regs = registries()
        listOf("subagent-1", "subagent-2", "subagent-3").forEach { rid ->
            SubagentOrchestration.registerSpawn(regs, job(rid), groupId = "sgroup-7")
        }

        val target = SubagentOrchestration.resolveJoinTargets(regs, emptyList(), "sgroup-7")

        assertEquals(listOf("subagent-1", "subagent-2", "subagent-3"), target!!.second.map { it.runId })
    }

    /** A single spawn still forms a group of one (the runner mints one unconditionally). */
    @Test
    fun `a single spawn forms a group of one`() {
        val regs = registries()
        SubagentOrchestration.registerSpawn(regs, job("subagent-9"), groupId = "sgroup-9")

        assertEquals(1, regs.groupsSnapshot().size)
        assertEquals(listOf("subagent-9"), regs.getGroup("sgroup-9")!!.runIds)
    }

    /**
     * A run that DEDUPED onto an in-flight identical task must still join the
     * new batch: the model dispatched three tasks and must be able to collect
     * three outcomes, not two plus silence.
     */
    @Test
    fun `a deduped run is attached to the new batch group`() {
        val regs = registries()
        // The original spawn, in an earlier batch.
        SubagentOrchestration.registerSpawn(regs, job("subagent-1"), groupId = "sgroup-1")
        // A later batch whose identical task deduped onto subagent-1.
        SubagentOrchestration.attachReusedRun(regs, "subagent-1", groupId = "sgroup-2")

        val target = SubagentOrchestration.resolveJoinTargets(regs, emptyList(), "sgroup-2")

        assertNotNull("a reused run must be joinable via the new batch", target)
        assertEquals(listOf("subagent-1"), target!!.second.map { it.runId })
    }

    /**
     * No group id (a direct runner invocation in tests / a non-batched call)
     * must not create a phantom group.
     */
    @Test
    fun `an empty group id registers the job without a group`() {
        val regs = registries()
        SubagentOrchestration.registerSpawn(regs, job("subagent-1"), groupId = "")

        assertTrue("no group may be minted", regs.groupsSnapshot().isEmpty())
        assertNotNull("the job itself must still be registered", regs.getJob("subagent-1"))
        assertNull(SubagentOrchestration.resolveJoinTargets(regs, emptyList(), null))
    }

    /** run_ids addressing keeps working alongside the group path. */
    @Test
    fun `run-id addressing still resolves independently of groups`() {
        val regs = registries()
        SubagentOrchestration.registerSpawn(regs, job("subagent-1"), groupId = "sgroup-1")

        val target = SubagentOrchestration.resolveJoinTargets(regs, listOf("subagent-1"), null)

        assertEquals("ad-hoc", target!!.first.id)
        assertEquals(listOf("subagent-1"), target.second.map { it.runId })
    }

    /**
     * Group-addressed CANCEL resolves its members. Before the fix
     * `cancel_subagents(group_id=…)` could not find the group at all.
     */
    @Test
    fun `a group id addresses its members for cancel`() {
        val regs = registries()
        SubagentOrchestration.registerSpawn(regs, job("subagent-1"), groupId = "sgroup-3")
        SubagentOrchestration.registerSpawn(regs, job("subagent-2"), groupId = "sgroup-3")

        val group = regs.getGroup("sgroup-3")
        assertNotNull("cancel by group must find the batch", group)
        assertEquals(listOf("subagent-1", "subagent-2"), regs.getJobs(group!!.runIds).map { it.runId })
    }

    /**
     * Registration is atomic w.r.t. the registry maps: concurrent spawns of
     * one batch interleave freely (they are launched in parallel), and every
     * member must land exactly once.
     */
    @Test
    fun `concurrent batch registration keeps every member exactly once`() = runBlocking {
        val regs = registries()
        val ids = (1..8).map { "subagent-$it" }
        val jobs = ids.map { job(it) }

        kotlinx.coroutines.coroutineScope {
            jobs.forEach { j ->
                launch(Dispatchers.Default) {
                    SubagentOrchestration.registerSpawn(regs, j, groupId = "sgroup-1")
                }
            }
        }

        val group = regs.getGroup("sgroup-1")!!
        assertEquals("no member may be dropped or duplicated", 8, group.runIds.size)
        assertEquals(ids.toSet(), group.runIds.toSet())
    }

    // ── join outcome attribution ─────────────────────────────────────────

    /**
     * [T-subagent-orchestration-fix] A slot a failed sibling left empty must
     * be attributed to ITS OWN job. The historical fallback used `jobs[0]` for
     * every empty slot (`map` cannot see the index), so a cancelled run's
     * timeout outcome could carry the first run's id and skill — and the
     * parent would then act on a report belonging to a different task.
     */
    @Test
    fun `an unfilled outcome slot is attributed to its own job`() {
        val jobs = listOf(
            job("run-a"),
            job("run-b"),
            job("run-c"),
        )
        // Only the middle slot resolved; a and c are the "sibling died" case.
        val outcomes = arrayOfNulls<SubagentOrchestration.JobOutcome>(jobs.size)
        outcomes[1] = SubagentOrchestration.JobOutcome(
            runId = "run-b", success = true, report = "b done", skillName = "skill-b",
        )

        val filled = SubagentOrchestration.fillMissingOutcomes(jobs, outcomes, timeoutMs = 1234L)

        assertEquals(3, filled.size)
        assertEquals("run-a", filled[0].runId)
        assertEquals("run-c", filled[2].runId)
        assertEquals("run-b", filled[1].runId)
        // The synthesized fallbacks must NOT borrow run-b's identity.
        assertFalse("run-a must not be labelled run-b", filled[0].runId == "run-b")
        assertFalse("run-c must not be labelled run-b", filled[2].runId == "run-b")
        assertTrue(filled[0].error!!.contains("join timed out"))
    }

    /** A fully-resolved join passes outcomes through untouched, in order. */
    @Test
    fun `filled outcomes preserve resolution order when nothing is missing`() {
        val jobs = listOf(job("run-a"), job("run-b"))
        val outcomes = arrayOf<SubagentOrchestration.JobOutcome?>(
            SubagentOrchestration.JobOutcome("run-a", true, "a", "skill-a"),
            SubagentOrchestration.JobOutcome("run-b", false, "b", "skill-b"),
        )

        val filled = SubagentOrchestration.fillMissingOutcomes(jobs, outcomes, timeoutMs = 1L)

        assertEquals(listOf("run-a", "run-b"), filled.map { it.runId })
        assertTrue(filled[0].success)
        assertFalse(filled[1].success)
    }
}
