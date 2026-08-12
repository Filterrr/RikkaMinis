package com.openminis.app.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LLMModelTest {

    @Test
    fun testClaudeFable5() {
        assertEquals("claude-fable-5", LLMModel.claudeFable5.id)
        assertEquals("Claude Fable 5", LLMModel.claudeFable5.displayName)
        assertEquals("Anthropic", LLMModel.claudeFable5.provider)
        assertEquals(1_000_000, LLMModel.claudeFable5.contextWindow)
        assertEquals(128_000, LLMModel.claudeFable5.maxOutputTokens)
        assertEquals(true, LLMModel.claudeFable5.supportsReasoning)
    }

    @Test
    fun testClaudeOpus48() {
        assertEquals("claude-opus-4-8", LLMModel.claudeOpus48.id)
        assertEquals("Claude Opus 4.8", LLMModel.claudeOpus48.displayName)
        assertEquals("Anthropic", LLMModel.claudeOpus48.provider)
        assertEquals(1_000_000, LLMModel.claudeOpus48.contextWindow)
        assertEquals(128_000, LLMModel.claudeOpus48.maxOutputTokens)
        assertEquals(true, LLMModel.claudeOpus48.supportsReasoning)
    }

    @Test
    fun testClaudeOpus46() {
        assertEquals("claude-opus-4-6", LLMModel.claudeOpus46.id)
        assertEquals("Claude Opus 4.6", LLMModel.claudeOpus46.displayName)
        assertEquals("Anthropic", LLMModel.claudeOpus46.provider)
        assertEquals(1_000_000, LLMModel.claudeOpus46.contextWindow)
        assertEquals(128_000, LLMModel.claudeOpus46.maxOutputTokens)
        assertEquals(true, LLMModel.claudeOpus46.supportsReasoning)
    }

    @Test
    fun testClaudeSonnet5() {
        assertEquals("claude-sonnet-5", LLMModel.claudeSonnet5.id)
        assertEquals("Claude Sonnet 5", LLMModel.claudeSonnet5.displayName)
        assertEquals("Anthropic", LLMModel.claudeSonnet5.provider)
        assertEquals(1_000_000, LLMModel.claudeSonnet5.contextWindow)
        assertEquals(64_000, LLMModel.claudeSonnet5.maxOutputTokens)
        assertEquals(true, LLMModel.claudeSonnet5.supportsReasoning)
    }

    @Test
    fun testClaudeSonnet46() {
        assertEquals("claude-sonnet-4-6", LLMModel.claudeSonnet46.id)
        assertEquals("Claude Sonnet 4.6", LLMModel.claudeSonnet46.displayName)
        assertEquals("Anthropic", LLMModel.claudeSonnet46.provider)
        assertEquals(1_000_000, LLMModel.claudeSonnet46.contextWindow)
        assertEquals(64_000, LLMModel.claudeSonnet46.maxOutputTokens)
        assertEquals(true, LLMModel.claudeSonnet46.supportsReasoning)
    }

    @Test
    fun testClaudeHaiku45() {
        assertEquals("claude-haiku-4-5", LLMModel.claudeHaiku45.id)
        assertEquals("Claude Haiku 4.5", LLMModel.claudeHaiku45.displayName)
        assertEquals("Anthropic", LLMModel.claudeHaiku45.provider)
        assertEquals(200_000, LLMModel.claudeHaiku45.contextWindow)
        assertEquals(64_000, LLMModel.claudeHaiku45.maxOutputTokens)
        assertEquals(true, LLMModel.claudeHaiku45.supportsReasoning)
    }

    @Test
    fun testAllAnthropic() {
        assertEquals(6, LLMModel.allAnthropic.size)
        assertTrue(LLMModel.allAnthropic.all { it.provider == "Anthropic" })
    }

    @Test
    fun testGemini3Pro() {
        assertEquals("gemini-3-pro-preview", LLMModel.gemini3Pro.id)
        assertEquals("Gemini 3 Pro (Preview)", LLMModel.gemini3Pro.displayName)
        assertEquals("Google", LLMModel.gemini3Pro.provider)
        assertNull(LLMModel.gemini3Pro.contextWindow)
        assertNull(LLMModel.gemini3Pro.maxOutputTokens)
        assertNull(LLMModel.gemini3Pro.supportsReasoning)
    }

    @Test
    fun testGemini3Flash() {
        assertEquals("gemini-3-flash-preview", LLMModel.gemini3Flash.id)
        assertEquals("Gemini 3 Flash (Preview)", LLMModel.gemini3Flash.displayName)
        assertEquals("Google", LLMModel.gemini3Flash.provider)
    }

    @Test
    fun testGemini25Pro() {
        assertEquals("gemini-2.5-pro", LLMModel.gemini25Pro.id)
        assertEquals("Gemini 2.5 Pro", LLMModel.gemini25Pro.displayName)
        assertEquals("Google", LLMModel.gemini25Pro.provider)
    }

    @Test
    fun testGemini25Flash() {
        assertEquals("gemini-2.5-flash", LLMModel.gemini25Flash.id)
        assertEquals("Gemini 2.5 Flash", LLMModel.gemini25Flash.displayName)
        assertEquals("Google", LLMModel.gemini25Flash.provider)
    }

    @Test
    fun testGemini25FlashLite() {
        assertEquals("gemini-2.5-flash-lite", LLMModel.gemini25FlashLite.id)
        assertEquals("Gemini 2.5 Flash Lite", LLMModel.gemini25FlashLite.displayName)
        assertEquals("Google", LLMModel.gemini25FlashLite.provider)
    }

    @Test
    fun testAllGemini() {
        assertEquals(5, LLMModel.allGemini.size)
        assertTrue(LLMModel.allGemini.all { it.provider == "Google" })
    }

    @Test
    fun testGpt55() {
        assertEquals("gpt-5.5", LLMModel.gpt55.id)
        assertEquals("GPT-5.5", LLMModel.gpt55.displayName)
        assertEquals("OpenAI", LLMModel.gpt55.provider)
        assertEquals(true, LLMModel.gpt55.supportsReasoning)
    }

    @Test
    fun testGpt53Codex() {
        assertEquals("gpt-5.3-codex", LLMModel.gpt53Codex.id)
        assertEquals("GPT-5.3 Codex", LLMModel.gpt53Codex.displayName)
        assertEquals("OpenAI", LLMModel.gpt53Codex.provider)
        assertEquals(true, LLMModel.gpt53Codex.supportsReasoning)
    }

    @Test
    fun testGpt52Codex() {
        assertEquals("gpt-5.2-codex", LLMModel.gpt52Codex.id)
        assertEquals("GPT-5.2 Codex", LLMModel.gpt52Codex.displayName)
        assertEquals("OpenAI", LLMModel.gpt52Codex.provider)
        assertEquals(true, LLMModel.gpt52Codex.supportsReasoning)
    }

    @Test
    fun testGpt51CodexMax() {
        assertEquals("gpt-5.1-codex-max", LLMModel.gpt51CodexMax.id)
        assertEquals("GPT-5.1 Codex Max", LLMModel.gpt51CodexMax.displayName)
        assertEquals("OpenAI", LLMModel.gpt51CodexMax.provider)
        assertEquals(true, LLMModel.gpt51CodexMax.supportsReasoning)
    }

    @Test
    fun testGpt52() {
        assertEquals("gpt-5.2", LLMModel.gpt52.id)
        assertEquals("GPT-5.2", LLMModel.gpt52.displayName)
        assertEquals("OpenAI", LLMModel.gpt52.provider)
        assertEquals(true, LLMModel.gpt52.supportsReasoning)
    }

    @Test
    fun testGpt4o() {
        assertEquals("gpt-4o", LLMModel.gpt4o.id)
        assertEquals("GPT-4o", LLMModel.gpt4o.displayName)
        assertEquals("OpenAI", LLMModel.gpt4o.provider)
        assertNull(LLMModel.gpt4o.supportsReasoning)
    }

    @Test
    fun testGpt4oMini() {
        assertEquals("gpt-4o-mini", LLMModel.gpt4oMini.id)
        assertEquals("GPT-4o Mini", LLMModel.gpt4oMini.displayName)
        assertEquals("OpenAI", LLMModel.gpt4oMini.provider)
    }

    @Test
    fun testO3() {
        assertEquals("o3", LLMModel.o3.id)
        assertEquals("o3", LLMModel.o3.displayName)
        assertEquals("OpenAI", LLMModel.o3.provider)
        assertEquals(true, LLMModel.o3.supportsReasoning)
    }

    @Test
    fun testO4Mini() {
        assertEquals("o4-mini", LLMModel.o4Mini.id)
        assertEquals("o4 Mini", LLMModel.o4Mini.displayName)
        assertEquals("OpenAI", LLMModel.o4Mini.provider)
        assertEquals(true, LLMModel.o4Mini.supportsReasoning)
    }

    @Test
    fun testCodexMini() {
        assertEquals("codex-mini-latest", LLMModel.codexMini.id)
        assertEquals("Codex Mini", LLMModel.codexMini.displayName)
        assertEquals("OpenAI", LLMModel.codexMini.provider)
        assertEquals(true, LLMModel.codexMini.supportsReasoning)
    }

    @Test
    fun testAllOpenAI() {
        assertEquals(10, LLMModel.allOpenAI.size)
        assertTrue(LLMModel.allOpenAI.all { it.provider == "OpenAI" })
    }

    @Test
    fun testOrClaudeSonnet4() {
        assertEquals("anthropic/claude-sonnet-4", LLMModel.orClaudeSonnet4.id)
        assertEquals("Claude Sonnet 4", LLMModel.orClaudeSonnet4.displayName)
        assertEquals("OpenRouter", LLMModel.orClaudeSonnet4.provider)
    }

    @Test
    fun testOrGemini25Flash() {
        assertEquals("google/gemini-2.5-flash", LLMModel.orGemini25Flash.id)
        assertEquals("Gemini 2.5 Flash", LLMModel.orGemini25Flash.displayName)
        assertEquals("OpenRouter", LLMModel.orGemini25Flash.provider)
    }

    @Test
    fun testOrGpt4o() {
        assertEquals("openai/gpt-4o", LLMModel.orGpt4o.id)
        assertEquals("GPT-4o", LLMModel.orGpt4o.displayName)
        assertEquals("OpenRouter", LLMModel.orGpt4o.provider)
    }

    @Test
    fun testOrLlamaMaverick() {
        assertEquals("meta-llama/llama-4-maverick", LLMModel.orLlamaMaverick.id)
        assertEquals("Llama 4 Maverick", LLMModel.orLlamaMaverick.displayName)
        assertEquals("OpenRouter", LLMModel.orLlamaMaverick.provider)
    }

    @Test
    fun testAllOpenRouter() {
        assertEquals(4, LLMModel.allOpenRouter.size)
        assertTrue(LLMModel.allOpenRouter.all { it.provider == "OpenRouter" })
    }

    @Test
    fun testGrok45() {
        assertEquals("grok-4.5", LLMModel.grok45.id)
        assertEquals("Grok 4.5", LLMModel.grok45.displayName)
        assertEquals("xAI", LLMModel.grok45.provider)
        assertEquals(true, LLMModel.grok45.supportsReasoning)
    }

    @Test
    fun testGrok43() {
        assertEquals("grok-4.3", LLMModel.grok43.id)
        assertEquals("Grok 4.3", LLMModel.grok43.displayName)
        assertEquals("xAI", LLMModel.grok43.provider)
        assertEquals(true, LLMModel.grok43.supportsReasoning)
    }

    @Test
    fun testGrok420Reasoning() {
        assertEquals("grok-4.20-0309-reasoning", LLMModel.grok420Reasoning.id)
        assertEquals("Grok 4.20 Reasoning", LLMModel.grok420Reasoning.displayName)
        assertEquals("xAI", LLMModel.grok420Reasoning.provider)
        assertEquals(true, LLMModel.grok420Reasoning.supportsReasoning)
    }

    @Test
    fun testGrok420NonReasoning() {
        assertEquals("grok-4.20-0309-non-reasoning", LLMModel.grok420NonReasoning.id)
        assertEquals("Grok 4.20", LLMModel.grok420NonReasoning.displayName)
        assertEquals("xAI", LLMModel.grok420NonReasoning.provider)
        assertNull(LLMModel.grok420NonReasoning.supportsReasoning)
    }

    @Test
    fun testGrok420MultiAgent() {
        assertEquals("grok-4.20-multi-agent-0309", LLMModel.grok420MultiAgent.id)
        assertEquals("Grok 4.20 Multi-Agent", LLMModel.grok420MultiAgent.displayName)
        assertEquals("xAI", LLMModel.grok420MultiAgent.provider)
        assertEquals(true, LLMModel.grok420MultiAgent.supportsReasoning)
    }

    @Test
    fun testGrokBuild01() {
        assertEquals("grok-build-0.1", LLMModel.grokBuild01.id)
        assertEquals("Grok Build 0.1", LLMModel.grokBuild01.displayName)
        assertEquals("xAI", LLMModel.grokBuild01.provider)
        assertNull(LLMModel.grokBuild01.supportsReasoning)
    }

    @Test
    fun testGrok3Mini() {
        assertEquals("grok-3-mini", LLMModel.grok3Mini.id)
        assertEquals("Grok 3 Mini", LLMModel.grok3Mini.displayName)
        assertEquals("xAI", LLMModel.grok3Mini.provider)
        assertEquals(true, LLMModel.grok3Mini.supportsReasoning)
    }

    @Test
    fun testGrok3MiniFast() {
        assertEquals("grok-3-mini-fast", LLMModel.grok3MiniFast.id)
        assertEquals("Grok 3 Mini Fast", LLMModel.grok3MiniFast.displayName)
        assertEquals("xAI", LLMModel.grok3MiniFast.provider)
        assertEquals(true, LLMModel.grok3MiniFast.supportsReasoning)
    }

    @Test
    fun testGrokComposer25Fast() {
        assertEquals("grok-composer-2.5-fast", LLMModel.grokComposer25Fast.id)
        assertEquals("Grok Composer 2.5 Fast", LLMModel.grokComposer25Fast.displayName)
        assertEquals("xAI", LLMModel.grokComposer25Fast.provider)
        assertNull(LLMModel.grokComposer25Fast.supportsReasoning)
    }

    @Test
    fun testGrok4Fast() {
        assertEquals("grok-4-fast", LLMModel.grok4Fast.id)
        assertEquals("Grok 4 Fast", LLMModel.grok4Fast.displayName)
        assertEquals("xAI", LLMModel.grok4Fast.provider)
        assertEquals(true, LLMModel.grok4Fast.supportsReasoning)
    }

    @Test
    fun testGrok4FastNonReasoning() {
        assertEquals("grok-4-fast-non-reasoning", LLMModel.grok4FastNonReasoning.id)
        assertEquals("Grok 4 Fast (Non-Reasoning)", LLMModel.grok4FastNonReasoning.displayName)
        assertEquals("xAI", LLMModel.grok4FastNonReasoning.provider)
        assertNull(LLMModel.grok4FastNonReasoning.supportsReasoning)
    }

    @Test
    fun testGrokCodeFast1() {
        assertEquals("grok-code-fast-1", LLMModel.grokCodeFast1.id)
        assertEquals("Grok Code Fast 1", LLMModel.grokCodeFast1.displayName)
        assertEquals("xAI", LLMModel.grokCodeFast1.provider)
        assertEquals(true, LLMModel.grokCodeFast1.supportsReasoning)
    }

    @Test
    fun testAllXAI() {
        assertEquals(12, LLMModel.allXAI.size)
        assertTrue(LLMModel.allXAI.all { it.provider == "xAI" })
    }

    @Test
    fun testKimiK3() {
        assertEquals("kimi-k3", LLMModel.kimiK3.id)
        assertEquals("Kimi K3", LLMModel.kimiK3.displayName)
        assertEquals("Kimi", LLMModel.kimiK3.provider)
    }

    @Test
    fun testKimiK2() {
        assertEquals("kimi-k2", LLMModel.kimiK2.id)
        assertEquals("Kimi K2", LLMModel.kimiK2.displayName)
        assertEquals("Kimi", LLMModel.kimiK2.provider)
    }

    @Test
    fun testAllKimi() {
        assertEquals(2, LLMModel.allKimi.size)
        assertTrue(LLMModel.allKimi.all { it.provider == "Kimi" })
    }

    @Test
    fun testAllModels() {
        assertEquals(39, LLMModel.allModels.size)
    }

    @Test
    fun testModelDisplayName() {
        assertEquals("", LLMModel.modelDisplayName(""))
        assertEquals("gpt-4o", LLMModel.modelDisplayName("gpt-4o"))
        assertEquals("OpenAI /