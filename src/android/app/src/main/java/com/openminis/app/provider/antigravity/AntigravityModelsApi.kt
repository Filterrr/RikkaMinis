package com.openminis.app.provider.antigravity

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Antigravity models API — mirrors iOS `AntigravityModelsAPI`.
 *
 * Antigravity exposes its model catalog via a POST to
 * `<baseURL>/v1internal:fetchAvailableModels`. The response has shown up in
 * two shapes across versions:
 *   - **Dict**: `{"models": {"gemini-3-pro-high": {"displayName": ...}, ...}}`
 *   - **Array**: `{"models": [{"name": "gemini-3-pro-high", "displayName": ...}, ...]}`
 *
 * Both are accepted. On total failure across all candidate base URLs (and
 * empty cache) the caller is returned an empty list — provider wiring layer
 * should fall back to a curated list if one gets added to `LLMModel`.
 *
 * **Scaffolding only**: there's no Antigravity `ProviderType` in Android yet,
 * so this API has no runtime consumer. It lands here so the OAuth + provider
 * code that will wire it later has a stable surface to call.
 */
object AntigravityModelsApi {
    private const val USER_AGENT = "antigravity/1.107.0 android/aarch64"
    private val client = OkHttpClient()
    private val cache = ProviderModelsCache("antigravity")

    /**
     * Fetch Antigravity's model list from [baseURL]. Returns empty list on
     * hard failure. When [context] is provided, results are cached for 7
     * days at `cacheDir/models-cache/antigravity/<sha256>.json`.
     */
    suspend fun fetchModels(
        accessToken: String,
        baseURL: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val cacheKey = baseURL + "|" + accessToken
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val request = Request.Builder()
            .url("${baseURL.trimEnd('/')}/v1internal:fetchAvailableModels")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", USER_AGENT)
            .header("X-Client-Name", "antigravity")
            .header("X-Client-Version", "1.107.0")
            .header("Accept", "application/json")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        val body = response.body?.string() ?: run {
            response.close()
            return@withContext emptyList()
        }

        if (!response.isSuccessful) {
            if (context != null && (response.code == 401 || response.code == 403)) {
                cache.invalidate(context, cacheKey)
            }
            response.close()
            return@withContext emptyList()
        }
        response.close()

        val models = try {
            parseModels(JSONObject(body))
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        if (models.isEmpty()) return@withContext emptyList()
        val enriched = ModelsDevApi.enrichModels(models)
        if (context != null) cache.save(context, cacheKey, enriched)
        enriched
    }

    private fun parseModels(json: JSONObject): List<LLMModel> {
        val modelsField = json.opt("models") ?: return emptyList()
        val out = mutableListOf<LLMModel>()

        // [T-antigravity-models-filter] The raw catalog mixes real chat models
        // with IDE-internal plumbing: `chat_*` probes (isInternal=true,
        // MODEL_CHAT_* enums), `tab_*` / `tab_jump_*` / `command` previews
        // (MODEL_PLACEHOLDER_* enums), `-tiered` routing aliases, and
        // deprecated entries (deprecatedModelIds). None of them accept the
        // standard generateContent chat path — filter them so the provider's
        // model list only shows usable models. Mirrors CLIProxyAPI, which
        // uses the /models endpoint for capability hints only and serves its
        // own curated list (verified against the live catalog 2026-09).
        val deprecated = json.optJSONObject("deprecatedModelIds")?.let { dep ->
            val keys = mutableListOf<String>()
            val it = dep.keys()
            while (it.hasNext()) keys.add(it.next())
            keys.toSet()
        } ?: emptySet()

        fun isInternalId(id: String): Boolean =
            id.startsWith("chat_") || id.startsWith("tab_") || id.startsWith("command") ||
                id.endsWith("-tiered") || id in deprecated

        fun isUsable(meta: JSONObject): Boolean =
            !meta.optBoolean("isInternal", false) &&
                (meta.has("displayName") || meta.has("supportsImages") || meta.has("supportsThinking"))

        when (modelsField) {
            is JSONObject -> {
                // Dict format: key = model id, value = metadata object.
                val keys = modelsField.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    if (isInternalId(id)) continue
                    val meta = modelsField.optJSONObject(id) ?: continue
                    if (!isUsable(meta)) continue
                    out.add(modelFromMeta(id, meta))
                }
            }
            is JSONArray -> {
                // Array format: each entry has `name` (id) + `displayName`.
                for (i in 0 until modelsField.length()) {
                    val obj = modelsField.optJSONObject(i) ?: continue
                    val id = obj.optString("name").ifEmpty { obj.optString("id") }
                    if (id.isEmpty() || isInternalId(id)) continue
                    if (!isUsable(obj)) continue
                    out.add(modelFromMeta(id, obj))
                }
            }
        }
        return out
    }

    /**
     * Build an [LLMModel] from one catalog entry, carrying the upstream
     * capability flags into the model's metadata slots (models.dev enrichment
     * still wins where it has data — applyDevData only overrides non-null).
     */
    private fun modelFromMeta(id: String, meta: JSONObject): LLMModel {
        val serverName = meta.optString("displayName", id).ifEmpty { id }
        return LLMModel(
            id = id,
            displayName = saneDisplayName(id, serverName),
            provider = "Antigravity",
            contextWindow = meta.optInt("maxTokens", 0).takeIf { it > 0 },
            maxOutputTokens = meta.optInt("maxOutputTokens", 0).takeIf { it > 0 },
            supportsReasoning = meta.optBoolean("supportsThinking", false).takeIf { it },
            inputModalities = if (meta.optBoolean("supportsImages", false)) listOf("text", "image") else null,
        )
    }

    /**
     * The catalog's displayNames can go STALE relative to the id: the live
     * API returned `gemini-2.5-flash` with displayName "Gemini 3.1 Flash
     * Lite" (2026-09), colliding with the real 3.1 Flash Lite entry. When
     * the version family in the display name contradicts the id's family
     * (neither is a prefix of the other), fall back to the id-based
     * heuristic formatter — the id is the load-bearing truth.
     */
    private fun saneDisplayName(id: String, serverName: String): String {
        val idFamily = VERSION_FAMILY.find(id)?.groupValues?.get(1)
        val dnFamily = VERSION_FAMILY.find(serverName)?.groupValues?.get(1)
        if (idFamily == null || dnFamily == null) return serverName
        val consistent = dnFamily.startsWith(idFamily) || idFamily.startsWith(dnFamily)
        return if (consistent) serverName else LLMModel.modelDisplayName(fromId = id)
    }

    private val VERSION_FAMILY = Regex("""(\d+(?:\.\d+)*)""")
}
