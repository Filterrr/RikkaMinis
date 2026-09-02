package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-ui] Unit tests for the run registry that drives the in-chat
 * prompt pill and the second-level SubagentDetailScreen.
 */
class SubagentRunRegistryTest {

    private fun makeRegistry() = SubagentRunRegistry()

    @Test
    fun `register adds run and marks active`() {
        val registry = makeRegistry()
        val run = registry.register("b1", "general-agent", "general-agent", "do a thing", "Do Thing", 12)
        assertEquals(listOf(run), registry.runs.value)
        assertTrue(registry.hasActiveRuns.value)
        assertTrue(run.isActive)
        assertEquals(0, run.turn)
        assertEquals(12, run.maxTurns)
    }

    @Test
    fun `finish flips status and clears active flag`() {
        val registry = makeRegistry()
        val run = registry.register("b1", "s", "s", "q", "t", 4)
        registry.finish(run.id, SubagentRunRegistry.RunStatus.SUCCESS, resultText = "done")
        val finished = registry.runs.value.single()
        assertFalse(finished.isActive)
        assertFalse(registry.hasActiveRuns.value)
        assertEquals(SubagentRunRegistry.RunStatus.SUCCESS, finished.status)
        assertEquals("done", finished.resultText)
        assertTrue(finished.endedAtMs >= finished.startedAtMs)
    }

    @Test
    fun `turn step lifecycle streams through registry`() {
        val registry = makeRegistry()
        val run = registry.register("b1", "s", "s", "q", "t", 4)

        registry.turnStarted(run.id, 2)
        assertEquals(2, registry.runs.value.single().turn)

        registry.stepStarted(run.id, "call-1", 2, "shell_execute", "List files")
        val step = registry.runs.value.single().steps.single()
        assertEquals("call-1", step.id)
        assertEquals(SubagentRunRegistry.ToolStepStatus.RUNNING, step.status)

        registry.stepOutput(run.id, "call-1", "line-a\nline-b")
        assertEquals("line-a\nline-b", registry.runs.value.single().steps.single().output)

        registry.stepFinished(run.id, "call-1", success = true, output = "line-a\nline-b\nline-c")
        val done = registry.runs.value.single().steps.single()
        assertEquals(SubagentRunRegistry.ToolStepStatus.SUCCESS, done.status)
        assertTrue(done.durationMs >= 0)
        assertTrue(done.output.endsWith("line-c"))
    }

    @Test
    fun `appendResultText appends and bounds size`() {
        val registry = makeRegistry()
        val run = registry.register("b1", "s", "s", "q", "t", 4)
        registry.appendResultText(run.id, "hello ")
        registry.appendResultText(run.id, "world")
        assertEquals("hello world", registry.runs.value.single().resultText)

        val big = "x".repeat(SubagentRunRegistry.MAX_RESULT_TEXT_CHARS + 5000)
        registry.appendResultText(run.id, big)
        assertEquals(
            SubagentRunRegistry.MAX_RESULT_TEXT_CHARS,
            registry.runs.value.single().resultText.length,
        )
    }

    @Test
    fun `register keeps newest first and prunes to cap`() {
        val registry = makeRegistry()
        var last: SubagentRunRegistry.Run? = null
        repeat(SubagentRunRegistry.MAX_RETAINED_RUNS + 5) {
            last = registry.register("b$it", "s", "s", "q$it", "t$it", 4)
        }
        val runs = registry.runs.value
        assertEquals(SubagentRunRegistry.MAX_RETAINED_RUNS, runs.size)
        assertEquals(last!!.id, runs.first().id)
    }

    @Test
    fun `clear empties everything`() {
        val registry = makeRegistry()
        registry.register("b1", "s", "s", "q", "t", 4)
        registry.clear()
        assertTrue(registry.runs.value.isEmpty())
        assertFalse(registry.hasActiveRuns.value)
    }

    @Test
    fun `update on unknown run id is a no-op`() {
        val registry = makeRegistry()
        registry.turnStarted("nope", 1)
        registry.stepOutput("nope", "step", "out")
        registry.finish("nope", SubagentRunRegistry.RunStatus.FAILED, error = "x")
        assertTrue(registry.runs.value.isEmpty())
    }

    @Test
    fun `dispatch gate allows single acquire and release cycle`() {
        assertTrue(SubagentDispatchGate.tryAcquire())
        assertFalse(SubagentDispatchGate.tryAcquire())
        SubagentDispatchGate.release()
        assertTrue(SubagentDispatchGate.tryAcquire())
        SubagentDispatchGate.release()
    }

    @Test
    fun `dispatch gate self-heals a stale lease`() {
        // Simulate a leaked hold: acquire and never release.
        assertTrue(SubagentDispatchGate.tryAcquire())
        // Immediately after, the slot is genuinely busy.
        assertFalse(SubagentDispatchGate.tryAcquire())
        // Force the lease timestamp into the stale window (as if the holder
        // died 32 minutes ago without releasing — the false-interrupt path).
        val field = SubagentDispatchGate::class.java.getDeclaredField("acquiredAtMs")
        field.isAccessible = true
        field.set(SubagentDispatchGate, System.currentTimeMillis() - 32L * 60L * 1000L)
        // A new spawn force-takes the stale lease instead of failing forever.
        assertTrue(SubagentDispatchGate.tryAcquire())
        // And the takeover is a real exclusive hold again.
        assertFalse(SubagentDispatchGate.tryAcquire())
        SubagentDispatchGate.release()
        assertTrue(SubagentDispatchGate.tryAcquire())
        SubagentDispatchGate.release()
    }

    @Test
    fun `spawn_agent definition carries run_until and registry key`() {
        val def = SubagentSkill.definition()
        assertEquals("spawn_agent", def.name)
        assertTrue(def.parameters.containsKey("run_until"))
        assertTrue(def.required.containsAll(listOf("tool_title", "skill_name", "query")))
        assertEquals("subagentRunRegistry", SubagentSkill.RUN_REGISTRY_KEY)
    }
}
