package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.tools.SubagentSourceText
import com.openminis.app.tools.SubagentRunRegistry.Run
import com.openminis.app.tools.SubagentRunRegistry.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [port/upstream-stop-scope] Stop must reach the runs that outlive the turn —
 * and must not claim to reach the ones it cannot.
 *
 * The distinction this file pins: a DETACHED run owns a coroutine on the
 * orchestration scope and survives `cancelStream()`, so it has to be cancelled
 * explicitly; an INLINE run executes inside the turn being stopped, so
 * "cancelling" it is either a no-op or a double-cancel that can mark a run
 * cancelled right before it delivers a real result.
 */
class SubagentStopScopeTest {

    private fun run(id: String, status: RunStatus) = Run(
        id = id,
        blockId = "b-$id",
        skillId = "s",
        skillName = "general-agent",
        query = "q",
        title = "t",
        status = status,
    )

    @Test
    fun `detached running, queued and inline are counted separately`() {
        val summary = StopScopePolicy.summarize(
            listOf(
                run("a", RunStatus.RUNNING),
                run("b", RunStatus.QUEUED),
                run("c", RunStatus.RUNNING),
            ),
            isDetached = { it != "c" },
        )
        assertEquals(1, summary.runningDetached)
        assertEquals(1, summary.queuedRuns)
        assertEquals(1, summary.inlineRuns)
        assertTrue(summary.canCancelRuns)
    }

    @Test
    fun `finished runs are not part of any stop`() {
        val summary = StopScopePolicy.summarize(
            listOf(run("a", RunStatus.SUCCESS), run("b", RunStatus.FAILED), run("c", RunStatus.CANCELLED)),
            isDetached = { true },
        )
        assertEquals(StopSummary(), summary)
        assertFalse(summary.hasAnything)
    }

    @Test
    fun `a chat with only inline work cannot cancel runs out of band`() {
        val summary = StopScopePolicy.summarize(
            listOf(run("a", RunStatus.RUNNING)),
            isDetached = { false },
        )
        assertTrue("the turn's stop does reach it", summary.hasAnything)
        assertFalse("but not as a run cancellation", summary.canCancelRuns)
        assertTrue(
            "and the sheet must say so rather than implying it will be stopped",
            StopScopePolicy.describe(summary).contains("ends with it"),
        )
    }

    @Test
    fun `the description names everything that will happen`() {
        val text = StopScopePolicy.describe(
            StopSummary(runningDetached = 2, queuedRuns = 1, inlineRuns = 1),
        )
        assertTrue(text.contains("2 background sub-agents will be stopped"))
        assertTrue(text.contains("1 queued spawn will be dropped"))
        assertTrue(text.contains("1 sub-agent running inside this turn ends with it"))
    }

    @Test
    fun `singular and plural read correctly`() {
        assertEquals(
            "1 background sub-agent will be stopped.",
            StopScopePolicy.describe(StopSummary(runningDetached = 1)),
        )
    }

    @Test
    fun `an idle conversation says so`() {
        assertEquals(
            "Nothing else is running in this conversation.",
            StopScopePolicy.describe(StopSummary()),
        )
    }

    // ── Wiring (ChatViewModel cannot be compiled here) ──────────────────

    @Test
    fun `Stop actually cancels the detached runs`() {
        val vm = SubagentSourceText.stripComments(
            ProductionSources.read("ui/chat/ChatViewModel.kt"),
        )
        assertTrue(
            "cancelStream must reach the runs that outlive the turn",
            vm.contains("cancelAllSubagentRuns()"),
        )
        assertTrue(
            "the reason is recorded on every run this stop cancels",
            vm.contains("StopScopePolicy.REASON"),
        )
        assertTrue(
            "inline runs must be excluded — they end with the turn",
            vm.contains(".filter { it.detached }"),
        )
    }

    @Test
    fun `the recorded reason is a stable sentence`() {
        assertTrue(StopScopePolicy.REASON.isNotBlank())
        assertTrue(StopScopePolicy.REASON.contains("stopped"))
    }
}