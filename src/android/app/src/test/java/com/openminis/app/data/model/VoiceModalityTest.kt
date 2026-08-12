package com.openminis.app.data.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private data class LLMModel(
    val id: String,
    val displayName: String,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null
)

private fun List<String>?.normalizeModalities(): List<String>? = this

private val LLMModel.normalizedInputs: List<String>? get() = inputModalities.normalizeModalities()
private val LLMModel.normalizedOutputs: List<String>? get() = outputModalities.normalizeModalities()

val LLMModel.hasAudioInput: Boolean
    get() = normalizedInputs?.contains("audio") == true

val LLMModel.hasAudioOutput: Boolean
    get() = normalizedOutputs?.contains("audio") == true

val LLMModel.hasVoiceModality: Boolean
    get() = hasAudioInput || hasAudioOutput

val LLMModel.isVoiceTemplateSeedShape: Boolean
    get() {
        val ins = normalizedInputs
        val outs = normalizedOutputs
        val asrSeed = ins == listOf("audio") && outs == null
        val ttsSeed = ins == null && outs == listOf("audio")
        return asrSeed || ttsSeed
    }

fun LLMModel.withInferredVoiceModality(): LLMModel {
    if (inputModalities != null || outputModalities != null) return this
    val (ins, outs) = VoiceModality.inferDedicatedVoiceModality(id, displayName) ?: return this
    return copy(inputModalities = ins, outputModalities = outs)
}

class VoiceModalityTest {

    // ── VoiceModality.inferDedicatedVoiceModality ───────────────────────────────

    @Test
    fun `inferDedicatedVoiceModality returns null when no pattern matches`() {
        val result = VoiceModality.inferDedicatedVoiceModality("some-model", "Some Model")
        assertNull(result)
    }

    @Test
    fun `inferDedicatedVoiceModality matches ASR patterns`() {
        val patterns = listOf(
            "model-asr" to "ASR Model",
            "asr-model" to "ASR Model",
            "model_asr" to "ASR Model",
            "asr_model" to "ASR Model",
            "whisper" to "Whisper",
            "transcribe" to "Transcribe",
            "speech-to-text" to "SpeechToText",
            "speech2text" to "Speech2Text",
            "stt-model" to "STT Model",
            "model-stt" to "Model STT",
            "model_stt" to "Model STT",
            "stt_model" to "STT Model",
            "voice-input" to "Voice Input",
            "voice_input" to "Voice Input"
        )
        for ((id, name) in patterns) {
            val result = VoiceModality.inferDedicatedVoiceModality(id, name)
            assertNotNull(result, "Expected match for id=$id, name=$name")
            assertEquals(listOf("audio"), result?.first)
            assertEquals(listOf("text"), result?.second)
        }
    }

    @Test
    fun `inferDedicatedVoiceModality matches TTS patterns`() {
        val patterns = listOf(
            "tts" to "TTS",
            "model-tts" to "TTS Model",
            "model_tts" to "TTS Model",
            "text-to-speech" to "TextToSpeech",
            "text2speech" to "Text2Speech",
            "audio-gen" to "AudioGen",
            "audio-generation" to "AudioGeneration",
            "seed-tts" to "SeedTTS",
            "voice-output" to "Voice Output",
            "voice_output" to "Voice Output"
        )
        for ((id, name) in patterns) {
            val result = VoiceModality.inferDedicatedVoiceModality(id, name)
            assertNotNull(result, "Expected match for id=$id, name=$name")
            assertEquals(listOf("text"), result?.first)
            assertEquals(listOf("audio"), result?.second)
        }
    }

    @Test
    fun `inferDedicatedVoiceModality prefers ASR over TTS when both patterns present`() {
        // "asr" appears first in asrInferencePatterns, so it should match ASR even if TTS also present
        val result = VoiceModality.inferDedicatedVoiceModality("asr-tts", "Both")
        assertNotNull(result)
        assertEquals(listOf("audio"), result?.first)
        assertEquals(listOf("text"), result?.second)
    }

    // ── LLMModel.hasAudioInput ─────────────────────────────────────────────────

    @Test
    fun `hasAudioInput true when inputModalities contains audio`() {
        val model = LLMModel(id = "x", displayName = "x", inputModalities = listOf("audio"))
        assertTrue(model.hasAudioInput)
    }

    @Test
    fun `hasAudioInput false when inputModalities does not contain audio`() {
        val model = LLMModel(id = "x", displayName = "x", inputModalities = listOf("text"))
        assertFalse(model.hasAudioInput)
    }

    @Test
    fun `hasAudioInput false when inputModalities is null`() {
        val model = LLMModel(id = "x", displayName = "x", inputModalities = null)
        assertFalse(model.hasAudioInput)
    }

    // ── LLMModel.hasAudioOutput ────────────────────────────────────────────────

    @Test
    fun `hasAudioOutput true when outputModalities contains audio`() {
        val model = LLMModel(id = "x", displayName = "x", outputModalities = listOf("audio"))
        assertTrue(model.hasAudioOutput)
    }

