package com.openminis.app.provider.antigravity

import android.content.Context
import android.util.Log
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [T-antigravity-oauth] Antigravity OAuth credential store.
 *
 * Design decision (differs from upstream OpenMinis, which kept a parallel
 * `oauth_prefs` token slot): the OAuth token bundle is stored VERBATIM as the
 * provider instance's `apiKey` slot (`provider_secrets` → `apikey_<id>`).
 * Everything downstream of [com.openminis.app.provider.ProviderFactory] —
 * chat routing, model refresh, backup export, the offload worker — already
 * resolves that one slot and passes it in as "the credential". Storing the
 * token JSON there means all of those paths keep working with ZERO
 * behavioural change; the provider unwraps the JSON and refreshes silently,
 * writing the renewed bundle back into the same slot.
 *
 * Wire protocol mirrors CLIProxyAPI (github.com/router-for-me/CLIProxyAPI)
 * `internal/auth/antigravity` — the reference implementation this port
 * follows. Credentials below are the Antigravity IDE's public built-in
 * OAuth client (installed-client model, same as every CLI proxy).
 */
object AntigravityOAuthStore {
    private const val TAG = "AntigravityOAuth"

    const val CLIENT_ID =
        "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
    const val CLIENT_SECRET = "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"

    /** Loopback port the Go core binds for the OAuth redirect. */
    const val CALLBACK_PORT = 51121
    const val REDIRECT_PATH = "/oauth-callback"
    const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT$REDIRECT_PATH"

    const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo?alt=json"

    /**
     * Cloud Code Assist base URLs probed in order during project discovery.
     * Matches upstream OpenMinis `AntigravityOAuthManager.cloudCodeBaseURLs`:
     * the daily/sandbox release channel first, public prod as fallback. The
     * winning URL is persisted and pinned for chat requests.
     */
    val CLOUD_CODE_BASE_URLS = listOf(
        "https://daily-cloudcode-pa.sandbox.googleapis.com",
        "https://cloudcode-pa.googleapis.com",
    )

    /** Default when no discovery has run yet (matches the Go executor). */
    const val DEFAULT_BASE_URL = "https://daily-cloudcode-pa.sandbox.googleapis.com"

    val SCOPES: String = listOf(
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/cclog",
        "https://www.googleapis.com/auth/experimentsandconfigs",
    ).joinToString(" ")

    /** Antigravity IDE client UA (mirrors the repo's AntigravityModelsApi). */
    const val USER_AGENT = "antigravity/1.107.0 android/aarch64"

    // ── Token bundle ────────────────────────────────────────────────────

    data class TokenBundle(
        val accessToken: String,
        val refreshToken: String?,
        /** Absolute epoch-ms expiry, 0 when unknown. */
        val expireAt: Long,
    )

    /**
     * True when [stored] is an Antigravity token bundle (vs a plain API key).
     * Only shape-check — no network. An Antigravity instance whose user
     * pasted a static token manually is handled the same way: [parse]
     * returns null and the provider falls back to Bearer <raw>.
     */
    fun isTokenJson(stored: String?): Boolean {
        if (stored == null) return false
        val s = stored.trim()
        if (!s.startsWith("{")) return false
        return runCatching {
            val obj = JSONObject(s)
            obj.has("access_token") || obj.has("accessToken")
        }.getOrDefault(false)
    }

    fun parse(stored: String?): TokenBundle? {
        if (!isTokenJson(stored)) return null
        return runCatching {
            val obj = JSONObject(stored!!.trim())
            // Accept both snake_case (Google wire) and camelCase (iOS export).
            val access = obj.optString("access_token")
                .ifEmpty { obj.optString("accessToken") }
            if (access.isEmpty()) return@runCatching null
            val refresh = obj.optString("refresh_token")
                .ifEmpty { obj.optString("refreshToken") }
                .ifEmpty { null }
            val expireAt = obj.optLong("expire_at", 0L)
                .takeIf { it > 0 } ?: obj.optLong("expireAt", 0L)
            TokenBundle(access, refresh, expireAt)
        }.getOrNull()
    }

    fun serialize(bundle: TokenBundle): String = JSONObject().apply {
        put("access_token", bundle.accessToken)
        bundle.refreshToken?.let { put("refresh_token", it) }
        if (bundle.expireAt > 0) put("expire_at", bundle.expireAt)
        put("token_type", "Bearer")
        put("provider", "antigravity")
    }.toString()

    // ── Refresh ─────────────────────────────────────────────────────────

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Return an access token that is valid for the next while: pass through
     * when the bundle is fresh, refresh when inside the 4-hour window, and
     * hard-refresh when already expired. [onRefreshed] persists the renewed
     * bundle (write-back into the apiKey slot) — pass a no-op to skip.
     * Null means the credential is unusable (refresh failed while expired).
     */
    suspend fun ensureFreshToken(
        bundle: TokenBundle,
        onRefreshed: (TokenBundle) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val fourHoursMs = 4L * 3600 * 1000
        val needsRefresh = bundle.expireAt in 1..(now + fourHoursMs)
        if (!needsRefresh) return@withContext bundle.accessToken

        val refresh = bundle.refreshToken
            ?: return@withContext if (bundle.expireAt == 0L || bundle.expireAt > now) {
                bundle.accessToken // no refresh token but not expired yet
            } else null

        val renewed = refreshTokens(refresh)
        if (renewed != null) {
            onRefreshed(renewed)
            return@withContext renewed.accessToken
        }
        // Refresh failed. Usable if the token hasn't actually expired yet.
        if (bundle.expireAt == 0L || bundle.expireAt > now) bundle.accessToken else null
    }

