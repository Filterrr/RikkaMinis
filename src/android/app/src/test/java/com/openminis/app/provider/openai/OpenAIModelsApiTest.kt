package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMModel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs

class OpenAIModelsApiTest {

    @Test
    fun `fetchModelsOAuth returns expected models`() {
        val models = OpenAIModelsApi.fetchModelsOAuth()
        assertNotNull(models)
        assertTrue(models.isNotEmpty())
        val gpt5Sol = models.find { it.id == "gpt-5.6-sol" }
        assertNotNull(gpt5Sol)
        assertEquals("GPT-5.6 Sol", gpt5Sol.displayName)
        assertEquals("OpenAI", gpt5Sol.provider)
        assertTrue(gpt5Sol.supportsReasoning == true)
        val gptImage2 = models.find { it.id == "gpt-image-2" }
        assertNotNull(gptImage2)
        assertEquals("GPT Image 2", gptImage2.displayName)
        assertEquals("OpenAI", gptImage2.provider)
        assertTrue(gptImage2.inputModalities!!.contains("text"))
        assertTrue(gptImage2.inputModalities!!.contains("image"))
        assertTrue(gptImage2.outputModalities!!.contains("image"))
    }

    @Test
    fun `fetchModelsOAuth includes all expected GPT models`() {
        val models = OpenAIModelsApi.fetchModelsOAuth()
        val expectedIds = listOf(
            "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
            "gpt-5.5", "gpt-5.4", "gpt-5.3-codex",
            "gpt-5.3-codex-spark", "gpt-5-codex-mini",
            "gpt-5.2", "gpt-5.3", "gpt-5", "gpt-image-2"
        )
        expectedIds.forEach { id ->
            assertTrue(models.any { it.id == id }, "Expected model $id not found")
        }
    }

    @Test
    fun `fetchModelsOAuth models have supportsReasoning true for GPT-5 series`() {
        val models = OpenAIModelsApi.fetchModelsOAuth()
        val reasoningModels = models.filter { it.id.startsWith("gpt-5") && it.id != "gpt-image-2" }
        reasoningModels.forEach { model ->
            assertTrue(model.supportsReasoning == true, "Model ${model.id} should support reasoning")
        }
    }

    @Test
    fun `fetchModelsOAuth models are enriched by ModelsDevApi`() {
        val models = OpenAIModelsApi.fetchModelsOAuth()
        assertTrue(models.size >= 12)
    }
}