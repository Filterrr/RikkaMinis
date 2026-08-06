package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ExpMem-outcome] Pins the exchange-outcome decision table and its feedback
 * policy. These semantics are what make the experience memory trustworthy:
 * an empty reply or a turn-limit exhaustion must NEVER look like a success.
 */
class ExperienceExchangeClassifierTest {

    @Test
    fun `normal exit with visible content and no tool failure is SUCCESS`() {
        assertEquals(
            Outcome.SUCCESS,
            ExperienceExchangeClassifier.classify(
                loopExitedNormally = true,
                hasVisibleContent = true,
                hasToolFailure = false,
            )
        )
    }

    @Test
    fun `reply with a failed or timed-out tool is PARTIAL not SUCCESS`() {
        // ⑫: reply produced but at least one tool FAILED/TIMEOUT → PARTIAL
        assertEquals(
            Outcome.PARTIAL,
            ExperienceExchangeClassifier.classify(
                loopExitedNormally = true,
                hasVisibleContent = true,
                hasToolFailure = true,
            )
        )
    }

    @Test
    fun `empty response after retries is EMPTY_RESPONSE not SUCCESS`() {
        // ⑪: loop exited cleanly but neither text nor tool call → EMPTY_RESPONSE
        assertEquals(
            Outcome.EMPTY_RESPONSE,
            ExperienceExchangeClassifier.classify(
                loopExitedNormally = true,
                hasVisibleContent = false,
                hasToolFailure = false,
            )
        )
    }

    @Test
    fun `exhausted agent turns is TURN_LIMIT regardless of content`() {
        // ⑬: loopExitedNormally=false means we hit MAX_AGENT_TURNS — even if
        // partial text/tools accumulated, the run did not finish cleanly.
        assertEquals(
            Outcome.TURN_LIMIT,
            ExperienceExchangeClassifier.classify(
                loopExitedNormally = false,
                hasVisibleContent = true,
                hasToolFailure = false,
            )
        )
        assertEquals(
            Outcome.TURN_LIMIT,
            ExperienceExchangeClassifier.classify(
                loopExitedNormally = false,
                hasVisibleContent = false,
                hasToolFailure = true,
            )
        )
    }

    // ── feedback policy: only outcomes with ground truth get ±1 ──

    @Test
    fun `SUCCESS gives plus-one feedback`() {
        assertEquals(1, Outcome.SUCCESS.feedbackDelta)
    }

    @Test
    fun `explicit failures give minus-one feedback`() {
        assertEquals(-1, Outcome.FAILURE.feedbackDelta)
        assertEquals(-1, Outcome.EMPTY_RESPONSE.feedbackDelta)
        assertEquals(-1, Outcome.TURN_LIMIT.feedbackDelta)
        assertEquals(-1, Outcome.EXCEPTION.feedbackDelta)
    }

    @Test
    fun `PARTIAL and no-ground-truth outcomes give no feedback`() {
        // ⑫: PARTIAL has a reply but failed tools — no ground truth, never +1.
        assertNull(Outcome.PARTIAL.feedbackDelta)
        // ⑭: cancelled/interrupted runs are never rewarded nor punished.
        assertNull(Outcome.CANCELLED.feedbackDelta)
        assertNull(Outcome.INTERRUPTED.feedbackDelta)
    }

    @Test
    fun `only outcomes with ground truth are storable`() {
        assertEquals(
            setOf(Outcome.SUCCESS, Outcome.PARTIAL, Outcome.FAILURE, Outcome.EMPTY_RESPONSE, Outcome.TURN_LIMIT, Outcome.EXCEPTION),
            Outcome.STORABLE
        )
        // ⑭: cancel/interrupt must never pollute the episode store.
        assertTrue(Outcome.CANCELLED !in Outcome.STORABLE)
        assertTrue(Outcome.INTERRUPTED !in Outcome.STORABLE)
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
