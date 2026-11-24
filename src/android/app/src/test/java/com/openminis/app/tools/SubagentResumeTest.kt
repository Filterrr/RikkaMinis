package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-budget-hardening] The continuation hand-off.
 *
 * Why: a run stopped by its budget used to be finishable only by spawning
 * the same task again, paying twice for every read and every dead end. The
 * hand-off must carry exactly what a continuation needs and nothing that
 * would let it redo the work:
 *
 *  - the previous terminal status + why it stopped;
 *  - the partial report (bounded — it rides in a USER message);
 *  - the artifact paths and the ONE directory to keep writing into;
 *  - an explicit "do not redo what is evidenced" instruction;
 *  - and a statement that the budget is FRESH (otherwise the model paces
 *    itself against a clock that no longer exists).
 *
 * It deliberately does NOT replay the transcript — see the object's KDoc for
 * why a reconstructed tool-call chain would be worse than a summary.
 */
class SubagentResumeTest {

    private fun context(
        runId: String = "run-7",
        skill: String = "general-agent",
        status: String = "TIMED_OUT",
        reason: String = "exceeded the 600-second run budget after 40 turn(s)",
        turns: Int = 40,
        maxTurns: Int = 40,
        report: String = "Found the entry point at app/Main.kt:12.",
        artifacts: List<String> = listOf("/var/minis/workspace/subagents/run-7/notes.md"),
        dir: String = "/var/minis/workspace/subagents/run-7",
    ) = SubagentResume.context(
        previousRunId = runId,
        skillName = skill,
        statusLabel = status,
        stopReason = reason,
        turns = turns,
        maxTurns = maxTurns,
        resultText = report,
        artifacts = artifacts,
        workspaceDir = dir,
    )

    @Test
    fun `the hand-off names the run, the status and why it stopped`() {
        val text = context()
        assertTrue(text.contains("run-7"))
        assertTrue(text.contains("general-agent"))
        assertTrue(text.contains("TIMED_OUT"))
        assertTrue(text.contains("40/40 turn(s)"))
        assertTrue(text.contains("600-second run budget"))
    }

    @Test
    fun `the hand-off quotes the partial report and forbids redoing the work`() {
        val text = context()
        assertTrue(text.contains("app/Main.kt:12"))
        assertTrue(text.contains("DONE"))
        assertTrue(text.contains("Do not re-run", ignoreCase = true) || text.contains("do NOT re-run"))
        assertTrue(text.contains("FRESH budget"))
    }

    @Test
    fun `an empty report is stated, not silently omitted`() {
        val text = context(report = "")
        assertTrue(text.contains("produced no report text"))
    }

    @Test
    fun `a huge report is truncated with a marker`() {
        val text = context(report = "x".repeat(SubagentResume.MAX_REPORT_CHARS + 500))
        assertTrue(text.contains("truncated"))
        assertFalse(
            "the quoted report must respect the cap",
            text.contains("x".repeat(SubagentResume.MAX_REPORT_CHARS + 1)),
        )
    }

    @Test
    fun `artifacts are listed with the directory that owns them`() {
        val text = context()
        assertTrue(text.contains("/var/minis/workspace/subagents/run-7/notes.md"))
        assertTrue(text.contains("/var/minis/workspace/subagents/run-7"))
        assertTrue(text.contains("same directory"))
    }

    @Test
    fun `no recorded artifacts says so instead of claiming an empty list is complete`() {
        val text = context(artifacts = emptyList())
        assertTrue(text.contains("none recorded"))
    }

    @Test
    fun `artifact listing is bounded and counts what it left out`() {
        val many = (1..SubagentResume.MAX_ARTIFACTS + 3).map { "/tmp/f$it.md" }
        val text = context(artifacts = many)
        assertTrue(text.contains("/tmp/f${SubagentResume.MAX_ARTIFACTS}.md"))
        assertFalse(text.contains("/tmp/f${SubagentResume.MAX_ARTIFACTS + 1}.md"))
        assertTrue(text.contains("and 3 more"))
    }

    @Test
    fun `a blank stop reason does not leave a dangling dash`() {
        val text = context(reason = "")
        assertFalse(text.contains(" — ."))
        assertFalse(text.contains("as TIMED_OUT after 40/40 turn(s) —"))
    }

    @Test
    fun `the counts stay honest when the previous run had no turn budget`() {
        val text = context(turns = 7, maxTurns = 0)
        assertTrue(text.contains("7 turn(s)"))
        assertFalse(text.contains("7/0"))
    }

    // ── wiring: the spawn tool must actually accept and use it ───────────

    @Test
    fun `the spawn schema publishes resume_run_id and the runner reads it`() {
        val schema = ProductionSources.read("tools/SubagentSkill.kt")
        assertTrue(
            "spawn_agent must publish resume_run_id",
            schema.contains("\"resume_run_id\" to AgentToolParam("),
        )
        val runner = ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "the runner must read the argument",
            runner.contains("args.optString(\"resume_run_id\", \"\")"),
        )
        assertTrue(
            "a live run must be refused, not resumed",
            runner.contains("SubagentRunRegistry.RunStatus.RUNNING"),
        )
        assertTrue(
            "the continuation must keep writing into the previous run's directory",
            runner.contains("resumeArtifactsDir.isNotBlank()"),
        )
        assertTrue(
            "the hand-off must reach the child's first message",
            runner.contains("\$resumeContext"),
        )
    }

    @Test
    fun `shell commands are capped by the remaining run budget`() {
        val runner = ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "the shell branch must receive the remaining budget",
            runner.contains("budgetLeftSeconds = runSecondsLeft(deadlineNanos)"),
        )
        assertTrue(
            "a command must be refused when there is no time left to start it",
            runner.contains("SubagentSkill.MIN_SHELL_BUDGET_SECONDS"),
        )
        assertTrue(
            "a command's own timeout must be capped by what remains",
            runner.contains("SubagentSkill.SHELL_BUDGET_RESERVE_SECONDS"),
        )
    }

    @Test
    fun `the budget is re-stated mid-run, not only at birth`() {
        val runner = ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "the loop must recompute a budget note each turn",
            runner.contains("SubagentSkill.budgetNote("),
        )
        assertTrue(
            "the note must ride in the system prompt (history alternation stays intact)",
            runner.contains("systemPrompt = effectiveSystemPrompt"),
        )
    }
}