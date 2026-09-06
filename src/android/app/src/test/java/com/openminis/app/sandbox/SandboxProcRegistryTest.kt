package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SandboxProcRegistry]'s /proc parsing helpers — the pure
 * logic behind the hud-truthful-sampler CPU/MEM attribution fix.
 *
 * Background: the shell-tool HUD used to sample the app process while the
 * workload runs in a forked PRoot tracer (ptrace bills guest syscalls to
 * the tracer). These tests pin the tracer-side parsing: /proc/<pid>/stat
 * utime+stime extraction (comm may contain spaces/parens) and /proc/<pid>/
 * status VmRSS extraction.
 */
class SandboxProcRegistryTest {

    // ── internalProcStatCpuTicks ──────────────────────────────────────

    @Test
    fun `parses utime plus stime from proc stat`() {
        // Fields after "pid (comm) ": state=0 ppid=1 ... utime=11 stime=12.
        // 13 tail fields; utime=100, stime=50 → 150 ticks.
        val tail = (0 until 13).joinToString(" ") { if (it == 11) "100" else if (it == 12) "50" else "$it" }
        val raw = "12345 (proot) $tail"
        assertEquals(150L, SandboxProcRegistry.internalProcStatCpuTicks(raw))
    }

    @Test
    fun `comm containing spaces and parens does not break parsing`() {
        // Real-world hazard: a renamed tracer whose comm contains " ) ".
        val tail = (0 until 13).joinToString(" ") { if (it == 11) "7" else if (it == 12) "3" else "$it" }
        val raw = "42 (proot: 2 (weird) name) $tail"
        assertEquals(10L, SandboxProcRegistry.internalProcStatCpuTicks(raw))
    }

    @Test
    fun `garbage or truncated stat returns null`() {
        assertNull(SandboxProcRegistry.internalProcStatCpuTicks(null))
        assertNull(SandboxProcRegistry.internalProcStatCpuTicks(""))
        assertNull(SandboxProcRegistry.internalProcStatCpuTicks("1 (x"))
        assertNull(SandboxProcRegistry.internalProcStatCpuTicks("1 (x) 1 2 3"))
        // Non-numeric utime
        assertNull(SandboxProcRegistry.internalProcStatCpuTicks(
            "1 (x) " + (0 until 13).joinToString(" ") { if (it == 11) "abc" else "$it" }
        ))
    }

    // ── internalVmRssKb ───────────────────────────────────────────────

    @Test
    fun `parses VmRSS from status text`() {
        val status = buildString {
            appendLine("Name:   proot")
            appendLine("VmPeak:  100000 kB")
            appendLine("VmRSS:      12345 kB")
            appendLine("Threads:  1")
        }
        assertEquals(12345L, SandboxProcRegistry.internalVmRssKb(status))
    }

    @Test
    fun `missing VmRSS returns null`() {
        assertNull(SandboxProcRegistry.internalVmRssKb(null))
        assertNull(SandboxProcRegistry.internalVmRssKb(""))
        assertNull(SandboxProcRegistry.internalVmRssKb("Name:   proot\nThreads:  1\n"))
        // Malformed value → null, not 0 (caller distinguishes "unreadable")
        assertNull(SandboxProcRegistry.internalVmRssKb("VmRSS: garbage\n"))
    }

    // ── register / unregister lifecycle ───────────────────────────────

    @Test
    fun `register rejects non-positive pids`() {
        SandboxProcRegistry.clear()
        assertFalse(SandboxProcRegistry.register(0))
        assertFalse(SandboxProcRegistry.register(-5))
        assertEquals(0, SandboxProcRegistry.size)
        SandboxProcRegistry.clear()
    }

    @Test
    fun `unregister is idempotent`() {
        SandboxProcRegistry.clear()
        assertTrue(SandboxProcRegistry.register(42))
        assertEquals(1, SandboxProcRegistry.size)
        SandboxProcRegistry.unregister(42)
        SandboxProcRegistry.unregister(42) // second call is a no-op
        assertEquals(0, SandboxProcRegistry.size)
        assertFalse(SandboxProcRegistry.isRegistered)
        SandboxProcRegistry.clear()
    }

    @Test
    fun `clear resets registration flag`() {
        SandboxProcRegistry.clear()
        assertTrue(SandboxProcRegistry.register(7))
        assertTrue(SandboxProcRegistry.isRegistered)
        SandboxProcRegistry.clear()
        assertFalse(SandboxProcRegistry.isRegistered)
    }
}
