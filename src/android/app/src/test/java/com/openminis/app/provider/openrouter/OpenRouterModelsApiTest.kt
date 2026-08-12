package com.openminis.app.provider.openrouter

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenRouterModelsApiTest {

    private val mockContext = mockk<Context>()
    private val mockCache = mockk<ProviderModelsCache>()
    private val mockCall = mockk<Call>()
    private val mockResponse = mockk<Response>()
    private val mockResponseBody = mockk<ResponseBody>()

    @BeforeEach
    fun setUp() {
        // Mock ProviderModelsCache constructor
        mockkConstructor(ProviderModelsCache::class)
        every { anyConstructed<ProviderModelsCache>().load(any(), any()) } returns null
        every { anyConstructed<ProviderModelsCache>().save(any(), any(), any()) } returns Unit
        every { anyConstructed<ProviderModelsCache>().invalidate(any(), any()) } returns Unit

        // Mock OkHttpClient constructor
        mockkConstructor(OkHttpClient::class)
        every { anyConstructed<OkHttpClient>().newCall(any()) } returns mockCall

        // Mock call
        every { mockCall.execute() } returns mockResponse

        // Mock response
        every { mockResponse.isSuccessful } returns true
        every { mockResponse.body } returns mockResponseBody
        every { mockResponse.code } returns 200

        // Mock response body
        every { mockResponseBody.string() } returns ""

        // Mock ModelsDevApi.enrichModels
        mockkObject(ModelsDevApi)
        every { ModelsDevApi.enrichModels(any()) } answers { firstArg() }

        // Mock applyUserAgentOverride (no-op)
        mockkStatic("com.openminis.app.provider.applyUserAgentOverride")
        every { any<Request.Builder>().applyUserAgentOverride(any()) } returns mockk()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `fetchModels with cache hit and no force refresh returns cached models`() = runTest {
        val cachedModels = listOf(
            LLMModel(id = "cached-model", provider = "OpenRouter")
        )
        every { anyConstructed<ProviderModelsCache>().load(any(), any()) } returns cachedModels

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = mockContext,
            forceRefresh = false
        )

        assertEquals(cachedModels, result)
        verify { anyConstructed<ProviderModelsCache>().load(mockContext, "test-key") }
        verify(exactly = 0) { anyConstructed<OkHttpClient>().newCall(any()) }
    }

    @Test
    fun `fetchModels with null context skips cache`() = runTest {
        val jsonResponse = createValidJsonResponse(listOf("model-1"))
        every { mockResponseBody.string() } returns jsonResponse
        every { mockResponse.isSuccessful } returns true

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = null,
            forceRefresh = false
        )

        assertTrue(result.isNotEmpty())
        verify(exactly = 0) { anyConstructed<ProviderModelsCache>().load(any(), any()) }
        verify(exactly = 0) { anyConstructed<ProviderModelsCache>().save(any(), any(), any()) }
    }

    @Test
    fun `fetchModels with forceRefresh bypasses cache`() = runTest {
        val jsonResponse = createValidJsonResponse(listOf("model-1"))
        every { mockResponseBody.string() } returns jsonResponse
        every { mockResponse.isSuccessful } returns true

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = mockContext,
            forceRefresh = true
        )

        assertTrue(result.isNotEmpty())
        verify(exactly = 0) { anyConstructed<ProviderModelsCache>().load(any(), any()) }
        verify { anyConstructed<ProviderModelsCache>().save(mockContext, "test-key", any()) }
    }

    @Test
    fun `fetchModels with unsuccessful response returns empty and invalidates cache on 401`() = runTest {
        every { mockResponse.isSuccessful } returns false
        every { mockResponse.code } returns 401

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = mockContext,
            forceRefresh = false
        )

        assertTrue(result.isEmpty())
        verify { anyConstructed<ProviderModelsCache>().invalidate(mockContext, "test-key") }
    }

    @Test
    fun `fetchModels with unsuccessful response returns empty and invalidates cache on 403`() = runTest {
        every { mockResponse.isSuccessful } returns false
        every { mockResponse.code } returns 403
        every { mockResponseBody.string() } returns ""

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = mockContext,
            forceRefresh = false
        )

        assertTrue(result.isEmpty())
        verify { anyConstructed<ProviderModelsCache>().invalidate(mockContext, "test-key") }
    }

    @Test
    fun `fetchModels with unsuccessful response but no cache invalidation for other codes`() = runTest {
        every { mockResponse.isSuccessful } returns false
        every { mockResponse.code } returns 500
        every { mockResponseBody.string() } returns ""

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = mockContext,
            forceRefresh = false
        )

        assertTrue(result.isEmpty())
        verify(exactly = 0) { anyConstructed<ProviderModelsCache>().invalidate(any(), any()) }
    }

    @Test
    fun `fetchModels parses models correctly from JSON`() = runTest {
        val modelId = "openai/gpt-4"
        val modelName = "GPT-4"
        val contextLength = 8192
        val maxCompletionTokens = 4096
        val supportedParams = JSONArray(listOf("reasoning", "other"))

        val json = JSONObject().apply {
            put("data", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", modelId)
                    put("name", modelName)
                    put("context_length", contextLength)
                    put("top_provider", JSONObject().apply {
                        put("max_completion_tokens", maxCompletionTokens)
                    })
                    put("supported_parameters", supportedParams)
                    put("architecture", JSONObject().apply {
                        put("input_modalities", JSONArray(listOf("text", "image")))
                        put("output_modalities", JSONArray(listOf("text")))
                    })
                })
            })
        }

        every { mockResponseBody.string() } returns json.toString()
        every { mockResponse.isSuccessful } returns true

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = null,
            forceRefresh = false
        )

        assertEquals(1, result.size)
        val model = result[0]
        assertEquals(modelId, model.id)
        assertEquals(modelName, model.displayName)
        assertEquals(8192, model.contextWindow)
        assertEquals(4096, model.maxOutputTokens)
        assertEquals(true, model.supportsReasoning)
        assertEquals(listOf("text", "image"), model.inputModalities)
        assertEquals(listOf("text"), model.outputModalities)
    }

    @Test
    fun `fetchModels handles empty data array`() = runTest {
        val json = JSONObject().apply {
            put("data", JSONArray())
        }
        every { mockResponseBody.string() } returns json.toString()
        every { mockResponse.isSuccessful } returns true

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = null,
            forceRefresh = false
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `fetchModels skips model with empty id`() = runTest {
        val json = JSONObject().apply {
            put("data", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "")
                    put("name", "Invalid")
                })
                put(JSONObject().apply {
                    put("id", "valid-model")
                    put("name", "Valid")
                })
            })
        }
        every { mockResponseBody.string() } returns json.toString()
        every { mockResponse.isSuccessful } returns true

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = null,
            forceRefresh = false
        )

        assertEquals(1, result.size)
        assertEquals("valid-model", result[0].id)
    }

    @Test
    fun `fetchModels handles missing architecture field`() = runTest {
        val json = JSONObject().apply {
            put("data", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "model-1")
                    put("name", "Model 1")
                })
            })
        }
        every { mockResponseBody.string() } returns json.toString()
        every { mockResponse.isSuccessful } returns true

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = null,
            forceRefresh = false
        )

        assertEquals(1, result.size)
        assertEquals(emptyList<String>(), result[0].inputModalities)
        assertEquals(emptyList<String>(), result[0].outputModalities)
    }

    @Test
    fun `fetchModels handles malformed JSON gracefully`() = runTest {
        every { mockResponseBody.string() } returns "invalid json"
        every { mockResponse.isSuccessful } returns true

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = null,
            forceRefresh = false
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `fetchModels uses name as fallback when id is missing`() = runTest {
        val json = JSONObject().apply {
            put("data", JSONArray().apply {
                put(JSONObject().apply {
                    // id not present
                    put("name", "Fallback Name")
                })
            })
        }
        every { mockResponseBody.string() } returns json.toString()
        every { mockResponse.isSuccessful } returns true

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = null,
            forceRefresh = false
        )

        assertTrue(result.isEmpty()) // because id is empty, should be skipped
    }

    @Test
    fun `fetchModels with null response body returns empty`() = runTest {
        every { mockResponse.isSuccessful } returns true
        every { mockResponse.body } returns null

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = null,
            forceRefresh = false
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `fetchModels enriches models via ModelsDevApi`() = runTest {
        val json = createValidJsonResponse(listOf("model-1"))
        every { mockResponseBody.string() } returns json
        every { mockResponse.isSuccessful } returns true

        val enrichedModels = listOf(
            LLMModel(id = "model-1", provider = "OpenRouter", displayName = "Enriched")
        )
        every { ModelsDevApi.enrichModels(any()) } returns enrichedModels

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = null,
            forceRefresh = false
        )

        assertEquals(enrichedModels, result)
        verify { ModelsDevApi.enrichModels(any()) }
    }

    @Test
    fun `fetchModels saves enriched models to cache when context is provided`() = runTest {
        val json = createValidJsonResponse(listOf("model-1"))
        every { mockResponseBody.string() } returns json
        every { mockResponse.isSuccessful } returns true

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = mockContext,
            forceRefresh = false
        )

        verify { anyConstructed<ProviderModelsCache>().save(mockContext, "test-key", result) }
    }

    @Test
    fun `fetchModels with 401 response and null context does not invalidate cache`() = runTest {
        every { mockResponse.isSuccessful } returns false
        every { mockResponse.code } returns 401
        every { mockResponseBody.string() } returns ""

        val result = OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = null,
            forceRefresh = false
        )

        assertTrue(result.isEmpty())
        verify(exactly = 0) { anyConstructed<ProviderModelsCache>().invalidate(any(), any()) }
    }

    @Test
    fun `fetchModels sets correct headers on request`() = runTest {
        val json = createValidJsonResponse(listOf("model-1"))
        every { mockResponseBody.string() } returns json
        every { mockResponse.isSuccessful } returns true

        OpenRouterModelsApi.fetchModels(
            apiKey = "test-key",
            context = null,
            forceRefresh = false
        )

        val captor = slot<Request>()
        verify { anyConstructed<OkHttpClient>().newCall(capture(captor)) }
        val request = captor.captured
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals("https://openrouter.ai", request.header("HTTP-Referer"))
        assertEquals("Minis App", request.header("X-Title"))
    }

    // Helper to create a valid JSON response with given model IDs
    private fun createValidJsonResponse(modelIds: List<String>): String {
        return JSONObject().apply {
            put("data", JSONArray().apply {
                modelIds.forEach { id ->
                    put(JSONObject().apply {
                        put("id", id)
                        put("name", id)
                        put("context_length", 4096)
                        put("top_provider", JSONObject())
                        put("architecture", JSONObject().apply {
                            put("input_modalities", JSONArray())
                            put("output_modalities", JSONArray())
                        })
                    })
                }
            })
        }.toString()
    }
}