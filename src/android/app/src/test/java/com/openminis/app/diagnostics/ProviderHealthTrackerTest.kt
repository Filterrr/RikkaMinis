package com.openminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat-provider-health] Unit tests for the in-process provider health
 * aggregation (pure JVM — no Android deps in the tracker).
 */
class ProviderHealthTrackerTest {

    private fun attempt(modelId: String = "m1", ttfb: Long? = null, success: Boolean = false) =
        ProviderHealthTracker.Attempt(
            modelId = modelId, modelDisplayName = "Model $modelId",
            startedAtMs = 0L, ttfbMs = ttfb, success = success,
        )

    @Test
    fun `success rate and percentiles are computed per model`() {
        val t = freshTracker()
        // m1: 4 successes (ttfb 100,200,300,400) + 1 failure
        repeat(4) { i ->
            var a = t.begin("m1", "Model m1")
            a = t.recordFirstToken(a, 100L * (i + 1))
            t.finish(a, success = true, durationMs = 1000)
        }
        t.finish(t.begin("m1", "Model m1"), success = false, failureReason = "429")

        val snap = t.snapshot()
        assertEquals(1, snap.size)
        val m1 = snap.first()
        assertEquals(0.8, m1.successRate, 0.0001)
        assertEquals(200L, m1.ttfbP50Ms)
        assertEquals(400L, m1.ttfbP95Ms)
        assertEquals("429", m1.lastFailureReason)
    }

    @Test
    fun `failure-only model has null percentiles`() {
        val t = freshTracker()
        t.finish(t.begin("m2", "Model m2"), success = false, failureReason = "timeout")
        val m2 = t.snapshot().single()
        assertEquals(0.0, m2.successRate, 0.0001)
        assertNull(m2.ttfbP50Ms)
        assertNull(m2.ttfbP95Ms)
        assertEquals("timeout", m2.lastFailureReason)
    }

    @Test
    fun `ring buffer evicts oldest beyond capacity`() {
        val t = freshTracker()
        repeat(30) { i ->
            var a = t.begin("m3", "Model m3")
            a = t.recordFirstToken(a, i.toLong())
            t.finish(a, success = true)
        }
        val m3 = t.snapshot().single()
        assertEquals(20, m3.attempts.size)
        // Oldest (ttfb 0..9) evicted → min remaining ttfb is 10.
        assertTrue(m3.attempts.all { it.ttfbMs!! >= 10L })
    }

    @Test
    fun `models sorted worst success rate first`() {
        val t = freshTracker()
        t.finish(t.begin("bad", "Bad"), success = false, failureReason = "x")
        var a = t.begin("good", "Good")
        t.finish(t.recordFirstToken(a, 50L), success = true)
        val ids = t.snapshot().map { it.modelId }
        assertEquals(listOf("bad", "good"), ids)
    }

    @Test
    fun `clear empties everything`() {
        val t = freshTracker()
        t.finish(t.begin("m", "M"), success = true)
        t.clear()
        assertTrue(t.snapshot().isEmpty())
    }

    // The tracker is a singleton; isolate per-test by clearing.
    private fun freshTracker(): ProviderHealthTracker {
        ProviderHealthTracker.clear()
        return ProviderHealthTracker
    }
}
