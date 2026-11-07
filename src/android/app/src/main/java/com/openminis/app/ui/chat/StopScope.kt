package com.openminis.app.ui.chat

import com.openminis.app.tools.SubagentRunRegistry.Run
import com.openminis.app.tools.SubagentRunRegistry.RunStatus

/**
 * [port/upstream-stop-scope] What pressing Stop can actually reach.
 *
 * Before this, Stop cancelled the parent's turn and told the sub-agent registry
 * to reconcile — but nothing CANCELLED the detached runs. They kept burning
 * tokens in the background, and the user had no way to tell that from the UI:
 * the pills stayed lit and the only escape was to cancel them one by one.
 *
 * The scope is deliberately narrow, because "Stop" means "stop what is
 * happening in this conversation", not "stop the app":
 *
 *  - **detached runs of THIS chat** — cancelled. They own their coroutine.
 *  - **inline runs** — NOT cancellable out of band. They execute inside the
 *    dispatch stack of the turn being stopped, so stopping the turn is what
 *    ends them, and by the time this is read they are already unwinding.
 *    Reporting them as stopped would be a lie the user cannot check.
 *  - **other chats' runs** — untouched. A fan-out in another conversation is
 *    not this button's business.
 *
 * Pure: no Android, no coroutines — the counts are derived from a snapshot so
 * the decision is unit-testable.
 */
data class StopSummary(
    /** Runs executing right now in this chat, on their own coroutine. */
    val runningDetached: Int = 0,
    /** Spawns that never got a slot yet. Stopping them spends nothing. */
    val queuedRuns: Int = 0,
    /** Runs executing inside the parent turn — not cancellable out of band. */
    val inlineRuns: Int = 0,
) {
    val hasAnything: Boolean get() = runningDetached > 0 || queuedRuns > 0 || inlineRuns > 0

    /** True when Stop will actually change something beyond the parent turn. */
    val canCancelRuns: Boolean get() = runningDetached > 0 || queuedRuns > 0
}

object StopScopePolicy {

    /**
     * Summarise what a Stop would reach, given this chat's runs.
     *
     * [isDetached] is passed in rather than read from a job, so the policy stays
     * pure and the caller decides what "detached" means (the orchestration
     * registry is the authority in production).
     */
    fun summarize(runs: List<Run>, isDetached: (String) -> Boolean): StopSummary {
        var running = 0
        var queued = 0
        var inline = 0
        for (run in runs) {
            if (!run.isActive) continue
            if (!isDetached(run.id)) {
                inline++
                continue
            }
            when (run.status) {
                RunStatus.RUNNING -> running++
                RunStatus.QUEUED -> queued++
                // Terminal states are filtered by isActive above; the remaining
                // active state is QUEUED/RUNNING only.
                else -> Unit
            }
        }
        return StopSummary(runningDetached = running, queuedRuns = queued, inlineRuns = inline)
    }

    /**
     * One line for the confirmation sheet.
     *
     * Says what will NOT be stopped as well as what will: a user who is told
     * only "2 runs will stop" and then sees an inline run keep writing has been
     * misled by omission.
     */
    fun describe(summary: StopSummary): String {
        if (!summary.hasAnything) return "Nothing else is running in this conversation."
        val parts = mutableListOf<String>()
        if (summary.runningDetached > 0) {
            parts += "${summary.runningDetached} background sub-agent" +
                if (summary.runningDetached == 1) " will be stopped" else "s will be stopped"
        }
        if (summary.queuedRuns > 0) {
            parts += "${summary.queuedRuns} queued spawn" +
                if (summary.queuedRuns == 1) " will be dropped" else "s will be dropped"
        }
        if (summary.inlineRuns > 0) {
            parts += "${summary.inlineRuns} sub-agent" +
                if (summary.inlineRuns == 1) " running inside this turn ends with it" else
                    "s running inside this turn end with it"
        }
        return parts.joinToString("; ") + "."
    }

    /** The reason string recorded on every run this stop cancels. */
    const val REASON: String = "stopped with the conversation by the user"
}