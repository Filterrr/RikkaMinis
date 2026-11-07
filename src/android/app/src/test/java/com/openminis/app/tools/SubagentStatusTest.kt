package com.openminis.app.tools

import com.openminis.app.tools.SubagentRunAssessment.Outcome
import com.openminis.app.tools.SubagentRunRegistry.Run
import com.openminis.app.tools.SubagentRunRegistry.RunStatus
import com.openminis.app.tools.SubagentRunRegistry.Step
import com.openminis.app.tools.SubagentRunRegistry.ToolStepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [port/upstream-subagent-status] The `subagent_status` read-out must tell the
 * parent the truth about a run, and must stay bounded.
 *
 * The rules pinned here are the ones a careless rewrite silently breaks:
 *  - a finished run is labelled by its VERDICT, not by RunStatus.SUCCESS
 *    (a DEGRADED digest reported as SUCCESS is the exact bug
 *    SubagentRunAssessment was introduced to kill);
 *  - CANCELLED outranks the verdict (cancel records FAILED, which would
 *    otherwise read as a crash);
 *  - an active run with no tool in flight says so instead of showing nothing;
 *  - a run that delivered nothing says so instead of showing nothing;
 *  - the list is ordered by what the parent can ACT on;
 *  - the output is capped, and the cap is announced.
 */
class SubagentStatusTest {

    private fun run(
        id: String,
        status: RunStatus,
        startedAtMs: Long = 1_000L,
        endedAtMs: Long = 0L,
        title: String = "t",
        skillName: String = "general-agent",
        turn: Int = 0,
        maxTurns: Int = 0,
        resultText: String = "",
        error: String? = null,
        outcome: Outcome? = null,
        steps: List<Step> = emptyList(),
        tokensIn: Int = -1,
        tokensOut: Int = -1,
        modelLabel: String = "",
        groupId: String = "",
    ) = Run(
        id = id,
        blockId = "b-$id",
        skillId = "skill-$id",
        skillName = skillName,
        query = "q",
        title = title,
        groupId = groupId,
        status = status,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        turn = turn,
        maxTurns = maxTurns,
        resultText = resultText,
        runOutcome = outcome,
        error = error,
        steps = steps,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        modelLabel = modelLabel,
    )

    private fun step(tool: String, status: ToolStepStatus, title: String = "") =
        Step(id = "s-$tool", turn = 1, toolName = tool, toolTitle = title, status = status)

    @Test
    fun `an empty conversation says so instead of returning an empty report`() {
        val out = SubagentStatus.format(emptyList(), nowMs = 10_000L)
        assertTrue(out.startsWith("No sub-agent runs"))
    }

    @Test
    fun `a running run reports its state, turn, current tool, model and cost`() {
        val r = run(
            "abcdef012345", RunStatus.RUNNING,
            startedAtMs = 1_000L, turn = 4, maxTurns = 75,
            steps = listOf(step("shell_execute", ToolStepStatus.SUCCESS), step("browser_use", ToolStepStatus.RUNNING, "Open docs")),
            tokensIn = 12_345, tokensOut = 1_900, modelLabel = "Flash (primary)",
        )
        val out = SubagentStatus.format(listOf(r), nowMs = 61_000L)
        assertTrue("state line", out.contains("state:   RUNNING"))
        assertTrue("turn budget", out.contains("turn 4/75"))
        assertTrue("elapsed", out.contains("(1m0s)"))
        assertTrue("in-flight tool", out.contains("now:     browser_use — Open docs"))
        assertTrue("model", out.contains("model:   Flash (primary)"))
        assertTrue("cost", out.contains("tokens:  in 12.3k / out 1.9k"))
        assertTrue("short id", out.contains("#abcdef01"))
        assertFalse("no raw id", out.contains("abcdef012345"))
    }

    @Test
    fun `a finished run is labelled by its verdict, not by RunStatus SUCCESS`() {
        val r = run("r1", RunStatus.SUCCESS, endedAtMs = 5_000L, outcome = Outcome.DEGRADED, resultText = "digest")
        assertEquals("DEGRADED", SubagentStatus.stateLabel(r))
    }

    @Test
    fun `a SUCCESS run with no recorded verdict falls back to SUCCESS`() {
        assertEquals("SUCCESS", SubagentStatus.stateLabel(run("r1", RunStatus.SUCCESS)))
    }

    @Test
    fun `CANCELLED outranks the FAILED verdict that cancel records`() {
        val r = run("r1", RunStatus.CANCELLED, outcome = Outcome.FAILED, error = "cancelled by parent agent")
        assertEquals("CANCELLED", SubagentStatus.stateLabel(r))
        assertTrue(SubagentStatus.line(r, 10_000L).contains("error:   cancelled by parent agent"))
    }

