package com.openminis.app.tools

/**
 * [fix/subagent-verdict-and-report-gate] Counts the tool calls a run
 * ACTUALLY executed.
 *
 * ## The defect
 *
 * The runner counted `toolCalls.size` — the number of calls the model
 * PROPOSED. But the loop detector short-circuits a call at CRITICAL level
 * and returns a synthesized error result WITHOUT running the tool. Those
 * calls were counted as executed, so a run whose every call was blocked
 * reported three executed tools and the verdict COMPLETED, while nothing
 * had actually touched the filesystem. The counter's own comment claimed it
 * counted "actually EXECUTED" calls — the implementation did not match the
 * comment.
 *
 * Measured: 3 calls, all blocked → the old count said 3 → outcome
 * COMPLETED (deliverable); the truth is 0 → outcome UNSTARTED (not
 * deliverable). A run that did nothing must say so, whatever its prose.
 *
 * ## Why this is an object
 *
 * Same reason as [SubagentReportDecision]: the bug lived in a call site's
 * arithmetic, which no unit test could reach. Moving the count here makes
 * the rule testable on its own.
 *
 * Pure + JVM-testable: no Android, no registry, no clock.
 */
object SubagentExecutedCallCount {

    /**
     * Count the calls that really ran.
     *
     * @param proposedCallIds every call the model proposed, in order.
     * @param blockedCallIds the calls the loop detector short-circuited at
     *   CRITICAL level. These never executed.
     * @return the number of calls that actually executed.
     */
    fun count(proposedCallIds: List<String>, blockedCallIds: Set<String>): Int =
        proposedCallIds.count { it !in blockedCallIds }

    /**
     * Accumulate onto a running total.
     */
    fun add(
        current: Int,
        proposedCallIds: List<String>,
        blockedCallIds: Set<String>,
    ): Int = current + count(proposedCallIds, blockedCallIds)
}
