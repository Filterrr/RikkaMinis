package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ask-user] 挂起式提问队列的竞态契约。
 *
 * 这套语义是从 [SpawnApprovalQueue] 抄过来的（resolve-once / remove-on-timeout
 * / teardown-denies），但抄过来没验过。它守的是三条会真的挂死 agent 的路径：
 * 用户点了两次、等待方超时后对话框还在、VM 销毁时还有人在等。
 */
class AskUserQueueTest {

    @Test
    fun `resolve delivers exactly once`() {
        val queue = AskUserQueue()
        val (request, deferred) = queue.submit("Which API?", listOf("A", "B"), allowFreeText = false)

        assertTrue(queue.resolve(request.id, AskUserAnswer(1, null)))
        assertFalse("second resolve must not deliver twice", queue.resolve(request.id, AskUserAnswer(0, null)))

        assertTrue(deferred.isCompleted)
        assertEquals(1, deferred.getCompleted()?.selectedIndex)
        assertTrue("resolved ask leaves the UI queue", queue.requests.value.isEmpty())
    }

    @Test
    fun `remove on timeout leaves the deferred unresolved`() {
        val queue = AskUserQueue()
        val (request, deferred) = queue.submit("Q", listOf("A"), allowFreeText = false)

        queue.remove(request.id)
        assertFalse(deferred.isCompleted)
        assertFalse("a late tap resolves nothing", queue.resolve(request.id, AskUserAnswer(0, null)))
        assertTrue(queue.requests.value.isEmpty())
    }

    @Test
    fun `teardown completes every outstanding ask with null`() {
        val queue = AskUserQueue()
        val (a, da) = queue.submit("Q1", emptyList(), allowFreeText = true)
        val (b, db) = queue.submit("Q2", listOf("x"), allowFreeText = false)

        assertEquals(2, queue.cancelAllOutstanding())
        assertTrue(da.isCompleted && db.isCompleted)
        assertEquals(null, da.getCompleted())
        assertEquals(null, db.getCompleted())
        assertTrue(queue.requests.value.isEmpty())
        assertTrue(a.id != b.id)
    }

    @Test
    fun `options are trimmed, blanks dropped and capped`() {
        val queue = AskUserQueue()
        val (request, _) = queue.submit(
            "pick",
            listOf(" a ", "", "  ", "b", "c", "d", "e", "f", "g"),
            allowFreeText = false,
        )
        assertEquals(AskUserQueue.MAX_OPTIONS, request.options.size)
        assertEquals("a", request.options.first())
        assertFalse(request.allowFreeText)
    }

    @Test
    fun `no options forces free text so the dialog is not empty`() {
        val queue = AskUserQueue()
        val (request, _) = queue.submit("open ended", emptyList(), allowFreeText = false)
        assertTrue("a dialog with nothing tappable is a dead end", request.allowFreeText)
    }

    @Test
    fun `questions are capped and trimmed`() {
        val queue = AskUserQueue()
        val long = "x".repeat(AskUserQueue.MAX_QUESTION_CHARS + 500)
        val (request, _) = queue.submit("  $long  ", emptyList(), allowFreeText = true)
        assertEquals(AskUserQueue.MAX_QUESTION_CHARS, request.question.length)
    }

    @Test
    fun `unknown ids are ignored`() {
        val queue = AskUserQueue()
        assertFalse(queue.resolve("ask-nope", AskUserAnswer(0, "x")))
        queue.remove("ask-nope")
        assertEquals(0, queue.cancelAllOutstanding())
    }
}