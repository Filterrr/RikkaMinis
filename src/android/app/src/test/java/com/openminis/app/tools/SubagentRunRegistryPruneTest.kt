package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-registry-prune-active-runs] The registry's retention prune
 * used to drop the OLDEST entry regardless of its status. A long-running
 * detached run — a 15-minute research is normal — therefore vanished from
 * the registry as soon as 20 newer runs appeared, and the consequences
 * cascaded:
 *
 *  - `finishOnce` / `updateRun` match by runId, so the run's real terminal
 *    write found no row and was DISCARDED — the pill never showed how it
 *    ended;
 *  - `completeJobFromRegistry` read a null snapshot, so the parent received
 *    an empty report and a COMPLETED verdict for a run that may have failed;
 *  - the run could no longer be cancelled from the UI.
 *
 * The prune must drop only TERMINAL runs, whose outcomes have already been
 * delivered.
 */
class SubagentRunRegistryPruneTest {

    private fun register(reg: SubagentRunRegistry, i: Int) = reg.register(
        blockId = "blk-$i",
        skillId = "general-agent",
        skillName = "general-agent",
        query = "task $i",
        title = "task $i",
        maxTurns = 40,
    )

    @Test
    fun `a long-running active run survives newer registrations`() {
        val reg = SubagentRunRegistry()
        val longRun = register(reg, 0)
        repeat(30) { i -> register(reg, i + 1) }
        // Everything except the long run finishes normally.
        reg.runs.value.filter { it.id != longRun.id }.forEach {
            reg.finishIfActive(it.id, SubagentRunRegistry.RunStatus.SUCCESS, "done")
        }
        // More registrations come in.
        repeat(10) { i -> register(reg, 100 + i) }

        assertTrue(
            "the live run must not be pruned away: ${reg.runs.value.map { it.id }}",
            reg.runs.value.any { it.id == longRun.id },
        )
    }

    @Test
    fun `the terminal write of a surviving live run still lands`() {
        val reg = SubagentRunRegistry()
        val longRun = register(reg, 0)
        repeat(30) { i -> register(reg, i + 1) }
        reg.runs.value.filter { it.id != longRun.id }.forEach {
            reg.finishIfActive(it.id, SubagentRunRegistry.RunStatus.SUCCESS, "done")
        }
        repeat(10) { i -> register(reg, 100 + i) }

        reg.finishIfActive(longRun.id, SubagentRunRegistry.RunStatus.SUCCESS, "real result")

        val row = reg.runs.value.firstOrNull { it.id == longRun.id }
        assertNotNull("the run's row must still exist", row)
        assertEquals(SubagentRunRegistry.RunStatus.SUCCESS, row!!.status)
        assertEquals("real result", row.resultText)
    }

    @Test
    fun `terminal runs are pruned to keep the list bounded`() {
        val reg = SubagentRunRegistry()
        repeat(30) { i ->
            val run = register(reg, i)
            reg.finishIfActive(run.id, SubagentRunRegistry.RunStatus.SUCCESS, "done")
        }
        assertTrue(
            "the retained history must stay bounded: ${reg.runs.value.size}",
            reg.runs.value.size <= SubagentRunRegistry.MAX_RETAINED_RUNS,
        )
    }

    @Test
    fun `live runs are hard-capped so the list cannot grow without bound`() {
        val reg = SubagentRunRegistry()
        repeat(200) { i -> register(reg, i) }
        assertTrue(
            "a pathological number of live runs must still be bounded: ${reg.runs.value.size}",
            reg.runs.value.size <= SubagentRunRegistry.MAX_RETAINED_RUNS + SubagentRunRegistry.MAX_LIVE_RUNS,
        )
    }

    @Test
    fun `the list comes back down once the live runs finish`() {
        val reg = SubagentRunRegistry()
        repeat(40) { i -> register(reg, i) }
        reg.runs.value.forEach {
            reg.finishIfActive(it.id, SubagentRunRegistry.RunStatus.SUCCESS, "x")
        }
        // The 40 finished runs overshot the budget while they were live.
        // Registering more runs prunes the finished ones.
        repeat(25) { i -> register(reg, 200 + i) }
        assertTrue(
            "the TERMINAL history must be pruned back down: ${reg.runs.value.size}",
            reg.runs.value.count { !it.isActive } <= SubagentRunRegistry.MAX_RETAINED_RUNS,
        )

        // And once the newer runs finish too, the whole list is terminal
        // history and the next registration prunes it back to the budget.
        reg.runs.value.forEach {
            reg.finishIfActive(it.id, SubagentRunRegistry.RunStatus.SUCCESS, "x")
        }
        repeat(5) { i -> register(reg, 300 + i) }
        assertTrue(
            "everything terminal must shrink to the budget: ${reg.runs.value.size}",
            reg.runs.value.size <= SubagentRunRegistry.MAX_RETAINED_RUNS,
        )
    }
}
