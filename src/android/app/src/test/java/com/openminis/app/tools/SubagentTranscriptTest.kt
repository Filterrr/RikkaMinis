package com.openminis.app.tools

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 *   • the accumulated reasoning blob cannot duplicate the streamed deltas
 *   • a retried stream attempt rolls back its partial writes (no doubling)
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

    // ── reasoning blob dedup（provider 双通道）──────────────────────────

    @Test
    fun `accumulated reasoning blob does not duplicate the streamed deltas`() {
        val (registry, run) = registryWithRun()

        // What OpenAI-family actually does: every reasoning delta arrives as
        // ThinkingDelta, then ONE accumulated ReasoningContent blob at the
        // stream end. The blob is a duplicate of the deltas — appending it
        // verbatim doubled the whole panel (bug found in self-review).
        registry.appendSegmentThinking(run.id, "Step A: ")
        registry.appendSegmentThinking(run.id, "check B. ")
        registry.appendSegmentThinking(run.id, "Step A: check B. ", dedupe = true)

        val snapshot = registry.runs.value.single()
        assertEquals("Step A: check B. ", snapshot.thinkingText)
        // Exactly ONE thinking segment survived.
        assertEquals(
            1,
            snapshot.segments.count { it is SubagentRunRegistry.Segment.Thinking },
        )
    }

    @Test
    fun `reasoning blob with genuinely new tail is still accepted`() {
        val (registry, run) = registryWithRun()
        registry.appendSegmentThinking(run.id, "Step A. ")
        // A provider that sends more text in the final blob than it streamed.
        registry.appendSegmentThinking(run.id, "Step A. Extra conclusion.", dedupe = true)

        val snapshot = registry.runs.value.single()
        assertEquals("Step A. Extra conclusion.", snapshot.thinkingText)
    }

    @Test
    fun `reasoning blob from a fresh panel is accepted whole`() {
        val (registry, run) = registryWithRun()
        // Provider that ONLY sends the accumulated blob (no deltas).
        registry.appendSegmentThinking(run.id, "All the reasoning at once.", dedupe = true)
        assertEquals("All the reasoning at once.", registry.runs.value.single().thinkingText)
    }

    // ── retry rollback ───────────────────────────────────────────────────

    @Test
    fun `resetTurn rolls back a failed attempt's partial transcript`() {
        val (registry, run) = registryWithRun()
        registry.appendSegmentText(run.id, "committed before the attempt. ")
        val snap = registry.turnSnapshot(run.id)

        // The failed attempt's partial writes:
        registry.appendSegmentText(run.id, "partial narr")
        registry.appendSegmentThinking(run.id, "partial think")
        registry.stepStarted(run.id, "call-partial", 1, "file_read", "Read")

        registry.resetTurn(run.id, snap, listOf("call-partial"))

        val snapshot = registry.runs.value.single()
        assertEquals(
            listOf("committed before the attempt. "),
            snapshot.segments.filterIsInstance<SubagentRunRegistry.Segment.Text>()
                .map { it.content },
        )
        assertEquals(0, snapshot.segments.count { it is SubagentRunRegistry.Segment.Thinking })
        assertNull(snapshot.stepsById["call-partial"])
    }

    @Test
    fun `retry after rollback coalesces onto the pre-attempt segment`() {
        val (registry, run) = registryWithRun()
        registry.appendSegmentText(run.id, "pre. ")
        val snap = registry.turnSnapshot(run.id)

        // The failed attempt coalesces into the PRE-ATTEMPT segment (same
        // turn, same kind) — this is the case a seq-only filter misses.
        registry.appendSegmentText(run.id, "LOST PARTIAL")
        registry.resetTurn(run.id, snap)

        // The retry's first delta extends the PRE-ATTEMPT tail — byte-equal
        // to the state had the failed attempt never written.
        registry.appendSegmentText(run.id, "retry. ")
        assertEquals(
            "pre. retry. ",
            (registry.runs.value.single().segments.single()
                as SubagentRunRegistry.Segment.Text).content,
        )
    }

    @Test
    fun `rollback also truncates a coalesced thinking segment`() {
        val (registry, run) = registryWithRun()
        registry.appendSegmentThinking(run.id, "before ")
        val snap = registry.turnSnapshot(run.id)
        registry.appendSegmentThinking(run.id, "failed partial")
        registry.resetTurn(run.id, snap)
        registry.appendSegmentThinking(run.id, "after")

        val snapshot = registry.runs.value.single()
        assertEquals("before after", snapshot.thinkingText)
        assertEquals(
            1,
            snapshot.segments.count { it is SubagentRunRegistry.Segment.Thinking },
        )
    }

    @Test
    fun `rollback undoes resultText written by the failed attempt`() {
        val (registry, run) = registryWithRun()
        // Pre-attempt state (earlier turns already delivered text).
        registry.appendResultText(run.id, "turn-1 answer. ")
        val snap = registry.turnSnapshot(run.id)

        // The failed attempt streamed partial text — the runner writes BOTH
        // the transcript AND resultText per delta, so the retry would double
        // the partial answer inside what the parent eventually consumes.
        registry.appendResultText(run.id, "partial ")
        registry.appendSegmentText(run.id, "partial ")
        registry.resetTurn(run.id, snap)

        // Retry delivers the full turn text.
        registry.appendResultText(run.id, "full answer.")

        val snapshot = registry.runs.value.single()
        assertEquals("turn-1 answer. full answer.", snapshot.resultText)
        assertFalse(snapshot.resultText.contains("partial"))
    }

    // ── stable UI keys ───────────────────────────────────────────────────

    @Test
    fun `segment seq stays stable when the transcript is pruned`() {
        val (registry, run) = registryWithRun()
        registry.appendSegmentText(run.id, "first — will be pruned")
        val firstSeq = registry.runs.value.single().segments.single().seq

        // Push past the cap so pruning rotates the head.
        repeat(SubagentRunRegistry.MAX_SEGMENTS) { i ->
            registry.appendSegmentText(run.id, "t$i ")
            registry.stepStarted(run.id, "call-$i", 1, "file_read", "Read $i")
        }

        val remaining = registry.runs.value.single().segments
            .filterIsInstance<SubagentRunRegistry.Segment.Text>()
        // The pruned-away segment is gone; every surviving seq differs from it
        // and is strictly increasing — i.e. seq is identity, not index.
        assertTrue(remaining.none { it.seq == firstSeq })
        assertEquals(remaining.map { it.seq }, remaining.map { it.seq }.sorted())
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
