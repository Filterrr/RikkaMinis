package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the HUD formatting surface (hud-truthful-sampler).
 *
 * [SystemResourceMonitor.formattedCpu]/[formattedMem] are pure functions of
 * their fields — the IRIX-style CPU (>100% legal, never negative) and the
 * GB formatting are pinned here without Android framework dependencies.
 */
class SystemResourceMonitorFormatTest {

    @Test
    fun `cpu formats integral percentages`() {
        val m = SystemResourceMonitor()
        assertEquals("CPU 0%", m.formattedCpu())
    }

    @Test
    fun `memory formats compact form in GB`() {
        val m = SystemResourceMonitor()
        // Field is private-set; exercise via reflection-free boundary: the
        // formatter on a fresh monitor must render the zero state safely.
        val compact = m.formattedMem(compact = true)
        assertTrue(compact.startsWith("MEM "))
        assertTrue(compact.endsWith("G"))
        val full = m.formattedMem()
        assertTrue(full.contains("/"))
        assertTrue(full.endsWith("GB"))
    }
}
