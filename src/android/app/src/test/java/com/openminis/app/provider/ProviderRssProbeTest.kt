package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [provider-rss D3] Tests for the pure provider RSS attribution probe.
 *
 * Mirrors OffloadRssProbeTest / BrowserRssProbeTest: validates (1)
 * [parseVmRssKb] as a pure function and (2) [record]/[summary]/[reset]
 * aggregation + LEAK-SUSPECT flagging. No Android dependency — /proc is
 * never touched here, we feed synthetic deltas through the aggregator.
 */
class ProviderRssProbeTest {

    @Before
    fun setUp() {
        ProviderRssProbe.reset()
    }

    // ---- parseVmRssKb ----

    @Test
    fun `parseVmRss plain status text`() {
        val text = """
            Name:   com.openminis.app
            State:  R (running)
            VmRSS:    123456 kB
            VmSize:   999 kB
        """.trimIndent()
        assertEquals(123456L, ProviderRssProbe.parseVmRssKb(text))
    }

    @Test
    fun `parseVmRss missing VmRSS line returns zero`() {
        val text = """
            Name:   com.openminis.app
            VmSize: 999 kB
        """.trimIndent()
        assertEquals(0L, ProviderRssProbe.parseVmRssKb(text))
    }

    @Test
    fun `parseVmRss empty string returns zero`() {
        assertEquals(0L, ProviderRssProbe.parseVmRssKb(""))
    }

    @Test
    fun `parseVmRss malformed number returns zero`() {
        val text = "VmRSS:   not-a-number kB\n"
        assertEquals(0L, ProviderRssProbe.parseVmRssKb(text))
    }

    // ---- record / aggregation ----

    @Test
    fun `record accumulates per kind count and total delta`() {
        ProviderRssProbe.record("sendMessage:mock", 100_000, 110_000)    // +10k kB
        ProviderRssProbe.record("sendMessage:mock", 110_000, 115_000)    // +5k kB
        ProviderRssProbe.record("streamMessage:mock", 120_000, 160_000)  // +40k kB

        val summary = ProviderRssProbe.summary()
        assertTrue("summary should mention sendMessage", summary.contains("sendMessage"))
        assertTrue("summary should mention streamMessage", summary.contains("streamMessage"))
    }

    @Test
    fun `record preserves negative delta`() {
        ProviderRssProbe.record("streamMessage:mock", 100_000, 80_000) // -20k kB (reclaimed)
        val summary = ProviderRssProbe.summary()
        assertTrue(summary.contains("cum=-19MB") || summary.contains("cum=-20MB"))
    }

    @Test
    fun `record zero delta when before equals after`() {
        ProviderRssProbe.record("sendMessage:mock", 100_000, 100_000)
        val summary = ProviderRssProbe.summary()
        assertTrue(summary.contains("cum=0MB"))
        assertTrue(summary.contains("count=1"))
    }

    // ---- LEAK-SUSPECT ----

    @Test
    fun `cumulative growth crossing 1 GiB flags leak suspect`() {
        repeat(105) { i ->
            val before = 100_000L + i * 10_000L
            ProviderRssProbe.record("sendMessage:mock", before, before + 10_000)
        }
        val summary = ProviderRssProbe.summary()
        assertTrue(
            "expected LEAK-SUSPECT marker on sendMessage, got:\n$summary",
            summary.contains("sendMessage") && summary.contains("[LEAK-SUSPECT]")
        )
    }

    @Test
    fun `small cumulative growth does not flag leak suspect`() {
        repeat(5) { i ->
            val before = 100_000L + i * 1_000L
            ProviderRssProbe.record("streamMessage:mock", before, before + 1_000)
        }
        val summary = ProviderRssProbe.summary()
        assertFalse(
            "streamMessage should NOT be LEAK-SUSPECT, got:\n$summary",
            summary.contains("[LEAK-SUSPECT]")
        )
    }

    @Test
    fun `reset clears all aggregation`() {
        ProviderRssProbe.record("sendMessage:mock", 100_000, 110_000)
        ProviderRssProbe.reset()
        val summary = ProviderRssProbe.summary()
        assertFalse(summary.contains("sendMessage"))
    }
}
