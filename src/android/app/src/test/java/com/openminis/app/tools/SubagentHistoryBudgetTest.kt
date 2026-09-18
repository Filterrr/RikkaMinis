package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-context-budget] Tests for the sub-agent loop's history trimmer.
 *
 * The shape under test is what [SubagentRunner] actually builds: ONE real user
 * prompt (the task) followed by assistant rounds and tool_result carriers.
 * A "turn" is one assistant round plus its carriers — the main loop's
 * "turns start at a user prompt" rule would never fire here.
 */
class SubagentHistoryBudgetTest {

    private fun task() = LLMMessage(LLMMessage.Role.USER, "TASK " .repeat(20))

    private fun assistantRound(payloadChars: Int): List<LLMMessage> = listOf(
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "working",
            contentParts = listOf(
                AgentContentPart.ToolUse(id = "t", name = "shell_execute", input = org.json.JSONObject()),
            ),
        ),
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "Result of shell_execute (t):\n" + "x".repeat(payloadChars),
            contentParts = listOf(
                AgentContentPart.ToolResult(
                    id = "t", name = "shell_execute",
                    content = "x".repeat(payloadChars),
                ),
            ),
        ),
    )

    private fun history(rounds: Int, payloadChars: Int = 1500): List<LLMMessage> =
        listOf(task()) + (1..rounds).flatMap { assistantRound(payloadChars) }

    @Test
    fun `window of zero disables trimming`() {
        val h = history(rounds = 10)
        val r = SubagentHistoryBudget.trim(h, contextWindowTokens = 0)
        assertFalse(r.didTrim)
        assertEquals(h, r.messages)
    }

    @Test
    fun `history under budget is untouched`() {
        val h = history(rounds = 3, payloadChars = 10)
        assertFalse(SubagentHistoryBudget.trim(h, contextWindowTokens = 200_000).didTrim)
    }

    @Test
    fun `over-budget history trims down to the keep floor`() {
        val h = history(rounds = 12)
        val r = SubagentHistoryBudget.trim(h, contextWindowTokens = 2000, keepRecentTurns = 4)
        assertTrue("expected trim, est=${r.estimatedTokens}", r.didTrim)
        assertTrue("under budget: ${r.estimatedTokens}", r.estimatedTokens <= 2000)
        val assistantsKept = r.messages.count { it.role == LLMMessage.Role.ASSISTANT }
        assertEquals(4, assistantsKept)
    }

    @Test
    fun `keep floor wins over the budget when they cannot both hold`() {
        // 4 rounds cannot fit in 100 tokens. The floor is a hard constraint —
        // an agent stripped of its active state cannot recover, while a
        // provider context error is at least loud and retryable.
        val h = history(rounds = 12)
        val r = SubagentHistoryBudget.trim(h, contextWindowTokens = 100, keepRecentTurns = 4)
        assertTrue(r.didTrim)
        assertEquals(4, r.messages.count { it.role == LLMMessage.Role.ASSISTANT })
    }

    @Test
    fun `task message survives as the first kept message`() {
        val r = SubagentHistoryBudget.trim(history(rounds = 12), contextWindowTokens = 1200)
        assertTrue(r.messages.first().content.startsWith("TASK"))
    }

    @Test
    fun `trimming is announced to the model`() {
        val r = SubagentHistoryBudget.trim(history(rounds = 12), contextWindowTokens = 1200)
        assertTrue(r.messages.first().content.contains(SubagentHistoryBudget.ELISION_PREFIX))
    }

    @Test
    fun `no orphan tool_result carrier at the head of the kept window`() {
        val h = history(rounds = 12)
        val r = SubagentHistoryBudget.trim(h, contextWindowTokens = 1200)
        // First message after the task must be an assistant round, not a result.
        assertEquals(LLMMessage.Role.ASSISTANT, r.messages[1].role)
    }

    @Test
    fun `tool_result payload is not double counted`() {
        // content and ToolResult.content carry the same text; counting both
        // would double every tool output — the bulk of a sub-agent history.
        val withDup = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "y".repeat(3500),
            contentParts = listOf(
                AgentContentPart.ToolResult(id = "t", name = "shell_execute", content = "y".repeat(3500)),
            ),
        )
        val estimate = SubagentHistoryBudget.estimateTokens(listOf(withDup))
        assertTrue("got $estimate tokens", estimate in 900..1200)
    }

    @Test
    fun `api reported token count overrides the local estimate`() {
        val h = history(rounds = 5, payloadChars = 100)
        // Local estimate is small, but the API says we're huge → must trim.
        val r = SubagentHistoryBudget.trim(h, contextWindowTokens = 500, apiContextTokens = 9000)
        assertTrue(r.didTrim)
    }

    @Test
    fun `history shorter than the keep window is left alone`() {
        val h = history(rounds = 2, payloadChars = 5000)
        val r = SubagentHistoryBudget.trim(h, contextWindowTokens = 100, keepRecentTurns = 4)
        assertFalse("nothing to drop without violating the floor", r.didTrim)
    }

    // ── [T-subagent-vision] image payloads in the loop ───────────────────

    /** A tool_result round carrying image bytes (read_image / screenshot). */
    private fun imageRound(bytes: Int, linuxPath: String? = "/var/minis/workspace/chart.png"): List<LLMMessage> = listOf(
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "reading",
            contentParts = listOf(
                AgentContentPart.ToolUse(id = "img", name = "read_image", input = org.json.JSONObject()),
            ),
        ),
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "Result of read_image (img):\n[chart.png | 800x600 | N bytes]",
            contentParts = listOf(
                AgentContentPart.ToolResult(
                    id = "img", name = "read_image",
                    content = "[chart.png | 800x600 | N bytes]",
                    imageData = ByteArray(bytes),
                    imageMimeType = "image/jpeg",
                    imageLinuxPath = linuxPath,
                ),
            ),
        ),
    )

    private fun historyWithImages(rounds: Int, bytesPerImage: Int): MutableList<LLMMessage> {
        val h = mutableListOf(task())
        repeat(rounds) { h.addAll(imageRound(bytesPerImage)) }
        return h
    }

    /**
     * Image bytes must count toward the token estimate. Before the vision
     * forwarding, `estimateTokens` read only `part.content`; a screenshot-heavy
     * run would then under-report its true payload and sail past the trimming
     * threshold into the provider's real context limit — dying on an error
     * instead of degrading, the exact failure this module exists to prevent.
     */
    @Test
    fun `tool result images count toward the token estimate`() {
        val textOnly = historyWithImages(rounds = 1, bytesPerImage = 0).let { h ->
            // Strip the bytes to make a pure-text baseline of the same shape.
            h.map { msg ->
                msg.copy(contentParts = msg.contentParts.map { p ->
                    if (p is AgentContentPart.ToolResult) p.copy(imageData = null, imageMimeType = null) else p
                })
            }
        }
        val withImages = historyWithImages(rounds = 1, bytesPerImage = 50_000)

        val plain = SubagentHistoryBudget.estimateTokens(textOnly)
        val imaged = SubagentHistoryBudget.estimateTokens(withImages)
        assertTrue("an image must raise the estimate (plain=$plain imaged=$imaged)", imaged > plain)
    }

    /** Stale rounds lose their bytes; recent rounds keep theirs. */
    @Test
    fun `stale images are elided and recent rounds keep theirs`() {
        val history = historyWithImages(rounds = 8, bytesPerImage = 1000)

        val (slimmed, elided) = SubagentHistoryBudget.elideStaleImages(history, keepRecentTurns = 4)

        assertTrue("older images must be elided", elided > 0)
        val remaining = slimmed.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>()
            .count { it.imageData != null }
        assertEquals("exactly the newest 4 rounds keep pixels", 4, remaining)
        assertEquals("message count is unchanged", history.size, slimmed.size)
    }

    /** An elided image keeps a re-fetchable pointer — never a silent hole. */
    @Test
    fun `an elided image names its linux path`() {
        val history = historyWithImages(rounds = 8, bytesPerImage = 1000)

        val (slimmed, _) = SubagentHistoryBudget.elideStaleImages(history, keepRecentTurns = 2)

        val dropped = slimmed.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>()
            .first { it.imageData == null }
        assertTrue("must name the recovery route", dropped.content.contains("read_image"))
        assertTrue(dropped.content.contains("/var/minis/workspace/chart.png"))
    }

    /** With no path there is nothing to point at, but the loss is still stated. */
    @Test
    fun `an elided image without a path says it is gone`() {
        val history = historyWithImages(rounds = 8, bytesPerImage = 1000)
        // Strip the paths to force the unaddressable branch.
        val pathless = history.map { msg ->
            msg.copy(contentParts = msg.contentParts.map { p ->
                if (p is AgentContentPart.ToolResult) p.copy(imageLinuxPath = null) else p
            })
        }.toMutableList()

        val (slimmed, _) = SubagentHistoryBudget.elideStaleImages(pathless, keepRecentTurns = 2)

        val dropped = slimmed.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>()
            .first { it.imageData == null }
        assertTrue(dropped.content.contains("no longer addressable"))
    }

    /** A short history (inside the keep window) is untouched, byte-for-byte. */
    @Test
    fun `a history inside the keep window keeps every image`() {
        val history = historyWithImages(rounds = 3, bytesPerImage = 1000)

        val (slimmed, elided) = SubagentHistoryBudget.elideStaleImages(history, keepRecentTurns = 4)

        assertEquals(0, elided)
        assertEquals(history, slimmed)
    }

    /** Idempotent: a second pass over an already-elided history changes nothing. */
    @Test
    fun `elision is idempotent`() {
        val history = historyWithImages(rounds = 8, bytesPerImage = 1000)
        val (once, firstCount) = SubagentHistoryBudget.elideStaleImages(history, keepRecentTurns = 2)
        val (twice, secondCount) = SubagentHistoryBudget.elideStaleImages(once, keepRecentTurns = 2)

        assertTrue(firstCount > 0)
        assertEquals("a re-run must find nothing left to elide", 0, secondCount)
        assertEquals(once, twice)
    }
}
