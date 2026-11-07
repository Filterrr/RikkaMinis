package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [port/upstream-subagent-steer] A course correction must reach the child
 * WITHOUT breaking the transcript.
 *
 * The delivery cases pinned here exist because a tool result is a USER-role
 * message in this loop. Appending a plain USER message at a turn boundary
 * therefore produces USER/USER — the shape that once made the repair pass
 * invent an "interrupted by a new user message" turn on EVERY tool-executing
 * turn ([fix/subagent-phantom-bridge]). The rule this file encodes: a
 * correction is MERGED into a user tail, and only stands alone after an
 * assistant tail.
 */
class SubagentSteerTest {

    private fun user(content: String = "") = LLMMessage(role = LLMMessage.Role.USER, content = content)

    private fun toolResultMessage() = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(
            AgentContentPart.ToolResult(id = "t1", name = "file_read", content = "file contents", isError = false),
        ),
    )

    private fun assistant(content: String = "") =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = content)

    /** No adjacent USER/USER pair anywhere. */
    private fun alternates(history: List<LLMMessage>) =
        history.zipWithNext().none { (a, b) ->
            a.role == LLMMessage.Role.USER && b.role == LLMMessage.Role.USER
        }

    @Test
    fun `nothing queued delivers nothing and leaves the history alone`() {
        val history = mutableListOf(user("task"))
        assertEquals(0, SubagentSteer.deliver(history, emptyList()))
        assertEquals(1, history.size)
        assertEquals(0, SubagentSteer.deliver(history, listOf("   ")))
        assertEquals(1, history.size)
    }

    @Test
    fun `a correction on an empty history opens a user turn`() {
        val history = mutableListOf<LLMMessage>()
        assertEquals(1, SubagentSteer.deliver(history, listOf("narrow it to 2024")))
        assertEquals(1, history.size)
        assertEquals(LLMMessage.Role.USER, history[0].role)
        assertTrue(history[0].content.startsWith(SubagentSteer.PREFIX))
        assertTrue(history[0].content.contains("narrow it to 2024"))
    }

    @Test
    fun `a correction after a tool result merges into that message instead of adding a USER`() {
        val history = mutableListOf<LLMMessage>(user("task"), assistant("working"), toolResultMessage())
        assertEquals(1, SubagentSteer.deliver(history, listOf("use the 2024 file")))
        // Still three messages: no second USER turn was created.
        assertEquals(3, history.size)
        assertTrue(alternates(history))
        val tail = history.last()
        assertEquals(LLMMessage.Role.USER, tail.role)
        assertTrue(tail.contentParts.any { it is AgentContentPart.Text && it.text.contains("use the 2024 file") })
        // The tool result itself is untouched.
        assertTrue(tail.contentParts.any { it is AgentContentPart.ToolResult })
    }

    @Test
    fun `a correction after a plain user turn is appended to its content`() {
        val history = mutableListOf<LLMMessage>(user("original task"))
        assertEquals(1, SubagentSteer.deliver(history, listOf("also cover 2023")))
        assertEquals(1, history.size)
        assertTrue(history[0].content.contains("original task"))
        assertTrue(history[0].content.contains("also cover 2023"))
    }

    @Test
    fun `a correction after an assistant turn is its own user message`() {
        val history = mutableListOf<LLMMessage>(user("task"), assistant("plan: do x"))
        assertEquals(1, SubagentSteer.deliver(history, listOf("drop x")))
        assertEquals(3, history.size)
        assertEquals(LLMMessage.Role.USER, history[2].role)
        assertTrue(alternates(history))
    }

    @Test
    fun `several corrections sent before one boundary become ONE numbered message`() {
        val text = SubagentSteer.promptText(listOf("first", "second"))
        assertTrue(text.startsWith(SubagentSteer.PREFIX))
        assertTrue(text.contains("2 corrections"))
        assertTrue(text.contains("1) first"))
        assertTrue(text.contains("2) second"))
        // Counted once, not twice.
        val history = mutableListOf<LLMMessage>(user("task"), assistant("x"))
        assertEquals(2, SubagentSteer.deliver(history, listOf("first", "second")))
        assertEquals(3, history.size)
    }

    @Test
    fun `the correction says it does not restart the task`() {
        val text = SubagentSteer.promptText(listOf("tweak the format"))
        assertTrue("keeps existing work", text.contains("Keep the work you have already done"))
        assertTrue("names the delegating agent", text.contains("delegating agent"))
    }

    @Test
    fun `every delivery case keeps the transcript alternating`() {
        val cases = listOf(
            mutableListOf<LLMMessage>(),
            mutableListOf(user("task")),
            mutableListOf(user("task"), assistant("a")),
            mutableListOf(user("task"), assistant("a"), toolResultMessage()),
        )
        for (history in cases) {
            SubagentSteer.deliver(history, listOf("correction"))
            assertTrue("alternation broken for ${history.size} messages", alternates(history))
        }
    }

    // ── Registry mailbox ────────────────────────────────────────────────

    private fun registryWithActiveRun(): Pair<SubagentRunRegistry, String> {
        val registry = SubagentRunRegistry()
        val run = registry.register("b1", "s", "s", "q", "t", 12)
        return registry to run.id
    }

    @Test
    fun `an unknown run cannot be steered`() {
        val registry = SubagentRunRegistry()
        assertFalse(registry.enqueueSteer("nope", "hi"))
    }

    @Test
    fun `an active run queues the correction and hands it over exactly once`() {
        val (registry, id) = registryWithActiveRun()
        assertTrue(registry.enqueueSteer(id, "  narrow it  "))
        assertEquals(1, registry.pendingSteerCount(id))
        assertEquals(listOf("narrow it"), registry.drainSteers(id))
        assertEquals(0, registry.pendingSteerCount(id))
        assertTrue("drained once only", registry.drainSteers(id).isEmpty())
    }

    @Test
    fun `a blank correction is refused rather than queued`() {
        val (registry, id) = registryWithActiveRun()
        assertFalse(registry.enqueueSteer(id, "   "))
        assertEquals(0, registry.pendingSteerCount(id))
    }

    @Test
    fun `the mailbox is bounded per run and refuses instead of dropping`() {
        val (registry, id) = registryWithActiveRun()
        repeat(registry.MAX_PENDING_STEERS_PER_RUN) {
            assertTrue(registry.enqueueSteer(id, "m$it"))
        }
        // The over-limit message is REFUSED (caller can say so), and nothing
        // already queued is silently discarded.
        assertFalse(registry.enqueueSteer(id, "too many"))
        assertEquals(registry.MAX_PENDING_STEERS_PER_RUN, registry.pendingSteerCount(id))
    }

    @Test
    fun `a finished run cannot be steered`() {
        val (registry, id) = registryWithActiveRun()
        registry.finishIfActive(id, SubagentRunRegistry.RunStatus.SUCCESS, resultText = "done")
        assertFalse(registry.enqueueSteer(id, "too late"))
    }

    @Test
    fun `a correction queued but never read is recorded, not silently dropped`() {
        val (registry, id) = registryWithActiveRun()
        assertTrue(registry.enqueueSteer(id, "never read"))
        registry.finishIfActive(id, SubagentRunRegistry.RunStatus.CANCELLED, error = "stopped")
        val run = registry.runs.value.first { it.id == id }
        assertTrue(
            "unread correction must be surfaced: ${run.notices}",
            run.notices.contains("never read"),
        )
        assertEquals(0, registry.pendingSteerCount(id))
    }
}