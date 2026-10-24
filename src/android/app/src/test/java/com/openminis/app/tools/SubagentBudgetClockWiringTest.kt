package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-queue-budget] The run's wall-clock budget must start when
 * the RUN starts, not when the spawn was requested — otherwise the time
 * spent queueing for a concurrency slot eats the task's own budget.
 *
 * The pure clock is only worth anything if the CALL SITES use it, so these
 * guards pin the call sites.
 */
class SubagentBudgetClockWiringTest {

    private val runnerSource: String by lazy {
        ProductionSources.read("tools/SubagentRunner.kt")
    }

    @Test
    fun `the run budget is started from the run clock, not from the spawn deadline`() {
        // Both the detached path and the inline path must start the clock
        // where the run actually begins.
        val starts = runnerSource.windowed("SubagentBudgetClock.startRun(timeoutSeconds)".length)
            .count { it == "SubagentBudgetClock.startRun(timeoutSeconds)" }
        assertTrue(
            "both spawn paths must start the run clock: found $starts",
            starts >= 2,
        )
    }

    @Test
    fun `the spawn-time deadline is not passed to executeLoop`() {
        // The old bug: the spawn-time absolute deadline was handed straight
        // to executeLoop, so the queue wait was charged to the run.
        //
        // The guard has to distinguish the two uses of the same identifier:
        // `scheduler.run(..., deadlineNanos = deadlineNanos, ...)` is
        // CORRECT (that is the queue bound), while
        // `executeLoop(..., deadlineNanos = deadlineNanos, ...)` is the bug.
        // So the assertion is scoped to the executeLoop call sites — and it
        // checks ALL of them, because a single missed call site is exactly
        // how the bug survived the first fix.
        val callSites = runnerSource.split("executeLoop(").drop(1)
            .map { site ->
                // Bound each site to its own call: the first `)` on its own
                // line closes the argument list. Without this bound the site
                // would run on to the end of the file and swallow the
                // scheduler call, which legitimately uses the spawn-time
                // deadline as the QUEUE bound.
                val end = site.indexOf("\n                )")
                    .let { if (it < 0) site.indexOf("\n            )") else it }
                if (end > 0) site.substring(0, end) else site.take(1200)
            }
            .filter { it.contains("deadlineNanos =") }
        assertTrue(
            "expected at least two executeLoop call sites, found ${callSites.size}",
            callSites.size >= 2,
        )
        for ((i, site) in callSites.withIndex()) {
            assertFalse(
                "executeLoop call site #$i must not receive the spawn-time deadline:\n$site",
                site.contains("deadlineNanos = deadlineNanos,"),
            )
        }
        assertTrue(
            "executeLoop must receive a clock started at run start",
            runnerSource.contains(
                "deadlineNanos = SubagentBudgetClock.startRun(timeoutSeconds).runDeadlineNanos,",
            ),
        )
    }

    @Test
    fun `the spawn-time deadline survives only as the queue bound`() {
        // The queue deadline is still needed — a spawn must not wait forever
        // for a slot — but it is a QUEUE budget, not the run's budget.
        assertTrue(
            "the scheduler must still get a queue bound",
            runnerSource.contains("scheduler.run(skill.id, config.maxParallel, deadlineNanos)"),
        )
    }
}
