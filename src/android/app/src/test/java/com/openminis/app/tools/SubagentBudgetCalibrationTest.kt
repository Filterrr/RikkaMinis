package com.openminis.app.tools

import com.openminis.app.tools.SubagentRunRegistry.Run
import com.openminis.app.tools.SubagentRunRegistry.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-budget-hardening] Turn pace learned from finished runs.
 *
 * The fixed 15 s/turn was one calibration on one relay; the registry records
 * turns + duration for every finished run. These tests pin the selection
 * order (skill+model → skill → global), the median (not mean) and the
 * floor/ceiling — a run stuck in a retry loop for ten minutes must not move
 * the estimate for the next twenty.
 */
class SubagentBudgetCalibrationTest {

    private fun run(
        id: String,
        skill: String = "general-agent",
        model: String = "model-a",
        turns: Int = 10,
        durationMs: Long = 100_000L,
        status: RunStatus = RunStatus.SUCCESS,
    ): Run = Run(
        id = id,
        blockId = id,
        skillId = skill,
        skillName = skill,
        query = "q-$id",
        title = "t",
        status = status,
        startedAtMs = 1_000L,
        endedAtMs = 1_000L + durationMs,
        turn = turns,
        maxTurns = 40,
        modelLabel = model,
    )

    @Test
    fun `same skill and model are preferred`() {
        val runs = listOf(
            run("a", durationMs = 100_000L),          // 10 s/turn
            run("b", durationMs = 100_000L),
            run("c", durationMs = 200_000L),          // outlier, but median holds
            run("x", skill = "other", durationMs = 900_000L),
        )
        val e = SubagentBudgetCalibration.estimate(runs, "general-agent", "model-a")!!
        assertEquals(10, e.secondsPerTurn)
        assertEquals(1, e.medianMinutes) // 100 s → 1 min (integer division)
        assertEquals(3, e.samples)
        assertTrue(!e.skillOnly)
    }

    @Test
    fun `a different model on the same skill is the second choice`() {
        val runs = listOf(
            run("a", model = "old-model"),
            run("b", model = "old-model"),
            run("c", model = "old-model"),
            run("d", model = "model-a"),
        )
        val e = SubagentBudgetCalibration.estimate(runs, "general-agent", "model-a")!!
        assertTrue("no same-model sample had enough history", e.skillOnly)
        assertEquals(4, e.samples)
    }

    @Test
    fun `fewer than three usable runs refuses to guess`() {
        assertNull(
            SubagentBudgetCalibration.estimate(
                listOf(run("a"), run("b")),
                "general-agent",
                "model-a",
            ),
        )
        // Runs that never turned or never took time are not evidence.
        assertNull(
            SubagentBudgetCalibration.estimate(
                listOf(
                    run("a", turns = 0), run("b", turns = 0), run("c", turns = 0),
                    run("d", durationMs = 0L), run("e", durationMs = 0L),
                ),
                "general-agent",
                "model-a",
            ),
        )
        // A live run must not be sampled.
        assertNull(
            SubagentBudgetCalibration.estimate(
                listOf(
                    run("a", status = RunStatus.RUNNING),
                    run("b", status = RunStatus.RUNNING),
                    run("c", status = RunStatus.RUNNING),
                ),
                "general-agent",
                "model-a",
            ),
        )
    }

    @Test
    fun `the pace is clamped into the sane band`() {
        // 10 minutes over 2 turns = 300 s/turn — a stuck run, not a pace.
        val slow = (1..3).map { run("s$it", turns = 2, durationMs = 600_000L) }
        assertEquals(
            SubagentSkill.MAX_SECONDS_PER_TURN,
            SubagentBudgetCalibration.estimate(slow, "general-agent", "model-a")!!.secondsPerTurn,
        )
        // 6 seconds over 10 turns = 0.6 s/turn — impossible for a model call.
        val fast = (1..3).map { run("f$it", turns = 10, durationMs = 6_000L) }
        assertEquals(
            SubagentSkill.MIN_SECONDS_PER_TURN,
            SubagentBudgetCalibration.estimate(fast, "general-agent", "model-a")!!.secondsPerTurn,
        )
    }

    @Test
    fun `the schema hint names skills and their medians, and stays empty without history`() {
        assertTrue(
            "no history must not invent a number",
            SubagentBudgetCalibration.schemaHint(emptyList()).isEmpty(),
        )
        val runs = listOf(
            run("a", skill = "general-agent", durationMs = 360_000L),
            run("b", skill = "general-agent", durationMs = 360_000L),
            run("c", skill = "general-agent", durationMs = 360_000L),
        )
        val hint = SubagentBudgetCalibration.schemaHint(runs)
        assertTrue(hint.contains("general-agent"))
        assertTrue(hint.contains("median 6 min"))
        assertTrue(hint.contains("3 run(s)"))
    }

    @Test
    fun `the estimate feeds the minutes-to-turns conversion`() {
        // 20 minutes at the observed 10 s/turn is 120 turns, not 80.
        val pace = 10
        assertEquals(
            120,
            SubagentSkill.turnBudgetForTimeoutSeconds(20 * 60, pace),
        )
        val b = SubagentSkill.resolveRunBudget(
            parentMinutes = 20,
            explicitTimeoutSeconds = 0,
            skillMaxTurns = 75,
            secondsPerTurn = pace,
        )
        assertEquals(120, b.maxTurns)
        assertEquals(1200, b.timeoutSeconds)
    }

    @Test
    fun `the cost ceiling is capped so the readout stays believable`() {
        assertEquals(
            480_000L,
            SubagentSkill.worstCaseTokensForBudget(maxTurns = 240, perTurnOutputCeiling = 8_000),
        )
        assertEquals(0L, SubagentSkill.worstCaseTokensForBudget(240, 0))
    }

    @Test
    fun `the inline queue wait scales with the task budget`() {
        assertEquals(
            SubagentSkill.MIN_INLINE_QUEUE_WAIT_MS,
            SubagentSkill.inlineQueueWaitMs(120),
        )
        assertEquals(
            SubagentSkill.MAX_INLINE_QUEUE_WAIT_MS,
            SubagentSkill.inlineQueueWaitMs(3600),
        )
        // 20 minutes → 2 minutes of waiting, in the middle of the band.
        assertEquals(120_000L, SubagentSkill.inlineQueueWaitMs(20 * 60))
        assertEquals(
            SubagentSkill.MIN_INLINE_QUEUE_WAIT_MS,
            SubagentSkill.inlineQueueWaitMs(0),
        )
    }

    @Test
    fun `a null estimate leaves the caller on the published default`() {
        assertNull(SubagentBudgetCalibration.estimate(emptyList(), "general-agent", "m"))
        assertNotNull(SubagentBudgetCalibration.estimate(
            (1..3).map { run("r$it") },
            "general-agent",
            "model-a",
        ))
    }
}