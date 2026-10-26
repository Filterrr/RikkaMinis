package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] P1-4 — a run that did NOT finish cleanly
 * must still deliver what it produced.
 *
 * The failure this guards against is easy to miss because the subsystem
 * already HAD the right behaviour on the happy path:
 * [SubagentReportDecision] rebuilds a digest from the tool calls when the
 * model's closing message carries no findings. The crash / timeout /
 * cancel paths did not — they passed the raw accumulated text, which for
 * a tool-driven run is empty, so the parent received "FAILED, no report"
 * while the journal held the only copy of the work.
 *
 * Measured live: 3 audit sub-agents, 16 turns and 8+ successful tool
 * calls each, 0 reports delivered to the parent.
 */
class SubagentPartialRecoveryTest {

    private fun step(
        tool: String = "shell_execute",
        title: String = "search the repo",
        success: Boolean = true,
        output: String = "found 3 candidate files",
    ) = SubagentPartialRecovery.Step(
        toolName = tool,
        toolTitle = title,
        success = success,
        output = output,
    )

    @Test
    fun `the model's own words win when there are any`() {
        val r = SubagentPartialRecovery.recover(
            accumulatedText = "I found the defect in SubagentRunner.kt line 1880.",
            steps = listOf(step()),
            stopReason = "CRASHED (Stream error)",
        )
        assertEquals(SubagentRunAssessment.ReportSource.PARTIAL, r.source)
        assertEquals("I found the defect in SubagentRunner.kt line 1880.", r.report)
    }

    @Test
    fun `an empty report is rebuilt from the tool steps`() {
        val r = SubagentPartialRecovery.recover(
            accumulatedText = "",
            steps = listOf(step(output = "grep found 12 hits")),
            stopReason = "CRASHED (Stream error)",
        )
        assertEquals(SubagentRunAssessment.ReportSource.RUNTIME_DIGEST, r.source)
        assertTrue(
            "the tool output must survive",
            r.report.contains("grep found 12 hits"),
        )
    }

    @Test
    fun `the digest names the tool and its success`() {
        val r = SubagentPartialRecovery.recover(
            accumulatedText = "",
            steps = listOf(step(tool = "file_read", title = "read the runner", success = false)),
            stopReason = "TIMED OUT after 900s",
        )
        assertTrue(r.report.contains("read the runner"))
        assertTrue(r.report.contains("file_read"))
        assertTrue("a failed step must be marked as failed", r.report.contains("FAILED"))
    }

    @Test
    fun `the gap line names WHY the run stopped`() {
        val r = SubagentPartialRecovery.recover(
            accumulatedText = "",
            steps = listOf(step()),
            stopReason = "TIMED OUT after 900s",
        )
        assertTrue(
            "the parent must know the evidence is incomplete, and why",
            r.report.contains("TIMED OUT after 900s"),
        )
    }

    @Test
    fun `the digest is never dressed up as a conclusion`() {
        val r = SubagentPartialRecovery.recover(
            accumulatedText = "",
            steps = listOf(step()),
            stopReason = "CRASHED (Stream error)",
        )
        assertTrue(
            "a partial must be marked partial",
            r.report.contains("partial"),
        )
        assertTrue(
            "a partial must not read as a conclusion",
            r.report.contains("not a conclusion"),
        )
    }

    @Test
    fun `nothing survived means nothing is invented`() {
        val r = SubagentPartialRecovery.recover(
            accumulatedText = "   ",
            steps = emptyList(),
            stopReason = "CRASHED (Stream error)",
        )
        assertEquals("", r.report)
        assertEquals(SubagentRunAssessment.ReportSource.NONE, r.source)
    }

    @Test
    fun `a failed step still carries its output`() {
        val r = SubagentPartialRecovery.recover(
            accumulatedText = "",
            steps = listOf(step(success = false, output = "exit code 1: no such file")),
            stopReason = "CRASHED",
        )
        assertTrue(
            "a failed step's output is evidence too",
            r.report.contains("no such file"),
        )
    }
}
