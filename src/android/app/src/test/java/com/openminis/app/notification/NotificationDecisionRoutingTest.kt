package com.openminis.app.notification

import com.openminis.app.tools.SpawnApprovalQueue
import com.openminis.app.tools.SpawnApprovalRegistry
import com.openminis.app.tools.SpawnDecision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-notif-inline-decision] Contract tests for the notification → gate routing:
 * id namespacing, delivery to the right family, and the "stale tap" behaviour
 * that keeps a replayed PendingIntent from resurrecting anything.
 *
 * Pure JVM — the routing seam exists precisely so this logic is testable
 * without a notification shade.
 */
class NotificationDecisionRoutingTest {

    @After
    fun tearDown() {
        NotificationDecisionRouter.uninstall()
        SpawnApprovalRegistry.clear()
    }

    // ── id namespacing ──────────────────────────────────────────────────────

    @Test
    fun `config ids are recognised by prefix and spawn ids are not`() {
        assertTrue(NotificationDecisionRouter.isConfigId("cfg-abc"))
        assertFalse(NotificationDecisionRouter.isConfigId("spawn-ask-1"))
        assertFalse("an empty id must not classify as config", NotificationDecisionRouter.isConfigId(""))
    }

    @Test
    fun `minted ask ids carry the spawn prefix and never collide`() {
        val ids = (1..50).map { SpawnApprovalQueue.nextAskId() }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith(NotificationDecisionRouter.SPAWN_ID_PREFIX) })
        assertTrue("spawn ids must not read as config ids", ids.none { NotificationDecisionRouter.isConfigId(it) })
    }

    // ── dispatch to the right family ────────────────────────────────────────

    @Test
    fun `config decision routes to the config handler with the approve flag`() {
        val seen = mutableListOf<Pair<String, Boolean>>()
        NotificationDecisionRouter.onConfigDecision = { id, approve -> seen.add(id to approve); true }
        // A spawn handler must not be consulted for a config id — installing one
        // that would throw makes accidental cross-routing fail loudly.
        NotificationDecisionRouter.onSpawnDecision = { _, _ -> error("spawn handler called for a config id") }

        assertTrue(NotificationDecisionRouter.dispatch("cfg-1", approve = true))
        assertTrue(NotificationDecisionRouter.dispatch("cfg-2", approve = false))
        assertEquals(listOf("cfg-1" to true, "cfg-2" to false), seen)
    }

    @Test
    fun `spawn decision maps approve to ALLOW_ONCE and deny to DENY`() {
        val seen = mutableListOf<Pair<String, SpawnDecision>>()
        NotificationDecisionRouter.onSpawnDecision = { id, decision -> seen.add(id to decision); true }

        assertTrue(NotificationDecisionRouter.dispatch("spawn-ask-1", approve = true))
        assertTrue(NotificationDecisionRouter.dispatch("spawn-ask-2", approve = false))
        assertEquals(
            listOf("spawn-ask-1" to SpawnDecision.ALLOW_ONCE, "spawn-ask-2" to SpawnDecision.DENY),
            seen,
        )
    }

    @Test
    fun `a decision with no installed handler reports not-delivered`() {
        // Cold start: the receiver can run before MinisApp installed the hooks.
        // It must report failure rather than claim success.
        assertFalse(NotificationDecisionRouter.dispatch("cfg-x", approve = true))
        assertFalse(NotificationDecisionRouter.dispatch("spawn-ask-9", approve = false))
    }

    @Test
    fun `an empty id is never dispatched`() {
        var called = false
        NotificationDecisionRouter.onConfigDecision = { _, _ -> called = true; true }
        NotificationDecisionRouter.onSpawnDecision = { _, _ -> called = true; true }
        assertFalse(NotificationDecisionRouter.dispatch("", approve = true))
        assertFalse(called)
    }

    @Test
    fun `a stale tap returns false without throwing`() {
        // The handler's own "is this still live?" answer is what the receiver
        // reports; the router must pass it through untouched.
        NotificationDecisionRouter.onConfigDecision = { _, _ -> false }
        assertFalse(NotificationDecisionRouter.dispatch("cfg-gone", approve = true))
    }

    // ── registry: which live queue owns an id ───────────────────────────────

    @Test
    fun `registry finds the owning queue and delivers exactly one decision`() {
        val qa = SpawnApprovalQueue()
        val qb = SpawnApprovalQueue()
        SpawnApprovalRegistry.register("sessionA", qa)
        SpawnApprovalRegistry.register("sessionB", qb)

        val askA = qa.submit("general-agent", "general-agent", "task A", "m", false)
        val askB = qb.submit("general-agent", "general-agent", "task B", "m", false)

        assertEquals("sessionA", SpawnApprovalRegistry.sessionForAsk(askA.request.id))
        assertEquals("sessionB", SpawnApprovalRegistry.sessionForAsk(askB.request.id))

        assertEquals("sessionB", SpawnApprovalRegistry.resolveById(askB.request.id, SpawnDecision.DENY))
        // Delivered exactly once: the ask retired, so a second tap is a no-op.
        assertNull(SpawnApprovalRegistry.resolveById(askB.request.id, SpawnDecision.DENY))
        // The sibling chat's ask is untouched.
        assertTrue(qa.isOutstanding(askA.request.id))
    }

    @Test
    fun `registry reports nothing for an unknown or blank id`() {
        val q = SpawnApprovalQueue()
        SpawnApprovalRegistry.register("s", q)
        assertNull(SpawnApprovalRegistry.sessionForAsk("spawn-ask-404"))
        assertNull(SpawnApprovalRegistry.resolveById("spawn-ask-404", SpawnDecision.DENY))
        assertNull(SpawnApprovalRegistry.resolveById("", SpawnDecision.DENY))
    }

    @Test
    fun `registry ignores a removed queue and blank session ids`() {
        val q = SpawnApprovalQueue()
        SpawnApprovalRegistry.register("", q)
        assertNull("a blank session id must not register", SpawnApprovalRegistry.sessionForAsk("spawn-ask-1"))

        SpawnApprovalRegistry.register("s", q)
        SpawnApprovalRegistry.unregister("s", q)
        assertNull(SpawnApprovalRegistry.sessionForAsk(SpawnApprovalQueue.nextAskId()))
    }

    @Test
    fun `unregister does not evict a newer queue for the same session`() {
        // A rotation/renavigation installs a fresh VM+queue for the same chat;
        // the old VM's onCleared must not blank out the registry entry.
        val oldQueue = SpawnApprovalQueue()
        val newQueue = SpawnApprovalQueue()
        SpawnApprovalRegistry.register("s", oldQueue)
        SpawnApprovalRegistry.register("s", newQueue)
        SpawnApprovalRegistry.unregister("s", oldQueue)

        val ask = newQueue.submit("general-agent", "general-agent", "t", "m", false)
        assertEquals("s", SpawnApprovalRegistry.sessionForAsk(ask.request.id))
    }
}
