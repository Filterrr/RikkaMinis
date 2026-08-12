package com.openminis.app.data.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class VoiceProviderTemplateTest {

    @Test
    fun `template returns null when forBaseURL is null`() {
        assertNull(VoiceProviderTemplate.template(null))
    }

    @Test
    fun `template returns null when forBaseURL is empty`() {
        assertNull(VoiceProviderTemplate.template(""))
    }

    @Test
    fun `template returns null when forBaseURL is blank`() {
        assertNull(VoiceProviderTemplate.template("   "))
    }

    @Test
    fun `template returns matching template for elevenlabs`() {
        val result = VoiceProviderTemplate.template("https://api.elevenlabs.io")
        assertNotNull(result)
        assertEquals("elevenlabs", result?.id)
    }

    @Test
    fun `template returns matching template for deepgram`() {
        val result = VoiceProviderTemplate.template("https://api.deepgram.com")
        assertNotNull(result)
        assertEquals("deepgram", result?.id)
    }

    @Test
    fun `template returns matching template for azure`() {
        val result = VoiceProviderTemplate.template("https://eastus.tts.speech.microsoft.com")
        assertNotNull(result)
        assertEquals("azure-tts", result?.id)
    }

    @Test
    fun `template returns matching template for minimax`() {
        val result = VoiceProviderTemplate.template("https://api.minimax.chat")
        assertNotNull(result)
        assertEquals("minimax", result?.id)
    }

    @Test
    fun `template returns matching template for alibaba`() {
        val result = VoiceProviderTemplate.template("https://dashscope.aliyuncs.com")
        assertNotNull(result)
        assertEquals("alibaba", result?.id)
    }

    @Test
    fun `template returns matching template for doubao`() {
        val result = VoiceProviderTemplate.template("https://openspeech.bytedance.com")
        assertNotNull(result)
        assertEquals("doubao", result?.id)
    }

    @Test
    fun `template returns matching template for doubao with volcano`() {
        val result = VoiceProviderTemplate.template("https://api.volcano.com")
        assertNotNull(result)
        assertEquals("doubao", result?.id)
    }

    @Test
    fun `template returns matching template for xunfei`() {
        val result = VoiceProviderTemplate.template("https://api.xfyun.cn")
        assertNotNull(result)
        assertEquals("xunfei", result?.id)
    }

    @Test
    fun `template returns matching template for mimo`() {
        val result = VoiceProviderTemplate.template("https://api.xiaomimimo.com")
        assertNotNull(result)
        assertEquals("mimo", result?.id)
    }

    @Test
    fun `template is case insensitive`() {
        val result = VoiceProviderTemplate.template("HTTPS://API.ELEVENLABS.IO")
        assertNotNull(result)
        assertEquals("elevenlabs", result?.id)
    }

    @Test
    fun `template returns null when no marker matches`() {
        val result = VoiceProviderTemplate.template("https://unknown.api.com")
        assertNull(result)
    }

    @Test
    fun `mockEntries returns empty list when template not found`() {
        val instance = ProviderInstance(id = "test", customBaseURL = "https://unknown.com")
        val result = VoiceProviderTemplate.mockEntries(instance)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mockEntries returns empty list when customBaseURL is null`() {
        val instance = ProviderInstance(id = "test", customBaseURL = null)
        val result = VoiceProviderTemplate.mockEntries(instance)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mockEntries returns models for elevenlabs`() {
        val instance = ProviderInstance(id = "test", customBaseURL = "https://api.elevenlabs.io")
        val result = VoiceProviderTemplate.mockEntries(instance)
        assertEquals(4, result.size)
        result.forEach { entry ->
            assertEquals("test", entry.providerInstanceId)
            assertEquals("elevenlabs", entry.baseModel.provider)
        }
    }

    @Test
    fun `mockEntries returns models for deepgram`() {
        val instance = ProviderInstance(id = "test", customBaseURL = "https://api.deepgram.com")
        val result = VoiceProviderTemplate.mockEntries(instance)
        assertEquals(5, result.size)
        result.forEach { entry ->
            assertEquals("test", entry.providerInstanceId)
            assertEquals("deepgram", entry.baseModel.provider)
        }
    }

    @Test
    fun `mockEntries returns models for azure-tts`() {
        val instance = ProviderInstance(id = "test", customBaseURL = "https://eastus.tts.speech.microsoft.com")
        val result = VoiceProviderTemplate.mockEntries(instance)
        assertEquals(37, result.size)
        result.forEach { entry ->
            assertEquals("test", entry.providerInstanceId)
            assertEquals("azure-tts", entry.baseModel.provider)
        }
    }

    @Test
    fun `mockEntries returns models for minimax`() {
        val instance = ProviderInstance(id = "test", customBaseURL = "https://api.minimax.chat")
        val result = VoiceProviderTemplate.mockEntries(instance)
        assertEquals(2, result.size)
        result.forEach { entry ->
            assertEquals("test", entry.providerInstanceId)
            assertEquals("minimax", entry.baseModel.provider)
        }
    }

    @Test
    fun `mockEntries returns models for alibaba`() {
        val instance = ProviderInstance(id = "test", customBaseURL = "https://dashscope.aliyuncs.com")
        val result = VoiceProviderTemplate.mockEntries(instance)
        assertEquals(2, result.size)
        result.forEach { entry ->
            assertEquals("test", entry.providerInstanceId)
            assertEquals("alibaba", entry.baseModel.provider)
        }
    }

    @Test
    fun `mockEntries returns models for doubao`() {
        val instance = ProviderInstance(id = "test", customBaseURL = "https://openspeech.bytedance.com")
        val result = VoiceProviderTemplate.mockEntries(instance)
        assertEquals(9, result.size)
        result.forEach { entry ->
            assertEquals("test", entry.providerInstanceId)
            assertEquals("doubao", entry.baseModel.provider)
        }
    }

    @Test
    fun `mockEntries returns models for xunfei`() {
        val instance = ProviderInstance(id = "test", customBaseURL = "https://api.xfyun.cn")
        val result = VoiceProviderTemplate.mockEntries(instance)
        assertEquals(3, result.size)
        result.forEach { entry ->
            assertEquals("test", entry.providerInstanceId)
            assertEquals("xunfei", entry.baseModel.provider)
        }
    }

    @Test
    fun `mockEntries returns models for mimo`() {
        val instance = ProviderInstance(id = "test", customBaseURL = "https://api.xiaomimimo.com")
        val result = VoiceProviderTemplate.mockEntries(instance)
        assertEquals(10, result.size)
        result.forEach { entry ->
            assertEquals("test", entry.providerInstanceId)
            assertEquals("mimo", entry.baseModel.provider)
        }
    }

    @Test
    fun `all templates have non-empty id`() {
        VoiceProviderTemplate.all.forEach { template ->
            assertFalse(template.id.isBlank(), "Template id should not be blank")
        }
    }

    @Test
    fun `all templates have non-empty name`() {
        VoiceProviderTemplate.all.forEach { template ->
            assertFalse(template.name.isBlank(), "Template name should not be blank")
        }
    }

    @Test
    fun `all templates have non-empty baseURL`() {
        VoiceProviderTemplate.all.forEach { template ->
            assertFalse(template.baseURL.isBlank(), "Template baseURL should not be blank")
        }
    }

    @Test
    fun `all templates have non-empty baseURLMarkers`() {
        VoiceProviderTemplate.all.forEach { template ->
            assertTrue(template.baseURLMarkers.isNotEmpty(), "Template baseURLMarkers should not be empty")
        }
    }

    @Test
    fun `all templates have non-empty mockModels`() {
        VoiceProviderTemplate.all.forEach { template ->
            assertTrue(template.mockModels.isNotEmpty(), "Template mockModels should not be empty")
        }
    }

    @Test
    fun `all templates have valid capability`() {
        VoiceProviderTemplate.all.forEach { template ->
            assertNotNull(template.capability, "Template capability should not be null")
        }
    }

    @Test
    fun `all templates have valid providerType`() {
        VoiceProviderTemplate.all.forEach { template ->
            assertNotNull(template.providerType, "Template providerType should not be null")
        }
    }

    @Test
    fun `all mockModels have audio in outputModalities for TTS capability`() {
        VoiceProviderTemplate.all.filter { it.capability == VoiceProviderTemplate.Capability.TTS }.forEach { template ->
            template.mockModels.forEach { model ->
                assertTrue(model.outputModalities.contains("audio"), "TTS model ${model.id} should have audio output")
            }
        }
    }

    @Test
    fun `all mockModels have audio in inputModalities for ASR capability`() {
        VoiceProviderTemplate.all.filter { it.capability == VoiceProviderTemplate.Capability.ASR }.forEach { template ->
            template.mockModels.forEach { model ->
                assertTrue(model.inputModalities.contains("audio"), "ASR model ${model.id} should have audio input")
            }
        }
    }

    @Test
    fun `all templates have unique ids`() {
        val ids = VoiceProviderTemplate.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Template ids should be unique")
    }
}