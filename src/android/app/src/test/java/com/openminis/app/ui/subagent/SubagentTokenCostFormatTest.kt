package com.openminis.app.ui.subagent

import com.openminis.app.tools.SubagentRunRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-subagent-token-accounting] Formatting contract for the cost chip on the
 * pill / detail page.
 *
 * The load-bearing rule is the ABSENT case: a provider that never reports
 * usage stores -1 ("unknown"), and rendering that as 0 would tell the user a
 * delegated run was FREE. Unknown must render as nothing, never as a number.
 */
class SubagentTokenCostFormatTest {

    private fun run(inTok: Int, outTok: Int) = SubagentRunRegistry.Run(
        id = "r1", blockId = "b1", skillId = "general-agent", skillName = "general-agent",
        query = "q", title = "t", tokensIn = inTok, tokensOut = outTok,
    )

    @Test
    fun `both sides unknown renders no chip`() {
        assertNull(formatSubagentTokenCost(run(inTok = -1, outTok = -1)))
    }

    @Test
    fun `real usage formats with an arrow and kilo suffix`() {
        assertEquals("1.2k→350", formatSubagentTokenCost(run(inTok = 1200, outTok = 350)))
        assertEquals("52.5k→3.3k", formatSubagentTokenCost(run(inTok = 52_473, outTok = 3_316)))
    }

    @Test
    fun `single-sided reports keep the known half and question the other`() {
        // A mid-stream snapshot can legitimately have input but no output yet.
        assertEquals("900→?", formatSubagentTokenCost(run(inTok = 900, outTok = -1)))
        assertEquals("?→900", formatSubagentTokenCost(run(inTok = -1, outTok = 900)))
    }

    @Test
    fun `a genuine zero is rendered as zero, not as unknown`() {
        assertEquals("0→0", formatSubagentTokenCost(run(inTok = 0, outTok = 0)))
    }
}
