package com.openminis.app.tools

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-chat-stream][T-subagent-thinking] Tests for the interleaved
 * transcript (narration / reasoning / tool calls in arrival order) and the
 * reasoning-level resolution the runner performs before its loop.
 *
 * These pin the properties the detail page depends on:
 *   • order is true interleaving, not "all text then all steps"
 *   • consecutive deltas coalesce (no per-token segment explosion)
 *   • reasoning never lands in [SubagentRunRegistry.Run.resultText]
 *   • both lists are bounded
 */
class SubagentTranscriptTest {

    private fun registryWithRun(): Pair<SubagentRunRegistry, SubagentRunRegistry.Run> {
        val registry = SubagentRunRegistry()
        val run = registry.register("b1", "s", "s", "q", "t", 12)
        return registry to run
    }

    @Test
    fun `text and tool calls interleave in arrival order`() {
        val (registry, run) = registryWithRun()

        registry.appendSegmentText(run.id, "Let me look at the repo. ")
        registry.appendSegmentText(run.id, "Starting with the README.")
        registry.turnStarted(run.id, 1)
        registry.stepStarted(run.id, "call-1", 1, "file_read", "Read README")
        registry.appendSegmentText(run.id, "README says X, now checking the code.")
        registry.stepStarted(run.id, "call-2", 1, "shell_execute", "List files")

        val segments = registry.runs.value.single().segments
        assertEquals(4, segments.size)
        assertTrue(segments[0] is SubagentRunRegistry.Segment.Text)
        // Two consecutive deltas coalesced into ONE narration segment.
        assertEquals(
            "Let me look at the repo. Starting with the README.",
            (segments[0] as SubagentRunRegistry.Segment.Text).content,
        )
        assertEquals("call-1", (segments[1] as SubagentRunRegistry.Segment.ToolCall).id)
        assertTrue(segments[2] is SubagentRunRegistry.Segment.Text)
        assertEquals("call-2", (segments[3] as SubagentRunRegistry.Segment.ToolCall).id)
    }

    @Test
    fun `thinking is captured separately from the report text`() {
        val (registry, run) = registryWithRun()

        registry.appendSegmentThinking(run.id, "The user wants A, but A needs B. ")
        registry.appendSegmentThinking(run.id, "So do B first.")
        // The runner feeds BOTH accumulators from the same Text delta:
        // resultText is what the parent consumes, the transcript is what the
        // page renders. Mirror both calls here so the separation is tested.
        registry.appendResultText(run.id, "Done.")
        registry.appendSegmentText(run.id, "Done.")

        val snapshot = registry.runs.value.single()
        // Reasoning accumulates on its own field...
        assertEquals("The user wants A, but A needs B. So do B first.", snapshot.thinkingText)
        // ...and NEVER leaks into resultText (the parent consumes that).
        assertEquals("Done.", snapshot.resultText)
        assertFalse(snapshot.resultText.contains("A needs B"))

        val segments = snapshot.segments
        assertTrue(segments[0] is SubagentRunRegistry.Segment.Thinking)
        assertTrue(segments[1] is SubagentRunRegistry.Segment.Text)
    }

    @Test
    fun `duplicate tool call id does not create a second pill`() {
        val (registry, run) = registryWithRun()
        registry.stepStarted(run.id, "call-1", 1, "shell_execute", "Run")
        // A retried stream re-emits the same call id.
        registry.stepStarted(run.id, "call-1", 1, "shell_execute", "Run")

        val tools = registry.runs.value.single().segments
            .filterIsInstance<SubagentRunRegistry.Segment.ToolCall>()
        assertEquals(1, tools.size)
    }

    @Test
    fun `transcript is bounded by MAX_SEGMENTS keeping the tail`() {
        val (registry, run) = registryWithRun()
        // Alternate kinds so coalescing can never collapse them.
        repeat(SubagentRunRegistry.MAX_SEGMENTS + 40) { i ->
            registry.appendSegmentText(run.id, "t$i ")
            registry.stepStarted(run.id, "call-$i", 1, "file_read", "Read $i")
        }
        val segments = registry.runs.value.single().segments
        assertTrue(segments.size <= SubagentRunRegistry.MAX_SEGMENTS)
        // Tail retained: the newest tool call is still present.
        val lastTool = segments.last { it is SubagentRunRegistry.Segment.ToolCall }
        assertEquals(
            "call-${SubagentRunRegistry.MAX_SEGMENTS + 39}",
            (lastTool as SubagentRunRegistry.Segment.ToolCall).id,
        )
    }

