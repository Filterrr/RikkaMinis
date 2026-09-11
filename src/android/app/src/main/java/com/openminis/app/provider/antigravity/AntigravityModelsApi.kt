package com.openminis.app.provider.antigravity

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Antigravity models catalog fetch — mirrors iOS `AntigravityModelsAPI` /
 * CLIProxyAPI `cmd/fetch_antigravity_models`.
 *
 * Uses the OAuth access token (NOT an API key): the caller resolves the
 * token through [AntigravityCredentialStore.validAccessToken], which
 * transparently refreshes when expired. Project id scopes the request when
 * known (upstream sends `{"project": <id>}` only when it has one).
 *
 * Endpoint order: daily → prod (upstream's fallback order), accepting both
 * the dict and array `models` response shapes. The UA matches the
 * OAuth-era fingerprint (AntigravityOAuth.requestUserAgent) rather than the
 * older hardcoded 1.107.0 string this file previously carried.
 */
object AntigravityModelsApi {

    private const val TAG = "AntigravityModelsApi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cache = com.openminis.app.provider.ProviderModelsCache("antigravity")

    /**
     * Fetch Antigravity's model list using the OAuth access token. Returns
     * an empty list on hard failure. When [context] is provided, results are
     * cached for 7 days at `cacheDir/models-cache/antigravity/<sha256>.json`.
     */
    suspend fun fetchModels(
        accessToken: String,
        projectId: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val cacheKey = projectId.orEmpty() + "|" + accessToken.hashCode()
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val models = fetchLive(accessToken, projectId)

        if (models.isNotEmpty()) {
            val enriched = com.openminis.app.provider.ModelsDevApi.enrichModels(models)
            if (context != null) cache.save(context, cacheKey, enriched)
            enriched
        } else {
            models
        }
    }

    private fun fetchLive(accessToken: String, projectId: String?): List<LLMModel> {
        val payload = if (projectId?.isNotBlank() == true) {
            JSONObject().put("project", projectId.trim())
        } else {
            JSONObject()
        }

        val bases = listOf(AntigravityOAuth.DAILY_API_ENDPOINT, AntigravityOAuth.API_ENDPOINT)
        for (base in bases) {
            val request = Request.Builder()
                .url("$base/${AntigravityOAuth.API_VERSION}:fetchAvailableModels")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", AntigravityOAuth.requestUserAgent())
                .header("X-Client-Name", "antigravity")
                .header("X-Client-Version", AntigravityOAuth.FALLBACK_VERSION)
                .header("Accept", "application/json")
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                AppLogger.warning(TAG, "fetchAvailableModels $base failed: ${e.message}")
                continue
            }

            val body = response.body?.string() ?: run {
                response.close()
                continue
            }

            if (!response.isSuccessful) {
                AppLogger.warning(TAG, "fetchAvailableModels $base: HTTP ${response.code}")
                response.close()
                continue
            }
            response.close()

            val json = try { JSONObject(body) } catch (_: Exception) { continue }
            val models = try {
                parseModels(json)
            } catch (_: Exception) {
                continue
            }
            if (models.isNotEmpty()) return models
        }
        return emptyList()
    }

    /** Both upstream response shapes: {"models": {id: meta}} and {"models": [{name, displayName}]}. */
    private fun parseModels(json: JSONObject): List<LLMModel> {
        val modelsField = json.opt("models") ?: return emptyList()
        val out = mutableListOf<LLMModel>()

        when (modelsField) {
            is JSONObject -> {
                val keys = modelsField.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val meta = modelsField.optJSONObject(id)
                    val displayName = meta?.optString("displayName", id)?.ifEmpty { id } ?: id
                    out.add(LLMModel(id = id, displayName = displayName, provider = "Antigravity"))
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until modelsField.length()) {
                    val obj = modelsField.optJSONObject(i) ?: continue
                    val id = obj.optString("name").ifEmpty { obj.optString("id") }
                    if (id.isEmpty()) continue
                    val displayName = obj.optString("displayName", id).ifEmpty { id }
                    out.add(LLMModel(id = id, displayName = displayName, provider = "Antigravity"))
                }
            }
        }
        return out
    }
}
