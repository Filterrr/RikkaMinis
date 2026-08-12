package com.openminis.app.conversation

import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

class ContextCompactorTest {

    @Nested
    @DisplayName("decide()")
    inner class DecideTests {

        @Test
        fun `should return COMPACT_IN_FLIGHT when isCompacting is true`() {
            val result = ContextCompactor.decide(
                estimatedTokens = 1000,
                contextWindow = 2000,
                policy = ContextPolicy.AUTO,
                tailTokens = 5000,
                isCompacting = true
            )
            assertEquals(ContextCompactor.Decision.COMPACT_IN_FLIGHT, result)
        }

        @Test
        fun `should return OK when estimatedTokens is zero or negative`() {
            val result = ContextCompactor.decide(
                estimatedTokens = 0,
                contextWindow = 2000,
                policy = ContextPolicy.AUTO,
                tailTokens = 5000,
                isCompacting = false
            )
            assertEquals(ContextCompactor.Decision.OK, result)
        }

        @Test
        fun `should return OK when contextWindow is zero or negative`() {
            val result = ContextCompactor.decide(
                estimatedTokens = 1000,
                contextWindow = 0,
                policy = ContextPolicy.AUTO,
                tailTokens = 5000,
                isCompacting = false
            )
            assertEquals(ContextCompactor.Decision.OK, result)
        }

        @Test
        fun `should return EXHAUSTED when estimatedTokens is at least contextWindow`() {
            val result = ContextCompactor.decide(
                estimatedTokens = 2000,
                contextWindow = 2000,
                policy = ContextPolicy.AUTO,
                tailTokens = 5000,
                isCompacting = false
            )
            assertEquals(ContextCompactor.Decision.EXHAUSTED, result)
        }

        @Test
        fun `should return OK when policy does not need compact`() {
            val result = ContextCompactor.decide(
                estimatedTokens = 500,
                contextWindow = 2000,
                policy = ContextPolicy.DISABLED,
                tailTokens = 5000,
                isCompacting = false
            )
            assertEquals(ContextCompactor.Decision.OK, result)
        }

        @Test
        fun `should return RECENT_AUTO_COMPACT when lastAutoCompact was too recent`() {
            val nowMs = System.currentTimeMillis()
            val result = ContextCompactor.decide(
                estimatedTokens = 1500,
                contextWindow = 2000,
                policy = ContextPolicy.AUTO,
                tailTokens = 5000,
                isCompacting = false,
                lastAutoCompactAtMs = nowMs - 1000,
                nowMs = nowMs,
                minIntervalMs = 5000
            )
            assertEquals(ContextCompactor.Decision.RECENT_AUTO_COMPACT, result)
        }

        @Test
        fun `should return TAIL_TOO_SMALL when tailTokens is less than minTailTokens`() {
            val result = ContextCompactor.decide(
                estimatedTokens = 1500,
                contextWindow = 2000,
                policy = ContextPolicy.AUTO,
                tailTokens = 100,
                isCompacting = false,
                lastAutoCompactAtMs = 0,
                minTailTokens = 500
            )
            assertEquals(ContextCompactor.Decision.TAIL_TOO_SMALL, result)
        }

        @Test
        fun `should return AUTO_COMPACT when all conditions are met`() {
            val result = ContextCompactor.decide(
                estimatedTokens = 1500,
                contextWindow = 2000,
                policy = ContextPolicy.AUTO,
                tailTokens = 1000,
                isCompacting = false,
                lastAutoCompactAtMs = 0,
                minTailTokens = 500
            )
            assertEquals(ContextCompactor.Decision.AUTO_COMPACT, result)
        }

        @Test
        fun `should use default minIntervalMs when not provided`() {
            val nowMs = System.currentTimeMillis()
            val result = ContextCompactor.decide(
                estimatedTokens = 1500,
                contextWindow = 2000,
                policy = ContextPolicy.AUTO,
                tailTokens = 1000,
                isCompacting = false,
                lastAutoCompactAtMs = nowMs - 1000,
                nowMs = nowMs
            )
            assertEquals(ContextCompactor.Decision.RECENT_AUTO_COMPACT, result)
        }
    }

    @Nested
    @DisplayName("estimateTokens()")
    inner class EstimateTokensTests {

        @Test
        fun `should estimate tokens for simple text`() {
            val text = "Hello, world!"
            val expected = (text.length / ContextCompactor.CHARS_PER_TOKEN).toLong()
            assertEquals(expected, ContextCompactor.estimateTokens(text))
        }

        @Test
        fun `should return zero for empty string`() {
            assertEquals(0L, ContextCompactor.estimateTokens(""))
        }

        @Test
        fun `should handle long text`() {
            val text = "A".repeat(100)
            val expected = (100 / ContextCompactor.CHARS_PER_TOKEN).toLong()
            assertEquals(expected, ContextCompactor.estimateTokens(text))
        }
    }