    @Test
    fun `thinkingText is bounded`() {
        val (registry, run) = registryWithRun()
        repeat(20) {
            registry.appendSegmentThinking(
                run.id,
                "x".repeat(SubagentRunRegistry.MAX_THINKING_TEXT_CHARS / 10),
            )
        }
        val snapshot = registry.runs.value.single()
        assertTrue(snapshot.thinkingText.length <= SubagentRunRegistry.MAX_THINKING_TEXT_CHARS)
    }

    @Test
    fun `stepsById exposes live tool state for the stream pills`() {
        val (registry, run) = registryWithRun()
        registry.stepStarted(run.id, "call-1", 1, "shell_execute", "Run")
        registry.stepOutput(run.id, "call-1", "partial line")
        registry.stepFinished(run.id, "call-1", success = true, output = "done")

        val step = registry.runs.value.single().stepsById["call-1"]
        assertEquals(SubagentRunRegistry.ToolStepStatus.SUCCESS, step?.status)
        assertEquals("done", step?.output)
    }

    // ── thinking-level resolution ────────────────────────────────────────

    @Test
    fun `reasoning stays off when the skill did not ask for it`() {
        assertEquals(
            ThinkingLevel.OFF,
            resolveSubagentThinkingLevel(ThinkingLevel.OFF, modelSupportsReasoning = true),
        )
    }

    @Test
    fun `requested reasoning is honoured on a capable model`() {
        assertEquals(
            ThinkingLevel.HIGH,
            resolveSubagentThinkingLevel(ThinkingLevel.HIGH, modelSupportsReasoning = true),
        )
    }

    @Test
    fun `requested reasoning downgrades on a model that cannot reason`() {
        assertEquals(
            ThinkingLevel.OFF,
            resolveSubagentThinkingLevel(ThinkingLevel.HIGH, modelSupportsReasoning = false),
        )
    }

    @Test
    fun `unknown capability keeps the user's explicit request`() {
        // null = the catalog does not know; dropping the request silently
        // would be worse than sending it.
        assertEquals(
            ThinkingLevel.MEDIUM,
            resolveSubagentThinkingLevel(ThinkingLevel.MEDIUM, modelSupportsReasoning = null),
        )
    }

    // ── reasoning blob diffing ───────────────────────────────────────────

    @Test
    fun `accumulated reasoning only appends the new tail`() {
        assertEquals("de", newReasoningTail(previous = "abc", incoming = "abcde"))
        // Unchanged blob -> nothing new.
        assertEquals("", newReasoningTail(previous = "abc", incoming = "abc"))
        // Shorter blob arrives (the per-turn accumulator reset) -> still the
        // whole text, because dropping it loses a whole turn's reasoning.
        assertEquals("a", newReasoningTail(previous = "abc", incoming = "a"))
        // First blob -> whole text.
        assertEquals("abc", newReasoningTail(previous = "", incoming = "abc"))
        // Divergent blob -> returned whole rather than losing new text.
        assertEquals("xyz", newReasoningTail(previous = "abc", incoming = "xyz"))
        // Empty incoming -> nothing.
        assertEquals("", newReasoningTail(previous = "abc", incoming = ""))
    }

    // ── frontmatter parsing ──────────────────────────────────────────────

    @Test
    fun `thinking frontmatter names parse and unknown values stay off`() {
        assertEquals(ThinkingLevel.LOW, SubagentSkill.parseThinkingLevelName("low"))
        assertEquals(ThinkingLevel.MEDIUM, SubagentSkill.parseThinkingLevelName("MEDIUM"))
        assertEquals(ThinkingLevel.HIGH, SubagentSkill.parseThinkingLevelName(" high "))
        assertEquals(ThinkingLevel.XHIGH, SubagentSkill.parseThinkingLevelName("xhigh"))
        assertEquals(ThinkingLevel.MAX, SubagentSkill.parseThinkingLevelName("max"))
        assertEquals(ThinkingLevel.ULTRA, SubagentSkill.parseThinkingLevelName("ultra"))
        assertEquals(ThinkingLevel.OFF, SubagentSkill.parseThinkingLevelName("off"))
        // Unrecognised / empty -> OFF, never a guessed level.
        assertEquals(ThinkingLevel.OFF, SubagentSkill.parseThinkingLevelName("banana"))
        assertEquals(ThinkingLevel.OFF, SubagentSkill.parseThinkingLevelName(""))
    }

    @Test
    fun `reasoningEnabled flag records what actually ran`() {
        val (registry, run) = registryWithRun()
        assertFalse(registry.runs.value.single().reasoningEnabled)
        registry.setReasoningEnabled(run.id, true)
        assertTrue(registry.runs.value.single().reasoningEnabled)
    }
}
