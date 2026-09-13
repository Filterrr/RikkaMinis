package com.openminis.app.data.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-multi-api-key] JVM tests for credential-level routing: composite route
 * identity, rotation, and the terminal quota state.
 *
 * The point of these tests is the *contract* that makes multi-key safe to ship
 * on a shared main branch:
 *
 *  - with ONE credential, every composite id collapses to the bare entry id,
 *    so single-key behaviour is byte-for-byte what it was before the feature;
 *  - a spent key parks ALONE — its siblings keep serving;
 *  - exhaustion is terminal (no timer resurrects it), unlike a rate limit;
 *  - a fully-spent instance reports "no rotation available" instead of
 *    looping back to a key already proven dead in this turn.
 *
 * Zero Android dependencies; time is driven by the injectable clock.
 */
class CredentialRotationTest {

    private fun routerAt(now: () -> Long) = GroupRouter(clock = now)

    // ─── composite identity ────────────────────────────────────────────────

    @Test fun routeId_indexZero_isBareEntryId() {
        val router = routerAt { 0L }
        // The load-bearing compatibility guarantee: index 0 IS the historical
        // key, so pre-existing health records still match and single-key
        // instances never grow a suffixed key.
        assertEquals("entry-a", router.routeId("entry-a", 0))
        assertEquals("entry-a", router.routeId("entry-a", -1))
    }

    @Test fun routeId_nonZeroIndex_isSuffixed() {
        val router = routerAt { 0L }
        assertEquals("entry-a#1", router.routeId("entry-a", 1))
        assertEquals("entry-a#7", router.routeId("entry-a", 7))
    }

    // ─── rotation ──────────────────────────────────────────────────────────

    @Test fun rotateCredential_singleKey_returnsNull() {
        val router = routerAt { 0L }
        // No siblings → the caller must escalate, not retry the same key.
        assertNull(router.rotateCredential("e", fromIndex = 0, keyCount = 1))
        assertNull(router.rotateCredential("e", fromIndex = 0, keyCount = 0))
    }

    @Test fun rotateCredential_roundRobinsFromFailedIndex() {
        val router = routerAt { 0L }
        assertEquals(1, router.rotateCredential("e", fromIndex = 0, keyCount = 3))
        assertEquals(2, router.rotateCredential("e", fromIndex = 1, keyCount = 3))
        // Wraps around to the start rather than running off the end.
        assertEquals(0, router.rotateCredential("e", fromIndex = 2, keyCount = 3))
    }

    @Test fun rotateCredential_skipsExhaustedSibling() {
        val router = routerAt { 0L }
        router.recordResult("e#1", RouteOutcome.QuotaExhausted)
        // Key #1 is spent for good → rotation must step over it to #2.
        assertEquals(2, router.rotateCredential("e", fromIndex = 0, keyCount = 3))
    }

    @Test fun rotateCredential_skipsCoolingSiblingButReusesAfterWindow() {
        var now = 1_000L
        val router = routerAt { now }
        router.recordResult("e#1", RouteOutcome.RateLimited(retryAfterMs = 5_000L))
        // Within the cooldown → skip to #2.
        assertEquals(2, router.rotateCredential("e", fromIndex = 0, keyCount = 3))
        // After the window → #1 is eligible again (recovery needs no promote).
        now = 7_000L
        assertEquals(1, router.rotateCredential("e", fromIndex = 0, keyCount = 3))
    }

    @Test fun rotateCredential_allSiblingsSpent_returnsNull() {
        val router = routerAt { 0L }
        router.recordResult("e#1", RouteOutcome.QuotaExhausted)
        router.recordResult("e#2", RouteOutcome.QuotaExhausted)
        // Every sibling is terminal: the caller must leave the member behind
        // rather than re-strike a key known dead in this turn.
        assertNull(router.rotateCredential("e", fromIndex = 0, keyCount = 3))
    }

    @Test fun exhaustedKeyStaysParkedAcrossCalls() {
        val router = routerAt { 0L }
        // Exhaustion is NOT a cooldown — there is no untilMs to wait past, and
        // a sibling succeeding says nothing about THIS key.
        router.recordResult("e#1", RouteOutcome.QuotaExhausted)
        router.recordResult("e#0", RouteOutcome.Success)
        router.recordResult("e#2", RouteOutcome.Success)
        // Rotation still steps over the spent key and finds a usable one...
        assertEquals(2, router.rotateCredential("e", fromIndex = 0, keyCount = 3))
        // ...but a rotation STARTING at the spent key skips to the next usable
        // sibling rather than handing #1 back.
        assertEquals(2, router.rotateCredential("e", fromIndex = 1, keyCount = 3))
        // The instance as a whole stays usable — that is the point of siblings.
        assertTrue(router.isEntryUsable("e", keyCount = 3))
        // #1 is still parked: it is never chosen as the *first* candidate.
        assertEquals(listOf(0, 2, 1), router.credentialOrder("e", keyCount = 3, fromIndex = 0))
    }

