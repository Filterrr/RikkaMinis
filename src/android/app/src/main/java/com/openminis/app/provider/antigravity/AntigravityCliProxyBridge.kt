package com.openminis.app.provider.antigravity

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * CLIProxyAPI management-API bridge — "EasyCLIProxyAPI, embedded".
 *
 * EasyCLIProxyAPI (github.com/router-for-me/EasyCLIProxyAPI) is a desktop
 * console that drives a [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI)
 * core over its management API. Its Antigravity OAuth page is a thin client:
 * it never touches Google itself — it asks the core for the authorization
 * URL, lets the core own the loopback dance, polls until the core reports
 * the session done, and (when the browser redirect cannot reach the client)
 * pastes the callback URL back to the core.
 *
 * This object is the Android port of that thin client (main →
 * src-tauri/src/management_api.rs, UI → src/pages/ManagementPages.tsx):
 *
 *   1. [fetchAuthUrl]      GET  /v0/management/antigravity-auth-url?is_webui=true
 *                          → {"url": ..., "state": ...}  (upstream starts a
 *                          51121 callback forwarder because is_webui=true)
 *   2. [pollAuthStatus]    GET  /v0/management/get-auth-status?state=...
 *                          → {"status": "ok" | "wait" | "error", "error": ...}
 *   3. [submitCallback]    POST /v0/management/oauth-callback
 *                          {"provider":"antigravity","redirect_url": ...}
 *   4. [fetchLatestAntigravityCredential]
 *                          GET /v0/management/auth-files → pick the newest
 *                          antigravity entry → GET /v0/management/auth-files/
 *                          download?name=... → full metadata JSON
 *                          (type/access_token/refresh_token/expires_in/
 *                          timestamp/expired/email/project_id).
 *
 * The core performs the actual OAuth round-trip exactly as its native
 * `/antigravity/callback` route does: it exchanges the code, fetches
 * userinfo + project id, and saves `antigravity-<email>.json` into its
 * auth-dir. Step 4 then mirrors the saved credential into
 * [AntigravityCredentialStore] so RikkaMinis' provider runtime (a faithful
 * port of the same upstream) uses the very tokens the core holds.
 *
 * Auth model (management_api.rs `management_authorization`): requests carry
 * `Authorization: Bearer <plaintext secret>` when the user configured one.
 * Hashed secret keys (bcrypt/argon2/…) cannot authenticate — the plaintext
 * is not recoverable — mirrored from EasyCLIProxyAPI
 * core_config/settings.rs `is_hashed_management_secret_key` in
 * [Config.isHashedSecret].
 */
object AntigravityCliProxyBridge {

    private const val TAG = "AntigravityCliBridge"

    const val PREFS_NAME = "cliproxy_bridge_prefs"
    const val PREF_HOST = "host"
    const val PREF_PORT = "port"
    const val PREF_TLS = "tls"
    const val PREF_SECRET = "secret"

    /** Upstream CLIProxyAPI default port (EasyCLIProxyAPI core default). */
    const val DEFAULT_PORT = 8317

    private const val MANAGEMENT_BASE = "/v0/management"
    private const val JSON_MEDIA = "application/json"

    // ── Connection config (persisted per device, shared by all instances) ──

    data class Config(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val secret: String,
    ) {
        val origin: String
            get() = "${if (tls) "https" else "http"}://$host:$port"

        val hasUsableSecret: Boolean
            get() = secret.isNotBlank() && !isHashedSecret(secret)

        companion object {
            /**
             * EasyCLIProxyAPI is_hashed_management_secret_key parity: a
             * hashed key cannot be replayed as a Bearer token.
             */
            fun isHashedSecret(raw: String): Boolean {
                val v = raw.trim()
                return v.startsWith("\$2a\$") ||
                    v.startsWith("\$2b\$") ||
                    v.startsWith("\$2y\$") ||
                    v.startsWith("\$argon2") ||
                    v.startsWith("\$scrypt\$") ||
                    v.startsWith("bcrypt:") ||
                    v.startsWith("argon2:") ||
                    v.startsWith("argon2id:") ||
                    v.startsWith("sha256:") ||
                    v.startsWith("sha512:")
            }

            fun sanitize(host: String, portText: String, tls: Boolean, secret: String): Config? {
                val h = host.trim().trimEnd('/')
                if (h.isEmpty()) return null
                val p = portText.trim().toIntOrNull() ?: return null
                if (p !in 1..65535) return null
                return Config(host = h, port = p, tls = tls, secret = secret.trim())
            }
        }
    }

