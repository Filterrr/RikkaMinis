package com.openminis.app.provider.antigravity

import com.openminis.app.data.model.LLMModel
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Antigravity OAuth2 client — a faithful Android port of CLIProxyAPI's
 * `internal/auth/antigravity` (github.com/router-for-me/CLIProxyAPI).
 *
 * Flow (identical to the upstream Go implementation):
 *  1. [buildAuthUrl]  — Google OAuth2 authorization URL (loopback redirect,
 *     port 51121, upstream's exact client id / secret / scopes).
 *  2. The caller opens the URL in a browser of the user's choosing and a
 *     loopback server captures `http://localhost:51121/oauth-callback`.
 *  3. [exchangeCodeForTokens] — authorization code → access/refresh tokens.
 *  4. [fetchUserInfo] — Google userinfo email (used as the credential label).
 *  5. [fetchProjectId] — `loadCodeAssist`, falling back to `onboardUser`
 *     (tier auto-detection + polling), mirroring upstream exactly.
 *  6. [refreshTokens] — `grant_type=refresh_token` rotation.
 *
 * The upstream request/User-Agent fingerprint (short UA for requests,
 * node-style UA for the control plane, `X-Goog-Api-Client`) is reproduced
 * so Google's backend treats the app like the native Antigravity client.
 */
object AntigravityOAuth {

    private const val TAG = "AntigravityOAuth"

    // ── Upstream constants (CLIProxyAPI internal/auth/antigravity/constants.go) ──
    const val CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
    const val CLIENT_SECRET = "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"
    const val CALLBACK_PORT = 51121
    const val CALLBACK_PATH = "/oauth-callback"

    val SCOPES = listOf(
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/cclog",
        "https://www.googleapis.com/auth/experimentsandconfigs",
    )

    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo?alt=json"

    const val API_ENDPOINT = "https://cloudcode-pa.googleapis.com"
    const val DAILY_API_ENDPOINT = "https://daily-cloudcode-pa.googleapis.com"
    const val API_VERSION = "v1internal"

    /** Upstream fallback client version (misc/antigravity_version.go). */
    const val FALLBACK_VERSION = "2.9.1"
    const val NODE_API_CLIENT_UA = "google-api-nodejs-client/10.3.0"
    const val GOOG_API_CLIENT_UA = "gl-node/22.21.1"

    /**
     * Antigravity user-facing models catalog — mirrors CLIProxyAPI
     * `internal/registry/models/models.json` (key "antigravity") 1:1, so the
     * pre-login / fetch-failure fallback list matches what the live
     * `fetchAvailableModels` call returns. The previous catalog was missing
     * the two Claude targets and `gemini-3.1-flash-image`, so a fallback
     * rendered an incomplete model list (and users saw wrong/absent model
     * IDs after a transient fetch failure).
     */
    val FALLBACK_MODELS: List<LLMModel> = listOf(
        LLMModel("claude-opus-4-6-thinking", "Claude Opus 4.6 (Thinking)", "Antigravity", contextWindow = 200_000, maxOutputTokens = 64_000, supportsReasoning = true),
        LLMModel("claude-sonnet-4-6", "Claude Sonnet 4.6 (Thinking)", "Antigravity", contextWindow = 200_000, maxOutputTokens = 64_000, supportsReasoning = true),
        LLMModel("gemini-3-flash", "Gemini 3 Flash", "Antigravity", contextWindow = 1_048_576, maxOutputTokens = 65_536, supportsReasoning = true),
        LLMModel("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite", "Antigravity", contextWindow = 1_048_576, maxOutputTokens = 65_535, supportsReasoning = true),
        LLMModel("gemini-3.1-flash-image", "Gemini 3.1 Flash Image", "Antigravity", contextWindow = 1_048_576, maxOutputTokens = 65_535, supportsReasoning = true),
        LLMModel("gemini-3.6-flash-high", "Gemini 3.6 Flash", "Antigravity", contextWindow = 1_048_576, maxOutputTokens = 65_536, supportsReasoning = true),
        LLMModel("gemini-3.7-flash-high", "Gemini 3.7 Flash", "Antigravity", contextWindow = 1_048_576, maxOutputTokens = 65_536, supportsReasoning = true),
        LLMModel("gemini-3.8-flash-high", "Gemini 3.8 Flash", "Antigravity", contextWindow = 1_048_576, maxOutputTokens = 65_536, supportsReasoning = true),
        LLMModel("gemini-pro-agent", "Gemini 3.1 Pro (High)", "Antigravity", contextWindow = 1_048_576, maxOutputTokens = 65_535, supportsReasoning = true),
        LLMModel("gemini-3.1-pro-low", "Gemini 3.1 Pro (Low)", "Antigravity", contextWindow = 1_048_576, maxOutputTokens = 65_535, supportsReasoning = true),
        LLMModel("gpt-oss-120b-medium", "GPT-OSS 120B (Medium)", "Antigravity", contextWindow = 114_000, maxOutputTokens = 32_768),
    )

    // ── User-Agent reproduction (misc/antigravity_version.go) ──

    /**
     * Short runtime UA used by generate / userinfo / loadCodeAssist.
     * Byte-exact upstream default: misc.AntigravityUserAgent() =
     * `antigravity/hub/<version> darwin/arm64` (the `hub` segment is what
     * cloudcode-pa's endpoint fingerprint expects).
     */
    fun requestUserAgent(): String = "antigravity/hub/$FALLBACK_VERSION darwin/arm64"

    /** Long control-plane UA used by onboardUser. */
    fun nodeUserAgent(): String = "${requestUserAgent()} $NODE_API_CLIENT_UA"

    // ── Data types (auth.go TokenResponse) ──

    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
        val tokenType: String?,
    )

    data class OAuthResult(
        val tokens: Tokens,
        val email: String?,
        val projectId: String?,
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Cryptographically random hex state (misc.GenerateRandomState): 16 bytes → 32 hex chars. */
    fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val hex = StringBuilder(32)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            hex.append("0123456789abcdef"[v ushr 4])
            hex.append("0123456789abcdef"[v and 0x0F])
        }
        return hex.toString()
    }

    // ── Step 1: authorization URL (auth.go BuildAuthURL) ──

    fun buildAuthUrl(state: String, redirectUri: String = loopbackRedirect()): String {
        val params = linkedMapOf(
            "access_type" to "offline",
            "client_id" to CLIENT_ID,
            "prompt" to "consent",
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to SCOPES.joinToString(" "),
            "state" to state,
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return "$AUTH_ENDPOINT?$query"
    }

    fun loopbackRedirect(): String = "http://localhost:$CALLBACK_PORT$CALLBACK_PATH"

    // ── Step 3: code exchange (auth.go ExchangeCodeForTokens) ──

    suspend fun exchangeCodeForTokens(code: String, redirectUri: String = loopbackRedirect()): Tokens =
        tokenRequest(
            form = mapOf(
                "code" to code,
                "client_id" to CLIENT_ID,
                "client_secret" to CLIENT_SECRET,
                "redirect_uri" to redirectUri,
                "grant_type" to "authorization_code",
            ),
        )

    // ── Step 6: refresh rotation (antigravity_executor_auth.go) ──

    suspend fun refreshTokens(refreshToken: String): Tokens = tokenRequest(
        form = mapOf(
            "client_id" to CLIENT_ID,
            "client_secret" to CLIENT_SECRET,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        ),
    )

    private suspend fun tokenRequest(form: Map<String, String>): Tokens = withContext(Dispatchers.IO) {
        val body = form.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw OAuthException("antigravity token exchange: execute request: ${e.message}", e)
        }

        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw OAuthException(
                    "antigravity token exchange: request failed: status ${resp.code}: ${text.take(500)}",
                )
            }
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw OAuthException("antigravity token exchange: decode response: ${e.message}", e)
            }
            val access = json.optString("access_token")
            if (access.isEmpty()) {
                throw OAuthException("antigravity token exchange: response missing access_token")
            }
            Tokens(
                accessToken = access,
                refreshToken = json.optString("refresh_token"),
                expiresIn = json.optLong("expires_in", 3600L),
                tokenType = json.optString("token_type").ifEmpty { null },
            )
        }
    }

    // ── Step 4: user email (auth.go FetchUserInfo) ──

    suspend fun fetchUserInfo(accessToken: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(USERINFO_ENDPOINT)
            .get()
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", requestUserAgent())
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw OAuthException("antigravity userinfo: execute request: ${e.message}", e)
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw OAuthException("antigravity userinfo: request failed: status ${resp.code}: ${text.take(500)}")
            }
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw OAuthException("antigravity userinfo: decode response: ${e.message}", e)
            }
            json.optString("email").trim().ifEmpty {
                throw OAuthException("antigravity userinfo: response missing email")
            }
        }
    }

    // ── Step 5: project id (auth.go FetchProjectID + OnboardUser) ──

    suspend fun fetchProjectId(accessToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val loadBody = JSONObject().put(
                "metadata",
                JSONObject().put("ideType", "ANTIGRAVITY"),
            )
            val loadUrl = "$API_ENDPOINT/$API_VERSION:loadCodeAssist"
            val loadRequest = Request.Builder()
                .url(loadUrl)
                .post(loadBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "*/*")
                .header("Content-Type", "application/json")
                .header("User-Agent", requestUserAgent())
                .build()

            val loadResponse = client.newCall(loadRequest).execute()
            val loadText = loadResponse.use { it.body?.string().orEmpty() }
            if (!loadResponse.isSuccessful) {
                AppLogger.warning(TAG, "loadCodeAssist failed: HTTP ${loadResponse.code}")
                return@withContext null
            }

            val loadJson = try { JSONObject(loadText) } catch (_: Exception) { JSONObject() }
            var projectId = extractCloudaicompanionProject(loadJson)
            if (projectId.isNotEmpty()) return@withContext projectId

            // Not provisioned yet → onboard (mirrors upstream OnboardUser).
            projectId = onboardUser(accessToken, defaultTierId(loadJson))
            projectId.ifEmpty { null }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "fetchProjectId failed: ${e.message}")
            null
        }
    }

    /** Upstream OnboardUser: POST onboardUser, poll until `done`, ≤5 attempts. */
    private suspend fun onboardUser(accessToken: String, tierId: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("tier_id", tierId)
            .put(
                "metadata",
                JSONObject()
                    .put("ide_type", "ANTIGRAVITY")
                    .put("ide_version", FALLBACK_VERSION)
                    .put("ide_name", "antigravity"),
            )
        val url = "$DAILY_API_ENDPOINT/$API_VERSION:onboardUser"
        val maxAttempts = 5
        repeat(maxAttempts) {
            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "*/*")
                .header("Content-Type", "application/json")
                .header("User-Agent", nodeUserAgent())
                .header("X-Goog-Api-Client", GOOG_API_CLIENT_UA)
                .build()

            val response = client.newCall(request).execute()
            val text = response.use { it.body?.string().orEmpty() }
            if (response.isSuccessful) {
                val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                if (json.optBoolean("done", false)) {
                    val data = json.optJSONObject("response")
                    val id = data?.let { extractCloudaicompanionProject(it) }.orEmpty()
                    if (id.isNotEmpty()) {
                        AppLogger.info(TAG, "onboardUser project_id obtained")
                    }
                    return@withContext id
                }
                // Not done yet → upstream sleeps 2s between polls.
                Thread.sleep(2_000)
                return@repeat
            }
            AppLogger.warning(TAG, "onboardUser attempt failed: HTTP ${response.code}: ${text.take(200)}")
            return@withContext ""
        }
        ""
    }

    /** extractCloudaicompanionProject (auth.go). */
    private fun extractCloudaicompanionProject(data: JSONObject): String {
        for (key in listOf("cloudaicompanionProject", "projectId", "project")) {
            when (val value = data.opt(key)) {
                is String -> {
                    val trimmed = value.trim()
                    if (trimmed.isNotEmpty()) return trimmed
                }
                is JSONObject -> {
                    val id = value.optString("id").trim()
                    if (id.isNotEmpty()) return id
                }
            }
        }
        return ""
    }

    /** defaultAntigravityTierID (auth.go): default tier, current tier, free-tier. */
    private fun defaultTierId(loadResp: JSONObject): String {
        val tiers = loadResp.optJSONArray("allowedTiers")
        if (tiers != null) {
            for (i in 0 until tiers.length()) {
                val tier = tiers.optJSONObject(i) ?: continue
                if (!tier.optBoolean("isDefault", false)) continue
                val id = tier.optString("id").trim()
                if (id.isNotEmpty()) return id
            }
        }
        loadResp.optJSONObject("currentTier")?.let { current ->
            val id = current.optString("id").trim()
            if (id.isNotEmpty()) return id
        }
        return "free-tier"
    }

    // ── Antigravity models catalog (cmd/fetch_antigravity_models) ──

    /**
     * POST `<base>/v1internal:fetchAvailableModels`, accepting both the dict
     * shape (`{"models": {id: {...}}}`) and the array shape. Tries the daily
     * endpoint first, then prod — mirrors upstream's endpoint fallback order.
     */
    suspend fun fetchAvailableModels(accessToken: String, projectId: String?): List<LLMModel> =
        withContext(Dispatchers.IO) {
            val payload = if (projectId?.isNotBlank() == true) {
                JSONObject().put("project", projectId.trim())
            } else {
                JSONObject()
            }
            val bases = listOf(DAILY_API_ENDPOINT, API_ENDPOINT)
            for (base in bases) {
                val request = Request.Builder()
                    .url("$base/$API_VERSION:fetchAvailableModels")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", requestUserAgent())
                    .build()

                val response = try { client.newCall(request).execute() } catch (_: Exception) { continue }
                val text = response.use { it.body?.string().orEmpty() }
                if (!response.isSuccessful) continue

                val json = try { JSONObject(text) } catch (_: Exception) { continue }
                val modelsField = json.opt("models") ?: continue
                val out = mutableListOf<LLMModel>()

                when (modelsField) {
                    is JSONObject -> {
                        val keys = modelsField.keys()
                        while (keys.hasNext()) {
                            val id = keys.next()
                            val meta = modelsField.optJSONObject(id)
                            val display = meta?.optString("displayName", id).orEmpty().ifEmpty { id }
                            out.add(
                                LLMModel(
                                    id = id,
                                    displayName = display,
                                    provider = "Antigravity",
                                    contextWindow = 1_048_576,
                                    supportsReasoning = true,
                                ),
                            )
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until modelsField.length()) {
                            val obj = modelsField.optJSONObject(i) ?: continue
                            val id = obj.optString("name").ifEmpty { obj.optString("id") }
                            if (id.isEmpty()) continue
                            val display = obj.optString("displayName", id).ifEmpty { id }
                            out.add(
                                LLMModel(
                                    id = id,
                                    displayName = display,
                                    provider = "Antigravity",
                                    contextWindow = 1_048_576,
                                    supportsReasoning = true,
                                ),
                            )
                        }
                    }
                }
                if (out.isNotEmpty()) return@withContext out
            }
            emptyList()
        }

    class OAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
