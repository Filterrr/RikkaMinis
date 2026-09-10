package com.openminis.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the [feat-network-pack] pure-decision helpers:
 * OfflineRetryHold.shouldHold, AdaptiveTtfbBudget.budgetMs, and the
 * origin/debounce logic expressed through ConnectionWarmer's helpers where
 * testable without Android runtime.
 */
class NetworkPackTest {

    // ── OfflineRetryHold ─────────────────────────────────────────────

    @Test
    fun `hold while offline and under budget`() {
        assertTrue(OfflineRetryHold.shouldHold(offlineNow = true, heldMs = 0L))
        assertTrue(OfflineRetryHold.shouldHold(offlineNow = true, heldMs = 89_000L))
    }

    @Test
    fun `never hold while online`() {
        assertFalse(OfflineRetryHold.shouldHold(offlineNow = false, heldMs = 0L))
        assertFalse(OfflineRetryHold.shouldHold(offlineNow = false, heldMs = 500_000L))
    }

    @Test
    fun `hold expires at max budget`() {
        assertFalse(
            OfflineRetryHold.shouldHold(
                offlineNow = true,
                heldMs = OfflineRetryHold.OFFLINE_MAX_HOLD_MS,
            ),
        )
    }

    // ── AdaptiveTtfbBudget ───────────────────────────────────────────

    @Test
    fun `thin history returns route default`() {
        val d = 1_800_000L // 30 min backstop
        assertEquals(d, com.openminis.app.diagnostics.ProviderHealthTracker.AdaptiveTtfbBudget.budgetMs(emptyList(), d))
        assertEquals(d, com.openminis.app.diagnostics.ProviderHealthTracker.AdaptiveTtfbBudget.budgetMs(listOf(500L, 900L), d))
    }

    @Test
    fun `healthy fast history tightens budget within floor`() {
        val budget = com.openminis.app.diagnostics.ProviderHealthTracker.AdaptiveTtfbBudget.budgetMs(
            listOf(4_000L, 5_000L, 6_000L, 5_500L, 4_800L),
            routeDefaultMs = 1_800_000L,
        )
        // P95 of a fast route ≈ 6000ms → 6000*1.6=9600 clamped to floor 15s.
        assertEquals(com.openminis.app.diagnostics.ProviderHealthTracker.AdaptiveTtfbBudget.FLOOR_MS, budget)
    }

    @Test
    fun `slow relay history widens budget above 30s`() {
        val budget = com.openminis.app.diagnostics.ProviderHealthTracker.AdaptiveTtfbBudget.budgetMs(
            List(10) { 30_000L + it * 1_000L }, // 30s..39s, P95 ≈ 39s
            routeDefaultMs = 1_800_000L,
        )
        assertTrue("expected >30s, got $budget", budget > 30_000L)
        assertTrue("expected ≤120s ceiling, got $budget", budget <= com.openminis.app.diagnostics.ProviderHealthTracker.AdaptiveTtfbBudget.CEILING_MS)
    }

    @Test
    fun `absurd history clamps to ceiling`() {
        val budget = com.openminis.app.diagnostics.ProviderHealthTracker.AdaptiveTtfbBudget.budgetMs(
            List(5) { 10_000_000L },
            routeDefaultMs = 1_800_000L,
        )
        assertEquals(com.openminis.app.diagnostics.ProviderHealthTracker.AdaptiveTtfbBudget.CEILING_MS, budget)
    }

    // ── DoH preset matching (P1-5) ───────────────────────────────────

    @Test
    fun `known DoH host pins bootstrap IPs`() {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("dns.alidns.com").build()
        val ips = DoHBootstrap.pinnedIps(url)
        assertTrue("alidns must pin", ips != null && ips.size == 2)
        assertEquals("223.5.5.5", ips!![0].hostAddress)
    }

    @Test
    fun `custom DoH host stays unpinned`() {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("my.own.doh.example").build()
        assertNull(DoHBootstrap.pinnedIps(url))
    }

    // ── FaultAttribution (feat5) ─────────────────────────────────────

    @Test
    fun `timeout maps to connection layer`() {
        val r = com.openminis.app.diagnostics.FaultAttribution.attribute("SocketTimeoutException: timeout")
        assertEquals(com.openminis.app.diagnostics.FaultAttribution.Layer.CONNECTION, r.layer)
    }

    @Test
    fun `dns failure maps to connection layer`() {
        val r = com.openminis.app.diagnostics.FaultAttribution.attribute("UnknownHostException: api.relay.example")
        assertEquals(com.openminis.app.diagnostics.FaultAttribution.Layer.CONNECTION, r.layer)
    }

    @Test
    fun `bad gateway maps to service layer`() {
        val r = com.openminis.app.diagnostics.FaultAttribution.attribute("HTTP 502 Bad Gateway")
        assertEquals(com.openminis.app.diagnostics.FaultAttribution.Layer.SERVICE, r.layer)
    }

    @Test
    fun `first chunk timeout maps to service layer`() {
        val r = com.openminis.app.diagnostics.FaultAttribution.attribute(
            "provider produced no first chunk within 30000ms (hadChunks=false)",
        )
        assertEquals(com.openminis.app.diagnostics.FaultAttribution.Layer.SERVICE, r.layer)
    }

    @Test
    fun `invalid key maps to credentials layer`() {
        val r = com.openminis.app.diagnostics.FaultAttribution.attribute("Incorrect API key provided")
        assertEquals(com.openminis.app.diagnostics.FaultAttribution.Layer.CREDENTIALS, r.layer)
    }

    @Test
    fun `unknown text maps to unclear`() {
        val r = com.openminis.app.diagnostics.FaultAttribution.attribute("something exotic happened")
        assertEquals(com.openminis.app.diagnostics.FaultAttribution.Layer.UNCLEAR, r.layer)
    }

    @Test
    fun `null reason maps to unclear`() {
        val r = com.openminis.app.diagnostics.FaultAttribution.attribute(null)
        assertEquals(com.openminis.app.diagnostics.FaultAttribution.Layer.UNCLEAR, r.layer)
    }
}
