package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [CpuRollingWindow] — the pure rolling-window math behind
 * the hud-truthful-sampler CPU fix. The HUD must report the PRoot tracer's
 * workload in top-style IRIX % (100% = 1 core), and the window must not
 * silently bias or drop legal samples when tracers appear/disappear
 * mid-window (per-command shell recycle).
 */
class CpuRollingWindowTest {

    private val TPS = 100L // CLK_TCK on Android

    @Test
    fun `empty window has no percentage`() {
        assertNull(CpuRollingWindow(5).percent(TPS))
    }

    @Test
    fun `one full core of load reads 100 percent`() {
        val w = CpuRollingWindow(5)
        w.add(100L, 1_000_000_000L) // 100 ticks = 1s CPU over 1s wall
        assertEquals(100f, w.percent(TPS)!!, 0.01f)
    }

    @Test
    fun `two cores of load reads 200 percent IRIX style`() {
        val w = CpuRollingWindow(5)
        w.add(200L, 1_000_000_000L)
        assertEquals(200f, w.percent(TPS)!!, 0.01f)
    }

    @Test
    fun `zero-delta idle seconds are kept not dropped`() {
        val w = CpuRollingWindow(5)
        w.add(0L, 1_000_000_000L) // idle — legal data
        assertEquals(0f, w.percent(TPS)!!, 0.001f)
    }

    @Test
    fun `window averages across busy and idle samples`() {
        val w = CpuRollingWindow(5)
        w.add(100L, 1_000_000_000L) // busy
        w.add(0L, 1_000_000_000L)   // idle
        w.add(100L, 1_000_000_000L) // busy
        w.add(0L, 1_000_000_000L)   // idle
        // Sum-weighted: 200 ticks / 4s wall / 100tps = 50%
        assertEquals(50f, w.percent(TPS)!!, 0.01f)
    }

    @Test
    fun `window caps at capacity dropping oldest`() {
        val w = CpuRollingWindow(3)
        w.add(300L, 1_000_000_000L) // pushed out
        w.add(0L, 1_000_000_000L)
        w.add(0L, 1_000_000_000L)
        w.add(0L, 1_000_000_000L)
        // Only the last 3 (all idle) remain → 0%
        assertEquals(0f, w.percent(TPS)!!, 0.001f)
    }

    @Test
    fun `negative tick delta is dropped not clamped`() {
        val w = CpuRollingWindow(5)
        w.add(100L, 1_000_000_000L)
        w.add(-50L, 1_000_000_000L) // tracer left the sum mid-window
        // 100 ticks / 1s effective → still 100%, negative credit rejected
        assertEquals(100f, w.percent(TPS)!!, 0.01f)
    }

    @Test
    fun `non-positive wall time is dropped`() {
        val w = CpuRollingWindow(5)
        w.add(100L, 1_000_000_000L)
        w.add(100L, 0L)
        w.add(100L, -1L)
        assertEquals(100f, w.percent(TPS)!!, 0.01f)
    }

    @Test
    fun `growing tick sum from late-registered tracer is kept`() {
        // A PRoot tracer registering mid-window makes the cumulative sum
        // jump by its whole lifetime ticks. That CPU was really burned —
        // the HUD must show it, not discard it as "impossible".
        val w = CpuRollingWindow(5)
        w.add(500L, 1_000_000_000L)
        assertTrue(w.percent(TPS)!! > 400f)
    }

    @Test
    fun `clear empties the window`() {
        val w = CpuRollingWindow(5)
        w.add(100L, 1_000_000_000L)
        w.clear()
        assertNull(w.percent(TPS))
    }

    @Test
    fun `sub-second slices are handled without bias`() {
        val w = CpuRollingWindow(5)
        // 4 × 250ms slices, 50% load each → 50 ticks/slice... actually
        // 50% of one core over 0.25s = 12.5 ticks → rounded to 13 (ticks
        // are integers; sub-jiffy error is bounded and averages out).
        repeat(4) { w.add(13L, 250_000_000L) }
        // 52 ticks / 1s / 100tps = 52% — close to 50 within jiffy quantization
        assertTrue("pct=${w.percent(TPS)}", w.percent(TPS)!! in 49f..55f)
    }
}
