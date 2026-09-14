package com.openminis.app.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-approval] Races of the pending-approval state machine: exactly
 * one answer wins, a timed-out ask removes its own row without resurrecting a
 * dead waiter, and teardown denies everything outstanding so no parent turn
 * can freeze behind a dialog nobody can see.
 */
class SubagentApprovalQueueTest {

    private fun submit(q: SpawnApprovalQueue, skill: String = "general-agent", task: String = "do it") =
        q.submit(skillId = skill, skillName = skill, task = task, modelLabel = "cheap-model", detached = false)

    @Test
    fun `submit registers a visible ask and truncates the task preview`() {
        val q = SpawnApprovalQueue()
        val ask = submit(q, task = "x".repeat(2000))
        assertEquals(1, q.requests.value.size)
        val row = q.requests.value.first()
        assertEquals(SpawnApprovalQueue.TASK_PREVIEW_CHARS, row.taskPreview.length)
        assertEquals("cheap-model", row.modelLabel)
        assertTrue(q.isOutstanding(row.id))
    }

    @Test
    fun `first resolver wins and the row retires`() = runBlocking {
        val q = SpawnApprovalQueue()
        val ask = submit(q)
        val waiter = async { ask.deferred.await() }
        delay(30)
        assertTrue(q.resolve(ask.request.id, SpawnDecision.ALLOW_ONCE))
        assertEquals(SpawnDecision.ALLOW_ONCE, waiter.await())
        assertFalse("row must retire with the decision", q.isOutstanding(ask.request.id))
        assertTrue(q.requests.value.isEmpty())
        // Second answer delivers nothing and reopens nothing.
        assertFalse(q.resolve(ask.request.id, SpawnDecision.DENY))
    }

    @Test
    fun `losing a resolve race delivers exactly one decision`() = runBlocking {
        val q = SpawnApprovalQueue()
        val ask = submit(q)
        // Two resolvers race (dialog tap vs. a per-skill shortcut). WHICH one
        // wins is nondeterministic; that only ONE wins is the invariant.
        val attempts = listOf(SpawnDecision.ALLOW_ONCE, SpawnDecision.DENY).map { decision ->
            async { decision to q.resolve(ask.request.id, decision) }
        }.map { it.await() }
        assertEquals("exactly one resolver delivers", 1, attempts.count { it.second })
        val winner = attempts.first { it.second }.first
        assertTrue("deferred completed", ask.deferred.isCompleted)
        assertEquals("the winner's decision stands", winner, ask.deferred.getCompleted())
    }

    @Test
    fun `remove drops the row and a late tap delivers nothing`() = runBlocking {
        val q = SpawnApprovalQueue()
        val ask = submit(q)
        // Waiter-timeout exit: remove, then the user taps the vanished dialog.
        q.remove(ask.request.id)
        assertFalse(q.isOutstanding(ask.request.id))
        assertTrue(q.requests.value.isEmpty())
        assertFalse("a tap after remove must not resolve", q.resolve(ask.request.id, SpawnDecision.ALWAYS_ALLOW))
        assertFalse("waiter stays unresolved after remove", ask.deferred.isCompleted)
    }

    @Test
    fun `remove is a no-op for an already-resolved ask and for unknown ids`() {
        val q = SpawnApprovalQueue()
        val ask = submit(q)
        assertTrue(q.resolve(ask.request.id, SpawnDecision.DENY))
        q.remove(ask.request.id) // no throw, row already retired
        q.remove("never-existed")
        assertTrue(q.requests.value.isEmpty())
    }

    @Test
    fun `denyAllOutstanding wakes every parked spawn and empties the list`() = runBlocking {
        val q = SpawnApprovalQueue()
        val asks = (1..3).map { submit(q, task = "task-$it") }
        val waiters = asks.map { async { it.deferred.await() } }
        delay(30)
        assertEquals(3, q.denyAllOutstanding())
        waiters.forEach { assertEquals(SpawnDecision.DENY, it.await()) }
        assertTrue(q.requests.value.isEmpty())
        assertEquals("a second teardown denies nothing", 0, q.denyAllOutstanding())
    }

    @Test
    fun `resolveForSkill shortcuts every ask of one skill at once`() = runBlocking {
        val q = SpawnApprovalQueue()
        val a1 = submit(q, skill = "read-only-researcher", task = "a")
        val a2 = submit(q, skill = "read-only-researcher", task = "b")
        val b1 = submit(q, skill = "general-agent", task = "c")
        assertEquals(2, q.resolveForSkill("read-only-researcher", SpawnDecision.ALLOW_ONCE))
        assertEquals(SpawnDecision.ALLOW_ONCE, a1.deferred.await())
        assertEquals(SpawnDecision.ALLOW_ONCE, a2.deferred.await())
        assertTrue("other skills keep waiting", q.isOutstanding(b1.request.id))
    }

    @Test
    fun `ask order in the list is arrival order`() {
        val q = SpawnApprovalQueue()
        val first = submit(q, task = "first")
        val second = submit(q, task = "second")
        val ids = q.requests.value.map { it.id }
        assertEquals(listOf(first.request.id, second.request.id), ids)
    }
}