    /** Google token-endpoint refresh. Null on any failure. */
    suspend fun refreshTokens(refreshToken: String): TokenBundle? =
        withContext(Dispatchers.IO) {
            runCatching {
                val form = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to CLIENT_ID,
                    "client_secret" to CLIENT_SECRET,
                ).entries.joinToString("&") {
                    "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
                }
                val request = Request.Builder()
                    .url(TOKEN_URL)
                    .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "token refresh failed: ${resp.code} ${sanitize(body)}")
                        return@withContext null
                    }
                    val obj = JSONObject(body)
                    val access = obj.optString("access_token")
                    if (access.isEmpty()) return@withContext null
                    val expiresIn = obj.optLong("expires_in", 0L)
                    TokenBundle(
                        accessToken = access,
                        refreshToken = obj.optString("refresh_token").ifEmpty { refreshToken },
                        expireAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
                    )
                }
            }.getOrNull()
        }

    // ── Project discovery (post-login / lazy) ───────────────────────────

    /**
     * Probe both Cloud Code base URLs with `v1internal:loadCodeAssist` until
     * one yields a project ID. Returns (projectId, baseURL) or null. On
     * "no project yet" (fresh Google accounts), falls through to the Go
     * core's onboardUser flow against the daily endpoint.
     */
    suspend fun discoverProject(accessToken: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            for (baseURL in CLOUD_CODE_BASE_URLS) {
                val projectId = loadCodeAssist(accessToken, baseURL)
                if (!projectId.isNullOrEmpty()) return@withContext projectId to baseURL
            }
            onboardUser(accessToken)?.let { pid ->
                return@withContext pid to CLOUD_CODE_BASE_URLS[0]
            }
            null
        }

    private fun loadCodeAssist(accessToken: String, baseURL: String): String? =
        runCatching {
            val request = Request.Builder()
                .url("$baseURL/v1internal:loadCodeAssist")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", USER_AGENT)
                .header("X-Client-Name", "antigravity")
                .header("X-Client-Version", "1.107.0")
                .header("Accept", "application/json")
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val obj = JSONObject(body)
                obj.optString("gcpProjectId")
                    .ifEmpty { obj.optString("cloudaicompanionProject") }
                    .ifEmpty { null }
            }
        }.getOrNull()

    /**
     * Port of CLIProxyAPI `OnboardUser`: register the account against the
     * daily control plane, polling up to 5 rounds until the long-running
     * operation reports done and carries a project ID.
     */
    private fun onboardUser(accessToken: String): String? {
        val body = JSONObject().apply {
            put("tier_id", "free-tier")
            put("metadata", JSONObject().apply {
                put("ide_type", "ANTIGRAVITY")
                put("ide_version", "1.107.0")
                put("ide_name", "antigravity")
            })
        }
        for (attempt in 1..5) {
            val result = runCatching {
                val request = Request.Builder()
                    .url("https://daily-cloudcode-pa.googleapis.com/v1internal:onboardUser")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $accessToken")
                    .header("User-Agent", USER_AGENT)
                    .header("X-Goog-Api-Client", "gl-node/18.18.2 fire/0.8.6")
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (resp.code != 200) return@runCatching null
                    val obj = JSONObject(resp.body?.string() ?: return@runCatching null)
                    if (!obj.optBoolean("done", false)) return@runCatching "" // keep polling
                    obj.optJSONObject("response")
                        ?.let { extractProject(it) }
                        .orEmpty()
                }
            }.getOrNull()
            if (result == null) return null
            if (result.isNotEmpty()) return result
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    private fun extractProject(data: JSONObject): String {
        for (key in listOf("cloudaicompanionProject", "projectId", "project")) {
            when (val v = data.opt(key)) {
                is String -> if (v.isNotBlank()) return v.trim()
                is JSONObject -> {
                    val id = v.optString("id")
                    if (id.isNotBlank()) return id.trim()
                }
            }
        }
        return ""
    }

    // ── Meta prefs (project / pinned base URL / email) ──────────────────

    private fun metaPrefs(context: Context) = EncryptedPrefsFactory.safeCreate(context, "antigravity_meta")

    fun saveMeta(context: Context, instanceId: String, projectId: String?, baseURL: String?, email: String?) {
        metaPrefs(context).edit().apply {
            projectId?.let { putString("project_$instanceId", it) }
            baseURL?.let { putString("baseurl_$instanceId", it) }
            email?.let { putString("email_$instanceId", it) }
        }.apply()
    }

    fun loadProject(context: Context?, instanceId: String): String? =
        context?.let { metaPrefs(it).getString("project_$instanceId", null) }

    fun loadBaseURL(context: Context?, instanceId: String): String? =
        context?.let { metaPrefs(it).getString("baseurl_$instanceId", null) }

    fun loadEmail(context: Context?, instanceId: String): String? =
        context?.let { metaPrefs(it).getString("email_$instanceId", null) }

    /**
     * Move meta keys + pending credential from [fromId] to [toId]. Used by
     * the Add-Provider flow: the OAuth login runs BEFORE the instance exists
     * (against the fixed pending id), and Save migrates everything onto the
     * freshly-created instance id.
     */
    fun migrateMeta(context: Context, fromId: String, toId: String) {
        val prefs = metaPrefs(context)
        val edit = prefs.edit()
        for (key in listOf("project", "baseurl", "email")) {
            prefs.getString("${key}_$fromId", null)?.let {
                edit.putString("${key}_$toId", it)
                edit.remove("${key}_$fromId")
            }
        }
        edit.apply()
    }

    private fun sanitize(body: String, maxLen: Int = 200): String =
        Regex("\"(access_token|refresh_token|client_secret)\"\\s*:\\s*\"[^\"]*\"")
            .replace(body) { "\"${it.groupValues[1]}\":\"***\"" }
            .take(maxLen)
}
