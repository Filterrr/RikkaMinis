package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-report-gate-final-message] Pins THAT the report-hygiene gate
 * judges the model's FINAL MESSAGE rather than the run's accumulated text.
 *
 * THE REGRESSION
 *
 * The gate's rule is "carries synthetic tags AND keeps almost no substance
 * once they are stripped" — a 12-meaningful-character floor. It was fed
 * `resultSb`, which holds EVERY turn's narration. Any earlier narration
 * therefore pushed a closing husk over that floor and the gate never fired:
 * the parent received the bare tag as the findings, which is the exact
 * failure the gate exists to prevent.
 *
 * The interaction was not hypothetical. PR #66 made the plan turn
 * non-terminal, and a plan turn ALWAYS carries prose — so the two fixes
 * cancelled each other out for exactly the runs they were written for.
 */
class SubagentReportGateFinalMessageTest {

    private val husk = "<parameter name=\"status\">done</parameter>"

    /** The runner's accumulation, verbatim. */
    private fun accumulate(vararg turns: String): String {
        val sb = StringBuilder()
        turns.forEach { text ->
            if (text.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text)
            }
        }
        return sb.toString().trim()
    }

    /** What the runner now judges: the last message, falling back to the run. */
    private fun judgedText(vararg turns: String): String {
        val finalText = accumulate(*turns)
        val last = turns.lastOrNull()?.trim().orEmpty()
        return SubagentReportGate.textToJudge(last, finalText)
    }

    /** The runner's actual decision, through the production class. */
    private fun gateVerdict(vararg turns: String): SubagentReportHygiene.Verdict {
        val finalText = accumulate(*turns)
        val last = turns.lastOrNull()?.trim().orEmpty()
        return SubagentReportGate.judge(last, finalText)
    }

    // ─── 1. the gate fires on the final message ───────────────────────────

    @Test
    fun `a husk closing a narrated run is still degenerate`() {
        // The shape that defeated the old gate: a plan turn's prose, then
        // the husk. 41 meaningful characters in the ACCUMULATED text — three
        // times the threshold — so the old gate said "fine, not degenerate".
        val turns = arrayOf(
            "I will gather the evidence before reporting.",
            husk,
        )
        val accumulated = accumulate(*turns)
        assertTrue(
            "precondition: the accumulated text is NOT degenerate under the old rule",
            !SubagentReportHygiene.judge(accumulated).degenerate,
        )
        assertTrue(
            "the final message IS degenerate — the gate must fire on it",
            gateVerdict(*turns).degenerate,
        )
    }

    @Test
    fun `a husk after a longer narration is still degenerate`() {
        val turns = arrayOf(
            "Now let me check the system information and the disk layout on this host.",
            husk,
        )
        assertTrue(gateVerdict(*turns).degenerate)
    }

    @Test
    fun `a husk closing a tool run is still degenerate`() {
        val turns = arrayOf(
            "Starting now.",
            "The output shows aarch64 and 28% disk usage.",
            husk,
        )
        assertTrue(gateVerdict(*turns).degenerate)
    }

    // ─── 2. real reports are untouched ────────────────────────────────────

    @Test
    fun `a real final report is not degenerate`() {
        val report = """
            status: done
            key_findings:
            - Ubuntu 24.04.4 LTS on aarch64
            gaps: none
            artifacts: none
        """.trimIndent()
        assertFalse(gateVerdict(report).degenerate)
    }

    @Test
    fun `a terse but real final answer is not degenerate`() {
        assertFalse(gateVerdict("HTTP 200 from example.com").degenerate)
    }

    @Test
    fun `a real final report after earlier narration is not degenerate`() {
        val turns = arrayOf("Let me check that.", "status: done\nkey_findings:\n- the answer is 42")
        assertFalse(gateVerdict(*turns).degenerate)
    }

    // ─── 3. the max-turns note must not mask a husk ───────────────────────

    @Test
    fun `the max-turns note does not mask a closing husk`() {
        // The runner appends "[Sub-agent reached max turns (N)]" to the
        // accumulator BEFORE computing finalText. That note is 14
        // meaningful characters — enough to push a husk over the floor when
        // the accumulated text is judged.
        val note = "\n\n[Sub-agent reached max turns (40)]"
        val accumulated = accumulate(husk + note)
        assertTrue(
            "precondition: judging the ACCUMULATED text (with the note) misses the husk",
            !SubagentReportHygiene.judge(accumulated).degenerate,
        )
        assertTrue(
            "judging the FINAL MESSAGE catches it regardless of the note",
            gateVerdict(husk).degenerate,
        )
    }

    // ─── 4. the runner must judge the last message ────────────────────────

    @Test
    fun `the runner judges the last message, not the accumulated text`() {
        val src = com.openminis.app.ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "the runner must capture the model's last message",
            src.contains("val finalMessage = lastText.trim()"),
        )
        assertTrue(
            "the gate must be fed the final message",
            src.contains("SubagentReportGate.judge(finalMessage, finalText)"),
        )
    }
}
