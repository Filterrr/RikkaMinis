package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [process-idle-reap-aggressive-reclaim] JVM tests for the idle-reap watchdog
 * decision ([ModelExecutionService.reapDecision]).
 *
 * The rule being pinned: the :modelservice process may only killProcess
 * itself when NO request is in flight. A concurrent request (parallel
 * session streaming on the same process) must always defer the reap —
 * otherwise the kill would truncate an active stream's stream.jsonl (no
 * DONE/error) and the UI would stall until the 6-min poll timeout: the
 * exact "回答着回答着突然卡住" regression this guard fixes.
 */
class ModelExecutionServiceReapTest {

    @Test
    fun `reap with zero in-flight requests kills`() {
        assertEquals(
            ModelExecutionService.ReapDecision.KILL,
            ModelExecutionService.reapDecision(activeRequests = 0),
        )
    }

    @Test
    fun `reap with one in-flight request defers`() {
        assertEquals(
            ModelExecutionService.ReapDecision.DEFER,
            ModelExecutionService.reapDecision(activeRequests = 1),
        )
    }

    @Test
    fun `reap with many in-flight requests defers`() {
        assertEquals(
            ModelExecutionService.ReapDecision.DEFER,
            ModelExecutionService.reapDecision(activeRequests = 3),
        )
    }

    @Test
    fun `reap never kills while any request is in flight`() {
        for (n in 1..10) {
            assertEquals(
                "activeRequests=$n must always DEFER",
                ModelExecutionService.ReapDecision.DEFER,
                ModelExecutionService.reapDecision(activeRequests = n),
            )
        }
    }
}