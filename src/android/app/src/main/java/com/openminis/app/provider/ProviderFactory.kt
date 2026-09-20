package com.openminis.app.provider

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.network.ConnectionWarmer
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.antigravity.AntigravityCredentialStore
import com.openminis.app.provider.antigravity.AntigravityOAuth
import com.openminis.app.provider.antigravity.AntigravityProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider

object ProviderFactory {

    /**
     * [fix-antigravity-version-fingerprint] One-shot load of the remote
     * Antigravity version fingerprint from assets. Release automation can
     * ship a new `antigravity_version.txt` in an APK-independent channel
     * (or a plain app update) without touching Kotlin; when the asset is
     * absent/malformed the compiled FALLBACK_VERSION stays in effect.
     */
    @Volatile
    private var antigravityVersionLoaded = false

    private fun maybeInitAntigravityVersion(context: Context?) {
        if (antigravityVersionLoaded || context == null) return
        synchronized(this) {
            if (antigravityVersionLoaded) return
            try {
                val text = context.assets.open("antigravity_version.txt")
                    .bufferedReader().use { it.readLine() }
                AntigravityOAuth.initRemoteVersion(text)
            } catch (_: Exception) {
                // Asset absent → keep the compiled fallback. Not an error.
            }
            antigravityVersionLoaded = true
        }
    }