    // ─── per-credential blast radius ───────────────────────────────────────

    @Test fun quotaExhaustionParksOnlyTheSpentCredential() {
        val router = routerAt { 0L }
        router.recordResult("e#0", RouteOutcome.QuotaExhausted)
        // The instance is still perfectly usable — that is the whole point.
        assertTrue(router.isEntryUsable("e", keyCount = 2))
        // And a success on the sibling does not resurrect #0.
        router.recordResult("e#1", RouteOutcome.Success)
        assertEquals(1, router.rotateCredential("e", fromIndex = 0, keyCount = 2))
    }

    @Test fun serverErrorIsNotPerCredential() {
        val router = routerAt { 0L }
        // A 5xx is the endpoint's fault, so it must NOT be keyed per credential
        // by the rotation logic — recording on the bare id keeps the whole
        // member in the circuit-breaker path. This matters more now that
        // rotation fires on ANY error: if a 3-key instance filed each 5xx under
        // its own key, the 3-failure circuit would need 9 failures to open and
        // the breaker would effectively stop working.
        repeat(GroupRouter.CIRCUIT_FAILURE_THRESHOLD) {
            router.recordResult("e", RouteOutcome.ServerError)
        }
        assertFalse(router.isUsable("e"))
    }

    @Test fun serverErrorPerCredentialWouldBreakTheBreaker() {
        val router = routerAt { 0L }
        // The counter-example, pinned so the reasoning above cannot be
        // "simplified" away later: spreading the SAME failures across composite
        // ids leaves every one of them under the threshold, so the member stays
        // selectable and the fault is retried forever.
        repeat(GroupRouter.CIRCUIT_FAILURE_THRESHOLD) { i ->
            router.recordResult("e#$i", RouteOutcome.ServerError)
        }
        assertTrue(router.isEntryUsable("e", keyCount = 3))
    }

    // ─── sticky credential memory ──────────────────────────────────────────

    @Test fun preferredCredential_withoutMemory_isIndexZero() {
        val router = routerAt { 0L }
        assertEquals(0, router.preferredCredential("e", keyCount = 3))
    }

    @Test fun preferredCredential_returnsRememberedKey() {
        val router = routerAt { 0L }
        router.rememberCredential("e", keyIndex = 2, keyCount = 3)
        // "Whoever worked last time works next time" — the whole feature.
        assertEquals(2, router.preferredCredential("e", keyCount = 3))
    }

    @Test fun singleKeyInstance_neverRecordsMemory() {
        val router = routerAt { 0L }
        // Scoped per product decision: index 0 is the only choice a
        // single-credential instance has, so a memory entry is pure storage
        // noise and must never enter the persisted map.
        router.rememberCredential("e", keyIndex = 0, keyCount = 1)
        assertTrue(router.stickyKeysSnapshot().isEmpty())
        assertEquals(0, router.preferredCredential("e", keyCount = 1))
    }

    @Test fun rememberedKeyOutOfRange_degradesToFirstUsable() {
        val router = routerAt { 0L }
        router.rememberCredential("e", keyIndex = 5, keyCount = 3)
        // The user deleted keys since the memory was written (or a sync/restore
        // changed the list). An out-of-range index must NOT be handed to the
        // worker as a prefs slot — it would read a nonexistent secret and
        // surface as missing_api_key.
        assertEquals(0, router.preferredCredential("e", keyCount = 3))
    }

    @Test fun rememberedCoolingKey_isSkippedNotHonoured() {
        var now = 1_000L
        val router = routerAt { now }
        router.rememberCredential("e", keyIndex = 2, keyCount = 3)
        router.recordResult("e#2", RouteOutcome.RateLimited(retryAfterMs = 5_000L))
        // Memory is a preference, not an obligation: honouring it past a 429
        // would re-create the failure the rotation ladder exists to escape.
        assertEquals(0, router.preferredCredential("e", keyCount = 3))
        // After the window lapses the memory applies again — the key recovered.
        now = 7_000L
        assertEquals(2, router.preferredCredential("e", keyCount = 3))
    }

