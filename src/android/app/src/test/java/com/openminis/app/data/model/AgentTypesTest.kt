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
        fun `alphanumeric and allowed chars are preserved`() {
            val input = "abcXYZ012_-"
            assertEquals("abcXYZ012_-", sanitizeToolId(input))
        }

        @Test
        fun `disallowed chars are replaced with dash`() {
            val input = "tool@call#id 1"
            assertEquals("tool-call-id-1", sanitizeToolId(input))
        }

        @Test
        fun `empty string returns empty`() {
            assertEquals("", sanitizeToolId(""))
        }

        @Test
        fun `all disallowed chars replaced`() {
            val input = "!@#$%^&*()"
            assertEquals("----------", sanitizeToolId(input))
        }

        @Test
        fun `unicode letters are replaced with dash`() {
            val input = "工具调用"
            assertEquals("----", sanitizeToolId(input))
        }

        @Test
        fun `underscore and hyphen preserved among other symbols`() {
            val input = "a_b-c.d/e"
            assertEquals("a_b-c-d-e", sanitizeToolId(input))
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
            val a = ToolCallMetadata("s")
            val b = ToolCallMetadata("s")
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
        }

        @Test
        fun `inequality for different values`() {
            assertNotEquals(ToolCallMetadata("a"), ToolCallMetadata("b"))
        }

        @Test
        fun `copy produces equal object`() {
            val original = ToolCallMetadata("sig")
            val copied = original.copy()
            assertEquals(original, copied)
        }
    }

    @Nested
    inner class AgentBlockStartTest {

        @Test
        fun `Text data object is singleton`() {
            val a: AgentBlockStart = AgentBlockStart.Text
            val b: AgentBlockStart = AgentBlockStart.Text
            assertEquals(a, b)
            assertEquals(AgentBlockStart.Text.hashCode(), a.hashCode())
        }

        @Test
        fun `ToolUse holds id and name`() {
            val toolUse = AgentBlockStart.ToolUse(id = "t1", name = "search")
            assertEquals("t1", toolUse.id)
            assertEquals("search", toolUse.name)
        }

        @Test
        fun `ToolUse equality and copy`() {
            val a = AgentBlockStart.ToolUse("id", "name")
            val b = a.copy()
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
        }

        @Test
        fun `ToolUse inequality`() {
            assertNotEquals(
                AgentBlockStart.ToolUse("id1", "name"),
                AgentBlockStart.ToolUse("id2", "name")
            )
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
        fun `TextDelta holds delta string`() {
            val event = AgentStreamEvent.TextDelta("hello")
            assertEquals("hello", event.delta)
        }

        @Test
        fun `ToolInputDelta holds name and accumulated`() {
            val event = AgentStreamEvent.ToolInputDelta(name = "tool", accumulated = "abc")
            assertEquals("tool", event.name)
            assertEquals("abc", event.accumulated)
        }

        @Test
        fun `ToolCallComplete holds all fields with default metadata`() {
            val args = JSONObject().put("key", "value")
            val event = AgentStreamEvent.ToolCallComplete(
                id = "id1",
                name = "tool1",
                args = args
            )
            assertEquals("id1", event.id)
            assertEquals("tool1", event.name)
            assertEquals("value", event.args.getString("key"))
            assertNull(event.metadata)
        }

        @Test
        fun `ToolCallComplete with metadata`() {
            val args = JSONObject()
            val metadata = ToolCallMetadata(thoughtSignature = "sig")
            val event = AgentStreamEvent.ToolCallComplete(
                id = "id",
                name = "n",
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
        fun `event subtypes are not equal to each other`() {
            val textDelta = AgentStreamEvent.TextDelta("x")
            val thinkingDelta = AgentStreamEvent.ThinkingDelta("x")
            assertNotEquals(textDelta, thinkingDelta)
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
        fun `all fields can be set`() {
            val parts = listOf<AgentContentPart>()
            val msg = AgentMessage(
                role = AgentMessage.Role.ASSISTANT,
                parts = parts,
                isInterrupted = true,
                reasoningContent = "rc",
                dbMessageId = "db-1"
            )
            assertEquals(AgentMessage.Role.ASSISTANT, msg.role)
            assertEquals(parts, msg.parts)
            assertTrue(msg.isInterrupted)
            assertEquals("rc", msg.reasoningContent)
            assertEquals("db-1", msg.dbMessageId)
        }

        @Test
        fun `mutable fields can be updated`() {
            val msg = AgentMessage(
                role = AgentMessage.Role.USER,
                parts = emptyList()
            )
            msg.isInterrupted = true
            msg.reasoningContent = "updated"
            msg.dbMessageId = "id-2"
            msg.parts = emptyList()

            assertTrue(msg.isInterrupted)
            assertEquals("updated", msg.reasoningContent)
            assertEquals("id-2", msg.dbMessageId)
        }

        @Test
        fun `equality and copy`() {
            val msg = AgentMessage(
                role = AgentMessage.Role.USER,
                parts = emptyList(),
                isInterrupted = false,
                reasoningContent = null,
                dbMessageId = null
            )
            val copy = msg.copy()
            assertEquals(msg, copy)
            assertEquals(msg.hashCode(), copy.hashCode())
        }

        @Test
        fun `inequality when fields differ`() {
            val a = AgentMessage(AgentMessage.Role.USER, emptyList())
            val b = AgentMessage(AgentMessage.Role.ASSISTANT, emptyList())
            assertNotEquals(a, b)
        }
    }

    @Nested
    inner class AgentMessageRoleTest {

        @Test
        fun `USER role value is user`() {
            assertEquals("user", AgentMessage.Role.USER.value)
        }

        @Test
        fun `ASSISTANT role value is assistant`() {
            assertEquals("assistant", AgentMessage.Role.ASSISTANT.value)
        }

        @Test
        fun `role enum has two values`() {
            val values = AgentMessage.Role.values()
            assertEquals(2, values.size)
            assertTrue(values.contains(AgentMessage.Role.USER))
            assertTrue(values.contains(AgentMessage.Role.ASSISTANT))
        }

        @Test
        fun `valueOf returns correct role`() {
            assertEquals(AgentMessage.Role.USER, AgentMessage.Role.valueOf("USER"))
            assertEquals(AgentMessage.Role.ASSISTANT, AgentMessage.Role.valueOf("ASSISTANT"))
        }
    }
}