package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ThinkingLevelCatalogTest {

    @Test
    fun `declaredMaxLevel returns MAX for gpt-5_6-sol`() {
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("gpt-5.6-sol-1"))
    }

    @Test
    fun `declaredMaxLevel returns MAX for gpt-5_6-terra`() {
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("gpt-5.6-terra-1"))
    }

    @Test
    fun `declaredMaxLevel returns MAX for gpt-5_6-luna`() {
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("gpt-5.6-luna-1"))
    }

    @Test
    fun `declaredMaxLevel returns XHIGH for gpt-5_5`() {
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevelCatalog.declaredMaxLevel("gpt-5.5-1"))
    }

    @Test
    fun `declaredMaxLevel returns HIGH for mimo`() {
        assertEquals(ThinkingLevel.HIGH, ThinkingLevelCatalog.declaredMaxLevel("model-mimo-1"))
    }

    @Test
    fun `declaredMaxLevel returns HIGH for agnes`() {
        assertEquals(ThinkingLevel.HIGH, ThinkingLevelCatalog.declaredMaxLevel("model-agnes-1"))
    }

    @Test
    fun `declaredMaxLevel returns HIGH for seed`() {
        assertEquals(ThinkingLevel.HIGH, ThinkingLevelCatalog.declaredMaxLevel("model-seed-1"))
    }

    @Test
    fun `declaredMaxLevel returns HIGH for bytedance-seed`() {
        assertEquals(ThinkingLevel.HIGH, ThinkingLevelCatalog.declaredMaxLevel("bytedance-seed-1"))
    }

    @Test
    fun `declaredMaxLevel returns MAX for claude-opus-4`() {
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("claude-opus-4-1"))
    }

    @Test
    fun `declaredMaxLevel returns MAX for claude opus 4 with dots`() {
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("claude.opus.4.1"))
    }

    @Test
    fun `declaredMaxLevel is case insensitive`() {
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("GPT-5.6-SOL-1"))
    }

    @Test
    fun `declaredMaxLevel returns null for unknown model`() {
        assertNull(ThinkingLevelCatalog.declaredMaxLevel("unknown-model"))
    }

    @Test
    fun `catalogMaxThinkingLevel returns OFF when supportsReasoning is false`() {
        val model = LLMModel(id = "gpt-5.6-sol-1", supportsReasoning = false)
        assertEquals(ThinkingLevel.OFF, model.catalogMaxThinkingLevel)
    }

    @Test
    fun `catalogMaxThinkingLevel returns declared level when supportsReasoning is true`() {
        val model = LLMModel(id = "gpt-5.6-sol-1", supportsReasoning = true)
        assertEquals(ThinkingLevel.MAX, model.catalogMaxThinkingLevel)
    }

    @Test
    fun `catalogMaxThinkingLevel returns XHIGH when no rule matches and supportsReasoning is true`() {
        val model = LLMModel(id = "unknown-model", supportsReasoning = true)
        assertEquals(ThinkingLevel.XHIGH, model.catalogMaxThinkingLevel)
    }

    @Test
    fun `catalogMaxThinkingLevel returns XHIGH when no rule matches and supportsReasoning is null`() {
        val model = LLMModel(id = "unknown-model", supportsReasoning = null)
        assertEquals(ThinkingLevel.XHIGH, model.catalogMaxThinkingLevel)
    }

    @Test
    fun `effectiveMaxThinkingLevel returns override when present`() {
        val model = LLMModel(id = "unknown-model", supportsReasoning = true)
        val entry = ModelEntry(model = model, overrides = ModelOverrides(maxThinkingLevel = ThinkingLevel.HIGH))
        assertEquals(ThinkingLevel.HIGH, entry.effectiveMaxThinkingLevel)
    }

    @Test
    fun `effectiveMaxThinkingLevel returns catalog level when override is null`() {
        val model = LLMModel(id = "gpt-5.6-sol-1", supportsReasoning = true)
        val entry = ModelEntry(model = model, overrides = ModelOverrides(maxThinkingLevel = null))
        assertEquals(ThinkingLevel.MAX, entry.effectiveMaxThinkingLevel)
    }
}