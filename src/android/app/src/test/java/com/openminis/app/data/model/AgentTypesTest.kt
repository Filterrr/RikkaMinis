package com.openminis.app.data.model

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AgentTypesTest {

    @Nested
    inner class SanitizeToolIdTest {
        @Test
        fun `alphanumeric and underscore and dash are preserved`() {
            val input = "abc123_-XYZ"
            assertEquals("abc123_-XYZ", sanitizeToolId(input))
        }

        @Test
        fun `special characters are replaced with dash`() {
            val input = "tool@call#id/1"
            assertEquals("tool-call-id-1", sanitizeToolId(input))
        }

        @Test
        fun `spaces are replaced with dash`() {
            assertEquals("a-b-c", sanitizeToolId("a b c"))
        }

        @Test
        fun `empty string returns empty`() {
            assertEquals("", sanitizeToolId(""))
        }

        @Test
        fun `unicode letters are replaced with dash`() {
            // isLetterOrDigit returns true for unicode letters in Kotlin
            val result = sanitizeToolId("工具_1")
            assertTrue(result.contains("-"))
            assertTrue(result.contains("_"))
            assertTrue(result.contains("1"))
        }

        @Test
        fun `all special chars replaced`() {
            val input = "!@#$%^&*()"
            assertEquals("----------", sanitizeToolId(input))
        }
    }

    @Nested
    inner class AgentStopReasonTest {
        @Test
        fun `enum values exist`() {
            val values = AgentStopReason.values()
            assertTrue(values.contains(AgentStopReason.END_TURN))
            assertTrue(values.contains(AgentStopReason.TOOL_USE))
            assertTrue(values.contains(AgentStopReason.MAX_TOKENS))
            assertEquals(3, values.size)
        }

        @Test
        fun `valueOf returns correct enum`() {
            assertEquals(AgentStopReason.END_TURN, AgentStopReason.valueOf("END_TURN"))
            assertEquals(AgentStopReason.TOOL_USE, AgentStopReason.valueOf("TOOL_USE"))
            assertEquals(AgentStopReason.MAX_TOKENS, AgentStopReason.valueOf("MAX_TOKENS"))
        }
    }

    @Nested
    inner class ToolCallMetadataTest {
        @Test
        fun `default thoughtSignature is null`() {
            val metadata = ToolCallMetadata()
            assertNull(metadata.thoughtSignature)
        }

        @Test
        fun `thoughtSignature can be set`() {
            val metadata = ToolCallMetadata(thoughtSignature = "sig-123")
            assertEquals("sig-123", metadata.thoughtSignature)
        }

        @Test
        fun `equality holds for same values`() {
            assertEquals(ToolCallMetadata("a"), ToolCallMetadata("a"))
            assertNotEquals(ToolCallMetadata("a"), ToolCallMetadata("b"))
        }

        @Test
        fun `copy works as expected`() {
            val original = ToolCallMetadata("sig")
            val copied = original.copy(thoughtSignature = "new")
            assertEquals("new", copied.thoughtSignature)
            assertEquals("sig", original.thoughtSignature)
        }
    }

    @Nested
    inner class AgentBlockStartTest {
        @Test
        fun `Text is singleton instance`() {
            val a: AgentBlockStart = AgentBlockStart.Text
            val b: AgentBlockStart = AgentBlockStart.Text
            assertEquals(a, b)
        }

        @Test
        fun `ToolUse holds id and name`() {
            val toolUse = AgentBlockStart.ToolUse(id = "t1", name = "search")
            assertEquals("t1", toolUse.id)
            assertEquals("search", toolUse.name)
        }

        @Test
        fun `ToolUse equality and copy`() {
            val t1 = AgentBlockStart.ToolUse("id1", "name1")
            val t2 = AgentBlockStart.ToolUse("id1", "name1")
            assertEquals(t1, t2)
            assertEquals(t1.copy(name = "name2"), AgentBlockStart.ToolUse("id1", "name2"))
        }
    }

    @Nested
    inner class AgentStreamEventTest {

        @Test
        fun `ContentBlockStart wraps AgentBlockStart`() {
            val event = AgentStreamEvent.ContentBlockStart(AgentBlockStart.Text)
            assertEquals(AgentBlockStart.Text, event.start)
        }

        @Test
        fun `ContentBlockStart wraps ToolUse`() {
            val start = AgentBlockStart.ToolUse("id", "name")
            val event = AgentStreamEvent.ContentBlockStart(start)
            assertEquals(start, event.start)
        }

        @Test
        fun `TextDelta holds delta string`() {
            val event = AgentStreamEvent.TextDelta("hello")
            assertEquals("hello", event.delta)
        }

        @Test
        fun `ToolInputDelta holds name and accumulated`() {
            val event = AgentStreamEvent.ToolInputDelta("tool", "acc")
            assertEquals("tool", event.name)
            assertEquals("acc", event.accumulated)
        }

        @Test
        fun `ToolCallComplete holds all fields with default metadata`() {
            val args = JSONObject().put("k", "v")
            val event = AgentStreamEvent.ToolCallComplete(
                id = "call1",
                name = "search",
                args = args
            )
            assertEquals("call1", event.id)
            assertEquals("search", event.name)
            assertEquals("v", event.args.getString("k"))
            assertNull(event.metadata)
        }

        @Test
        fun `ToolCallComplete with metadata`() {
            val args = JSONObject()
            val metadata = ToolCallMetadata("sig")
            val event = AgentStreamEvent.ToolCallComplete(
                id = "call1",
                name = "search",
                args = args,
                metadata = metadata
            )
            assertEquals(metadata, event.metadata)
        }

        @Test
        fun `Usage wraps LLMUsage`() {
            val usage = LLMUsage(inputTokens = 10, outputTokens = 20)
            val event = AgentStreamEvent.Usage(usage)
            assertEquals(usage, event.usage)
        }

        @Test
        fun `ThinkingDelta holds delta`() {
            val event = AgentStreamEvent.ThinkingDelta("think")
            assertEquals("think", event.delta)
        }

        @Test
        fun `ReasoningContent holds content`() {
            val event = AgentStreamEvent.ReasoningContent("reasoning")
            assertEquals("reasoning", event.content)
        }

        @Test
        fun `Done holds stop reason`() {
            val event = AgentStreamEvent.Done(AgentStopReason.END_TURN)
            assertEquals(AgentStopReason.END_TURN, event.stopReason)
        }

        @Test
        fun `events of different types are not equal`() {
            val a: AgentStreamEvent = AgentStreamEvent.TextDelta("x")
            val b: AgentStreamEvent = AgentStreamEvent.ThinkingDelta("x")
            assertNotEquals(a, b)
        }
    }

    @Nested
    inner class AgentMessageTest {

        @Test
        fun `default values are applied`() {
            val msg = AgentMessage(
                role = AgentMessage.Role.USER,
                parts = emptyList()
            )
            assertFalse(msg.isInterrupted)
            assertNull(msg.reasoningContent)
            assertNull(msg.dbMessageId)
            assertTrue(msg.parts.isEmpty())
        }

        @Test
        fun `fields can be mutated`() {
            val msg = AgentMessage(
                role = AgentMessage.Role.ASSISTANT,
                parts = emptyList()
            )
            msg.isInterrupted = true
            msg.reasoningContent = "thinking"
            msg.dbMessageId = "db-1"
            msg.parts = listOf(AgentContentPart.Text("hi"))

            assertTrue(msg.isInterrupted)
            assertEquals("thinking", msg.reasoningContent)
            assertEquals("db-1", msg.dbMessageId)
            assertEquals(1, msg.parts.size)
        }

        @Test
        fun `equality and copy`() {
            val msg1 = AgentMessage(
                role = AgentMessage.Role.USER,
                parts = emptyList(),
                isInterrupted = false,
                reasoningContent = null,
                dbMessageId = null
            )
            val msg2 = msg1.copy()
            assertEquals(msg1, msg2)
            assertNotEquals(msg1, msg2.copy(isInterrupted = true))
        }

        @Test
        fun `Role enum values`() {
            assertEquals("user", AgentMessage.Role.USER.value)
            assertEquals("assistant", AgentMessage.Role.ASSISTANT.value)
            assertEquals(2, AgentMessage.Role.values().size)
        }

        @Test
        fun `Role valueOf works`() {
            assertEquals(AgentMessage.Role.USER, AgentMessage.Role.valueOf("USER"))
            assertEquals(AgentMessage.Role.ASSISTANT, AgentMessage.Role.valueOf("ASSISTANT"))
        }
    }
}