    @Test
    fun `an active run with no tool in flight says it is between turns`() {
        val r = run("r1", RunStatus.RUNNING, startedAtMs = 0L)
        assertTrue(SubagentStatus.currentActivity(r)!!.startsWith("starting"))
        val r2 = run("r2", RunStatus.RUNNING, steps = listOf(step("file_read", ToolStepStatus.SUCCESS, "Read a.kt")))
        assertTrue(SubagentStatus.currentActivity(r2)!!.startsWith("between turns, last: file_read"))
    }

    @Test
    fun `a finished run is not given a now line`() {
        val r = run("r1", RunStatus.SUCCESS, endedAtMs = 5_000L, outcome = Outcome.COMPLETED, resultText = "done")
        assertNull(SubagentStatus.currentActivity(r))
        assertFalse(SubagentStatus.line(r, 10_000L).contains("now:"))
    }

    @Test
    fun `a run that delivered nothing says nothing was delivered`() {
        val r = run("r1", RunStatus.FAILED, endedAtMs = 5_000L, outcome = Outcome.FAILED)
        assertTrue(SubagentStatus.line(r, 10_000L).contains("result:  (nothing delivered)"))
    }

    @Test
    fun `ordering puts what the parent can act on first`() {
        val queued = run("q", RunStatus.QUEUED, startedAtMs = 10L)
        val running = run("r", RunStatus.RUNNING, startedAtMs = 90L)
        val oldDone = run("d1", RunStatus.SUCCESS, endedAtMs = 100L)
        val newDone = run("d2", RunStatus.SUCCESS, endedAtMs = 300L)
        val ordered = SubagentStatus.order(listOf(oldDone, queued, newDone, running)).map { it.id }
        assertEquals(listOf("r", "q", "d2", "d1"), ordered)
    }

    @Test
    fun `the header counts states`() {
        val runs = listOf(
            run("a", RunStatus.RUNNING), run("b", RunStatus.QUEUED),
            run("c", RunStatus.SUCCESS, endedAtMs = 1L), run("d", RunStatus.FAILED, endedAtMs = 2L),
        )
        assertEquals("4 sub-agent runs: 1 running, 1 queued, 2 finished", SubagentStatus.headerLine(runs))
        assertEquals("1 sub-agent run: 1 running", SubagentStatus.headerLine(listOf(run("a", RunStatus.RUNNING))))
    }

    @Test
    fun `the list is capped and the cap is announced`() {
        val runs = (1..25).map { run("r$it", RunStatus.SUCCESS, endedAtMs = it.toLong()) }
        val out = SubagentStatus.format(runs, nowMs = 1_000L)
        assertTrue(out.contains("(+5 more not shown"))
        assertTrue(out.contains("25 sub-agent runs"))
    }

    @Test
    fun `token usage the provider never reported is unknown, not zero`() {
        assertNull(SubagentStatus.costLabel(run("r1", RunStatus.RUNNING)))
        val half = run("r2", RunStatus.RUNNING, tokensIn = 400)
        assertEquals("in 400 / out unknown", SubagentStatus.costLabel(half))
    }

    @Test
    fun `elapsed buckets stay coarse and locale free`() {
        assertEquals("0s", SubagentStatus.elapsedLabel(0L))
        assertEquals("59s", SubagentStatus.elapsedLabel(59_000L))
        assertEquals("1m0s", SubagentStatus.elapsedLabel(60_000L))
        assertEquals("59m59s", SubagentStatus.elapsedLabel(3_599_000L))
        assertEquals("1h0m", SubagentStatus.elapsedLabel(3_600_000L))
    }

    @Test
    fun `a multi-line report is collapsed to one preview line`() {
        val r = run("r1", RunStatus.SUCCESS, endedAtMs = 5_000L, outcome = Outcome.COMPLETED,
            resultText = "line one\n\nline two\n" + "x".repeat(1_000))
        val line = SubagentStatus.line(r, 10_000L)
        val resultLine = line.split("\n").first { it.trimStart().startsWith("result:") }
        assertFalse("newlines collapsed", resultLine.contains("line one\n"))
        assertTrue("truncated marker", resultLine.endsWith("…"))
        assertTrue("bounded", resultLine.length < SubagentStatus.RESULT_PREVIEW_CHARS + 200)
    }

    @Test
    fun `the advice names the next move instead of inviting a poll loop`() {
        val out = SubagentStatus.format(listOf(run("r1", RunStatus.RUNNING)), nowMs = 10_000L)
        assertTrue(out.contains("join_subagents"))
        assertTrue(out.contains("Do not poll status in a loop"))
    }

    @Test
    fun `an all-finished conversation does not invite a join`() {
        val out = SubagentStatus.format(
            listOf(run("r1", RunStatus.SUCCESS, endedAtMs = 5_000L, outcome = Outcome.COMPLETED, resultText = "ok")),
            nowMs = 10_000L,
        )
        assertTrue(out.contains("Nothing is running"))
        assertFalse(out.contains("Do not poll status in a loop"))
    }
}