package com.openminis.app.data.routing

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for GroupRouter (Phase 1) — behavior snapshot of the
 * selection / fallback-ordering decisions extracted from ChatViewModel, plus
 * the pure MemberHealth semantics. Zero Android dependencies; time driven by
 * an injectable clock.
 */
class GroupRouterTest {

    // ─── helpers ───────────────────────────────────────────────────────────

    private fun member(id: String, modelId: String = "model-$id") = ModelEntry(
        providerInstanceId = "inst-$id",
        baseModel = LLMModel(modelId, "Model $id", "Test"),
        // ModelEntry.id is derived from uuid — must pin it so select() returns
        // the id the tests assert on (default uuid would be a random UUID).
        uuid = id,
    )

    private fun group(vararg ids: String, strategy: RoutingStrategy = RoutingStrategy.fallback) = ModelGroup(
        id = "g1",
        name = "Test Group",
        memberEntryIds = ids.toMutableList(),
        strategy = strategy,
    )

    private fun routerWithClock(nowMs: () -> Long = { 1000L }) = GroupRouter(clock = nowMs)

    // ─── select: fallback strategy ─────────────────────────────────────────

    @Test fun fallbackStrategy_returnsFirstMember() {
        val router = routerWithClock()
        val g = group("a", "b", "c")
        assertEquals("a", router.select(g, listOf(member("a"), member("b"), member("c"))))
    }

    @Test fun fallbackStrategy_preferredEntry_isHonoredWhenPresent() {
        val router = routerWithClock()
        val g = group("a", "b", "c")
        val members = listOf(member("a"), member("b"), member("c"))
        assertEquals("b", router.select(g, members, preferredEntryId = "b"))
    }

    @Test fun fallbackStrategy_preferredEntry_absent_fallsBackToFirst() {
        val router = routerWithClock()
        val g = group("a", "b", "c")
        // preferred id no longer in the (enabled) members — session proceeds on first
        assertEquals("a", router.select(g, listOf(member("a"), member("b")), preferredEntryId = "z"))
    }

    @Test fun select_emptyMembers_returnsNull() {
        val router = routerWithClock()
        assertNull(router.select(group("a"), emptyList()))
    }

    // ─── select: loadBalance rotation ──────────────────────────────────────

    @Test fun loadBalance_rotatesOneStepPastStickyAnchor() {
        val router = routerWithClock()
        val g = group("a", "b", "c", strategy = RoutingStrategy.loadBalance)
        val members = listOf(member("a"), member("b"), member("c"))
        // anchor b -> next is c
        assertEquals("c", router.select(g, members, stickyEntryId = "b"))
        // anchor c -> next is a (wraps)
        assertEquals("a", router.select(g, members, stickyEntryId = "c"))
    }

    @Test fun loadBalance_noAnchor_startsAtFirst() {
        val router = routerWithClock()
        val g = group("a", "b", "c", strategy = RoutingStrategy.loadBalance)
        // stickyEntryId null -> indexOfFirst = -1 -> (-1 + 1) % 3 = 0
        assertEquals("a", router.select(g, listOf(member("a"), member("b"), member("c"))))
    }

    @Test fun loadBalance_anchorNotInMembers_startsAtFirst() {
        val router = routerWithClock()
        val g = group("a", "b", "c", strategy = RoutingStrategy.loadBalance)
        val members = listOf(member("a"), member("b"), member("c"))
        assertEquals("a", router.select(g, members, stickyEntryId = "ghost"))
    }

    @Test fun loadBalance_preferredEntry_takesPrecedence() {
        val router = routerWithClock()
        val g = group("a", "b", "c", strategy = RoutingStrategy.loadBalance)
        val members = listOf(member("a"), member("b"), member("c"))
        // explicit pick wins over rotation
        assertEquals("b", router.select(g, members, preferredEntryId = "b", stickyEntryId = "a"))
    }

    // ─── fallbackOrder ─────────────────────────────────────────────────────

    @Test fun fallbackOrder_startsAfterActiveEntry_cycles() {
        val router = routerWithClock()
        val g = group("a", "b", "c", "d")
        assertEquals(
            listOf("c", "d", "a"),
            router.fallbackOrder(g, activeEntryId = "b", primaryModelId = "m", modelIdOf = { null }),
        )
    }

    @Test fun fallbackOrder_activeAtEnd_wrapsToStart() {
        val router = routerWithClock()
        val g = group("a", "b", "c")
        assertEquals(
            listOf("a", "b"),
            router.fallbackOrder(g, activeEntryId = "c", primaryModelId = "m", modelIdOf = { null }),
        )
    }

    @Test fun fallbackOrder_activeNotInGroup_modelIdMatchAnchors() {
        val router = routerWithClock()
        val g = group("a", "b", "c")
        // active entry "x" not in group; modelId "model-b" matches entry b
        val modelIdOf: (String) -> String? = { id -> "model-$id" }
        assertEquals(
            listOf("c", "a"),
            router.fallbackOrder(g, activeEntryId = "x", primaryModelId = "model-b", modelIdOf = modelIdOf),
        )
    }

    @Test fun fallbackOrder_noAnchorNoMatch_startsFromIndexOne() {
        val router = routerWithClock()
        val g = group("a", "b", "c")
        assertEquals(
            listOf("b", "c"),
            router.fallbackOrder(g, activeEntryId = null, primaryModelId = "unknown", modelIdOf = { null }),
        )
    }

    @Test fun fallbackOrder_singleMemberGroup_empty() {
        val router = routerWithClock()
        val g = group("a")
        assertTrue(router.fallbackOrder(g, activeEntryId = "a", primaryModelId = "m", modelIdOf = { null }).isEmpty())
    }

    // ─── health (Phase 1: map consulted, never populated) ─────────────────

    @Test fun noHealthRecorded_alwaysUsable() {
        val router = routerWithClock()
        assertTrue(router.isUsable("any-entry"))
        assertEquals(MemberHealth.Healthy, router.healthOf("any-entry"))
    }

    @Test fun recordResult_isNoopInPhase1() {
        val router = routerWithClock()
        router.recordResult("a", RouteOutcome.RateLimited(retryAfterMs = 5000))
        router.recordResult("a", RouteOutcome.AuthError)
        // Phase 1 contract: recording does not yet demote — stays healthy
        assertTrue(router.isUsable("a"))
        assertEquals(MemberHealth.Healthy, router.healthOf("a"))
    }

    // ─── MemberHealth.isUsable pure semantics ──────────────────────────────

    @Test fun healthy_alwaysUsable() {
        assertTrue(MemberHealth.Healthy.isUsable(0L))
        assertTrue(MemberHealth.Healthy.isUsable(Long.MAX_VALUE))
    }

    @Test fun cooling_expired_usable_notExpired_not() {
        val now = 1000L
        val cooling = MemberHealth.Cooling(untilMs = 1500L)
        assertFalse(cooling.isUsable(now))
        assertTrue(cooling.isUsable(1500L))        // boundary inclusive
        assertTrue(cooling.isUsable(2000L))        // expired -> auto recovery
    }

    @Test fun openCircuit_expired_usable() {
        val circuit = MemberHealth.OpenCircuit(untilMs = 2000L, failures = 3)
        assertFalse(circuit.isUsable(1000L))
        assertTrue(circuit.isUsable(2000L))
    }

    @Test fun dead_neverUsable() {
        assertFalse(MemberHealth.Dead.isUsable(0L))
        assertFalse(MemberHealth.Dead.isUsable(Long.MAX_VALUE))
    }
}
