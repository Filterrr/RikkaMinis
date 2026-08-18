package com.openminis.app.ui.chat

import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure compaction helpers extracted in FE-4 route A
 * ([resolveCompactAnchorIdx] / [resolveCompactStartIdx]).
 *
 * These mirror the exact branch semantics of the former inline logic in
 * ChatViewModel.compactAll (fcf9470).
 */
class ChatCompactionLogicTest {

    // ── helpers ────────────────────────────────────────────────

    private fun msg(
        role: LLMMessage.Role,
        id: String? = null,
        parts: List<AgentContentPart> = listOf(AgentContentPart.Text("x")),
    ) = LLMMessage(role = role, content = "x", contentParts = parts, dbMessageId = id)

    private fun toolResultUser(id: String? = null): LLMMessage =
        msg(LLMMessage.Role.USER, id, listOf(AgentContentPart.ToolResult("t", "n", "r")))

    private fun user(id: String? = null): LLMMessage = msg(LLMMessage.Role.USER, id)

    private fun assistant(id: String? = null): LLMMessage = msg(LLMMessage.Role.ASSISTANT, id)

    private fun marker(
        version: Int = 2,
        lastCompacted: String? = null,
        firstKept: String? = null,
        boundary: String? = null,
    ) = CompactMarkerEntity(
        id = "m",
        sessionId = "s",
        summary = "",
        firstKeptSortOrder = 0,
        compactedCount = 0,
        createdAt = 0L,
        boundaryMessageId = boundary,
        firstKeptMessageId = firstKept,
        lastCompactedMessageId = lastCompacted,
        version = version,
    )

    // ── resolveCompactAnchorIdx ────────────────────────────────

    @Test
    fun `empty history returns minus one`() {
        assertEquals(-1, resolveCompactAnchorIdx(emptyList(), null))
        assertEquals(-1, resolveCompactAnchorIdx(emptyList(), 0))
    }

    @Test
    fun `override walks back to closest persisted entry`() {
        val h = listOf(user("u1"), user(null), assistant("a1"))
        // override at index 2 (persisted) → stays 2
        assertEquals(2, resolveCompactAnchorIdx(h, 2))
        // override at index 1 (not persisted) → walks back to 0
        assertEquals(0, resolveCompactAnchorIdx(h, 1))
    }

    @Test
    fun `override clamps to history bounds`() {
        val h = listOf(user("u1"))
        assertEquals(0, resolveCompactAnchorIdx(h, 999))   // clamped to lastIndex 0
        assertEquals(0, resolveCompactAnchorIdx(h, -5))    // clamped to 0
    }

    @Test
    fun `tail walk back picks last persisted user not tool result`() {
        // Real shape: ... user prompt (persisted), tool result (user/tool-only),
        // assistant answer (persisted). keep-answer-active should anchor at the
        // last persisted USER prompt, skipping the tool-result and the answer.
        val h = listOf(
            user("u1"),
            toolResultUser("tr1"),
            assistant("a1"),
        )
        assertEquals(0, resolveCompactAnchorIdx(h, null))
    }

    @Test
    fun `tail walk back skips pure tool result user entries`() {
        val h = listOf(
            user("u1"),
            toolResultUser(null),   // unpersisted tool result
            assistant("a1"),
        )
        // state the walk back from tail: a1 is ASSISTANT → skip; tr1 (idx1) USER but
        // all ToolResult → skip; u1 (idx0) USER, not tool-only, persisted → anchor 0
        assertEquals(0, resolveCompactAnchorIdx(h, null))
    }

    @Test
    fun `tail walk back returns minus one when nothing persisted`() {
        val h = listOf(user(null), assistant(null))
        assertEquals(-1, resolveCompactAnchorIdx(h, null))
    }

    // ── resolveCompactStartIdx ─────────────────────────────────

    @Test
    fun `null marker starts at zero`() {
        assertEquals(0, resolveCompactStartIdx(listOf(user("u1")), null))
    }

    @Test
    fun `v2 marker starts after prev anchor`() {
        val h = listOf(user("u1"), assistant("a1"), user("u2"))
        val m = marker(version = 2, lastCompacted = "a1")
        // a1 at index 1 → start = 1 + 1 = 2
        assertEquals(2, resolveCompactStartIdx(h, m))
    }

    @Test
    fun `v1 marker starts at prev anchor inclusive`() {
        val h = listOf(user("u1"), assistant("a1"), user("u2"))
        val m = marker(version = 1, firstKept = "a1")
        // a1 at index 1 → start = 1
        assertEquals(1, resolveCompactStartIdx(h, m))
    }

    @Test
    fun `v1 marker falls back to boundary message id`() {
        val h = listOf(user("u1"), assistant("a1"), user("u2"))
        val m = marker(version = 1, firstKept = null, boundary = "u2")
        assertEquals(2, resolveCompactStartIdx(h, m))
    }

    @Test
    fun `prev anchor not in history restarts from top`() {
        val h = listOf(user("u1"), assistant("a1"))
        val m = marker(version = 2, lastCompacted = "ghost")
        assertEquals(0, resolveCompactStartIdx(h, m))
    }
}
