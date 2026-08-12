package com.openminis.app.provider

import com.openminis.app.auth.KimiDeviceFlow
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ModelListProvider
import com.openminis.app.data.repository.ModelListProviderRegistry
import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.gemini.GeminiModelsApi
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import com.openminis.app.provider.xai.XAIModelsApi

/**
 * [T8-1] Provider-package adapters that implement [ModelListProvider],
 * breaking the data→provider reverse dependency. The data layer's
 * [ModelListProviderRegistry] only knows the interface; these adapters
 * (here, in the provider package) delegate to the concrete `*ModelsApi`
 * singletons.
 *
 * Each adapter mirrors exactly the dispatch the old `ProviderRepository`
 * `when (instance.providerType)` block performed, so behaviour is
 * unchanged.
 */

private object AnthropicModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        return AnthropicModelsApi.fetchModels(
            apiKey,
            instance.effectiveBaseURL,
            isOAuth = instance.credentialType == ProviderCredential.oauth,
            // [T-provider-custom-user-agent] models-list UA override.
            customUserAgent = instance.customUserAgent,
        )
    }
}

private object GeminiModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(apiKey: String?, instance: ProviderInstance, thirdParty: Boolean): List<LLMModel> {
        if (apiKey == null) return emptyList()
        return GeminiModelsApi.fetchModels(apiKey)
    }
}

private object OpenAIModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(apiKey: String?, instance: ProviderInstance, thirdParty: Boolean): List<LLMModel> {
        if (apiKey == null) return emptyList()
        val baseURL = instance.effectiveBaseURL
        return OpenAIModelsApi.fetchModels(
            apiKey,
            baseURL,
            customUserAgent = instance.customUserAgent,
        )
    }

    // [T8-1] OpenAI Codex OAuth: static model list (OAuth tokens can't
    // call /v1/models). Mirrors the old ProviderRepository short-circuit.
    override suspend fun oauthModels(): List<LLMModel> = OpenAIModelsApi.fetchModelsOAuth()
}

private object OpenRouterModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(apiKey: String?, instance: ProviderInstance, thirdParty: Boolean): List<LLMModel> {
        if (apiKey == null) return emptyList()
        return OpenRouterModelsApi.fetchModels(apiKey)
    }
}

private object XAIModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(apiKey: String?, instance: ProviderInstance, thirdParty: Boolean): List<LLMModel> {
        // xAI: the model list is static (no /v1/models gating call needed —
        // XAIModelsApi exposes the spec-mandated set). Works for both
        // OAuth and API-key holders.
        return XAIModelsApi.fetchModelsOAuth()
    }
}

private object KimiModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(apiKey: String?, instance: ProviderInstance, thirdParty: Boolean): List<LLMModel> {
        // [T-kimi-oauth] Kimi Code: the OAuth token CAN call the models
        // endpoint — real fetch from GET /coding/v1/models. The upstream
        // lineup shifts across generations, so the live list replaces the
        // minimal built-in fallback.
        if (apiKey == null) return emptyList()
        val baseURL = instance.effectiveBaseURL ?: "${KimiDeviceFlow.CODING_API_BASE}/v1"
        return OpenAIModelsApi.fetchModels(
            apiKey,
            baseURL,
            customUserAgent = instance.customUserAgent,
        )
    }
}

/**
 * Register all built-in model-list providers. Called once at app
 * startup (see MinisApp / ProviderRepository init path).
 */
fun registerModelListProviders() {
    ModelListProviderRegistry.register(ProviderType.anthropic, AnthropicModelListAdapter)
    ModelListProviderRegistry.register(ProviderType.gemini, GeminiModelListAdapter)
    ModelListProviderRegistry.register(ProviderType.openAI, OpenAIModelListAdapter)
    ModelListProviderRegistry.register(ProviderType.openRouter, OpenRouterModelListAdapter)
    ModelListProviderRegistry.register(ProviderType.xAI, XAIModelListAdapter)
    ModelListProviderRegistry.register(ProviderType.kimiCode, KimiModelListAdapter)
}