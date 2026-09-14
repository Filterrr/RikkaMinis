package com.openminis.app.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-subagent-queue-budget] Tests for BOUNDED scheduler queueing.
 *
 * The behaviour under test is the fix for a user-visible freeze: an INLINE
 * spawn parks the parent agent turn on scheduler.run() returning, so a full
 * queue meant the chat froze for however long the queue head happened to run
 * — previously with no cap at all. A queue timeout must (a) surface as an
 * error rather than a hang, and (b) leak no permit.
 */
class SubagentSchedulerQueueTest {

    /** Run body that occupies its permits until [gate] completes. */
    private fun held(gate: CompletableDeferred<Unit>, inside: AtomicInteger): suspend () -> String = {
        inside.incrementAndGet()
        gate.await()
        inside.decrementAndGet()
        "ok"
    }

    private fun CoroutineScope.claimSlot(
        scheduler: SubagentScheduler,
        gate: CompletableDeferred<Unit>,
        inside: AtomicInteger,
    ) = launch(Dispatchers.Default) {
        scheduler.run("skill-b", 1, block = held(gate, inside))
    }

    @Test
    fun `bounded inline wait fails fast when no slot frees in time`() = runBlocking {
        val scheduler = SubagentScheduler(globalLimit = 1)
        val gate = CompletableDeferred<Unit>()
        val inside = AtomicInteger(0)
        val blocker = claimSlot(scheduler, gate, inside)
        delay(200)
        assertEquals("blocker must hold the slot", 1, inside.get())

        val started = System.currentTimeMillis()
        val err = assertThrows(SubagentScheduler.QueueTimeoutException::class.java) {
            runBlocking { scheduler.run("skill-a", 1, maxQueueWaitMs = 250L) { "should never run" } }
        }
        val waited = System.currentTimeMillis() - started
        assertTrue("must respect the queue cap, waited ${waited}ms", waited in 150..3000)
        assertTrue("error must name queueing: ${err.message}", err.message!!.contains("queue wait"))

        gate.complete(Unit)
        blocker.join()
        assertEquals(0, inside.get())
    }

    @Test
    fun `a queue timeout leaks no permit — the next spawn still runs`() = runBlocking {
        val scheduler = SubagentScheduler(globalLimit = 1)
        val gate = CompletableDeferred<Unit>()
        val inside = AtomicInteger(0)
        val blocker = claimSlot(scheduler, gate, inside)
        delay(200)

        assertThrows(SubagentScheduler.QueueTimeoutException::class.java) {
            runBlocking { scheduler.run("skill-a", 1, maxQueueWaitMs = 150L) { "nope" } }
        }

        gate.complete(Unit)
        blocker.join()
        assertEquals("global permit must be intact", 1, scheduler.availableGlobalPermits())

        // A later spawn gets through normally — proving nothing was stranded.
        val result = withTimeout(3_000) {
            scheduler.run("skill-a", 1, maxQueueWaitMs = 2_000L) { "ran fine" }
        }
        assertEquals("ran fine", result)
    }

    @Test
    fun `expired deadline refuses late work even when a slot is free`() = runBlocking {
        val scheduler = SubagentScheduler(globalLimit = 4)
        val err = assertThrows(SubagentScheduler.QueueTimeoutException::class.java) {
            runBlocking {
                scheduler.run("skill-a", 1, deadlineNanos = System.nanoTime() - 1L) { "must not run" }
            }
        }
        // This is the case tryAcquire(0) would have silently passed: a free
        // queue must NOT rescue a spawn whose own budget already died.
        assertTrue(err.message!!.contains("timeout_seconds"))
        assertEquals("no permit may be held after the refusal", 4, scheduler.availableGlobalPermits())
    }

    @Test
    fun `deadline-bounded wait lets a queued spawn run when a slot lands in time`() = runBlocking {
        val scheduler = SubagentScheduler(globalLimit = 1)
        val gate = CompletableDeferred<Unit>()
        val inside = AtomicInteger(0)
        val blocker = claimSlot(scheduler, gate, inside)
        delay(120)
        val spawned = async(Dispatchers.Default) {
            // Generous deadline: it may wait, and the slot frees at ~400ms.
            scheduler.run("skill-a", 1, deadlineNanos = System.nanoTime() + 8_000_000_000L) { "ran" }
        }
        delay(250)
        gate.complete(Unit)
        blocker.join()
        assertEquals("ran", withTimeout(5_000) { spawned.await() })
    }

    @Test
    fun `legacy zero-gated call keeps the old unbounded queueing`() = runBlocking {
        val scheduler = SubagentScheduler(globalLimit = 1)
        val gate = CompletableDeferred<Unit>()
        val inside = AtomicInteger(0)
        val blocker = claimSlot(scheduler, gate, inside)
        delay(150)
        // No deadline, no queue cap → must NOT fail; must park until free.
        val waiter = async(Dispatchers.Default) {
            scheduler.run("skill-a", 1) { "eventually" }
        }
        delay(200)
        assertTrue("waiter must still be parked, not failed", !waiter.isCompleted)
        gate.complete(Unit)
        blocker.join()
        assertEquals("eventually", withTimeout(3_000) { waiter.await() })
    }
}
