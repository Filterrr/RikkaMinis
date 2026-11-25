package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-parent-minutes-budget] The parent answers ONE question per
 * spawn — how many minutes does this task need — and BOTH budgets are derived
 * from that answer.
 *
 * Why this exists: the old contract advertised `max_turns: 75`, a promise
 * about round-trips. Nothing the parent can judge before delegating is a
 * number of round-trips; what it can judge is duration ("a ten-minute repo
 * sweep"). Worse, the turn budget and the wall clock were independent
 * numbers, and the run stops at whichever comes FIRST — so a 75-turn skill
 * under a 900 s clock was killed at ~turn 60 and reported TIMED_OUT as if
 * the model had given up.
 *
 * These tests pin the arithmetic and the precedence rules:
 *  - minutes → seconds exactly, seconds → turns at 15 s/turn, rounded UP;
 *  - turns clamp at [1, 100] (the token reservation is turns × output
 *    ceiling, so an unbounded number would be refused by the budget guard);
 *  - minutes clamp at [1, 60] (the 3600 s wall-clock ceiling);
 *  - `timeout_seconds` may only TIGHTEN a claimed minute budget;
 *  - no `max_minutes` keeps the legacy skill `max_turns` path byte-identical,
 *    so older callers (tests, direct API use) do not change behaviour.
 */
class SubagentMinutesBudgetTest {

    private fun budget(
        minutes: Int = 0,
        timeoutSeconds: Int = 0,
        skillMaxTurns: Int = 75,
    ) = SubagentSkill.resolveRunBudget(
        parentMinutes = minutes,
        explicitTimeoutSeconds = timeoutSeconds,
        skillMaxTurns = skillMaxTurns,
    )

    // ── seconds → turns ─────────────────────────────────────────────────

    @Test
    fun `turns are derived at the calibrated pace and round up`() {
        assertEquals(1, SubagentSkill.turnBudgetForTimeoutSeconds(1))
        assertEquals(1, SubagentSkill.turnBudgetForTimeoutSeconds(15))
        // 16 s does not fit in one 15 s turn → round UP instead of to zero.
        assertEquals(2, SubagentSkill.turnBudgetForTimeoutSeconds(16))
        assertEquals(4, SubagentSkill.turnBudgetForTimeoutSeconds(60))
        assertEquals(60, SubagentSkill.turnBudgetForTimeoutSeconds(900))
    }

    @Test
    fun `the turn ceiling matches the frontmatter clamp`() {
        // SubagentSkill.parseSubagentConfig clamps max_turns to [1, 100].
        // A derived budget must not exceed what the token reservation was
        // sized for — turns × per-turn output ceiling is reserved up front.
        assertEquals(
            SubagentSkill.MAX_TURNS_CEILING,
            SubagentSkill.turnBudgetForTimeoutSeconds(SubagentSkill.MAX_TIMEOUT_SECONDS),
        )
        assertEquals(240, SubagentSkill.turnBudgetForTimeoutSeconds(1_000_000))
    }

    @Test
    fun `minutes form is exactly the seconds form`() {
        for (m in 1..60) {
            assertEquals(
                "turn budget for $m min must equal the same window in seconds",
                SubagentSkill.turnBudgetForTimeoutSeconds(m * 60),
                SubagentSkill.turnBudgetForMinutes(m),
            )
        }
    }

    // ── resolution: the parent's answer wins ────────────────────────────

    @Test
    fun `the parent minute answer sets both budgets`() {
        val b = budget(minutes = 10)
        assertEquals(40, b.maxTurns)      // 600 s / 15 s per turn
        assertEquals(600, b.timeoutSeconds)
        assertEquals(10, b.parentMinutes)
        assertEquals(SubagentSkill.BudgetSource.PARENT_MINUTES, b.source)
    }

    @Test
    fun `a minute estimate over the ceiling is clamped, not rejected`() {
        val b = budget(minutes = 999)
        assertEquals(SubagentSkill.MAX_MINUTES, b.parentMinutes)
        assertEquals(SubagentSkill.MAX_TIMEOUT_SECONDS, b.timeoutSeconds)
        assertEquals(SubagentSkill.MAX_TURNS_CEILING, b.maxTurns)
    }

    @Test
    fun `timeout_seconds can tighten a claimed minute budget`() {
        val b = budget(minutes = 10, timeoutSeconds = 300)
        assertEquals(300, b.timeoutSeconds)
        assertEquals(20, b.maxTurns)      // the turn ceiling follows the tighter clock
        assertEquals(SubagentSkill.BudgetSource.PARENT_MINUTES, b.source)
    }

    @Test
    fun `timeout_seconds cannot extend a claimed minute budget`() {
        val b = budget(minutes = 10, timeoutSeconds = 9999)
        assertEquals(600, b.timeoutSeconds)
        assertEquals(40, b.maxTurns)
    }

    @Test
    fun `the shortest budget is one minute, not zero`() {
        val b = budget(minutes = SubagentSkill.MIN_MINUTES)
        assertEquals(60, b.timeoutSeconds)
        assertEquals(4, b.maxTurns)
    }

    // ── legacy path: no answer supplied ─────────────────────────────────

    @Test
    fun `no max_minutes keeps the skill turn budget and a clock that delivers it`() {
        val b = budget(skillMaxTurns = 75)
        assertNull(b.parentMinutes)
        assertEquals(SubagentSkill.BudgetSource.SKILL_TURNS, b.source)
        assertEquals(75, b.maxTurns)
        // 75 turns × 15 s × 1.25 headroom = 1406 s, raised to the 1800 s
        // default so the advertised turns are actually deliverable.
        assertEquals(SubagentSkill.DEFAULT_TIMEOUT_SECONDS, b.timeoutSeconds)
    }

    @Test
    fun `no max_minutes still honours an explicit timeout`() {
        val b = budget(timeoutSeconds = 300, skillMaxTurns = 75)
        assertEquals(300, b.timeoutSeconds)
        assertEquals(75, b.maxTurns)
        assertEquals(SubagentSkill.BudgetSource.SKILL_TURNS, b.source)
    }

    @Test
    fun `a skill turn budget above the ceiling is clamped on the legacy path too`() {
        val b = budget(skillMaxTurns = 500)
        assertEquals(SubagentSkill.MAX_TURNS_CEILING, b.maxTurns)
        assertTrue(b.timeoutSeconds <= SubagentSkill.MAX_TIMEOUT_SECONDS)
    }

    // ── the description shown to the parent ─────────────────────────────

    @Test
    fun `the budget description names the source and both limits`() {
        val minutes = SubagentSkill.describeBudget(budget(minutes = 10))
        assertTrue(minutes.contains("10 min"))
        assertTrue(minutes.contains("600s"))
        assertTrue(minutes.contains("40 turns"))

        val legacy = SubagentSkill.describeBudget(budget())
        assertTrue(legacy.contains("max_turns"))
        assertTrue(legacy.contains("75 turns"))
    }

    // ── wiring: the schema and the spawn site ───────────────────────────

    @Test
    fun `the spawn schema asks the parent for max_minutes and makes it required`() {
        val src = ProductionSources.read("tools/SubagentSkill.kt")
        assertTrue(
            "spawn_agent must publish a max_minutes parameter",
            src.contains("\"max_minutes\" to AgentToolParam("),
        )
        assertTrue(
            "max_minutes must be REQUIRED — an optional budget question is " +
                "never asked, and the run silently falls back to the skill number",
            src.contains(
                "required = listOf(\"tool_title\", \"skill_name\", \"query\", \"max_minutes\")",
            ),
        )
        // Order-insensitive on purpose: the list grows as fields are added,
        // and a substring of the whole row breaks every time another
        // parameter lands in the middle of it.
        val orderingStart = src.indexOf("propertyOrdering = listOf(")
        assertTrue(
            "spawn_agent must publish propertyOrdering (Gemini uses it for field order)",
            orderingStart > 0,
        )
        val orderingEnd = src.indexOf(')', orderingStart).let { if (it < 0) src.length else it }
        val ordering = src.substring(orderingStart, orderingEnd)
        assertTrue(
            "Gemini propertyOrdering must list max_minutes (it drives the field order shown to the model)",
            ordering.contains("\"max_minutes\""),
        )
    }

    // ── in-run budget note ──────────────────────────────────────────────

    @Test
    fun `no note while the run has plenty of time and turns`() {
        assertEquals("", SubagentSkill.budgetNote(500, 600, 30, 40))
    }

    @Test
    fun `the note starts at seventy percent and gets urgent at ninety`() {
        val warn = SubagentSkill.budgetNote(150, 600, 20, 40)
        assertTrue(warn.contains("70") || warn.contains("min"))
        assertTrue(warn.contains("intermediate findings"))
        val urgent = SubagentSkill.budgetNote(40, 600, 8, 40)
        assertTrue(urgent.contains("FINAL REPORT") || urgent.contains("final report NOW") || urgent.contains("NOW"))
    }

    @Test
    fun `the last two turns are always urgent regardless of the clock`() {
        val note = SubagentSkill.budgetNote(500, 600, 2, 40)
        assertTrue(note.contains("NOW"))
    }

    @Test
    fun `unknown clock or turn budget degrades to silence, never to a wrong number`() {
        assertEquals("", SubagentSkill.budgetNote(0, 0, 0, 0))
        // Unknown time (total 0) with known turns: still warns near the end.
        assertTrue(SubagentSkill.budgetNote(300, 0, 2, 40).contains("NOW"))
    }
}
