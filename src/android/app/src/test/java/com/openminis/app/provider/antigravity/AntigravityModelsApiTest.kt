package com.openminis.app.provider.antigravity

import com.openminis.app.data.model.LLMModel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

class AntigravityModelsApiTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetchModels returns empty list on network error`() = runBlocking {
        val result = AntigravityModelsApi.fetchModels("token", "http://localhost:0")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fetchModels returns empty list on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = AntigravityModelsApi.fetchModels("token", server.url("/").toString())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fetchModels returns empty list on 403`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = AntigravityModelsApi.fetchModels("token", server.url("/").toString())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fetchModels returns empty list on invalid json`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("invalid json"))
        val result = AntigravityModelsApi.fetchModels("token", server.url("/").toString())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseModels with JSONObject models`() {
        val json = JSONObject().apply {
            put("models", JSONObject().apply {
                put("model1", JSONObject().put("displayName", "Model One"))
                put("model2", JSONObject().put("displayName", "Model Two"))
            })
        }
        val result = invokeParseModels(json)
        assertEquals(2, result.size)
        assertEquals("model1", result[0].id)
        assertEquals("Model One", result[0].displayName)
        assertEquals("Antigravity", result[0].provider)
    }

    @Test
    fun `parseModels with JSONArray models`() {
        val json = JSONObject().apply {
            put("models", JSONArray().apply {
                put(JSONObject().put("name", "model1").put("displayName", "Model One"))
                put(JSONObject().put("id", "model2").put("displayName", "Model Two"))
            })
        }
        val result = invokeParseModels(json)
        assertEquals(2, result.size)
        assertEquals("model1", result[0].id)
        assertEquals("model2", result[1].id)
    }

    @Test
    fun `parseModels with empty models`() {
        val json = JSONObject().apply {
            put("models", JSONObject())
        }
        val result = invokeParseModels(json)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseModels with missing models field`() {
        val json = JSONObject()
        val result = invokeParseModels(json)
        assertTrue(result.isEmpty())
    }

    private fun invokeParseModels(json: JSONObject): List<LLMModel> {
        val method: Method = AntigravityModelsApi::class.java.getDeclaredMethod("parseModels", JSONObject::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(AntigravityModelsApi, json) as List<LLMModel>
    }
}