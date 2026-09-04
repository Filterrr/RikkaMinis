package com.openminis.app.ui.subagent

// [T-subagent-ui] Shared presentation layer for the two sub-agent surfaces
// (in-chat prompt row + second-level detail page): status color, status
// label, a live elapsed-time ticker, and the turn-progress fraction.
// Centralising them means the pill and the detail page can never disagree
// about what a given run state looks like.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.openminis.app.tools.SubagentRunRegistry
import com.openminis.app.ui.chat.ToolCancelColor
import com.openminis.app.ui.chat.ToolCheckColor
import com.openminis.app.ui.chat.ToolErrorColor
import com.openminis.app.ui.chat.toolAccentColor
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

/** The violet "another agent" family accent shared by all run states. */
internal val SubagentAccent: Color
    @Composable
    get() = toolAccentColor("spawn_agent")

/**
 * Status accent for a run: violet while it is alive (queued / running),
 * semantic once terminal (success green / failed red / cancelled yellow).
 */
@Composable
internal fun runStatusColor(run: SubagentRunRegistry.Run?): Color = when {
    run == null -> ChatColors.secondaryText
    run.isQueued -> ChatColors.tertiaryText
    run.isExecuting -> SubagentAccent
    run.status == SubagentRunRegistry.RunStatus.SUCCESS -> ToolCheckColor
    run.status == SubagentRunRegistry.RunStatus.FAILED -> ToolErrorColor
    else -> ToolCancelColor
}

/** Short human label for a run state — identical wording on both surfaces. */
internal fun runStatusLabel(run: SubagentRunRegistry.Run?): String = when {
    run == null -> "Finished"
    run.isQueued -> "Queued"
    run.isExecuting -> "Running"
    else -> when (run.status) {
        SubagentRunRegistry.RunStatus.SUCCESS -> "Completed"
        SubagentRunRegistry.RunStatus.FAILED -> "Failed"
        SubagentRunRegistry.RunStatus.CANCELLED -> "Cancelled"
        SubagentRunRegistry.RunStatus.QUEUED -> "Queued"
        SubagentRunRegistry.RunStatus.RUNNING -> "Running"
    }
}

/**
 * Live elapsed time for a run, in ms. While the run is active the returned
 * value re-composes once per second so the timer keeps ticking even between
 * registry publishes; once terminal it freezes at the recorded duration
 * (no ticker allocated).
 */
@Composable
internal fun rememberRunElapsedMs(run: SubagentRunRegistry.Run): Long {
    val active = run.isActive
    // Seed from the registry's own duration so a page opened mid-run shows
    // the true elapsed time on the first frame (the ticker then keeps it
    // within 1s of truth between registry publishes).
    var nowMs by remember(run.id) {
        mutableLongStateOf(run.startedAtMs + run.durationMs)
    }
    LaunchedEffect(run.id, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    return if (active) (nowMs - run.startedAtMs).coerceAtLeast(0L) else run.durationMs
}

/**
 * Turn progress in [0,1] for the slim progress bar, or null when the run
 * is not executing (queued / terminal runs show no bar). Clamped away from
 * both ends so a live run always reads as "in motion", never stuck-full.
 */
internal fun turnProgressFraction(run: SubagentRunRegistry.Run): Float? {
    if (!run.isExecuting || run.maxTurns <= 0) return null
    val fraction = run.turn.toFloat() / run.maxTurns.toFloat()
    return fraction.coerceIn(0.04f, 0.96f)
}

/** Compact duration formatting shared by pill + detail page. */
internal fun formatSubagentDuration(ms: Long): String = when {
    ms < 0L -> "0s"
    ms < 1000L -> "${ms}ms"
    ms < 60_000L -> String.format("%.1fs", ms / 1000.0)
    else -> String.format("%dm%02ds", ms / 60_000, (ms % 60_000) / 1000)
}