    fun loadConfig(context: Context): Config {
        val p = prefs(context)
        return Config(
            host = p.getString(PREF_HOST, "").orEmpty(),
            port = p.getInt(PREF_PORT, DEFAULT_PORT),
            tls = p.getBoolean(PREF_TLS, false),
            secret = p.getString(PREF_SECRET, "").orEmpty(),
        )
    }

    fun saveConfig(context: Context, config: Config) {
        prefs(context).edit()
            .putString(PREF_HOST, config.host)
            .putInt(PREF_PORT, config.port)
            .putBoolean(PREF_TLS, config.tls)
            .putString(PREF_SECRET, config.secret)
            .apply()
        AppLogger.info(TAG, "saved CLIProxyAPI bridge config (host=${config.host}, port=${config.port}, tls=${config.tls}, secret=${config.hasUsableSecret})")
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Response models ──

    /** `antigravity-auth-url` response (OAuthStartApiResponse in management_api.rs). */
    data class AuthUrlResponse(val url: String, val state: String)

    /** `get-auth-status` response: status ∈ {"ok", "wait", "error"}. */
    data class AuthStatus(val status: String, val error: String?)

    /**
     * Normalized antigravity credential pulled out of the core's auth-dir —
     * field names match upstream's saved metadata 1:1 (and
     * AntigravityCredentialStore's exported shape).
     */
    data class Credential(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
        val email: String?,
        val projectId: String?,
        val timestamp: Long,
    )

    class BridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ── HTTP plumbing ──

    /**
     * Cookie jar is required: with `is_webui=true` the core 302-redirects
     * the 51121 loopback forwarder to management endpoints; some core
     * builds set session cookies along that chain. Mirrors the desktop
     * GUI's reqwest client behaviour (which keeps cookies by default).
     */
    private val cookieJar = object : CookieJar {
        private val store = LinkedHashMap<String, List<Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isNotEmpty()) store[url.host] = cookies
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun Request.Builder.auth(config: Config): Request.Builder =
        if (config.hasUsableSecret) header("Authorization", "Bearer ${config.secret}") else this

    // ── Step 1: authorization URL (starts the core-side forwarder) ──

    suspend fun fetchAuthUrl(config: Config): AuthUrlResponse = withContext(Dispatchers.IO) {
        val url = "${config.origin}$MANAGEMENT_BASE/antigravity-auth-url?is_webui=true"
        val request = Request.Builder().url(url).get().auth(config).build()
        val json = executeJson(request, "antigravity-auth-url")
        val error = json.optString("error").ifBlank { json.optString("error_message") }
        if (error.isNotBlank()) throw BridgeException("内核返回错误：$error")
        val authUrl = json.optString("url").trim()
        if (authUrl.isEmpty()) throw BridgeException("内核未返回 OAuth 授权链接")
        AuthUrlResponse(
            url = authUrl,
            state = json.optString("state").trim(),
        )
    }

    // ── Step 2: poll the session state (1 Hz upstream) ──

    suspend fun pollAuthStatus(config: Config, state: String): AuthStatus = withContext(Dispatchers.IO) {
        val url = "${config.origin}$MANAGEMENT_BASE/get-auth-status?state=" +
            java.net.URLEncoder.encode(state, "UTF-8")
        val request = Request.Builder().url(url).get().auth(config).build()
        val json = executeJson(request, "get-auth-status")
        AuthStatus(
            status = json.optString("status", "wait").ifBlank { "wait" },
            error = json.optString("error").ifBlank { null },
        )
    }

    // ── Step 3: paste-path callback submission (unauthenticated upstream) ──

    suspend fun submitCallback(config: Config, redirectUrl: String) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("provider", "antigravity")
            .put("redirect_url", redirectUrl.trim())
        val request = Request.Builder()
            .url("${config.origin}$MANAGEMENT_BASE/oauth-callback")
            .post(body.toString().toRequestBody(JSON_MEDIA.toMediaType()))
            .auth(config)
            .build()
        val json = executeJson(request, "oauth-callback")
        val error = json.optString("error").ifBlank { null }
        if (error != null) throw BridgeException("内核拒绝回调：$error")
    }

    // ── Step 4: credential sync (auth-files list → download) ──

