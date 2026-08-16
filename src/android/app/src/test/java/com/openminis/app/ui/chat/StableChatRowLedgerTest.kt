package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [StableChatRowLedger] — the session-lifetime stable row list.
 *
 * Contract under test: once a row key is published it is never deleted or
 * reordered while its message is the active turn; growth is prefix-append at
 * the tail only.
 */
class StableChatRowLedgerTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun userMessage(id: String, content: String = "hello") =
        ChatMessage(id = id, role = "user", content = content)

    private fun assistantMessage(
        id: String,
        content: String = "",
        blocks: List<AssistantBlock> = emptyList(),
        isStreaming: Boolean = false,
        error: String? = null,
    ) = ChatMessage(
        id = id,
        role = "assistant",
        content = content,
        isStreaming = isStreaming,
        toolBlocks = blocks,
        error = error,
    )

    private fun textBlock(id: String, content: String) =
        AssistantBlock(id = id, kind = "text", content = content)

    private fun thinkingBlock(id: String, content: String, status: ToolBlockStatus? = null) =
        AssistantBlock(id = id, kind = "thinking", content = content, toolStatus = status)

    private fun toolBlock(id: String, status: ToolBlockStatus, title: String = "tool") =
        AssistantBlock(id = id, kind = "tool_use", toolStatus = status, toolTitle = title, toolName = "run")

    private fun keysOf(rows: List<FlatChatItem>): List<String> = rows.map { it.key }

    // ── cold open ───────────────────────────────────────────────────────────

    @Test fun `seed then reconcile keeps historical rows byte-identical`() {
        val history = listOf(userMessage("u1"), assistantMessage("a1", content = "Old answer"))
        val seedRows = buildFlatChatItems(history)
        val ledger = StableChatRowLedger()
        ledger.seed(seedRows, history.size)
        val snapshot = ledger.reconcile(history)
        assertEquals(keysOf(seedRows), keysOf(snapshot))
        assertEquals(
            seedRows.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().map { it.rawText },
            snapshot.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().map { it.rawText },
        )
    }

    @Test fun `reconcile without seed falls back to full canonical build`() {
        val messages = listOf(userMessage("u1"), assistantMessage("a1", content = "Answer"))
        val ledger = StableChatRowLedger()
        val rows = ledger.reconcile(messages)
        assertTrue(rows.isNotEmpty())
        assertEquals(listOf("user:u1", "header:a1", "legacy:a1"), keysOf(rows))
    }

    // ── thinking → tool → result → text flow ────────────────────────────────

    @Test fun `thinking then tool then text appends rows without reordering`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)

        // Turn starts: thinking streams (no status yet).
        val t1 = listOf(userMessage("u1"), assistantMessage(msgId, blocks = listOf(thinkingBlock("th1", "Reasoning")), isStreaming = true))
        val k1 = keysOf(ledger.reconcile(t1))
        // header + one toolrun group (thinking folded in)
        assertEquals(listOf("user:u1", "header:a1", "toolrun:a1"), k1)

        // Tool appears.
        val t2 = listOf(userMessage("u1"), assistantMessage(msgId,
            blocks = listOf(thinkingBlock("th1", "Reasoning", ToolBlockStatus.SUCCESS), toolBlock("tool_1", ToolBlockStatus.RUNNING)),
            isStreaming = true))
        val k2 = keysOf(ledger.reconcile(t2))
        // toolrun already published — no key churn, content updated in place.
        assertEquals(k1, k2)

        // Tool finished + text starts.
        val t3 = listOf(userMessage("u1"), assistantMessage(msgId,
            blocks = listOf(
                thinkingBlock("th1", "Reasoning", ToolBlockStatus.SUCCESS),
                toolBlock("tool_1", ToolBlockStatus.SUCCESS),
                textBlock("text_1_2", "The answer is"),
            ),
            isStreaming = true))
        val k3 = keysOf(ledger.reconcile(t3))
        // New text row appended at tail; previous keys unchanged prefix.
        assertTrue(k3.take(k2.size) == k2)
        assertEquals("mdslot:a1:text_1_2:0", k3.last())

        // Text grows across a paragraph boundary: one more slot appended.
        val t4 = listOf(userMessage("u1"), assistantMessage(msgId,
            blocks = listOf(
                thinkingBlock("th1", "Reasoning", ToolBlockStatus.SUCCESS),
                toolBlock("tool_1", ToolBlockStatus.SUCCESS),
                textBlock("text_1_2", "The answer is yes.\n\nMore detail here"),
            ),
            isStreaming = true))
        val k4 = keysOf(ledger.reconcile(t4))
        assertTrue(k4.take(k3.size) == k3)
        assertEquals(listOf("mdslot:a1:text_1_2:0", "mdslot:a1:text_1_2:1"), k4.takeLast(2))
    }

    @Test fun `stream end keeps every key identical`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val before = keysOf(ledger.reconcile(listOf(
            userMessage("u1"),
            assistantMessage(msgId,
                blocks = listOf(textBlock("text_1_0", "P1\n\nP2 tail")),
                isStreaming = true),
        )))
        val after = keysOf(ledger.reconcile(listOf(
            userMessage("u1"),
            assistantMessage(msgId,
                blocks = listOf(textBlock("text_1_0", "P1\n\nP2 tail")),
                isStreaming = false),
        )))
        assertEquals(before, after)
    }

    @Test fun `next user turn leaves previous turn keys byte-identical`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val turn1 = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("text_1_0", "Answer one")), isStreaming = false),
        )
        val k1 = keysOf(ledger.reconcile(turn1))
        // Next user message + new turn.
        val turn2 = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("text_1_0", "Answer one")), isStreaming = false),
            userMessage("u2"),
            assistantMessage("a2", blocks = listOf(textBlock("text_2_1", "Answer two")), isStreaming = false),
        )
        val k2 = keysOf(ledger.reconcile(turn2))
        assertTrue(k2.take(k1.size) == k1)
        // New rows: u2 bubble + a2 header + a2 text slot.
        assertEquals(listOf("user:u2", "header:a2", "mdslot:a2:text_2_1:0"), k2.drop(k1.size))
    }

    // ── text → tool → text (tool arrives late) ──────────────────────────────

    @Test fun `tool appearing after text appends without re-inserting before text`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val t1 = listOf(userMessage("u1"), assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "Preface")), isStreaming = true))
        val k1 = keysOf(ledger.reconcile(t1))
        assertEquals(listOf("user:u1", "header:a1", "mdslot:a1:text_1_0:0"), k1)

        // Tool arrives AFTER text — first-appearance order: toolrun appends.
        val t2 = listOf(userMessage("u1"), assistantMessage(msgId,
            blocks = listOf(
                textBlock("text_1_0", "Preface"),
                toolBlock("tool_2", ToolBlockStatus.RUNNING),
            ),
            isStreaming = true))
        val k2 = keysOf(ledger.reconcile(t2))
        assertTrue(k2.take(k1.size) == k1)
        assertEquals("toolrun:a1", k2.last())
    }

    // ── transient rows ──────────────────────────────────────────────────────

    @Test fun `typing row may disappear without moving anchored rows`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        // Streaming with no visible content yet → typing row.
        val t1 = listOf(userMessage("u1"), assistantMessage(msgId, content = "", isStreaming = true))
        val k1 = keysOf(ledger.reconcile(t1))
        assertTrue(k1.contains("typing:a1"))

        // Content arrives → typing dropped, text row appended, nothing else moved.
        val t2 = listOf(userMessage("u1"), assistantMessage(msgId,
            content = "visible", blocks = listOf(textBlock("text_1_1", "visible")), isStreaming = true))
        val k2 = keysOf(ledger.reconcile(t2))
        assertFalse(k2.contains("typing:a1"))
        assertTrue(k2.contains("header:a1"))
        assertTrue(k2.last() == "mdslot:a1:text_1_1:0")
    }

    @Test fun `error banner row may disappear after retry`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val failed = listOf(userMessage("u1"), assistantMessage(msgId, content = "text", error = "boom"))
        val k1 = keysOf(ledger.reconcile(failed))
        assertTrue(k1.contains("error:a1"))

        val retried = listOf(userMessage("u1"), assistantMessage(msgId, content = "text"))
        val k2 = keysOf(ledger.reconcile(retried))
        assertFalse(k2.contains("error:a1"))
        assertTrue(k2.contains("legacy:a1"))
    }

    // ── retry / text-structure reset ────────────────────────────────────────

    @Test fun `retry with new block ids resets only that message text rows`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val turn1 = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("text_1_0", "Old content")), isStreaming = false),
            userMessage("u2"),
            assistantMessage("a2", blocks = listOf(textBlock("text_2_1", "Second")), isStreaming = false),
        )
        val kBefore = keysOf(ledger.reconcile(turn1))

        // Retry rewrites turn 2 with a NEW turn counter in the block id.
        val retried = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("text_1_0", "Old content")), isStreaming = false),
            userMessage("u2"),
            assistantMessage("a2", blocks = listOf(textBlock("text_3_2", "Fresh retry answer")), isStreaming = false),
        )
        val kAfter = keysOf(ledger.reconcile(retried))
        // Turn 1 keys identical.
        assertEquals(kBefore.take(3), kAfter.take(3))
        // Turn 2: old text row gone, new text row present.
        assertFalse(kAfter.contains("mdslot:a2:text_2_1:0"))
        assertTrue(kAfter.contains("mdslot:a2:text_3_2:0"))
        assertTrue(kAfter.contains("header:a2"))
    }

    // ── neighbour lookback fixes ────────────────────────────────────────────

    @Test fun `back-to-back user messages get precededByUser flag`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val rows = ledger.reconcile(listOf(userMessage("u1"), userMessage("u2")))
        val u2 = rows.filterIsInstance<FlatChatItem.UserBubble>().last()
        assertTrue("second user bubble should carry precededByUser", u2.precededByUser)
    }

    @Test fun `resume continuation suppresses duplicate assistant header`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        // Assistant turn, then a resume creates a NEW assistant message right after.
        val rows = ledger.reconcile(listOf(
            userMessage("u1"),
            assistantMessage("a1", content = "First"),
            assistantMessage("a2", content = "Resumed continuation"),
        ))
        val headers = rows.filterIsInstance<FlatChatItem.AssistantHeader>()
        assertEquals("only one header for a resumed turn", 1, headers.size)
    }

    // ── info rows append in first-appearance order ──────────────────────────

    @Test fun `info row appends at tail and updates in place`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val t1 = listOf(userMessage("u1"), assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "Body")), isStreaming = true))
        val k1 = keysOf(ledger.reconcile(t1))
        val t2 = listOf(userMessage("u1"), assistantMessage(msgId,
            blocks = listOf(
                textBlock("text_1_0", "Body"),
                AssistantBlock(id = "info_9", kind = "info", content = "notice"),
            ),
            isStreaming = true))
        val k2 = keysOf(ledger.reconcile(t2))
        assertTrue(k2.take(k1.size) == k1)
        assertEquals("info:a1:info_9", k2.last())
        // No key churn on subsequent ticks.
        val k3 = keysOf(ledger.reconcile(t2))
        assertEquals(k2, k3)
    }
}
