package com.openminis.app.sandbox

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [hud-truthful-sampler] Registry of live PRoot tracer processes.
 *
 * Why this exists: the shell-tool CPU/MEM HUD used to sample
 * `/proc/self/stat` + `Debug.getMemoryInfo()` — i.e. the *app service*
 * process — while the actual work (hf-render, git, python, …) runs inside
 * a forked PRoot tracer child. PRoot translates guest syscalls via ptrace,
 * so guest CPU lands on the tracer process and guest RSS shows up in the
 * tracer's VmRSS. The app process barely moves while a shell command burns
 * CPU/memory, so the HUD read "CPU 0% / MEM 0.2G" under real load.
 *
 * Every PRoot entrypoint now registers its tracer PID here at process start
 * and unregisters at teardown:
 *  - [PersistentShell] (agent shell_execute path)
 *  - [ShellExecutor]   (legacy/test one-shot path)
 *  - [TerminalSession] (interactive terminal)
 * (native-offload handlers like minis-model-use run in the :toolservice
 * process — out of scope for the in-app HUD by design.)
 *
 * The monitor sums utime+stime and VmRSS across all registered PIDs (plus
 * the app's own, kept as the always-present floor). Per-command shells are
 * recycled aggressively, so the map is expected to stay at 0-2 entries —
 * O(n) with n tiny.
 *
 * Zero Android-framework dependencies beyond Log so the PID walking logic
 * stays JVM-testable.
 */
object SandboxProcRegistry {

    private const val TAG = "SandboxProcRegistry"

    /** tracer pid → creation timestamp (millis). ConcurrentHashMap-safe. */
    private val procs = ConcurrentHashMap<Int, Long>()

    @Volatile
    var isRegistered: Boolean = false
        private set

    val size: Int get() = procs.size

    /** Register a live PRoot tracer PID. Returns false if registration was skipped (pid <= 0). */
    fun register(pid: Int): Boolean {
        if (pid <= 0) return false
        procs[pid] = System.currentTimeMillis()
        isRegistered = true
        return true
    }

    /** Unregister a tracer PID (idempotent — missing pids are ignored). */
    fun unregister(pid: Int) {
        if (pid <= 0) return
        procs.remove(pid)
    }

    /** Test/debug helper: drop all registrations. */
    fun clear() {
        procs.clear()
        isRegistered = false
    }

    /**
     * Read `/proc/<pid>/stat` utime+stime (clock ticks) for every registered
     * tracer, plus the app's own utime+stime. Dead pids are silently dropped
     * (lazy GC — the owner's teardown path also unregisters).
     *
     * Pure-I/O wrapper: the parsing itself is in [internalSelfCpuTicks] /
     * [internalProcStatCpuTicks] (JVM-testable).
     */
    fun totalCpuTicks(): Long {
        var ticks = 0L
        var appCounted = false
        for ((pid, _) in procs) {
            if (pid == androidOwnPid()) appCounted = true
            ticks += internalProcStatCpuTicks(readProcFile("/proc/$pid/stat")) ?: 0L
        }
        if (!appCounted) ticks += internalSelfCpuTicks(readProcFile("/proc/self/stat")) ?: 0L
        return ticks
    }

    /**
     * Sum VmRSS (kB) of every registered tracer, plus the app's own VmRSS.
     * Returns null when nothing at all could be read (no registered procs AND
     * unreadable /proc/self/status) — callers fall back to Debug PSS then.
     */
    fun totalRssKb(): Long? {
        var kb = 0L
        var readAnything = false
        var appCounted = false
        for ((pid, _) in procs) {
            if (pid == androidOwnPid()) appCounted = true
            internalVmRssKb(readProcFile("/proc/$pid/status"))?.let { kb += it; readAnything = true }
        }
        val self = internalVmRssKb(readProcFile("/proc/self/status"))
        if (!appCounted && self != null) { kb += self; readAnything = true }
        return if (readAnything) kb else null
    }

    private fun androidOwnPid(): Int = try { android.os.Process.myPid() } catch (_: Throwable) { 0 }

    private fun readProcFile(path: String): String? = try {
        val f = File(path)
        if (f.canRead()) f.readText() else null
    } catch (_: Throwable) { null }

    companion object {
        /**
         * Parse `/proc/<pid>/stat` → utime + stime in clock ticks.
         * Field 2 is the parenthesized comm (may contain spaces/parens), so
         * anchor on the *last* ')'; after that, 0-indexed utime=11, stime=12.
         * Returns null when unreadable (proc hidepid, dead pid, app data race).
         */
        internal fun internalProcStatCpuTicks(raw: String?): Long? {
            if (raw.isNullOrEmpty()) return null
            val rparen = raw.lastIndexOf(')')
            if (rparen < 0 || rparen + 2 > raw.length) return null
            val tail = raw.substring(rparen + 2).trim().split(' ').filter { it.isNotEmpty() }
            if (tail.size < 13) return null
            val utime = tail[11].toLongOrNull() ?: return null
            val stime = tail[12].toLongOrNull() ?: return null
            return utime + stime
        }

        /** `/proc/self/stat` convenience → [internalProcStatCpuTicks]. */
        internal fun internalSelfCpuTicks(raw: String?): Long? = internalProcStatCpuTicks(raw)

        /** Parse a `/proc/<pid>/status` text → VmRSS in kB, or null when absent/unreadable. */
        internal fun internalVmRssKb(statusText: String?): Long? {
            if (statusText.isNullOrEmpty()) return null
            val line = statusText.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return null
            return line.substringAfter(":").trim().substringBefore(" kB").trim().toLongOrNull()
        }
    }
}
