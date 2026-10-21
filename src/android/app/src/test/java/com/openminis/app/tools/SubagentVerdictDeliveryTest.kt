package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-verdict-delivery] Pins that the run's VERDICT reaches EVERY
 * delivery path.
 *
 * The regression: the verdict (outcome + tool count + report source) was
 * computed for the INLINE path only — the one where the runner returns a
 * [SubagentResult] straight to a parent that spawned with
 * `run_until=done`. Every other path built its payload from the registry's
 * `resultText`, which carries the report but not the verdict:
 *
 * - the detached background wake-up ([buildWakePrompt]),
 * - `join_subagents` / `wait_any` (via [SubagentOrchestration.JobOutcome]),
 * - the pull path that completes a job's deferred from the registry
 *   ([SubagentRunner.completeJobFromRegistry]).
 *
 * So a detached run that executed ZERO tools and produced only a plan
 * arrived at the parent as a bare "status SUCCESS" plus the plan as its
 * findings — indistinguishable from a completed investigation.
 */
class SubagentVerdictDeliveryTest {

    // ─── 1. the registry carries the verdict ───────────────────────────────

    @Test
    fun `the registry run carries a verdict field`() {
        val run = SubagentRunRegistry.Run(
            id = "r1", blockId = "b1", skillId = "s", skillName = "general-agent",
            query = "q", title = "t",
        )
        assertEquals("", run.verdict)
    }

    @Test
    fun `setVerdict stores the verdict on the run`() {
        val registry = SubagentRunRegistry()
        registry.register(
            blockId = "b1", skillId = "s", skillName = "general-agent",
            query = "q", title = "t", maxTurns = 40,
        )
        val run = registry.runs.value.first()
        registry.setVerdict(run.id, "[unstarted] 0 tool calls executed - nothing was read, run or written.")

        val after = registry.runs.value.first { it.id == run.id }
        assertEquals(
            "[unstarted] 0 tool calls executed - nothing was read, run or written.",
            after.verdict,
        )
    }

    @Test
    fun `a blank verdict is not written`() {
        val registry = SubagentRunRegistry()
        registry.register(
            blockId = "b1", skillId = "s", skillName = "general-agent",
            query = "q", title = "t", maxTurns = 40,
        )
        val run = registry.runs.value.first()
        registry.setVerdict(run.id, "   ")
        assertEquals("", registry.runs.value.first { it.id == run.id }.verdict)
    }

    // ─── 2. the wake-up prompt shows it ───────────────────────────────────

    @Test
    fun `the background wake-up prompt states the verdict`() {
        val snapshot = SubagentRunRegistry.Run(
            id = "r1", blockId = "b1", skillId = "s", skillName = "general-agent",
            query = "q", title = "t",
            status = SubagentRunRegistry.RunStatus.SUCCESS,
            turn = 1, maxTurns = 40,
            resultText = "I will gather the evidence.",
            verdict = "[unstarted] 0 tool calls executed - nothing was read, run or written.",
        )
        val outcome = SubagentOrchestration.JobOutcome(
            runId = "r1", success = true, report = "I will gather the evidence.",
            skillName = "general-agent",
        )
        val prompt = buildWakePrompt(outcome, snapshot)
        assertTrue(
            "the wake-up must carry the verdict:\n$prompt",
            prompt.contains("verdict: [unstarted] 0 tool calls executed"),
        )
    }

    @Test
    fun `a run without a verdict still renders`() {
        val snapshot = SubagentRunRegistry.Run(
            id = "r1", blockId = "b1", skillId = "s", skillName = "general-agent",
            query = "q", title = "t",
            status = SubagentRunRegistry.RunStatus.SUCCESS,
            resultText = "done",
        )
        val outcome = SubagentOrchestration.JobOutcome(
            runId = "r1", success = true, report = "done", skillName = "general-agent",
        )
        val prompt = buildWakePrompt(outcome, snapshot)
        assertTrue(prompt.contains("run_id: r1"))
        assertFalse("no verdict line when there is none", prompt.contains("verdict:"))
    }

    // ─── 3. join / wait_any carry it ──────────────────────────────────────

    @Test
    fun `the job outcome carries the verdict`() {
        val outcome = SubagentOrchestration.JobOutcome(
            runId = "r1", success = true, report = "plan only",
            skillName = "general-agent",
            verdict = "[unstarted] 0 tool calls executed - nothing was read, run or written.",
        )
        assertEquals(
            "[unstarted] 0 tool calls executed - nothing was read, run or written.",
            outcome.verdict,
        )
    }

    @Test
    fun `a job outcome without a verdict is still constructible`() {
        val outcome = SubagentOrchestration.JobOutcome(
            runId = "r1", success = true, report = "x", skillName = "general-agent",
        )
        assertEquals("", outcome.verdict)
    }

    // ─── 4. the runner writes the verdict on the terminal paths ───────────

    @Test
    fun `the runner records the verdict on its terminal paths`() {
        val src = com.openminis.app.ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "the runner must record the verdict for the registry-reading paths",
            src.contains("registry.setVerdict("),
        )
        // Every terminal exit that produces a result must record one:
        // success, blank output, first-turn readout, timeout, failure.
        // The count is checked EXACTLY so removing one path turns this red.
        val setVerdictCalls = Regex("registry\\.setVerdict\\(").findAll(src).count()
        assertEquals(
            "every terminal path must record its verdict",
            5, setVerdictCalls,
        )
    }

    @Test
    fun `the join rendering shows the verdict`() {
        val src = com.openminis.app.ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "join_subagents must render the verdict",
            src.contains("verdict: "),
        )
    }

    // ─── 5. the pull path that completes a deferred ───────────────────────

    @Test
    fun `the deferred completion path reads the verdict from the registry`() {
        val src = com.openminis.app.ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "completeJobFromRegistry must carry the verdict from the snapshot",
            src.contains("verdict = snapshot?.verdict"),
        )
    }
}
