package com.openminis.app.tools

import com.openminis.app.tools.SubagentGroupSpread.ModelPlan
import com.openminis.app.tools.SubagentModelResolver.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-model-spread] Unit tests for the batch-spread planner and the
 * cross-tier `model` argument resolution.
 *
 * The load-bearing behaviours pinned here:
 *  1. rotation is deterministic and anchored (1 号 → assigned member, …);
 *  2. members occupied by live runs are skipped (the "每个运行的子 agent 请求
 *     不同模型" guarantee beyond one batch);
 *  3. cross-tier precedence: a value matching ANY model is never
 *     reinterpreted as a group.
 */
class SubagentGroupSpreadTest {

    private fun m(name: String) = Candidate(
        instanceId = "inst-$name",
        entryId = "uuid-$name",
        modelId = name,
        displayName = name,
    )

    private val a = m("a")
    private val b = m("b")
    private val c = m("c")

    // ── planBatchMemberIndexes ───────────────────────────────────────────

    @Test
    fun `sequential rotation assigns 1 号 to a 2 号 to b`() {
        val idx = SubagentGroupSpread.planBatchMemberIndexes(
            members = listOf(a, b, c),
            anchorIndex = 0,
            callCount = 3,
            liveEntryIds = emptySet(),
        )
        assertEquals(listOf(0, 1, 2), idx)
    }

    @Test
    fun `rotation is anchored at the parent's active member`() {
        val idx = SubagentGroupSpread.planBatchMemberIndexes(
            members = listOf(a, b, c),
            anchorIndex = 1, // parent active member is b
            callCount = 3,
            liveEntryIds = emptySet(),
        )
        // b, c, a — the parent's member first (least surprise), then around.
        assertEquals(listOf(1, 2, 0), idx)
    }

    @Test
    fun `members occupied by live runs are skipped`() {
        val idx = SubagentGroupSpread.planBatchMemberIndexes(
            members = listOf(a, b, c),
            anchorIndex = 0,
            callCount = 2,
            liveEntryIds = setOf(a.entryId),
        )
        // a is busy → the batch lands on b then c.
        assertEquals(listOf(1, 2), idx)
    }

    @Test
    fun `a batch that exhausts members degrades to round-robin`() {
        val idx = SubagentGroupSpread.planBatchMemberIndexes(
            members = listOf(a, b),
            anchorIndex = 0,
            callCount = 3,
            liveEntryIds = emptySet(),
        )
        // Two members, three calls: the third wraps back to a.
        assertEquals(listOf(0, 1, 0), idx)
    }

    @Test
    fun `all members occupied falls back to rotation instead of failing`() {
        val idx = SubagentGroupSpread.planBatchMemberIndexes(
            members = listOf(a, b),
            anchorIndex = 0,
            callCount = 2,
            liveEntryIds = setOf(a.entryId, b.entryId),
        )
        // Collision is preferable to a missing assignment.
        assertEquals(listOf(0, 1), idx)
    }

    @Test
    fun `occupied members within the same batch are skipped first`() {
        val idx = SubagentGroupSpread.planBatchMemberIndexes(
            members = listOf(a, b, c),
            anchorIndex = 0,
            callCount = 2,
            liveEntryIds = setOf(c.entryId),
        )
        // c busy → the two calls take a and b (both free and distinct).
        assertEquals(listOf(0, 1), idx)
    }

    @Test
    fun `a batch larger than the free set degrades to rotation`() {
        val idx = SubagentGroupSpread.planBatchMemberIndexes(
            members = listOf(a, b, c),
            anchorIndex = 0,
            callCount = 3,
            liveEntryIds = setOf(c.entryId),
        )
        // c busy AND a,b taken in-batch: the third call has no free member —
        // the documented degrade wraps (collision beats a missing assignment).
        assertEquals(listOf(0, 1, 2), idx)
    }

    @Test
    fun `empty members or zero calls yield nothing`() {
        assertEquals(
            emptyList<Int>(),
            SubagentGroupSpread.planBatchMemberIndexes(emptyList(), 0, 3, emptySet()),
        )
        assertEquals(
            emptyList<Int>(),
            SubagentGroupSpread.planBatchMemberIndexes(listOf(a), 0, 0, emptySet()),
        )
    }

    @Test
    fun `out-of-range anchor wraps like any rotation`() {
        val idx = SubagentGroupSpread.planBatchMemberIndexes(
            members = listOf(a, b, c),
            anchorIndex = 5, // 5 % 3 → starts at index 2
            callCount = 3,
            liveEntryIds = emptySet(),
        )
        assertEquals(listOf(2, 0, 1), idx)
    }

    // ── resolveSpawnModelPlan: cross-tier precedence ─────────────────────

    private val catalog = listOf(a, b)

    private fun groups(): Map<String, Pair<String, String>> =
        mapOf(
            "fast pool" to ("gid-1" to "fast pool"),
            "cheap" to ("gid-2" to "cheap"),
        )

    private fun lookup(q: String): Pair<String, String>? = groups()[q]

    private fun members(gid: String): List<Candidate> =
        if (gid == "gid-1" || gid == "gid-2") listOf(a, b) else emptyList()

    private fun resolve(raw: String?): ModelPlan = SubagentGroupSpread.resolveSpawnModelPlan(
        rawModel = raw,
        modelCatalog = catalog,
        groupLookup = ::lookup,
        groupMembers = ::members,
        knownGroupNames = { listOf("fast pool", "cheap") },
    )

    @Test
    fun `empty argument inherits`() {
        assertEquals(ModelPlan.Inherit, resolve(null))
        assertEquals(ModelPlan.Inherit, resolve("   "))
    }

    @Test
    fun `a model match always wins over group syntax`() {
        // "a" is both a model id AND (pretend) a group name — model wins.
        val both = SubagentGroupSpread.resolveSpawnModelPlan(
            rawModel = "a",
            modelCatalog = catalog,
            groupLookup = { "gid-x" to "a" },
            groupMembers = { listOf(a, b) },
        )
        assertTrue(both is ModelPlan.SingleModel)
        assertEquals("a", (both as ModelPlan.SingleModel).candidate.modelId)
    }

    @Test
    fun `prefixed group resolves to the group`() {
        val plan = resolve("group:fast pool")
        assertTrue(plan is ModelPlan.Mapped)
        assertEquals("gid-1", (plan as ModelPlan.Mapped).groupId)
    }

    @Test
    fun `bare group name resolves when no model matches`() {
        val plan = resolve("cheap")
        assertTrue(plan is ModelPlan.Mapped)
        assertEquals("gid-2", (plan as ModelPlan.Mapped).groupId)
    }

    @Test
    fun `unknown token fails and lists both models and groups`() {
        val plan = resolve("nope-9000")
        assertTrue(plan is ModelPlan.Failed)
        val msg = (plan as ModelPlan.Failed).message
        assertTrue(msg.contains("did not match"))
        assertTrue(msg.contains("fast pool"))
    }

    @Test
    fun `empty group prefix is malformed not a wildcard`() {
        val plan = resolve("group:")
        assertTrue(plan is ModelPlan.Failed)
        assertTrue((plan as ModelPlan.Failed).message.contains("names no group"))
    }

    @Test
    fun `a group with no usable members is reported as unusable`() {
        val plan = SubagentGroupSpread.resolveSpawnModelPlan(
            rawModel = "group:empty",
            modelCatalog = catalog,
            groupLookup = { "gid-empty" to "empty" },
            groupMembers = { emptyList() },
        )
        assertTrue(plan is ModelPlan.GroupUnusable)
        assertTrue((plan as ModelPlan.GroupUnusable).message.contains("no usable members"))
    }

    // ── SubagentModelArg.parse ───────────────────────────────────────────

    @Test
    fun `group prefix is stripped`() {
        val r = SubagentModelArg.parse("group:fast pool")
        assertTrue(r is SubagentModelArg.Result.Spread)
        assertEquals("fast pool", (r as SubagentModelArg.Result.Spread).query)
        assertEquals(
            "fast pool",
            (SubagentModelArg.parse("GROUP:fast pool") as SubagentModelArg.Result.Spread).query,
        )
    }

    @Test
    fun `bare token parses as spread candidate`() {
        val r = SubagentModelArg.parse("cheap")
        assertTrue(r is SubagentModelArg.Result.Spread)
        assertEquals("cheap", (r as SubagentModelArg.Result.Spread).query)
    }

    @Test
    fun `blank parses as single`() {
        assertEquals(SubagentModelArg.Result.Single, SubagentModelArg.parse(null))
        assertEquals(SubagentModelArg.Result.Single, SubagentModelArg.parse("  "))
    }

    @Test
    fun `empty group prefix is malformed`() {
        val r = SubagentModelArg.parse("group:   ")
        assertTrue(r is SubagentModelArg.Result.Malformed)
    }
}