    /**
     * Create a provider for an API-key instance.
     * [context] is retained for call-site compatibility; OAuth (which needed
     * it for encrypted token storage) was removed.
     */
    fun create(
        instance: ProviderInstance,
        apiKey: String,
        model: LLMModel,
        context: Context? = null,
        /**
         * [T-workbuddy-oauth] Pre-resolved WorkBuddy credential bundle.
         *
         * WorkBuddy needs more than a bearer token: the backend routes by
         * tenant, so every request must also carry `X-User-Id` /
         * `X-Enterprise-Id` / `X-Tenant-Id` / `X-Domain`. Those live in the
         * encrypted store, which the `:modelservice` worker cannot read
         * (per-process Android Keystore). The app process therefore resolves
         * the whole bundle and hands it across the process boundary; when it
         * is present it takes precedence over a store read.
         *
         * Null (the normal in-process case, and every non-WorkBuddy type)
         * leaves resolution to the store.
         */
        workBuddyInline: com.openminis.app.provider.workbuddy.WorkBuddyCredentialStore.Tokens? = null,
    ): LLMProvider {
        // T174: route through ProviderInstance.effectiveBaseURL instead of
        // re-implementing the trim-+-endsWith dance inline. The previous
        // version did `url.endsWith("/v1")` on the raw, untrimmed string,
        // so a customBaseURL of "https://api.deepseek.com/v1/" (trailing
        // slash) failed the check and the code appended a second "/v1",
        // producing requests to "/v1//v1/chat/completions" → HTTP 404.
        // Likewise "https://api.deepseek.com/" was concatenated as-is to
        // ".../" + "/v1/chat/completions" = ".//v1/chat/completions",
        // which DeepSeek tolerated only by accident. effectiveBaseURL
        // trimEnd('/')'s the input first, so all four customBaseURL
        // shapes (no slash, trailing slash, /v1, /v1/) now collapse to
        // the same canonical "https://host/v1" string. The /chat/
        // completions endpoint suffix at OpenAIProvider.kt:710 then
        // produces a single-slash join.
        val basePath = instance.effectiveBaseURL
        return (when (instance.providerType) {
            ProviderType.anthropic -> {
                // [T-provider-custom-user-agent] Only meaningful for custom-base
                // (relay) instances; on the official direct path it's null.
                if (basePath != null) AnthropicProvider(apiKey, model, basePath, customUserAgent = instance.customUserAgent)
                else AnthropicProvider(apiKey, model)
            }
            ProviderType.gemini -> {
                if (basePath != null) GeminiProvider(apiKey, model, basePath)
                else GeminiProvider(apiKey, model)
            }
            ProviderType.openAI -> {
                val base = basePath ?: "https://api.openai.com/v1"
                OpenAIProvider(
                    apiKey = apiKey,
                    model = model,
                    basePath = base,
                    useResponsesAPI = instance.useResponsesAPI,
                    // [T-provider-custom-user-agent] Covers both chat and
                    // /responses for custom-base OpenAI-compat relays; null
                    // on the official direct path.
                    customUserAgent = instance.customUserAgent,
                    // [T-android-azure-openai] Azure auths with api-key +
                    // deployments-path URL. Pass the RAW customBaseURL (not
                    // the /v1-appended, query-stripped effectiveBaseURL) so
                    // azureUrl() can preserve the ?api-version query.
                    isAzure = instance.azureMode,
                    azureBase = instance.customBaseURL,
                )
            }
            ProviderType.openRouter -> {
                // OpenRouter uses OpenAI-compatible API with custom base URL and headers
                OpenAIProvider(
                    apiKey = apiKey,
                    model = model,
                    basePath = "https://openrouter.ai/api/v1",
                    extraHeaders = mapOf(
                        "HTTP-Referer" to "https://github.com/OpenMinis/OpenMinis",
                        "X-Title" to "Minis App",
                    ),
                )
            }
            ProviderType.xAI -> {
                // xAI exposes an OpenAI-compatible /v1/chat/completions
                // endpoint at api.x.ai/v1. API key passed through verbatim.
                val base = basePath ?: "https://api.x.ai/v1"
                OpenAIProvider(
                    apiKey = apiKey,
                    model = model,
                    basePath = base,
                )
            }
            ProviderType.kimiCode -> {
                // Kimi Coding Plan — OpenAI-compatible upstream.
                // ⚠️ The `/v1` is load-bearing: /coding/chat/completions 404s;
                // only /coding/v1/chat/completions works (verified live on iOS).
                // Custom bases go through effectiveBaseURL's /v1-append logic.
                val base = basePath ?: "${KimiConstants.CODING_API_BASE}/v1"
                OpenAIProvider(
                    apiKey = apiKey,
                    model = model,
                    basePath = base,
                )
            }
            ProviderType.antigravity -> {
                // [T-antigravity-oauth] `apiKey` slot carries the OAuth access
                // token — resolved by callers via
                // AntigravityCredentialStore.validAccessToken() (auto-refresh).
                // Custom base = full upstream origin (no /v1 appending);
                // default mirrors upstream resolveAntigravityRequestBaseURL.
                maybeInitAntigravityVersion(context)
                // Project id rides through the encrypted store (keyed by
                // instance id) when the provider carries one.
                val storeProjectId = context?.let {
                    AntigravityCredentialStore.loadProjectId(it, instance.id)
                }
                AntigravityProvider(
                    accessToken = apiKey,
                    model = model,
                    basePath = basePath ?: AntigravityOAuth.DAILY_API_ENDPOINT,
                    projectId = storeProjectId,
                    // Repair path: re-run loadCodeAssist/onboardUser and
                    // persist, mirroring upstream PrepareRequestAuth.
                    projectIdRefresher = ctx@ {
                        val appContext = context ?: return@ctx null
                        val token = AntigravityCredentialStore.validAccessToken(appContext, instance.id)
                            ?: return@ctx null
                        val id = AntigravityOAuth.fetchProjectId(token)
                        if (!id.isNullOrBlank()) {
                            AntigravityCredentialStore.updateProjectId(appContext, instance.id, id)
                        }
                        id
                    },
                    // [fix-antigravity-401-refresh-retry] 401 → rotate the
                    // access token via the refresh_token and replay once
                    // (upstream executor parity). Uses the classified
                    // refresh ([fix-antigravity-refresh-failure-classify]):
                    // the provider reads lastRefreshFailure to tell a
                    // network blip (NetworkError) from a dead refresh token
                    // (honest re-login error). validAccessToken remains
                    // single-flighted.
                    accessTokenRefresher = ctx@ {
                        val appContext = context ?: return@ctx null
                        AntigravityCredentialStore.refreshAccessTokenOrNull(appContext, instance.id)
                    },
                )
            }
            ProviderType.workBuddy -> {
                // [T-workbuddy-oauth] WorkBuddy is OAuth-only and its token is
                // resolved FRESH on every request rather than being handed in
                // once: an agent loop can easily outlive a single access
                // token, and the provider's per-call delegate rebuild would
                // otherwise re-send a credential that expired mid-session.
                // The `apiKey` slot holds the marker (see
                // ProviderRepository.WORKBUDDY_OAUTH_MARKER), not the token.
                //
                // `context` is required here — without it there is no token
                // store to read, so we fail loudly at construction instead of
                // silently building a provider that 401s on first use.
                val appContext = context
                    ?: error("WorkBuddy provider requires a Context for credential resolution")
                val wbRegion = com.openminis.app.provider.workbuddy.WorkBuddyConstants.Region
                    .from(instance.workBuddyRegion)
                // [T-workbuddy-oauth] In the worker process the store is
                // unreadable, so an inline bundle supplied by the app process
                // is authoritative and static for the request's lifetime.
                // In-process callers pass null and get live resolution.
                val inline = workBuddyInline
                com.openminis.app.provider.workbuddy.WorkBuddyProvider(
                    context = appContext,
                    tokenResolver = {
                        inline ?: com.openminis.app.provider.workbuddy.WorkBuddyCredentialStore
                            .loadTokens(appContext, instance.id)
                            ?.let { stored ->
                                if (stored.isExpired && stored.hasRefreshToken) {
                                    com.openminis.app.provider.workbuddy.WorkBuddyCredentialStore
                                        .refreshAccessToken(appContext, instance.id) ?: stored
                                } else {
                                    stored
                                }
                            }
                    },
                    model = model,
                    region = wbRegion,
                    // [T-workbuddy-oauth] Honour a user-supplied base URL so
                    // an enterprise proxy/mirror can front the data plane.
                    customBackend = instance.customBaseURL,
                )
            }
        }).also { provider ->
            provider.instanceContext = instance
            // [OPT7-conn-warmup] Every provider build is a "user is heading
            // toward this endpoint" signal (model pick, group resolve, app
            // start restore, fallback candidate build). Pre-arm the TLS
            // connection so the first send skips DNS+TCP+TLS — 1-3s saved on
            // cold start, more through a proxy. Debounced per host inside
            // the warmer; fire-and-forget, no credentials on the wire.
            ConnectionWarmer.warm(
                instance.effectiveBaseURL ?: defaultBaseFor(instance.providerType),
            )
        }
    }

    /** [OPT7-conn-warmup] Default origin for instances with no custom base. */
    private fun defaultBaseFor(type: ProviderType): String = when (type) {
        ProviderType.anthropic -> "https://api.anthropic.com"
        ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
        ProviderType.openAI -> "https://api.openai.com/v1"
        ProviderType.openRouter -> "https://openrouter.ai/api/v1"
        ProviderType.xAI -> "https://api.x.ai/v1"
        ProviderType.kimiCode -> KimiConstants.CODING_API_BASE + "/v1"
        // [T-antigravity-oauth] Upstream default (resolveAntigravityRequestBaseURL).
        ProviderType.antigravity -> AntigravityOAuth.DAILY_API_ENDPOINT
        // [T-workbuddy-oauth] Domestic default; the warmed origin is only a
        // hint (the real host depends on the instance's region).
        ProviderType.workBuddy -> com.openminis.app.provider.workbuddy.WorkBuddyConstants
            .DEFAULT_REGION.backend
    }
}