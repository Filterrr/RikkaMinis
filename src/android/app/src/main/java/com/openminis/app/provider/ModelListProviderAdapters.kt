package com.openminis.app.provider

import android.content.Context
import com.openminis.app.provider.KimiConstants
import com.openminis.app.provider.antigravity.AntigravityCredentialStore
import com.openminis.app.provider.antigravity.AntigravityModelsApi
import com.openminis.app.provider.workbuddy.WorkBuddyApi
import com.openminis.app.provider.workbuddy.WorkBuddyConstants
import com.openminis.app.provider.workbuddy.WorkBuddyCredentialStore
import com.openminis.app.data.model.LLMModel
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
        forceRefresh: Boolean,   // no cache — accepted for interface, ignored
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        return AnthropicModelsApi.fetchModels(
            apiKey,
            instance.effectiveBaseURL,
            // [T-provider-custom-user-agent] models-list UA override.
            customUserAgent = instance.customUserAgent,
        )
    }
}

private object GeminiModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,   // no cache — accepted for interface, ignored
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        return GeminiModelsApi.fetchModels(apiKey)
    }
}

private object OpenAIModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        val baseURL = instance.effectiveBaseURL
        return OpenAIModelsApi.fetchModels(
            apiKey,
            baseURL,
            // [T-provider-custom-user-agent] models-list UA override.
            customUserAgent = instance.customUserAgent,
            // Bypass the 7-day ProviderModelsCache so a freshly-added custom
            // provider re-validates its URL+key against the live endpoint.
            forceRefresh = forceRefresh,
        )
    }
}

private object OpenRouterModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,   // no cache — accepted for interface, ignored
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        return OpenRouterModelsApi.fetchModels(apiKey)
    }
}

private object XAIModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,   // no cache — accepted for interface, ignored
    ): List<LLMModel> {
        // xAI: the model list is static (no /v1/models gating call needed —
        // XAIModelsApi exposes the spec-mandated set).
        return XAIModelsApi.fetchModels()
    }
}

private object KimiModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,
    ): List<LLMModel> {
        // [T-kimi-oauth] Kimi Code: the OAuth token CAN call the models
        // endpoint — real fetch from GET /coding/v1/models. The upstream
        // lineup shifts across generations, so the live list replaces the
        // minimal built-in fallback.
        if (apiKey == null) return emptyList()
        val baseURL = instance.effectiveBaseURL ?: "${KimiConstants.CODING_API_BASE}/v1"
        return OpenAIModelsApi.fetchModels(
            apiKey,
            baseURL,
            customUserAgent = instance.customUserAgent,
            forceRefresh = forceRefresh,
        )
    }
}

private object AntigravityModelListAdapter : ModelListProvider {
    /** Application context, injected at startup via [initAntigravityAdapter]. */
    @Volatile private var appContextRef: Context? = null

    /** Called once at app startup next to registerModelListProviders(). */
    fun init(context: Context) {
        appContextRef = context.applicationContext
    }

    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,
    ): List<LLMModel> {
        // [T-antigravity-oauth] The `apiKey` slot normally carries the OAuth
        // access token, pre-resolved (and refreshed) by
        // ProviderRepository.loadApiKey. It can still be NEAR-EXPIRY by the
        // time this call executes (e.g. a main-thread caller handed us the
        // possibly-stale token while the async refresh raced us) —
        // fetchAvailableModels then answers 401 and the model list silently
        // comes back empty ("no models" UI). Re-resolve through
        // AntigravityCredentialStore.validAccessToken() when possible: it
        // transparently rotates an expired token via the refresh_token
        // (single-flighted by the store's Mutex), mirroring the executor-side
        // refresh upstream does before every API call.
        val context = appContextRef
        val accessToken = if (context != null) {
            // Store-backed resolution: transparently refreshes an expired
            // token. A null here means the refresh token itself is dead —
            // do NOT fall back to the possibly-stale `apiKey`, otherwise we
            // ship a doomed 401 and the model list silently goes empty.
            AntigravityCredentialStore.validAccessToken(context, instance.id)
        } else {
            // Adapter not initialized (tests / exotic call orders) — use the
            // pre-resolved token handed in by the repository.
            apiKey
        } ?: return emptyList()
        return AntigravityModelsApi.fetchModels(
            accessToken = accessToken,
            projectId = context?.let { AntigravityCredentialStore.loadProjectId(it, instance.id) },
            context = context,
            forceRefresh = forceRefresh,
            instanceId = instance.id,
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
    ModelListProviderRegistry.register(ProviderType.antigravity, AntigravityModelListAdapter)
    ModelListProviderRegistry.register(ProviderType.workBuddy, WorkBuddyModelListAdapter)
}

/**
 * [T-workbuddy-oauth] WorkBuddy model catalog.
 *
 * Unlike the API-key providers, this list is **account-scoped**: it is the set
 * of models the signed-in tenant is entitled to, so it can only be fetched
 * through the OAuth token in [WorkBuddyCredentialStore] — the `apiKey`
 * argument hands us the marker string, not a credential, and is ignored.
 *
 * A signed-out instance returns the bundled placeholder catalog rather than an
 * empty list, so the provider is not a dead end before login: the user sees
 * what the account will offer, picks a model, and is prompted to sign in when
 * a request actually needs a token.
 */
private object WorkBuddyModelListAdapter : ModelListProvider {
    /** Application context, injected at startup via [initWorkBuddyAdapter]. */
    @Volatile private var appContextRef: Context? = null

    fun init(context: Context) {
        appContextRef = context.applicationContext
    }

    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,
    ): List<LLMModel> {
        val region = WorkBuddyConstants.Region.from(instance.workBuddyRegion)
        // Same reasoning as the Antigravity adapter: resolve through the store
        // so a token that expired since the last request is rotated
        // (single-flighted) instead of producing a silent 401 → empty list.
        val context = appContextRef
        val tokens = if (context != null) {
            WorkBuddyCredentialStore.loadTokens(context, instance.id)?.let { stored ->
                if (stored.isExpired && stored.hasRefreshToken) {
                    runCatching { WorkBuddyCredentialStore.refreshAccessToken(context, instance.id) }
                        .getOrNull() ?: stored
                } else {
                    stored
                }
            }
        } else {
            // Adapter not initialized (tests / exotic call orders) — no store
            // to read, so fall through to the bundled catalog below.
            null
        }

        if (tokens == null) {
            // Signed out: offer the bundled placeholder so the picker is not
            // empty. The live catalog replaces it after login.
            return WorkBuddyApi.bundledModels(region)
        }

        return try {
            // Honour an instance-level base URL (enterprise proxy/mirror) —
            // same override the chat path applies.
            val live = WorkBuddyApi.fetchModels(tokens, backendOverride = instance.customBaseURL)
            if (live.isEmpty()) WorkBuddyApi.bundledModels(region) else live
        } catch (e: Exception) {
            // A failed catalog fetch must not wipe an already-working model
            // list — fall back to the bundled snapshot, and to whatever the
            // repository already holds when even that is empty (achieved by
            // returning an empty list, which makes refreshModels PRESERVE).
            com.openminis.app.logging.AppLogger.warning(
                "WorkBuddyModels",
                "catalog fetch failed: ${e.message}",
            )
            WorkBuddyApi.bundledModels(region)
        }
    }
}

/** Application-context injection for adapters that need one (called at startup). */
fun initWorkBuddyAdapter(context: android.content.Context) {
    WorkBuddyModelListAdapter.init(context)
}

/** Application-context injection for adapters that need one (called at startup). */
fun initAntigravityAdapter(context: android.content.Context) {
    AntigravityModelListAdapter.init(context)
}