    @Test fun rememberedExhaustedKey_neverPreferred() {
        val router = routerAt { 0L }
        router.rememberCredential("e", keyIndex = 1, keyCount = 3)
        router.recordResult("e#1", RouteOutcome.QuotaExhausted)
        // Terminal: no clock advance revives it, so preference must move on.
        assertEquals(0, router.preferredCredential("e", keyCount = 3))
    }

    @Test fun restoreFromPersistence_rehydratesPreference() {
        val router = routerAt { 0L }
        router.rememberCredential("e", keyIndex = 1, keyCount = 3)
        val snapshot = router.stickyKeysSnapshot()
        // Simulate an app restart: a fresh router hydrating prefs data.
        val revived = routerAt { 0L }
        revived.restoreStickyKeys(snapshot)
        assertEquals(1, revived.preferredCredential("e", keyCount = 3))
    }

    @Test fun staleMemoryNeedsNoExplicitForget() {
        val router = routerAt { 0L }
        router.rememberCredential("e", keyIndex = 2, keyCount = 3)
        // Every way a memory can go stale is re-validated at use time — the
        // user deleting keys, or the remembered slot getting exhausted — so
        // there is no "forget" call sites must remember to make. The two
        // degradation paths above (out-of-range, exhausted) are the contract.
        assertEquals(2, router.preferredCredential("e", keyCount = 3))
        router.recordResult("e#2", RouteOutcome.QuotaExhausted)
        assertEquals(0, router.preferredCredential("e", keyCount = 3))
    }

    @Test fun memoryMigratesOnRotation_noRepeatProbeOfDeadKey() {
        val router = routerAt { 0L }
        // Sequence: #0 worked (memory: 0) → next turn starts at 0 → 429 →
        // rotation picks 1 → memory migrates to 1 → a turn AFTER that starts
        // at 1 directly. The dead-then-parked key #0 is never re-probed.
        router.rememberCredential("e", keyIndex = 0, keyCount = 2)
        assertEquals(0, router.preferredCredential("e", keyCount = 2))
        router.recordResult("e#0", RouteOutcome.RateLimited(retryAfterMs = 60_000L))
        val next = router.rotateCredential("e", fromIndex = 0, keyCount = 2)
        assertEquals(1, next)
        next?.let { router.rememberCredential("e", it, keyCount = 2) }
        assertEquals(1, router.preferredCredential("e", keyCount = 2))
    }

    // ─── ordering ──────────────────────────────────────────────────────────

    @Test fun credentialOrder_singleKey_isTrivial() {
        val router = routerAt { 0L }
        assertEquals(listOf(0), router.credentialOrder("e", keyCount = 1))
        assertEquals(emptyList<Int>(), router.credentialOrder("e", keyCount = 0))
    }

    @Test fun credentialOrder_putsUsableFirstAndKeepsDemotedAsProbes() {
        val router = routerAt { 0L }
        router.recordResult("e#1", RouteOutcome.QuotaExhausted)
        val order = router.credentialOrder("e", keyCount = 3, fromIndex = 0)
        // Usable keys keep rotation order (0 then 2) because a fresh request
        // should prefer the credential it used last; the parked one sinks to
        // the end as a last resort — dropping it entirely would lose the
        // provider's error detail when every key is spent.
        assertEquals(listOf(0, 2, 1), order)
    }

    @Test fun credentialOrder_rotatesFromFromIndex() {
        val router = routerAt { 0L }
        assertEquals(listOf(2, 0, 1), router.credentialOrder("e", keyCount = 3, fromIndex = 2))
    }

    // ─── bounded memory ────────────────────────────────────────────────────

    @Test fun healthMapKeepsTerminalRecordsWhileEvictingExpiredCooldowns() {
        val now = 1_000L
        val router = routerAt { now }
        // Fill past the cap with EXPIRED cooldowns plus one live, terminal
        // record. The expired entries carry no information a fresh request
        // wouldn't rediscover; the terminal one carries the fact the user must
        // be shown — so the terminal record must survive the eviction sweeps.
        router.recordResult("keep", RouteOutcome.QuotaExhausted)
        repeat(GroupRouter.MAX_HEALTH_ENTRIES + 5) { i ->
            // retryAfterMs=0 → the cooldown has already lapsed the instant it
            // is written, i.e. exactly the "expired demotion" pass 1 targets.
            router.recordResult("expired-$i", RouteOutcome.RateLimited(retryAfterMs = 0L))
        }
        // "keep" was inserted first but is terminal → it must NOT be the victim.
        assertFalse(router.isUsable("keep"))
    }
}
