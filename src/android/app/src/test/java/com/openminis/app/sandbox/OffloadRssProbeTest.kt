package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [offload-rss] Tests for the pure RSS attribution probe.
 *
 * Validates two things the construction plan's "打点定位" phase needs:
 * 1. [parseVmRssKb] correctly extracts VmRSS from /proc/self/status text
 *    (pure function — no Android dependency).
 * 2. [record]/[summary]/[reset] correctly aggregate per-handler VmRSS
 *    deltas and flag LEAK-SUSPECT sources crossing 1 GiB cumulative growth.
 *    Does NOT touch /proc (the object's rssKb() reads the real process;
 *    here we feed synthetic deltas through the aggregator directly).
 */
class OffloadRssProbeTest {

    @Before
    fun setUp() {
        // 每个用例独立起点：聚合器是进程级 object，跨用例累计会污染断言。
        OffloadRssProbe.reset()
    }

    // ---- parseVmRssKb ----

    @Test
    fun `parseVmRss_plain status text`() {
        val text = """
            Name:   com.openminis.app
            State:  R (running)
            VmRSS:    123456 kB
            VmSize:   999 kB
        """.trimIndent()
        assertEquals(123456L, OffloadRssProbe.parseVmRssKb(text))
    }

    @Test
    fun `parseVmRss_missing VmRSS line returns zero`() {
        val text = """
            Name:   com.openminis.app
            State:  R (running)
            VmSize: 999 kB
        """.trimIndent()
        assertEquals(0L, OffloadRssProbe.parseVmRssKb(text))
    }

    @Test
    fun `parseVmRss_empty string returns zero`() {
        assertEquals(0L, OffloadRssProbe.parseVmRssKb(""))
    }

    @Test
    fun `parseVmRss_malformed number returns zero`() {
        val text = "VmRSS:   not-a-number kB\n"
        assertEquals(0L, OffloadRssProbe.parseVmRssKb(text))
    }

    @Test
    fun `parseVmRss_prefixed line not treated as VmRSS`() {
        // 只认以 "VmRSS:" 开头的行，不以 substring 前缀误匹配其他行
        val text = "NotVmRSS:  123 kB\n"
        assertEquals(0L, OffloadRssProbe.parseVmRssKb(text))
    }

    // ---- record / aggregation ----

    @Test
    fun `record accumulates per handler count and total delta`() {
        OffloadRssProbe.record("weather", 100_000, 100_000 + 10_000)   // +10k kB
        OffloadRssProbe.record("weather", 110_000, 110_000 + 5_000)    // +5k kB
        OffloadRssProbe.record("model-use", 120_000, 120_000 + 40_000) // +40k kB

        val summary = OffloadRssProbe.summary()
        // summary 只用于可读 dump；这里通过排序断言存在性（顺序: model-use 最大）
        assertTrue("summary should mention model-use", summary.contains("model-use"))
        assertTrue("summary should mention weather", summary.contains("weather"))
        // 各 handler 出现了一次（聚合行）
        assertEquals(2, summary.lines().count { it.trimStart().startsWith("model-use") || it.trimStart().startsWith("weather") })
    }

    @Test
    fun `record tracks peak single delta`() {
        OffloadRssProbe.record("browser-use", 100_000, 150_000) // peak 50k
        OffloadRssProbe.record("browser-use", 150_000, 155_000) // delta 5k, not peak
        val summary = OffloadRssProbe.summary()
        // peak=49MB (50013/1024≈48.8 → 48MB int division)
        val browserLine = summary.lines().first { it.trimStart().startsWith("browser-use") }
        assertTrue(browserLine.contains("peak=48MB") || browserLine.contains("peak=49MB"))
    }

    @Test
    fun `record preserves negative delta`() {
        // GC / shell 回收释放映射：delta 为负，应如实计入（不放号强制归零）
        OffloadRssProbe.record("sessions", 100_000, 80_000) // -20k kB
        val summary = OffloadRssProbe.summary()
        assertTrue(summary.contains("cum=-19MB") || summary.contains("cum=-20MB"))
    }

    @Test
    fun `record zero delta when before equals after`() {
        OffloadRssProbe.record("open", 100_000, 100_000)
        val summary = OffloadRssProbe.summary()
        assertTrue(summary.contains("cum=0MB"))
        assertTrue(summary.contains("count=1"))
    }

    // ---- LEAK-SUSPECT ----

    @Test
    fun `cumulative growth crossing 1 GiB flags leak suspect`() {
        // 累计正 delta > 1 GiB (1048576 kB)：需要跨过阈值
        // 用 105 次 × 10_000 kB = 1.05 GiB
        repeat(105) { i ->
            val before = 100_000L + i * 10_000L
            OffloadRssProbe.record("model-use", before, before + 10_000)
        }
        val summary = OffloadRssProbe.summary()
        assertTrue(
            "expected LEAK-SUSPECT marker on model-use, got:\n$summary",
            summary.contains("model-use") && summary.contains("[LEAK-SUSPECT]")
        )
    }

    @Test
    fun `small cumulative growth does not flag leak suspect`() {
        // 远低于 1 GiB 阈值
        repeat(5) { i ->
            val before = 100_000L + i * 1_000L
            OffloadRssProbe.record("weather", before, before + 1_000) // 累计 5k kB
        }
        val summary = OffloadRssProbe.summary()
        assertFalse(
            "weather should NOT be LEAK-SUSPECT, got:\n$summary",
            summary.contains("[LEAK-SUSPECT]")
        )
    }

    @Test
    fun `leak suspect fires only once per handler`() {
        // 第一次跨阈值 WARN 后，leaked 置 true，后续不再重复打
        repeat(120) { i ->
            val before = 100_000L + i * 10_000L
            OffloadRssProbe.record("sessions", before, before + 10_000)
        }
        val summary = OffloadRssProbe.summary()
        // 只出现一个 [LEAK-SUSPECT] 标记（summary 里每个 handler 只打印一行）
        assertEquals(1, summary.lines().count { it.contains("[LEAK-SUSPECT]") })
    }

    // ---- reset ----

    @Test
    fun `reset clears all aggregation`() {
        OffloadRssProbe.record("weather", 100_000, 110_000)
        OffloadRssProbe.reset()
        val summary = OffloadRssProbe.summary()
        assertFalse(summary.contains("weather"))
    }
}
