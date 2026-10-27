package com.openminis.app.tools

/**
 * [feat/subagent-roster-routing] Pure presentation helpers for the sub-agent
 * surfaces (the in-chat prompt row and the second-level detail page).
 *
 * Why this file exists: these formatters and labels were living inside
 * `ui/subagent/SubagentUiCommon.kt`, whose file-level imports pull in
 * androidx.compose (runtime, ui.graphics) and the chat theme. That made the
 * functions untestable on the plain JVM — the only feedback loop for them
 * was the full Android build, which is slow enough that a formatting drift
 * (a missing "?" placeholder, a wrong duration unit) shipped unnoticed.
 *
 * The same lesson the sub-agent runtime already learned: pure logic belongs
 * in a pure file, so it can be unit-tested without the Android toolchain.
 * The Compose file keeps the parts that genuinely need Compose (colors,
 * the live ticker) and delegates the rest here.
 *
 * No Android, no Compose, no coroutines — every function is a pure map from
 * a value to a string.
 */
object SubagentRunFormat {

    /**
     * [T-subagent-token-accounting] "in→out" cost label for a run, or null
     * when the provider never reported usage (both -1).
     *
     * Unknown is RENDERED AS ABSENT, never as 0 or -1: a silent provider
     * must not masquerade as a free run. One side unknown keeps a "?"
     * placeholder so a mid-stream snapshot still shows what IS known.
     */
    fun tokenCost(run: SubagentRunRegistry.Run): String? {
        if (run.tokensIn < 0 && run.tokensOut < 0) return null
        fun k(n: Int): String = if (n < 1000) n.toString() else String.format("%.1fk", n / 1000.0)
        val inPart = if (run.tokensIn >= 0) k(run.tokensIn) else "?"
        val outPart = if (run.tokensOut >= 0) k(run.tokensOut) else "?"
        return "$inPart→$outPart"
    }

    /** Compact duration formatting shared by pill + detail page. */
    fun duration(ms: Long): String = when {
        ms < 0L -> "0s"
        ms < 1000L -> "${ms}ms"
        ms < 60_000L -> String.format("%.1fs", ms / 1000.0)
        else -> String.format("%dm%02ds", ms / 60_000, (ms % 60_000) / 1000)
    }

    /**
     * [T-subagent-queue-visibility] "waited 2m10s" label for a run that
     * spent time queued before executing, or null when it started
     * immediately (the common case — no chip, no noise) or the wait was
     * imperceptible.
     */
    fun queueWait(run: SubagentRunRegistry.Run): String? {
        val waited = run.queueWaitMs ?: return null
        if (waited < QUEUE_WAIT_NOTABLE_MS) return null
        return "waited ${duration(waited)}"
    }

    /**
     * Short human label for a run state — identical wording on both
     * surfaces.
     */
    fun statusLabel(run: SubagentRunRegistry.Run?): String = when {
        run == null -> "Finished"
        run.isQueued -> "Queued"
        run.isExecuting -> "Running"
        else -> when (run.status) {
            SubagentRunRegistry.RunStatus.SUCCESS -> "Completed"
            SubagentRunRegistry.RunStatus.FAILED -> "Failed"
            SubagentRunRegistry.RunStatus.TIMED_OUT -> "Timed out"
            SubagentRunRegistry.RunStatus.CANCELLED -> "Cancelled"
            SubagentRunRegistry.RunStatus.QUEUED -> "Queued"
            SubagentRunRegistry.RunStatus.RUNNING -> "Running"
        }
    }

    /**
     * Turn progress in [0,1] for the slim progress bar, or null when the run
     * is not executing (queued / terminal runs show no bar). Clamped away
     * from both ends so a live run always reads as "in motion", never
     * stuck-full.
     */
    fun turnProgressFraction(run: SubagentRunRegistry.Run): Float? {
        if (!run.isExecuting || run.maxTurns <= 0) return null
        val fraction = run.turn.toFloat() / run.maxTurns.toFloat()
        return fraction.coerceIn(0.04f, 0.96f)
    }

    /** Below this, a queue hop is scheduler jitter rather than a visible wait. */
    private const val QUEUE_WAIT_NOTABLE_MS = 1000L
}