    @Test
    fun `hasAudioOutput false when outputModalities does not contain audio`() {
        val model = LLMModel(id = "x", displayName = "x", outputModalities = listOf("text"))
        assertFalse(model.hasAudioOutput)
    }

    @Test
    fun `hasAudioOutput false when outputModalities is null`() {
        val model = LLMModel(id = "x", displayName = "x", outputModalities = null)
        assertFalse(model.hasAudioOutput)
    }

    // ── LLMModel.hasVoiceModality ──────────────────────────────────────────────

    @Test
    fun `hasVoiceModality true when audio input present`() {
        val model = LLMModel(id = "x", displayName = "x", inputModalities = listOf("audio"))
        assertTrue(model.hasVoiceModality)
    }

    @Test
    fun `hasVoiceModality true when audio output present`() {
        val model = LLMModel(id = "x", displayName = "x", outputModalities = listOf("audio"))
        assertTrue(model.hasVoiceModality)
    }

    @Test
    fun `hasVoiceModality false when no audio modality`() {
        val model = LLMModel(id = "x", displayName = "x", inputModalities = listOf("text"), outputModalities = listOf("text"))
        assertFalse(model.hasVoiceModality)
    }

    @Test
    fun `hasVoiceModality false when both modalities null`() {
        val model = LLMModel(id = "x", displayName = "x")
        assertFalse(model.hasVoiceModality)
    }

    // ── LLMModel.isVoiceTemplateSeedShape ──────────────────────────────────────

    @Test
    fun `isVoiceTemplateSeedShape true for ASR seed`() {
        val model = LLMModel(id = "x", displayName = "x", inputModalities = listOf("audio"), outputModalities = null)
        assertTrue(model.isVoiceTemplateSeedShape)
    }

    @Test
    fun `isVoiceTemplateSeedShape true for TTS seed`() {
        val model = LLMModel(id = "x", displayName = "x", inputModalities = null, outputModalities = listOf("audio"))
        assertTrue(model.isVoiceTemplateSeedShape)
    }

    @Test
    fun `isVoiceTemplateSeedShape false when both have audio`() {
        val model = LLMModel(id = "x", displayName = "x", inputModalities = listOf("audio"), outputModalities = listOf("audio"))
        assertFalse(model.isVoiceTemplateSeedShape)
    }

    @Test
    fun `isVoiceTemplateSeedShape false when neither is audio`() {
        val model = LLMModel(id = "x", displayName = "x", inputModalities = listOf("text"), outputModalities = listOf("text"))
        assertFalse(model.isVoiceTemplateSeedShape)
    }

    @Test
    fun `isVoiceTemplateSeedShape false when both null`() {
        val model = LLMModel(id = "x", displayName = "x")
        assertFalse(model.isVoiceTemplateSeedShape)
    }

    @Test
    fun `isVoiceTemplateSeedShape false when input is audio but output is not null and not audio`() {
        val model = LLMModel(id = "x", displayName = "x", inputModalities = listOf("audio"), outputModalities = listOf("text"))
        assertFalse(model.isVoiceTemplateSeedShape)
    }

    @Test
    fun `isVoiceTemplateSeedShape false when output is audio but input is not null and not audio`() {
        val model = LLMModel(id = "x", displayName = "x", inputModalities = listOf("text"), outputModalities = listOf("audio"))
        assertFalse(model.isVoiceTemplateSeedShape)
    }

    // ── LLMModel.withInferredVoiceModality ─────────────────────────────────────

    @Test
    fun `withInferredVoiceModality returns same model when inputModalities is not null`() {
        val original = LLMModel(id = "x", displayName = "x", inputModalities = listOf("text"))
        val result = original.withInferredVoiceModality()
        assertSame(original, result)
    }

    @Test
    fun `withInferredVoiceModality returns same model when outputModalities is not null`() {
        val original = LLMModel(id = "x", displayName = "x", outputModalities = listOf("text"))
        val result = original.withInferredVoiceModality()
        assertSame(original, result)
    }

    @Test
    fun `withInferredVoiceModality returns same model when inference returns null`() {
        val original = LLMModel(id = "unknown-model", displayName = "Unknown Model")
        val result = original.withInferredVoiceModality()
        assertSame(original, result)
    }

    @Test
    fun `withInferredVoiceModality returns copy with inferred modalities for ASR pattern`() {
        val original = LLMModel(id = "my-asr-model", displayName = "My ASR Model")
        val result = original.withInferredVoiceModality()
        assertNotSame(original, result)
        assertEquals(listOf("audio"), result.inputModalities)
        assertEquals(listOf("text"), result.outputModalities)
        // Other fields unchanged
        assertEquals(original.id, result.id)
        assertEquals(original.displayName, result.displayName)
    }

    @Test
    fun `withInferredVoiceModality returns copy with inferred modalities for TTS pattern`() {
        val original = LLMModel(id = "my-tts-model", displayName = "My TTS Model")
        val result = original.withInferredVoiceModality()
        assertNotSame(original, result)
        assertEquals(listOf("text"), result.inputModalities)
        assertEquals(listOf("audio"), result.outputModalities)
        assertEquals(original.id, result.id)
        assertEquals(original.displayName, result.displayName)
    }
}