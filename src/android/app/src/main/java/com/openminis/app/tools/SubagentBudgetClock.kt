package com.openminis.app.tools

/**
 * [fix/subagent-queue-budget] Separates the two budgets a spawn has:
 * the time it may WAIT for a concurrency slot, and the time it may RUN once
 * it has one.
 *
 * The bug: the run's deadline was computed at SPAWN time and handed to the
 * scheduler, which used it for BOTH the queue wait and the run. So a task
 * that sat in the queue for ten minutes started with only five minutes of
 * its fifteen-minute budget left — and the parent, which had already spent
 * its own wall-clock waiting, saw the child die of "timeout" after doing
 * almost nothing.
 *
 * The wait is a property of the QUEUE (how busy the app is); the run budget
 * is a property of the TASK (how much work it needs). Charging the queue's
 * delay to the task made the second number depend on the first, and the
 * failure looked like "the sub-agent is too slow" when the truth was "the
 * sub-agent never started".
 *
 * The clock is started when the run actually BEGINS. The queue wait gets its
 * own, separate deadline.
 */
class SubagentBudgetClock private constructor(
    /** The wall-clock deadline for the run, in [System.nanoTime] units. */
    val runDeadlineNanos: Long,
    /** The moment the run started. */
    val startedAtNanos: Long,
) {

    /** The outcome of asking the clock whether a run may still proceed. */
    enum class Verdict {
        /** Plenty of budget left — keep going. */
        OK,

        /** The run budget is exhausted. */
        RUN_BUDGET_EXHAUSTED,
    }


    private var nowNanos: Long = startedAtNanos

    /** Milliseconds left in the run budget (never negative). */
    fun remainingMillis(): Long {
        val left = runDeadlineNanos - nowNanos
        return if (left <= 0L) 0L else left / 1_000_000L
    }

    /** True when the run budget is exhausted. */
    fun isExhausted(): Boolean = remainingMillis() <= 0L

    /**
     * Advance the clock to [nowNanos] and report whether the run may
     * continue.
     */
    fun check(nowNanos: Long = System.nanoTime()): Verdict {
        this.nowNanos = nowNanos
        return if (isExhausted()) Verdict.RUN_BUDGET_EXHAUSTED else Verdict.OK
    }

    companion object {
        /**
         * Start a run clock. [timeoutSeconds] is the wall-clock budget for
         * the run itself; the time spent waiting for a slot is NOT counted.
         */
        fun startRun(timeoutSeconds: Int, nowNanos: Long = System.nanoTime()): SubagentBudgetClock =
            SubagentBudgetClock(
                runDeadlineNanos = nowNanos + timeoutSeconds.coerceAtLeast(0) * 1_000_000_000L,
                startedAtNanos = nowNanos,
            )
    }
}
