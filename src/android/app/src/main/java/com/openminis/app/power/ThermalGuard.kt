package com.openminis.app.power

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.openminis.app.logging.AppLogger

/**
 * [perf/thermal-guard] Central thermal-state tracker + degradation policy.
 *
 * Why this exists: before this, NOTHING in the app observed Android's thermal
 * status. When the device crossed `THERMAL_STATUS_MODERATE`/`SEVERE` the app
 * kept running every load (streaming re-parses, auto-screenshots, WebView JS
 * timers) at full cadence, so the OS had to defend the hardware by CPU
 * throttling / process kills — user-visible as "the longer I use it, the
 * hotter and laggier it gets". This object is the single place that observes
 * the state and exposes cheap degradation queries for hot paths.
 *
 * Design constraints:
 *  - Zero-cost reads: [throttleMultiplier] / [shouldSkipAutoSnapshot] are a
 *    volatile-int comparison. Call sites are per-token / per-action hot paths;
 *    anything heavier (listeners, binder calls) stays confined to [init].
 *  - No behaviour change below API 29: `currentThermalStatus` and
 *    `addThermalStatusListener` are Q+ APIs. On 26–28 [init] is a no-op and
 *    every query returns the neutral value (multiplier 1, never skip).
 *  - Never throws: thermal observation must not be able to crash a turn.
 *
 * Consumers (current):
 *  - streaming/flush throttle tiers → [adjusted] stretches the coalescing
 *    window (fewer parse+recompose passes under heat);
 *  - browser auto-snapshot → [shouldSkipAutoSnapshot] skips the JPEG encode
 *    (a CPU burst per visual-change action) from MODERATE up.
 *
 * Extension points for a future pass: pause KaTeX renders, stretch RSS probe
 * cadence, defer workspace snapshot zips — all read the same level here.
 */
object ThermalGuard {
    private const val TAG = "ThermalGuard"

    // Mirror the PowerManager constants so callers never touch the framework
    // enum directly (and so value comparisons stay stable on the no-op path).
    private const val NONE = 0
    private const val LIGHT = 1
    private const val MODERATE = 2
    private const val SEVERE = 3
    private const val CRITICAL = 4

    /** Neutral until [init] observes otherwise. */
    @Volatile
    private var level: Int = NONE

    @Volatile
    private var initialized: Boolean = false

    /**
     * Register the thermal listener. Idempotent; call once from
     * MinisApp.onCreate (main process only — nothing here is needed in the
     * :modelservice / :toolservice / :acra processes).
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pm = context.applicationContext
                .getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            level = pm.currentThermalStatus
            pm.addThermalStatusListener { status ->
                val previous = level
                level = status
                if (status > previous) {
                    AppLogger.warning(TAG, "thermal status → ${name(status)} (degradation active)")
                } else {
                    AppLogger.info(TAG, "thermal status → ${name(status)}")
                }
            }
            AppLogger.info(TAG, "thermal listener registered (level=${name(level)})")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "thermal listener registration failed: ${t.message}")
        }
    }

    /** Current raw status (mirrors PowerManager.THERMAL_STATUS_*). */
    fun currentLevel(): Int = level

    /** True from MODERATE up — the point where hardware is actively protecting itself. */
    fun isHeated(): Boolean = level >= MODERATE

    /**
     * Multiplier applied to streaming/flush throttle tiers. MODERATE doubles
     * the window, SEVERE+ triples it: fewer wakeups, fewer full-document
     * re-parses, less main/Default-thread churn while the SoC is throttled.
     */
    fun throttleMultiplier(): Int = when {
        level >= SEVERE -> 3
        level >= MODERATE -> 2
        else -> 1
    }

    /**
     * Auto-snapshot (browser_use visual-change actions) encodes a full
     * viewport JPEG per call — a CPU burst we can simply skip while the
     * device is hot. The agent can still request an explicit screenshot;
     * only the silent post-action capture is dropped.
     */
    fun shouldSkipAutoSnapshot(): Boolean = level >= MODERATE

    private fun name(status: Int): String = when (status) {
        NONE -> "NONE"
        LIGHT -> "LIGHT"
        MODERATE -> "MODERATE"
        SEVERE -> "SEVERE"
        CRITICAL -> "CRITICAL"
        else -> "STATUS_$status"
    }
}