    @Nested
    @DisplayName("estimateMessageTokens()")
    inner class EstimateMessageTokensTests {

        @Test
        fun `should estimate tokens for a simple message`() {
            val message = LLMMessage(
                role = "user",
                content = "Hello"
            )
            val expected = ContextCompactor.estimateTokens(message.content)
            assertEquals(expected, ContextCompactor.estimateMessageTokens(message))
        }

        @Test
        fun `should include tokens from content parts`() {
            val message = LLMMessage(
                role = "user",
                content = "Base",
                contentParts = listOf(
                    AgentContentPart.Text(" part1"),
                    AgentContentPart.Text(" part2")
                )
            )
            val expected = ContextCompactor.estimateTokens("Base") +
                    ContextCompactor.estimateTokens(" part1") +
                    ContextCompactor.estimateTokens(" part2")
            assertEquals(expected, ContextCompactor.estimateMessageTokens(message))
        }

        @Test
        fun `should include tokens from ToolResult content`() {
            val message = LLMMessage(
                role = "tool",
                content = "Tool result text",
                contentParts = listOf(
                    AgentContentPart.ToolResult("tool_output")
                )
            )
            val expected = ContextCompactor.estimateTokens("Tool result text") +
                    ContextCompactor.estimateTokens("tool_output")
            assertEquals(expected, ContextCompactor.estimateMessageTokens(message))
        }

        @Test
        fun `should include tokens from ToolUse input`() {
            val message = LLMMessage(
                role = "assistant",
                content = "Using tool",
                contentParts = listOf(
                    AgentContentPart.ToolUse(mapOf("param" to "value"))
                )
            )
            val expected = ContextCompactor.estimateTokens("Using tool") +
                    ContextCompactor.estimateTokens(mapOf("param" to "value").toString())
            assertEquals(expected, ContextCompactor.estimateMessageTokens(message))
        }

        @Test
        fun `should ignore ImageData parts`() {
            val message = LLMMessage(
                role = "user",
                content = "Image:",
                contentParts = listOf(
                    AgentContentPart.ImageData
                )
            )
            val expected = ContextCompactor.estimateTokens("Image:")
            assertEquals(expected, ContextCompactor.estimateMessageTokens(message))
        }
    }

    @Nested
    @DisplayName("estimateTailTokens()")
    inner class EstimateTailTokensTests {

        @Test
        fun `should return sum of all tokens when anchorDbId is null`() {
            val messages = listOf(
                LLMMessage(role = "user", content = "Hello"),
                LLMMessage(role = "assistant", content = "Hi")
            )
            val expected = messages.sumOf { ContextCompactor.estimateMessageTokens(it) }
            assertEquals(expected, ContextCompactor.estimateTailTokens(messages, null))
        }

        @Test
        fun `should return sum of all tokens when anchorDbId is empty`() {
            val messages = listOf(
                LLMMessage(role = "user", content = "Hello"),
                LLMMessage(role = "assistant", content = "Hi")
            )
            val expected = messages.sumOf { ContextCompactor.estimateMessageTokens(it) }
            assertEquals(expected, ContextCompactor.estimateTailTokens(messages, ""))
        }

        @Test
        fun `should return sum of all tokens when anchorDbId not found`() {
            val messages = listOf(
                LLMMessage(role = "user", content = "Hello", dbMessageId = "1"),
                LLMMessage(role = "assistant", content = "Hi", dbMessageId = "2")
            )
            val expected = messages.sumOf { ContextCompactor.estimateMessageTokens(it) }
            assertEquals(expected, ContextCompactor.estimateTailTokens(messages, "nonexistent"))
        }

        @Test
        fun `should return tokens after anchor message`() {
            val messages = listOf(
                LLMMessage(role = "user", content = "Hello", dbMessageId = "1"),
                LLMMessage(role = "assistant", content = "Hi", dbMessageId = "2"),
                LLMMessage(role = "user", content = "How are you?", dbMessageId = "3")
            )
            val expected = ContextCompactor.estimateMessageTokens(messages[2])
            assertEquals(expected, ContextCompactor.estimateTailTokens(messages, "2"))
        }

        @Test
        fun `should return zero when anchor is the last message`() {
            val messages = listOf(
                LLMMessage(role = "user", content = "Hello", dbMessageId = "1"),
                LLMMessage(role = "assistant", content = "Hi", dbMessageId = "2")
            )
            assertEquals(0L, ContextCompactor.estimateTailTokens(messages, "2"))
        }
    }

    @Nested
    @DisplayName("Constants")
    inner class ConstantsTests {

        @Test
        fun `DEFAULT_AUTO_COMPACT_MIN_INTERVAL_MS should be 5 minutes`() {
            assertEquals(5 * 60 * 1000L, ContextCompactor.DEFAULT_AUTO_COMPACT_MIN_INTERVAL_MS)
        }

        @Test
        fun `DEFAULT_AUTO_COMPACT_MIN_TAIL_TOKENS should be 8000`() {
            assertEquals(8000L, ContextCompactor.DEFAULT_AUTO_COMPACT_MIN_TAIL_TOKENS)
        }

        @Test
        fun `CHARS_PER_TOKEN should be 4`() {
            assertEquals(4, ContextCompactor.CHARS_PER_TOKEN)
        }

        @Test
        fun `AUTO_COMPACT_POLL_MS should be 200`() {
            assertEquals(200L, ContextCompactor.AUTO_COMPACT_POLL_MS)
        }

        @Test
        fun `AUTO_COMPACT_MAX_WAIT_MS should be 120000`() {
            assertEquals(120000L, ContextCompactor.AUTO_COMPACT_MAX_WAIT_MS)
        }

        @Test
        fun `COMPACT_SUMMARY_SYSTEM_PROMPT should not be empty`() {
            assertFalse(ContextCompactor.COMPACT_SUMMARY_SYSTEM_PROMPT.isBlank())
        }
    }
}