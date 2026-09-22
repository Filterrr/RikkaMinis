package com.openminis.app.ui.subagent

import com.openminis.app.tools.SubagentRunRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-ui-turns][T-subagent-ui-follow] Pure logic behind the detail
 * page's execution log.
 *
 * Two failure modes motivated these tests, both invisible to a compiler and
 * both wrong on screen:
 *
 *  1. The log was a flat step list, so the turn structure a sub-agent actually
 *     runs in was nowhere on screen.
 *  2. The auto-follow target was computed as `1 + stepCount - 1` against a
 *     list that also contains a header separator per turn and a result/error
 *     card. Every item added since that arithmetic was written moved the
 *     "newest thing" further from the index being scrolled to.
 */
class SubagentDetailLogTest {

    private fun step(
        id: String,
        turn: Int,
        tool: String = "shell_execute",
        title: String = tool,
    ) = SubagentRunRegistry.Step(
        id = id,
        turn = turn,
        toolName = tool,
        toolTitle = title,
    )

    // ── Timeline construction ────────────────────────────────────────────

    @Test
    fun `a step list becomes turn headers interleaved with their calls`() {
        val entries = buildLogEntries(
            steps = listOf(
                step("s1", turn = 1),
                step("s2", turn = 1),
                step("s3", turn = 2),
            ),
            maxTurns = 12,
        )

        assertEquals(
            listOf("T1", "s1", "s2", "T2", "s3"),
            entries.map {
                when (it) {
                    is LogEntry.TurnHeader -> "T${it.turn}"
                    is LogEntry.Call -> it.step.id
                }
            },
        )
    }

    @Test
    fun `only the first separator is flagged as first`() {
        val entries = buildLogEntries(
            steps = listOf(step("s1", 1), step("s2", 2), step("s3", 3)),
            maxTurns = 12,
        )
        val headers = entries.filterIsInstance<LogEntry.TurnHeader>()
        assertEquals(3, headers.size)
        assertEquals(listOf(true, false, false), headers.map { it.first })
    }

    @Test
    fun `an empty log produces no entries and no separators`() {
        assertEquals(emptyList<LogEntry>(), buildLogEntries(emptyList(), maxTurns = 12))
        assertEquals(0, turnCountOf(buildLogEntries(emptyList(), maxTurns = 12)))
    }

    @Test
    fun `turn count counts separators, not calls`() {
        val entries = buildLogEntries(
            steps = listOf(step("s1", 1), step("s2", 1), step("s3", 2)),
            maxTurns = 12,
        )
        assertEquals(2, turnCountOf(entries))
    }

    @Test
    fun `legacy steps without a turn stamp collapse into one turn`() {
        // A run registered before turn stamps existed reports turn = 0 on
        // every step. Rendering one separator per step would claim a
        // structure the run never had — they must group under a single turn.
        val entries = buildLogEntries(
            steps = listOf(step("s1", turn = 0), step("s2", turn = 0)),
            maxTurns = 8,
        )
        assertEquals(1, turnCountOf(entries))
        val header = entries.filterIsInstance<LogEntry.TurnHeader>().single()
        assertEquals(8, header.turn)  // falls back to the configured budget
    }

    @Test
    fun `a run with no configured budget falls back to turn one not zero`() {
        val entries = buildLogEntries(
            steps = listOf(step("s1", turn = 0)),
            maxTurns = 0,
        )
        assertEquals(1, entries.filterIsInstance<LogEntry.TurnHeader>().single().turn)
    }

    @Test
    fun `a real turn zero is distinguishable from a missing stamp`() {
        // Guards the `> 0` test in stepTurnOf: a step that legitimately says
        // turn 2 must keep it rather than be overridden by the fallback.
        assertEquals(2, stepTurnOf(step("s", 2), fallback = 99))
        assertEquals(99, stepTurnOf(step("s", 0), fallback = 99))
    }

    // ── Follow target ────────────────────────────────────────────────────

    @Test
    fun `follow target is the last item of the list`() {
        assertEquals(9, logFollowTarget(totalItems = 10))
        assertEquals(0, logFollowTarget(totalItems = 1))
    }

    @Test
    fun `follow target is null on an unmeasured list`() {
        // -1 would reach scrollToItem and throw; the caller must no-op.
        assertNull(logFollowTarget(totalItems = 0))
    }

    @Test
    fun `the old arithmetic and the new target disagree once separators exist`() {
        // Regression pin: 3 calls across 2 turns = header + 2 separators +
        // 3 calls = 6 items. The legacy `1 + stepCount - 1` = 3 pointed at a
        // middle row; the tail is index 5.
        val entries = buildLogEntries(
            steps = listOf(step("s1", 1), step("s2", 1), step("s3", 2)),
            maxTurns = 12,
        )
        val itemCount = 1 + entries.size  // + the task header
        assertEquals(6, itemCount)
        assertEquals(3, 1 + 3 - 1)          // the old, now-wrong target
        assertEquals(5, logFollowTarget(itemCount))
    }

    // ── At-bottom predicate ──────────────────────────────────────────────

    @Test
    fun `last item visible counts as at bottom`() {
        assertTrue(isLogNearBottom(visibleIndices = listOf(3, 4, 5), totalItems = 6))
    }

    @Test
    fun `an empty list is never at bottom`() {
        assertFalse(isLogNearBottom(visibleIndices = emptyList(), totalItems = 0))
    }

    @Test
    fun `reading history is not at bottom`() {
        assertFalse(isLogNearBottom(visibleIndices = listOf(0, 1, 2), totalItems = 6))
    }

    @Test
    fun `a single-item list at its only item is at bottom`() {
        assertTrue(isLogNearBottom(visibleIndices = listOf(0), totalItems = 1))
    }
}
