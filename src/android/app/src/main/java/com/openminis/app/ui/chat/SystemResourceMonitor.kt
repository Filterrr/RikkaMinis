package com.openminis.app.ui.chat

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.sandbox.SandboxProcRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Lightweight CPU/memory sampler used for shell-tool live HUDs.
 *
 * **[hud-truthful-sampler] What "the workload" is.** The HUD rides the shell
 * tool card, so the number the user expects is "what is this command
 * costing right now". Before this fix the sampler read the *app service*
 * process (`/proc/self/stat` + `Debug.getMemoryInfo()`) — but every shell
 * command runs inside a forked PRoot tracer child, whose syscalls are
 * translated via ptrace: guest CPU is billed to the tracer's
 * utime/stime, and guest RSS (the model's claim, the cache, hf-render
 * buffers) shows up in the tracer's VmRSS. The app process itself barely
 * moves, so the HUD read "CPU 0% / MEM 0.2G" while the tracer burned a
 * full core. T244/T303 already learned half of this lesson the hard way:
 * `/proc/stat` is permission-denied to apps on Android 8+ (system-wide
 * CPU), and the app-*own* numbers, while readable, are simply the wrong
 * attribution for a shell-tool HUD.
 *
 * Now the sampler walks the live PRoot tracer PIDs via
 * [SandboxProcRegistry] (PersistentShell / ShellExecutor / TerminalSession
 * register on spawn, unregister on teardown) and sums:
 *  - CPU: Σ utime+stime over tracers + app self (app kept as the floor so
 *    the HUD never reads 0% when the *agent itself* — JSON streaming,
 *    markdown rendering, Compose — is the busy one). (Δticks / Δwall /
 *    CLK_TCK) × 100, top-style IRIX %: 100% = one fully-loaded core.
 *  - MEM: Σ VmRSS over tracers + app self, falling back to
 *    `Debug.getMemoryInfo().totalPss` when nothing is readable.
 *    (PSS ≈ RSS for these processes — their pages are private, shared
 *    pages like the rootfs .so are a small overlap.)
 *
 * Per-command shells are recycled aggressively (see
 * ExecutionCoordinator's idle/memory recycle), so the registry stays at
 * 0-2 live tracers — the per-sample walk is trivial.
 *
 * Rolling window: cpuUsage is the sum-weighted average over the last
 * [windowSize] samples (sum(ticks) / (sum(wallSec) × CLK_TCK) × 100) —
 * the mathematically correct "% CPU over the last N seconds", immune to
 * a single GC pause or IO block aliasing the window. Window size 5 with
 * 1s sampling = 5-second running average; long enough to ride out GC/IO
 * stalls, short enough to track a command's load within its run.
 *
 * The class itself is stateful (last-tick baselines) but doesn't push
 * state into Compose — pair it with [rememberSystemResourceMonitor] to
 * drive a recompose loop while the caller wants live numbers.
 */
class SystemResourceMonitor {
    var cpuUsage: Float = 0f          // top-style IRIX %; 100% = 1 core
        private set
    var memUsedBytes: Long = 0L
        private set
    var memTotalBytes: Long = 0L
        private set

    private var prevCpuTicks: Long? = null
    private var prevSampleNanos: Long? = null

    private val cpuWindow = CpuRollingWindow(windowSize = 5)

    /** Resolved once. CLK_TCK is effectively 100 on Android historically,
     *  but ask the kernel rather than hard-coding. */
    private val clockTicksPerSec: Long = runCatching {
        android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
    }.getOrNull()?.takeIf { it > 0 } ?: 100L

    fun sampleOnce(context: Context) {
        sampleCpu()
        sampleMemory(context)
    }

    /**
     * Reset baselines so a freshly-(re)started monitor reports 0% on its
     * first sample rather than averaging across a long idle gap.
     */
    fun reset() {
        prevCpuTicks = null
        prevSampleNanos = null
        cpuWindow.clear()
        cpuUsage = 0f
    }

    private fun sampleCpu() {
        val ticks = SandboxProcRegistry.totalCpuTicks()
        val nowNanos = System.nanoTime()
        val prevTicks = prevCpuTicks
        val prevNanos = prevSampleNanos
        if (prevTicks != null && prevNanos != null) {
            // add() drops impossible deltas (tick-counter reset / clock skew)
            // but KEEPS zero deltas — idle seconds are real data, not gaps.
            cpuWindow.add(ticks - prevTicks, nowNanos - prevNanos)
            cpuWindow.percent(clockTicksPerSec)?.let { cpuUsage = it }
        }
        prevCpuTicks = ticks
        prevSampleNanos = nowNanos
    }

    private fun sampleMemory(context: Context) {
        val selfRss = SandboxProcRegistry.totalRssKb()
        if (selfRss != null) {
            memUsedBytes = selfRss * 1024L
        } else {
            // Nothing readable (no registered tracers AND unreadable self
            // status — e.g. Robolectric-less JVM contexts) → keep the legacy
            // Debug PSS as a best-effort floor instead of freezing 0.
            try {
                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                memUsedBytes = mi.totalPss.toLong() * 1024L
            } catch (_: Throwable) { /* keep prior value */ }
        }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val sysMem = ActivityManager.MemoryInfo()
                am.getMemoryInfo(sysMem)
                memTotalBytes = sysMem.totalMem
            }
        } catch (_: Throwable) { /* keep prior value */ }
    }

    fun formattedCpu(): String =
        // Allow values >100% (top-style IRIX) — coerce only the lower
        // bound so a transient negative tick delta doesn't leak through.
        String.format("CPU %.0f%%", cpuUsage.coerceAtLeast(0f))

    fun formattedMem(compact: Boolean = false): String {
        val usedGB = memUsedBytes / 1_073_741_824.0
        if (compact) return String.format("MEM %.1fG", usedGB)
        val totalGB = memTotalBytes / 1_073_741_824.0
        return String.format("MEM %.1f/%.1f GB", usedGB, totalGB)
    }
}

