package com.openminis.app.provider.voice

import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceProviderFactoryTest {

    @Test
    fun `make returns VoiceProvider for openAI with default base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = null)
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is VoiceProvider)
    }

    @Test
    fun `make returns VoiceProvider for openRouter`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openRouter, customBaseURL = null)
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is VoiceProvider)
    }

    @Test
    fun `make returns GroqVoiceProvider for groq com base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://api.groq.com")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is GroqVoiceProvider)
    }

    @Test
    fun `make returns AlibabaVoiceProvider for dashscope base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://dashscope.aliyuncs.com")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is AlibabaVoiceProvider)
    }

    @Test
    fun `make returns MiniMaxVoiceProvider for minimax base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://api.minimax.chat")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is MiniMaxVoiceProvider)
    }

    @Test
    fun `make returns DoubaoVoiceProvider for openspeech bytedance base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://openspeech.bytedance.com")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is DoubaoVoiceProvider)
    }

    @Test
    fun `make returns DoubaoVoiceProvider for volcano base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://volcano.com")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is DoubaoVoiceProvider)
    }

    @Test
    fun `make returns XunfeiVoiceProvider for xfyun base URL with compound api key`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://xfyun.cn")
        val provider = VoiceProviderFactory.make(instance, "key1;key2;key3")
        assertNotNull(provider)
        assertTrue(provider is XunfeiVoiceProvider)
    }

    @Test
    fun `make returns null for xfyun base URL with insufficient compound api key`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://xfyun.cn")
        val provider = VoiceProviderFactory.make(instance, "key1;key2")
        assertNull(provider)
    }

    @Test
    fun `make returns MimoVoiceProvider for xiaomimimo base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://xiaomimimo.com")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is MimoVoiceProvider)
    }

    @Test
    fun `make returns ElevenLabsVoiceProvider for elevenlabs base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://api.elevenlabs.io")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is ElevenLabsVoiceProvider)
    }

    @Test
    fun `make returns AzureTTSVoiceProvider for tts speech microsoft com base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://tts.speech.microsoft.com")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is AzureTTSVoiceProvider)
    }

    @Test
    fun `make removes cognitiveservices suffix for AzureTTSVoiceProvider`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://tts.speech.microsoft.com/cognitiveservices")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is AzureTTSVoiceProvider)
    }

    @Test
    fun `make returns DeepgramVoiceProvider for deepgram base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://api.deepgram.com")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is DeepgramVoiceProvider)
    }

    @Test
    fun `make returns VoiceProvider for openAI with unknown base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = "https://unknown.com")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is VoiceProvider)
    }

    @Test
    fun `make returns XAIVoiceProvider for xAI type`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.xAI, customBaseURL = null)
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is XAIVoiceProvider)
    }

    @Test
    fun `make returns MiniMaxVoiceProvider for anthropic type with minimax base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.anthropic, customBaseURL = "https://api.minimax.chat")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is MiniMaxVoiceProvider)
    }

    @Test
    fun `make returns null for anthropic type without minimax base URL`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.anthropic, customBaseURL = "https://api.anthropic.com")
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNull(provider)
    }

    @Test
    fun `make returns GeminiVoiceProvider for gemini type`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.gemini, customBaseURL = null)
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNotNull(provider)
        assertTrue(provider is GeminiVoiceProvider)
    }

    @Test
    fun `make returns null for kimiCode type`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.kimiCode, customBaseURL = null)
        val provider = VoiceProviderFactory.make(instance, "api-key")
        assertNull(provider)
    }

    @Test
    fun `supports returns true when make returns non-null`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.openAI, customBaseURL = null)
        assertTrue(VoiceProviderFactory.supports(instance, "api-key"))
    }

    @Test
    fun `supports returns false when make returns null`() {
        val instance = ProviderInstance(id = "test", providerType = ProviderType.kimiCode, customBaseURL = null)
        assertTrue(!VoiceProviderFactory.supports(instance, "api-key"))
    }

    @Test
    fun `splitCompound returns empty list for null input`() {
        val result = VoiceProviderFactory.splitCompound(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `splitCompound returns empty list for empty string`() {
        val result = VoiceProviderFactory.splitCompound("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `splitCompound returns list of parts for semicolon separated string`() {
        val result = VoiceProviderFactory.splitCompound("a;b;c")
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `splitCompound trims whitespace and filters empty parts`() {
        val result = VoiceProviderFactory.splitCompound(" a ; b ; ; c ")
        assertEquals(listOf("a", "b", "c"), result)
    }
}