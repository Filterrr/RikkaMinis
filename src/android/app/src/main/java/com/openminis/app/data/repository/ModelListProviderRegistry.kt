package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType

/**
 * [T8-1] Registry mapping [ProviderType] → [ModelListProvider].
 *
 * Provider-package implementations register themselves via [register]
 * so the data layer never imports provider classes. Registration is
 * idempotent (last registration for a type wins), which lets tests
 * override fetchers per type.
 *
 * Thread-safety: registration typically happens once at app startup;
 * reads from [fetchModels] are safe from any thread (backed by a
 * synchronized map).
 */
object ModelListProviderRegistry {

    private val providers = HashMap<ProviderType, ModelListProvider>()

    /** Register (or replace) the fetcher for [type]. */
    fun register(type: ProviderType, provider: ModelListProvider) {
        synchronized(providers) {
            providers[type] = provider
        }
    }

    /**
     * Fetch models for [instance] via its registered provider.
     * Returns an empty list when no fetcher is registered for the
     * instance's provider type (caller falls through to its fallback).
     */
    suspend fun fetchModels(instance: ProviderInstance, apiKey: String?, thirdParty: Boolean): List<LLMModel> {
        val provider = synchronized(providers) { providers[instance.providerType] } ?: return emptyList()
        return provider.fetchModels(apiKey, instance, thirdParty)
    }

    /**
     * Fetch OAuth static models for [instance] via its registered provider.
     * Returns an empty list when no fetcher is registered or the provider
     * has no static OAuth model list.
     */
    suspend fun oauthModels(instance: ProviderInstance): List<LLMModel> {
        val provider = synchronized(providers) { providers[instance.providerType] } ?: return emptyList()
        return provider.oauthModels()
    }

    /** Test hook: clear all registrations. */
    fun clear() {
        synchronized(providers) { providers.clear() }
    }
}