package com.openminis.app.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
        assertTrue(registry.hasActiveRunsSnapshot)
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
        assertFalse(registry.hasActiveRunsSnapshot)
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
        assertFalse(registry.hasActiveRunsSnapshot)
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
    fun `spawn_agent definition carries run_until and registry key`() {
        val def = SubagentSkill.definition()
        assertEquals("spawn_agent", def.name)
        assertTrue(def.parameters.containsKey("run_until"))
        assertTrue(def.required.containsAll(listOf("tool_title", "skill_name", "query")))
        assertEquals("subagentRunRegistry", SubagentSkill.RUN_REGISTRY_KEY)
    }

    /**
     * [T-subagent-atomic-registry] Regression for the lost-update race: the
     * old implementation did read-modify-write on the whole list, so two
     * interleaved updates collapsed to whichever swap landed last. With
     * MutableStateFlow.update every concurrent mutation must survive.
     */
    @Test
    fun `concurrent updates never lose an increment`() = runBlocking {
        val registry = makeRegistry()
        val run = registry.register("b1", "s", "s", "q", "t", 4)
        val writers = 8
        val perWriter = 50
        (1..writers).map {
            launch(kotlinx.coroutines.Dispatchers.Default) {
                repeat(perWriter) { registry.appendResultText(run.id, "x") }
            }
        }.joinAll()
        // 400 deltas × 1 char — a lost update would land short of that.
        assertEquals(writers * perWriter, registry.runs.value.single().resultText.length)
    }

    /**
     * [T-subagent-atomic-registry] A finish() racing a step update must not
     * be overwritten back to RUNNING (the old read-swap race could resurrect
     * a finished run's status).
     */
    @Test
    fun `finish survives concurrent step updates`() = runBlocking {
        val registry = makeRegistry()
        val run = registry.register("b1", "s", "s", "q", "t", 4)
        registry.stepStarted(run.id, "call-1", 1, "file_read", "read")
        val done = CompletableDeferred<Unit>()
        val writer = launch(kotlinx.coroutines.Dispatchers.Default) {
            repeat(200) { registry.stepOutput(run.id, "call-1", "line-$it") }
            done.complete(Unit)
        }
        registry.finish(run.id, SubagentRunRegistry.RunStatus.SUCCESS, resultText = "ok")
        writer.join()
        val final = registry.runs.value.single()
        assertEquals(SubagentRunRegistry.RunStatus.SUCCESS, final.status)
        assertTrue(final.endedAtMs > 0)
    }
}

// ── [T-subagent-scheduler] two-level scheduler tests ─────────────────────

class SubagentSchedulerTest {

    private fun <T> held(blockValue: T, gate: CompletableDeferred<Unit>, counter: AtomicInteger? = null): suspend () -> T = {
        counter?.incrementAndGet()
        gate.await()
        counter?.decrementAndGet()
        blockValue
    }

    /**
     * max_parallel is now REALLY consumed: a skill capped at 1 serializes
     * its own spawns — the second waits for the first instead of running
     * concurrently AND instead of failing.
     */
    @Test
    fun `skill limit one serializes spawns instead of failing`() = runBlocking {
        val scheduler = SubagentScheduler(globalLimit = 4)
        val gate = CompletableDeferred<Unit>()
        val inside = AtomicInteger(0)
        var observedPeak = 0
        val jobs = (1..3).map {
            launch(kotlinx.coroutines.Dispatchers.Default) {
                scheduler.run("skill-a", 1) {
                    inside.incrementAndGet()
                    observedPeak = maxOf(observedPeak, inside.get())
                    gate.await()
                    inside.decrementAndGet()
                    "ok"
                }
            }
        }
        // Give the first spawn time to take the single permit.
        kotlinx.coroutines.delay(200)
        assertEquals(1, inside.get())
        gate.complete(Unit)
        jobs.joinAll()
        assertEquals(1, observedPeak)
    }

    /** Global cap bounds concurrent runs across skills. */
    @Test
    fun `global cap bounds concurrent runs across skills`() = runBlocking {
        val scheduler = SubagentScheduler(globalLimit = 2)
        val gate = CompletableDeferred<Unit>()
        val inside = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val skills = listOf("skill-a", "skill-b", "skill-c", "skill-d")
        val jobs = skills.map { id ->
            launch(kotlinx.coroutines.Dispatchers.Default) {
                scheduler.run(id, 4) {
                    val now = inside.incrementAndGet()
                    peak.updateAndGet { p -> maxOf(p, now) }
                    gate.await()
                    inside.decrementAndGet()
                    "ok"
                }
            }
        }
        kotlinx.coroutines.delay(200)
        assertEquals(2, inside.get())
        gate.complete(Unit)
        jobs.joinAll()
        assertTrue("peak was ${peak.get()}", peak.get() <= 2)
        assertEquals(0, scheduler.activeGlobalCount())
    }

    /**
     * A serial skill queues on ITS OWN semaphore without eating global
     * permits another skill could use.
     */
    @Test
    fun `serial skill queues without starving other skills`() = runBlocking {
        val scheduler = SubagentScheduler(globalLimit = 2)
        val serialGate = CompletableDeferred<Unit>()
        val insideSerial = AtomicInteger(0)
        val serialJob = launch(kotlinx.coroutines.Dispatchers.Default) {
            scheduler.run("serial-skill", 1) {
                insideSerial.incrementAndGet()
                serialGate.await()
                insideSerial.decrementAndGet()
                "ok"
            }
        }
        kotlinx.coroutines.delay(200)
        assertEquals(1, insideSerial.get())
        // The serial skill holds 1 skill permit + 1 global permit.
        assertEquals(1, scheduler.availableGlobalPermits())
        // Another skill can still take the remaining global permit.
        val otherDone = CompletableDeferred<Unit>()
        val otherJob = launch(kotlinx.coroutines.Dispatchers.Default) {
            scheduler.run("other-skill", 4) {
                otherDone.await()
                "ok"
            }
        }
        kotlinx.coroutines.delay(200)
        assertEquals(0, scheduler.availableGlobalPermits())
        otherDone.complete(Unit)
        serialGate.complete(Unit)
        serialJob.join()
        otherJob.join()
        assertEquals(2, scheduler.availableGlobalPermits())
    }

    /** Over-cap spawns queue and eventually complete — never rejected. */
    @Test
    fun `over cap spawns queue and complete`() = runBlocking {
        val scheduler = SubagentScheduler(globalLimit = 1)
        val results = (1..6).map { n ->
            async(kotlinx.coroutines.Dispatchers.Default) {
                scheduler.run("skill-q", 1) { n }
            }
        }.awaitAll()
        assertEquals((1..6).toList(), results.sorted())
    }
}
