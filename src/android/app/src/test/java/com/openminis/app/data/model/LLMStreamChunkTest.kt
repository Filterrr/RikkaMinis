package com.openminis.app.data.model

import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LLMStreamChunkTest {

    @Test
    fun `Started is a data object`() {
        val chunk1 = LLMStreamChunk.Started
        val chunk2 = LLMStreamChunk.Started
        assertEquals(chunk1, chunk2)
        assertEquals("Started", chunk1.toString())
    }

    @Test
    fun `Text stores and returns text`() {
        val chunk = LLMStreamChunk.Text("Hello")
        assertEquals("Hello", chunk.text)
    }

    @Test
    fun `Text equality works`() {
        val chunk1 = LLMStreamChunk.Text("Hello")
        val chunk2 = LLMStreamChunk.Text("Hello")
        val chunk3 = LLMStreamChunk.Text("World")
        assertEquals(chunk1, chunk2)
        assertNotEquals(chunk1, chunk3)
    }

    @Test
    fun `Usage stores usage`() {
        val usage = LLMUsage(10, 20, 30)
        val chunk = LLMStreamChunk.Usage(usage)
        assertEquals(usage, chunk.usage)
    }

    @Test
    fun `Usage equality works`() {
        val usage1 = LLMUsage(10, 20, 30)
        val usage2 = LLMUsage(10, 20, 30)
        val chunk1 = LLMStreamChunk.Usage(usage1)
        val chunk2 = LLMStreamChunk.Usage(usage2)
        assertEquals(chunk1, chunk2)
    }

    @Test
    fun `Finished with stopReason and default truncated`() {
        val chunk = LLMStreamChunk.Finished("stop")
        assertEquals("stop", chunk.stopReason)
        assertFalse(chunk.truncated)
    }

    @Test
    fun `Finished with truncated true`() {
        val chunk = LLMStreamChunk.Finished("stop", truncated = true)
        assertTrue(chunk.truncated)
    }

    @Test
    fun `Finished with null stopReason`() {
        val chunk = LLMStreamChunk.Finished(null)
        assertNull(chunk.stopReason)
    }

    @Test
    fun `Finished equality works`() {
        val chunk1 = LLMStreamChunk.Finished("stop", true)
        val chunk2 = LLMStreamChunk.Finished("stop", true)
        val chunk3 = LLMStreamChunk.Finished("stop", false)
        assertEquals(chunk1, chunk2)
        assertNotEquals(chunk1, chunk3)
    }

    @Test
    fun `ThinkingDelta stores text`() {
        val chunk = LLMStreamChunk.ThinkingDelta("thinking")
        assertEquals("thinking", chunk.text)
    }

    @Test
    fun `ThinkingDelta equality works`() {
        val chunk1 = LLMStreamChunk.ThinkingDelta("thinking")
        val chunk2 = LLMStreamChunk.ThinkingDelta("thinking")
        val chunk3 = LLMStreamChunk.ThinkingDelta("other")
        assertEquals(chunk1, chunk2)
        assertNotEquals(chunk1, chunk3)
    }

    @Test
    fun `ReasoningContent stores content`() {
        val chunk = LLMStreamChunk.ReasoningContent("reasoning")
        assertEquals("reasoning", chunk.content)
    }

    @Test
    fun `ReasoningContent equality works`() {
        val chunk1 = LLMStreamChunk.ReasoningContent("reasoning")
        val chunk2 = LLMStreamChunk.ReasoningContent("reasoning")
        val chunk3 = LLMStreamChunk.ReasoningContent("other")
        assertEquals(chunk1, chunk2)
        assertNotEquals(chunk1, chunk3)
    }

    @Test
    fun `ToolUseStart stores id and name`() {
        val chunk = LLMStreamChunk.ToolUseStart("1", "toolA")
        assertEquals("1", chunk.id)
        assertEquals("toolA", chunk.name)
    }

    @Test
    fun `ToolUseStart equality works`() {
        val chunk1 = LLMStreamChunk.ToolUseStart("1", "toolA")
        val chunk2 = LLMStreamChunk.ToolUseStart("1", "toolA")
        val chunk3 = LLMStreamChunk.ToolUseStart("2", "toolB")
        assertEquals(chunk1, chunk2)
        assertNotEquals(chunk1, chunk3)
    }

    @Test
    fun `ToolInputDelta stores id and accumulated`() {
        val chunk = LLMStreamChunk.ToolInputDelta("1", "acc")
        assertEquals("1", chunk.id)
        assertEquals("acc", chunk.accumulated)
    }

    @Test
    fun `ToolInputDelta equality works`() {
        val chunk1 = LLMStreamChunk.ToolInputDelta("1", "acc")
        val chunk2 = LLMStreamChunk.ToolInputDelta("1", "acc")
        val chunk3 = LLMStreamChunk.ToolInputDelta("2", "other")
        assertEquals(chunk1, chunk2)
        assertNotEquals(chunk1, chunk3)
    }

    @Test
    fun `ToolCallComplete stores id, name, and args`() {
        val args = JSONObject("""{"key": "value"}""")
        val chunk = LLMStreamChunk.ToolCallComplete("1", "toolA", args)
        assertEquals("1", chunk.id)
        assertEquals("toolA", chunk.name)
        assertEquals(args, chunk.args)
    }

    @Test
    fun `ToolCallComplete equality works`() {
        val args1 = JSONObject("""{"key": "value"}""")
        val args2 = JSONObject("""{"key": "value"}""")
        val args3 = JSONObject("""{"key": "other"}""")
        val chunk1 = LLMStreamChunk.ToolCallComplete("1", "toolA", args1)
        val chunk2 = LLMStreamChunk.ToolCallComplete("1", "toolA", args2)
        val chunk3 = LLMStreamChunk.ToolCallComplete("1", "toolA", args3)
        assertEquals(chunk1, chunk2)
        assertNotEquals(chunk1, chunk3)
    }

    @Test
    fun `MediaAttachment stores attachment`() {
        val attachment = LLMMediaAttachment("type", "data")
        val chunk = LLMStreamChunk.MediaAttachment(attachment)
        assertEquals(attachment, chunk.attachment)
    }

    @Test
    fun `MediaAttachment equality works`() {
        val attachment1 = LLMMediaAttachment("type", "data")
        val attachment2 = LLMMediaAttachment("type", "data")
        val attachment3 = LLMMediaAttachment("type", "other")
        val chunk1 = LLMStreamChunk.MediaAttachment(attachment1)
        val chunk2 = LLMStreamChunk.MediaAttachment(attachment2)
        val chunk3 = LLMStreamChunk.MediaAttachment(attachment3)
        assertEquals(chunk1, chunk2)
        assertNotEquals(chunk1, chunk3)
    }
}