    /**
     * Returns the newest antigravity credential the core holds, or null when
     * the core has none yet. Two calls: the list narrows candidates, the
     * download returns the full metadata JSON (the list entry may redact
     * secrets, so the downloaded file is authoritative).
     */
    suspend fun fetchLatestAntigravityCredential(config: Config): Credential? = withContext(Dispatchers.IO) {
        val listRequest = Request.Builder()
            .url("${config.origin}$MANAGEMENT_BASE/auth-files")
            .get()
            .auth(config)
            .build()
        val listJson = executeJson(listRequest, "auth-files")
        val files = listJson.optJSONArray("files") ?: return@withContext null

        var newestName: String? = null
        var newestTs = Long.MIN_VALUE
        for (i in 0 until files.length()) {
            val entry = files.optJSONObject(i) ?: continue
            val provider = entry.optString("provider", entry.optString("type")).trim().lowercase()
            if (provider != "antigravity") continue
            val name = entry.optString("name").trim()
            if (name.isEmpty()) continue
            val ts = normalizeTimestamp(entry.opt("timestamp"))
            if (ts >= newestTs) {
                newestTs = ts
                newestName = name
            }
        }
        newestName ?: return@withContext null

        val downloadUrl = "${config.origin}$MANAGEMENT_BASE/auth-files/download?name=" +
            java.net.URLEncoder.encode(newestName, "UTF-8")
        val downloadRequest = Request.Builder()
            .url(downloadUrl)
            .get()
            .auth(config)
            .build()
        val downloadJson = executeJson(downloadRequest, "auth-files/download")
        credentialFromMetadata(downloadJson)
    }

    /**
     * Parses an upstream auth-file metadata JSON (or a list entry carrying
     * the same fields) into a [Credential]. Null when no access token.
     */
    fun credentialFromMetadata(json: JSONObject): Credential? {
        val access = json.optString("access_token").trim()
        if (access.isEmpty()) return null
        return Credential(
            accessToken = access,
            refreshToken = json.optString("refresh_token").trim(),
            expiresIn = json.optLong("expires_in", 3600L),
            email = json.optString("email").trim().ifBlank { null },
            projectId = json.optString("project_id").trim().ifBlank { null },
            timestamp = normalizeTimestamp(json.opt("timestamp")),
        )
    }

    // ── Connectivity probe for the settings UI ("保存并测试") ──

    data class TestResult(val ok: Boolean, val message: String)

    /**
     * Ping the core, then verify the management key when one is configured.
     * `get-auth-status` without a state answers `{"status":"ok"}` and is the
     * cheapest core-liveness probe; `config` behind Bearer auth proves the
     * key (401/403 otherwise).
     */
    suspend fun testConnection(config: Config): TestResult = withContext(Dispatchers.IO) {
        try {
            val pingRequest = Request.Builder()
                .url("${config.origin}$MANAGEMENT_BASE/get-auth-status")
                .get()
                .build()
            client.newCall(pingRequest).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext TestResult(
                        ok = false,
                        message = "内核可达但管理接口不可用（HTTP ${resp.code}）。请在内核配置管理密钥。",
                    )
                }
            }
            if (!config.hasUsableSecret) {
                return@withContext TestResult(
                    ok = false,
                    message = "内核可达，但尚未配置可用的管理密钥（哈希密钥无法用于桥接）。",
                )
            }
            val authedRequest = Request.Builder()
                .url("${config.origin}$MANAGEMENT_BASE/config")
                .get()
                .auth(config)
                .build()
            client.newCall(authedRequest).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    TestResult(ok = false, message = "内核可达，但管理密钥未通过校验（HTTP ${resp.code}）。")
                } else if (!resp.isSuccessful) {
                    TestResult(ok = false, message = "内核可达，但管理接口返回 HTTP ${resp.code}。")
                } else {
                    TestResult(ok = true, message = "内核可达，管理密钥有效。")
                }
            }
        } catch (e: Exception) {
            TestResult(ok = false, message = "无法连接内核：${e.message?.take(160) ?: "网络错误"}")
        }
    }

    // ── internals ──

    private suspend fun executeJson(request: Request, what: String): JSONObject = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw BridgeException("$what: 连接内核失败：${e.message ?: "网络错误"}", e)
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw BridgeException("$what: 内核返回 HTTP ${resp.code}：${text.take(200)}")
            }
            try {
                JSONObject(text)
            } catch (e: Exception) {
                throw BridgeException("$what: 响应不是合法 JSON", e)
            }
        }
    }

    /** Core timestamps may arrive in seconds or millis — normalize to millis. */
    private fun normalizeTimestamp(raw: Any?): Long = when (raw) {
        is Number -> {
            val v = raw.toLong()
            if (v in 1..999_999_999_999L) v * 1000 else v
        }
        is String -> raw.trim().toLongOrNull()?.let { if (it in 1..999_999_999_999L) it * 1000 else it } ?: 0L
        else -> 0L
    }
}
