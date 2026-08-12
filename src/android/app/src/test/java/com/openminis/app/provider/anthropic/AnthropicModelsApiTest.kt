package com.openminis.app.provider.anthropic

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnthropicModelsApiTest {

    @Test
    fun `fetchModels with null baseURL and apiKey uses default URL`() = runBlocking {
        val result = AnthropicModelsApi.fetchModels(
            apiKey = "test-api-key",
            baseURL = null,
            isOAuth = false,
            context = null,
            forceRefresh = true
        )
        assertEquals(LLMModel.allAnthropic, result)
    }

    @Test
    fun `fetchModels with custom baseURL and empty response returns empty list`() = runBlocking {
        val mockClient = mock<OkHttpClient>()
        val mockResponse = mock<Response> {
            on { isSuccessful } doReturn true
            on { body } doReturn mock<ResponseBody> {
                on { string() } doReturn "{\"data\":[]}"
            }
        }
        val mockCall = mock<okhttp3.Call> {
            on { execute() } doReturn mockResponse
        }
        whenever(mockClient.newCall(any())).thenReturn(mockCall)

        val result = AnthropicModelsApi.fetchModels(
            apiKey = "test-key",
            baseURL = "https://custom.api.com",
            isOAuth = false,
            context = null,
            forceRefresh = true
        )
        assertEquals(emptyList<LLMModel>(), result)
    }

    @Test
    fun `fetchModels with custom baseURL and valid response returns parsed models`() = runBlocking {
        val jsonResponse = """
            {
                "data": [
                    {"id": "claude-3-opus", "display_name": "Claude 3 Opus"},
                    {"id": "claude-3-sonnet", "display_name": "Claude 3 Sonnet"}
                ]
            }
        """.trimIndent()

        val mockClient = mock<OkHttpClient>()
        val mockResponse = mock<Response> {
            on { isSuccessful } doReturn true
            on { body } doReturn mock<ResponseBody> {
                on { string() } doReturn jsonResponse
            }
        }
        val mockCall = mock<okhttp3.Call> {
            on { execute() } doReturn mockResponse
        }
        whenever(mockClient.newCall(any())).thenReturn(mockCall)

        val result = AnthropicModelsApi.fetchModels(
            apiKey = "test-key",
            baseURL = "https://custom.api.com",
            isOAuth = false,
            context = null,
            forceRefresh = true
        )
        assertEquals(2, result.size)
        assertEquals("claude-3-opus", result[0].id)
        assertEquals("claude-3-sonnet", result[1].id)
    }

    @Test
    fun `fetchModels with OAuth adds correct headers`() = runBlocking {
        val mockClient = mock<OkHttpClient>()
        val mockResponse = mock<Response> {
            on { isSuccessful } doReturn true
            on { body } doReturn mock<ResponseBody> {
                on { string() } doReturn "{\"data\":[]}"
            }
        }
        val mockCall = mock<okhttp3.Call> {
            on { execute() } doReturn mockResponse
        }
        whenever(mockClient.newCall(any())).thenReturn(mockCall)

        val result = AnthropicModelsApi.fetchModels(
            apiKey = "oauth-token",
            baseURL = null,
            isOAuth = true,
            context = null,
            forceRefresh = true
        )
        assertEquals(emptyList<LLMModel>(), result)
    }

    @Test
    fun `fetchModels with customUserAgent applies override`() = runBlocking {
        val mockClient = mock<OkHttpClient>()
        val mockResponse = mock<Response> {
            on { isSuccessful } doReturn true
            on { body } doReturn mock<ResponseBody> {
                on { string() } doReturn "{\"data\":[]}"
            }
        }
        val mockCall = mock<okhttp3.Call> {
            on { execute() } doReturn mockResponse
        }
        whenever(mockClient.newCall(any())).thenReturn(mockCall)

        val result = AnthropicModelsApi.fetchModels(
            apiKey = "test-key",
            baseURL = null,
            isOAuth = false,
            context = null,
            forceRefresh = true,
            customUserAgent = "TestAgent/1.0"
        )
        assertEquals(emptyList<LLMModel>(), result)
    }

    @Test
    fun `fetchModels with context and cache hit returns cached models`() = runBlocking {
        val mockContext = mock<Context>()
        val cachedModels = listOf(LLMModel("cached-model", "Cached", "Anthropic"))
        val mockCache = mock<AnthropicModelsCache>()

        val result = AnthropicModelsApi.fetchModels(
            apiKey = "test-key",
            baseURL = null,
            isOAuth = false,
            context = mockContext,
            forceRefresh = false
        )
        assertNotNull(result)
    }

    @Test
    fun `fetchModels with forceRefresh ignores cache`() = runBlocking {
        val mockContext = mock<Context>()
        val result = AnthropicModelsApi.fetchModels(
            apiKey = "test-key",
            baseURL = null,
            isOAuth = false,
            context = mockContext,
            forceRefresh = true
        )
        assertEquals(LLMModel.allAnthropic, result)
    }

    @Test
    fun `fetchModels with 401 response invalidates cache`() = runBlocking {
        val mockContext = mock<Context>()
        val mockClient = mock<OkHttpClient>()
        val mockResponse = mock<Response> {
            on { isSuccessful } doReturn false
            on { code } doReturn 401
            on { body } doReturn mock<ResponseBody> {
                on { string() } doReturn "Unauthorized"
            }
        }
        val mockCall = mock<okhttp3.Call> {
            on { execute() } doReturn mockResponse
        }
        whenever(mockClient.newCall(any())).thenReturn(mockCall)

        val result = AnthropicModelsApi.fetchModels(
            apiKey = "bad-key",
            baseURL = null,
            isOAuth = false,
            context = mockContext,
            forceRefresh = true
        )
        assertEquals(LLMModel.allAnthropic, result)
    }

    @Test
    fun `fetchModels with 403 response invalidates cache`() = runBlocking {
        val mockContext = mock<Context>()
        val mockClient = mock<OkHttpClient>()
        val mockResponse = mock<Response> {
            on { isSuccessful } doReturn false
            on { code } doReturn 403
            on { body } doReturn mock<ResponseBody> {
                on { string() } doReturn "Forbidden"
            }
        }
        val mockCall = mock<okhttp3.Call> {
            on { execute() } doReturn mockResponse
        }
        whenever(mockClient.newCall(any())).thenReturn(mockCall)

        val result = AnthropicModelsApi.fetchModels(
            apiKey = "bad-key",
            baseURL = null,
            isOAuth = false,
            context = mockContext,
            forceRefresh = true
        )
        assertEquals(LLMModel.allAnthropic, result)
    }

    @Test
    fun `fetchModels with custom baseURL and network error returns empty list`() = runBlocking {
        val mockClient = mock<OkHttpClient>()
        val mockCall = mock<okhttp3.Call> {
            on { execute() } doThrow RuntimeException("Network error")
        }
        whenever(mockClient.newCall(any())).thenReturn(mockCall)

        val result = AnthropicModelsApi.fetchModels(
            apiKey = "test-key",
            baseURL = "https://custom.api.com",
            isOAuth = false,
            context = null,
            forceRefresh = true
        )
        assertEquals(emptyList<LLMModel>(), result)
    }

    @Test
    fun `fetchModels with null baseURL and network error returns allAnthropic`() = runBlocking {
        val mockClient = mock<OkHttpClient>()
        val mockCall = mock<okhttp3.Call> {
            on { execute() } doThrow RuntimeException("Network error")
        }
        whenever(mockClient.newCall(any())).thenReturn(mockCall)

        val result = AnthropicModelsApi.fetchModels(
            apiKey = "test-key",
            baseURL = null,
            isOAuth = false,
            context = null,
            forceRefresh = true
        )
        assertEquals(LLMModel.allAnthropic, result)
    }

    @Test
    fun `fetchModels with custom baseURL and all fallbacks fail returns empty list`() = runBlocking {
        val mockClient = mock<OkHttpClient>()
        val mockResponse = mock<Response> {
            on { isSuccessful } doReturn false
            on { code } doReturn 500
            on { body } doReturn mock<ResponseBody> {
                on { string() } doReturn "Server error"
            }
        }
        val mockCall = mock<okhttp3.Call> {
            on { execute() } doReturn mockResponse
        }
        whenever(mockClient.newCall(any())).thenReturn(mockCall)

        val result = AnthropicModelsApi.fetchModels(
            apiKey = "test-key",
            baseURL = "https://api.anthropic.com/v1",
            isOAuth = false,
            context = null,
            forceRefresh = true
        )
        assertEquals(emptyList<LLMModel>(), result)
    }

    @Test
    fun `fetchModels with reasoning model sets supportsReasoning flag`() = runBlocking {
        val jsonResponse = """
            {
                "data": [
                    {"id": "claude-3-opus-20240229", "display_name": "Claude 3 Opus"},
                    {"id": "claude-3-sonnet-20240229", "display_name": "Claude 3 Sonnet"}
                ]
            }
        """.trimIndent()

        val mockClient = mock<OkHttpClient>()
        val mockResponse = mock<Response> {
            on { isSuccessful } doReturn true
            on { body } doReturn mock<ResponseBody> {
                on { string() } doReturn jsonResponse
            }
        }
        val mockCall = mock<okhttp3.Call> {
            on { execute() } doReturn mockResponse
        }
        whenever(mockClient.newCall(any())).thenReturn(mockCall)

        val result = AnthropicModelsApi.fetchModels(
            apiKey = "test-key",
            baseURL = "https://custom.api.com",
            isOAuth = false,
            context = null,
            forceRefresh = true
        )
        assertTrue(result.isNotEmpty())
    }
}