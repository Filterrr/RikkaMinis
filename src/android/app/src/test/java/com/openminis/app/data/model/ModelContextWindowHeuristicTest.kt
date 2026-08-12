package com.openminis.app.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private data class FakeModel(
    override val id: String,
    override val contextWindow: Int? = null
) : LLMModel

class ModelContextWindowHeuristicTest {

    @Test
    fun `given contextWindow greater than zero should return that value`() {
        val model = FakeModel(id = "any", contextWindow = 200_000)
        assertEquals(200_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow equals zero should ignore and match pattern`() {
        val model = FakeModel(id = "claude-sonnet-4-7", contextWindow = 0)
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow negative should ignore and match pattern`() {
        val model = FakeModel(id = "gpt-4o", contextWindow = -1)
        assertEquals(128_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id matching claude-*-1m should return 1m`() {
        val model = FakeModel(id = "claude-3-1m")
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id matching claude-opus-4-5 should return 1m`() {
        val model = FakeModel(id = "claude-opus-4-5")
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id matching claude-sonnet-4-6 should return 1m`() {
        val model = FakeModel(id = "claude-sonnet-4-6")
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing gemini-2-5-pro should return 1m`() {
        val model = FakeModel(id = "my-gemini-2.5-pro-v1")
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing gemini-2-0-pro should return 1m`() {
        val model = FakeModel(id = "gemini-2.0-pro")
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing gemini-1-5-pro should return 1m`() {
        val model = FakeModel(id = "gemini-1.5-pro")
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing gemini-2-5-flash should return 1m`() {
        val model = FakeModel(id = "gemini-2.5-flash")
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing gemini-3-pro should return 1m`() {
        val model = FakeModel(id = "gemini-3-pro")
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing gemini-3-flash should return 1m`() {
        val model = FakeModel(id = "gemini-3-flash")
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id starting with claude- should return 200k`() {
        val model = FakeModel(id = "claude-3-opus")
        assertEquals(200_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing slash claude- should return 200k`() {
        val model = FakeModel(id = "provider/claude-3")
        assertEquals(200_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id starting with gpt-4o should return 128k`() {
        val model = FakeModel(id = "gpt-4o-mini")
        assertEquals(128_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing slash gpt-4o should return 128k`() {
        val model = FakeModel(id = "openai/gpt-4o")
        assertEquals(128_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id starting with gpt-4-turbo should return 128k`() {
        val model = FakeModel(id = "gpt-4-turbo-2024")
        assertEquals(128_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing slash gpt-4-turbo should return 128k`() {
        val model = FakeModel(id = "openai/gpt-4-turbo")
        assertEquals(128_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id starting with gpt-5 should return 128k`() {
        val model = FakeModel(id = "gpt-5-turbo")
        assertEquals(128_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing slash gpt-5 should return 128k`() {
        val model = FakeModel(id = "openai/gpt-5")
        assertEquals(128_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id starting with gpt-4 should return 16k`() {
        val model = FakeModel(id = "gpt-4-32k")
        assertEquals(16_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing slash gpt-4 should return 16k`() {
        val model = FakeModel(id = "openai/gpt-4")
        assertEquals(16_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id starting with gpt-3-5 should return 16k`() {
        val model = FakeModel(id = "gpt-3.5-turbo")
        assertEquals(16_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing slash gpt-3-5 should return 16k`() {
        val model = FakeModel(id = "openai/gpt-3.5")
        assertEquals(16_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id starting with deepseek- should return 128k`() {
        val model = FakeModel(id = "deepseek-chat")
        assertEquals(128_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id containing slash deepseek- should return 128k`() {
        val model = FakeModel(id = "company/deepseek-v2")
        assertEquals(128_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id not matching any pattern should return 128k`() {
        val model = FakeModel(id = "llama-3-70b")
        assertEquals(128_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id with uppercase should be case insensitive`() {
        val model = FakeModel(id = "GPT-4O-MINI")
        assertEquals(128_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id with mixed case should match million pattern`() {
        val model = FakeModel(id = "Claude-Opus-4-8")
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id with slash before claude- should return 200k`() {
        val model = FakeModel(id = "prefix/claude-3")
        assertEquals(200_000, inferContextWindowTokens(model))
    }

    @Test
    fun `given contextWindow null and id with slash before gpt-4o should return 128k`() {
        val model = FakeModel(id = "prefix/gpt-4o")
        assertEquals(128_000, inferContextWindowTokens(model))
    }
}