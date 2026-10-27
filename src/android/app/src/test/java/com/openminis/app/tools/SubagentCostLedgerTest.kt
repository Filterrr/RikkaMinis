package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] P1-3 / P2-11 — the chat-level ledger of
 * delegation spend.
 *
 * The contract this pins is mostly about HONESTY:
 *  - a ceiling is a ceiling, and is labelled as one;
 *  - "the provider reported nothing" is NOT "zero tokens";
 *  - a reported total replaces the ceiling, it does not add to it.
 */
class SubagentCostLedgerTest {

    @Test
    fun `an empty ledger has no label`() {
        val ledger = SubagentCostLedger()
        assertNull(ledger.summary())
        assertNull(ledger.worstCaseSummary())
    }

    @Test
    fun `a reserved run contributes its ceiling`() {
        val ledger = SubagentCostLedger()
        ledger.reserve("r1", "general-agent", 1000)
        assertEquals(1000L, ledger.totalWorstCaseTokens())
        assertEquals("≤1000 tok", ledger.worstCaseSummary())
    }

    @Test
    fun `a run with no reported usage stays unknown - never zero`() {
        val ledger = SubagentCostLedger()
        ledger.reserve("r1", "general-agent", 1000)
        ledger.settle("r1", reportedTokens = 0, hasReportedUsage = false)
        assertFalse("the provider reported nothing, so the total is unknown", ledger.hasAnyReportedUsage())
        assertNull("an unknown total must not render as a number", ledger.summary())
        // The ceiling is still available — it is a fact about the config.
        assertEquals("≤1000 tok", ledger.worstCaseSummary())
    }

    @Test
    fun `a reported total replaces the ceiling in the label`() {
        val ledger = SubagentCostLedger()
        ledger.reserve("r1", "general-agent", 1000)
        ledger.settle("r1", reportedTokens = 250, hasReportedUsage = true)
        assertTrue(ledger.hasAnyReportedUsage())
        assertEquals("250 tok", ledger.summary())
    }

    @Test
    fun `one reporting run makes the whole chat's total known`() {
        val ledger = SubagentCostLedger()
        ledger.reserve("r1", "a", 1000)
        ledger.reserve("r2", "b", 2000)
        ledger.settle("r1", reportedTokens = 250, hasReportedUsage = true)
        ledger.settle("r2", reportedTokens = 0, hasReportedUsage = false)
        // r2's usage is unknown, but r1's is known: the label states the
        // KNOWN total. The unknown part is visible in the ceiling.
        assertEquals("250 tok", ledger.summary())
        assertEquals("≤3000 tok", ledger.worstCaseSummary())
    }

    @Test
    fun `the ceiling sums across runs`() {
        val ledger = SubagentCostLedger()
        ledger.reserve("r1", "a", 1000)
        ledger.reserve("r2", "b", 2000)
        ledger.reserve("r3", "c", 500)
        assertEquals(3500L, ledger.totalWorstCaseTokens())
    }

    @Test
    fun `a forgotten run leaves the ledger`() {
        val ledger = SubagentCostLedger()
        ledger.reserve("r1", "a", 1000)
        ledger.forget("r1")
        assertEquals(0L, ledger.totalWorstCaseTokens())
        assertNull(ledger.worstCaseSummary())
    }

    @Test
    fun `a negative ceiling is clamped to zero`() {
        val ledger = SubagentCostLedger()
        ledger.reserve("r1", "a", -5)
        assertEquals(0L, ledger.totalWorstCaseTokens())
    }

    @Test
    fun `settling an unknown run is ignored`() {
        val ledger = SubagentCostLedger()
        ledger.settle("never-reserved", reportedTokens = 999, hasReportedUsage = true)
        assertEquals(0L, ledger.totalReportedTokens())
    }
}
