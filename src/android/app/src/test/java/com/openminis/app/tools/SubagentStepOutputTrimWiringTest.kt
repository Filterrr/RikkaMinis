package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-step-output-parity] The main loop and the sub-agent must
 * clip a tool's output with the SAME rule.
 *
 * They had drifted: the main loop gated the tail trim to `shell_execute`
 * (T263), while the sub-agent clipped EVERY tool with `takeLast(60)` —
 * which dropped file_read's metadata banner.
 */
class SubagentStepOutputTrimWiringTest {

    private val runnerSource: String by lazy {
        ProductionSources.read("tools/SubagentRunner.kt")
    }

    private val chatSource: String by lazy {
        ProductionSources.read("ui/chat/ChatViewModel.kt")
    }

    @Test
    fun `the sub-agent no longer clips with a bare takeLast`() {
        assertFalse(
            "the sub-agent must not clip every tool with takeLast",
            runnerSource.contains(
                "lines().takeLast(\n                            SubagentRunRegistry.MAX_STEP_OUTPUT_LINES",
            ),
        )
        assertFalse(
            "the sub-agent must not clip every tool with takeLast",
            runnerSource.contains(
                "lines().takeLast(\n                    SubagentRunRegistry.MAX_STEP_OUTPUT_LINES",
            ),
        )
    }

    @Test
    fun `the sub-agent routes every step preview through the shared rule`() {
        // Both call sites: the generic dispatcher and the shell executor.
        // The `output =` argument is several lines below the call, so each
        // site is bounded to the next call rather than to the first line.
        val sites = runnerSource.split("registry.stepFinished(").drop(1)
            .map { it.take(1200) }
        val withOutput = sites.filter { it.contains("output =") }
        assertTrue(
            "expected at least two stepFinished call sites, found ${withOutput.size}",
            withOutput.size >= 2,
        )
        for (site in withOutput) {
            assertTrue(
                "every step preview must go through SubagentStepOutputTrim",
                site.contains("SubagentStepOutputTrim.trim("),
            )
        }
    }

    @Test
    fun `the main loop routes its shell clip through the shared rule`() {
        assertTrue(
            "the main loop must use the shared rule",
            chatSource.contains("SubagentStepOutputTrim.trim(name, result.output)"),
        )
        assertTrue(
            "the main loop must use the shared rule's shell test",
            chatSource.contains("SubagentStepOutputTrim.isShell(name)"),
        )
    }

    @Test
    fun `neither loop hard-codes the shell tail length any more`() {
        // The 80 was duplicated at two sites in the main loop; now it lives
        // in one place. The match must ignore COMMENTS — the code comments
        // legitimately mention takeLast(80) while explaining the history —
        // and must not catch unrelated uses (the length-wall reminder also
        // takes the last 80 CHARS of the turn text).
        val code = chatSource.lines().filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(
            "the main loop must not hard-code takeLast(80) on tool output",
            code.contains("result.output.lines().takeLast(80)"),
        )
        assertFalse(
            "the main loop must not hard-code takeLast(80) on tool output",
            code.contains("?.lines()?.takeLast(80)"),
        )
    }

    @Test
    fun `every main-loop tool-output clip goes through the shared rule`() {
        // The uniform tail-clip appeared at TWO sites in the main loop: the
        // live path and the DB-restore path. Both must use the shared rule
        // now — a guard that only checks the first is how the second was
        // missed the first time.
        val clips = chatSource.lines()
            .filter { it.contains("takeLast(") && it.contains("output") }
        assertTrue(
            "the main loop should have no raw takeLast() on tool output, found: $clips",
            clips.isEmpty(),
        )
    }
}
