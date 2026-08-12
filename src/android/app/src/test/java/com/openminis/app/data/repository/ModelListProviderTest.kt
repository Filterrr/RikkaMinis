package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModelListProviderTest {

    private class TestModelListProvider : ModelListProvider {
        override suspend fun fetchModels(
            apiKey: String?,
            instance: ProviderInstance,
            thirdParty: Boolean
        ): List<LLMModel> {
            return listOf(LLMModel())
        }
    }

    @Test
    fun `fetchModels should return list from implementation`() = runTest {
        val provider = TestModelListProvider()
        val result = provider.fetchModels(
            apiKey = "test-api-key",
            instance = ProviderInstance(),
            thirdParty = true
        )
        
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `fetchModels should handle null apiKey`() = runTest {
        val provider = TestModelListProvider()
        val result = provider.fetchModels(
            apiKey = null,
            instance = ProviderInstance(),
            thirdParty = false
        )
        
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `oauthModels should return empty list by default`() = runTest {
        val provider = TestModelListProvider()
        val result = provider.oauthModels()
        
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }
}