/**
 * [hud-truthful-sampler] Rolling CPU window — pure, JVM-testable math.
 *
 * Sum-weighted average over the window: sum(ticks) / (sum(wallSec) ×
 * CLK_TCK) × 100 — the fraction of CPU the workload actually used across
 * the whole window, no bias toward shorter or longer slices. IRIX-style:
 * 100% = one fully-loaded core.
 *
 * Delta policy: impossible deltas (negative ticks — a tracer PID left the
 * sum mid-window because its shell was recycled; or non-positive wall) are
 * DROPPED rather than clamped, because a recycled tracer's already-counted
 * ticks must not also count as negative credit. A *growing* sum (new tracer
 * registers mid-window) is legal and kept — that CPU was really burned.
 */
internal class CpuRollingWindow(private val capacity: Int) {
    private val deltas = ArrayDeque<Pair<Long, Long>>()

    /** Latest computed percentage (null when the window is empty). */
    fun percent(clockTicksPerSec: Long): Float? {
        if (deltas.isEmpty()) return null
        var sumTicks = 0L
        var sumNanos = 0L
        for ((t, n) in deltas) { sumTicks += t; sumNanos += n }
        val sumWallSec = sumNanos / 1_000_000_000.0
        if (sumWallSec <= 0.0 || clockTicksPerSec <= 0) return null
        return (sumTicks / (clockTicksPerSec * sumWallSec) * 100.0).toFloat()
    }

    fun add(tickDelta: Long, wallNanos: Long) {
        if (tickDelta < 0 || wallNanos <= 0) return
        deltas.addLast(tickDelta to wallNanos)
        while (deltas.size > capacity) deltas.removeFirst()
    }

    fun clear() {
        deltas.clear()
    }
}

/**
 * Compose-friendly entrypoint. Polls every 1 s while [active] is true
 * (was 2 s — the 5-sample window still averages ≈5 s, but halving the
 * interval makes the HUD visibly track load shifts within the same shell
 * command), triggering a recompose on every sample.
 *
 * Stops sampling when [active] flips false (LaunchedEffect cancels its
 * coroutine). Cheap on backgrounded shells: no thread, no broadcast,
 * no allocations beyond the /proc reads.
 */
@Composable
fun rememberSystemResourceMonitor(active: Boolean): SystemResourceMonitor {
    val context = LocalContext.current
    val monitor = remember { SystemResourceMonitor() }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(active) {
        if (!active) {
            monitor.reset()
            return@LaunchedEffect
        }
        // Prime the baseline; first read returns 0% by design (no prior
        // tick snapshot to subtract). One second later we have a real delta.
        monitor.sampleOnce(context)
        tick++
        while (isActive) {
            delay(1000)
            monitor.sampleOnce(context)
            tick++
        }
    }
    // Read `tick` so Compose tracks this snapshot state and recomposes
    // whenever it advances. Without the read the LaunchedEffect would
    // tick happily but no consumer ever sees fresh values.
    @Suppress("UNUSED_VARIABLE") val t = tick
    return monitor
}
