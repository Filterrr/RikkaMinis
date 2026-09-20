package com.openminis.app.provider.workbuddy

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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP client for the CodeBuddy / WorkBuddy backend.
 *
 * Direct port of WorkBuddy2API Native's `NativeCore`: same routes, same
 * headers, same envelope unwrapping. Nothing here is WorkBuddy-APK-specific —
 * the app is only a client of these public routes, so RikkaMinis talks to the
 * upstream service itself instead of driving the other app.
 *
 * ### Envelope
 * Every JSON response may carry `{"code": <n>, "message": "...", "data": {...}}`.
 * `code` absent or `0` means success; anything else is an error whose text
 * belongs to `message`/`msg`. Some routes double-wrap (`data.data`), which
 * [unwrap] collapses.
 *
 * ### Authentication
 * Authenticated calls send `Authorization: Bearer <accessToken>` plus the
 * account identity headers the backend uses for tenant routing:
 * `X-User-Id`, `X-Enterprise-Id`, `X-Tenant-Id`, `X-Domain`.
 *
 * This class holds no state: tokens are supplied per call so it stays usable
 * from both the app process and the `:modelservice` worker.
 */
object WorkBuddyApi {

    private const val TAG = "WorkBuddyApi"

    /** Sentinel for "the envelope carried no `code` field at all". */
    private const val CODE_UNSET = Int.MIN_VALUE

    /**
     * Envelope code the backend returns while an OAuth login is still in
     * flight (`11217: login ing...`). Verified live against both tenants —
     * it is a control-flow signal, not an error.
     */
    private const val PENDING_LOGIN_CODE = 11217

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /**
     * Timeouts mirror upstream NativeCore: a 20s connect, generous read for
     * long generations, 60s write. `readTimeout` is deliberately long because
     * the chat route streams over minutes for large deliverables.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
            .dns(com.openminis.app.network.NetworkMonitor.buildDns())
            .build()
    }

    // ───────────────────────────── envelopes ─────────────────────────────

    /**
     * Resolve the backend origin for a call.
     *
     * [override] (an instance's `customBaseURL`) wins when present so an
     * enterprise proxy or mirror can be used; otherwise the region's own
     * backend is addressed. The override is taken verbatim — it is expected to
     * include any version path (e.g. `https://proxy.example.com/v2`), matching
     * how the Add-Provider form describes the field.
     */
    internal fun resolveBackend(
        region: WorkBuddyConstants.Region,
        override: String? = null,
    ): String = override?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: region.backend

    /**
     * Unwrap the `{code,message,data}` envelope. Throws [IOException] when the
     * upstream reports a non-zero code, so callers see the backend's own
     * message rather than an empty object. Mirrors upstream `unwrap`.
     */
    internal fun unwrap(root: JSONObject): JSONObject {
        if (root.has("code")) {
            val code = root.optInt("code", -1)
            if (code != WorkBuddyConstants.CODE_OK) {
                val message = root.optString("message", root.optString("msg", "上游请求失败"))
                throw IOException(message)
            }
        }
        val data = root.optJSONObject("data")
        return data?.optJSONObject("data")
            ?: data
            ?: root
    }

    internal fun unwrapArray(root: JSONObject, field: String): JSONArray {
        val payload = unwrap(root)
        return payload.optJSONArray(field)
            ?: root.optJSONArray(field)
            ?: JSONArray()
    }

    // ───────────────────────────── headers ─────────────────────────────

    /**
     * Headers for a call made *before* the user is authenticated (OAuth
     * bootstrap). The `X-No-*` set tells the backend explicitly that this
     * request carries no identity, which is required — omitting them makes
     * the backend reject with an auth error instead of serving the route.
     */
    private fun noAuthHeaders(region: WorkBuddyConstants.Region): Map<String, String> = mapOf(
        "X-No-Authorization" to "true",
        "X-No-User-Id" to "true",
        "X-No-Enterprise-Id" to "true",
        "X-No-Department-Info" to "true",
        "X-Domain" to region.defaultDomain,
        "User-Agent" to WorkBuddyConstants.CLIENT_UA,
    )

    /**
     * Headers for an authenticated call. [enterpriseId] falls back to the
     * empty account value when the tenant has none (personal accounts), which
     * the backend accepts.
     */
    private fun authHeaders(tokens: WorkBuddyCredentialStore.Tokens): Map<String, String> {
        val enterpriseId = tokens.enterpriseId
        val domain = tokens.domain.ifBlank {
            WorkBuddyConstants.Region.from(tokens.region).defaultDomain
        }
        return mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Authorization" to "Bearer ${tokens.accessToken}",
            "X-User-Id" to tokens.uid,
            "X-Enterprise-Id" to enterpriseId,
            "X-Tenant-Id" to enterpriseId,
            "X-Domain" to domain,
            "User-Agent" to WorkBuddyConstants.CLIENT_UA,
        )
    }

    // ───────────────────────────── transport ─────────────────────────────

    private fun buildRequest(
        url: String,
        method: String,
        body: JSONObject?,
        headers: Map<String, String>,
    ): Request {
        val builder = Request.Builder().url(url)
        for ((k, v) in headers) {
            if (v.isNotEmpty()) builder.header(k, v)
        }
        if (method.equals("GET", ignoreCase = true)) {
            builder.get()
        } else {
            builder.method(method.uppercase(), (body ?: JSONObject()).toString().toRequestBody(JSON_MEDIA))
        }
        return builder.build()
    }

    /**
     * Execute and decode a JSON request. [allowHttpError] keeps the body of a
     * non-2xx response (needed by the OAuth poll, where a "not yet authorized"
     * answer is a normal control-flow signal rather than a failure).
     */
    internal fun executeJson(request: Request, allowHttpError: Boolean = false): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!allowHttpError && !response.isSuccessful) {
                // Surface the upstream message when the body is JSON, so the
                // user sees why (quota, disabled model, …) instead of "HTTP 4xx".
                val detail = runCatching {
                    val obj = JSONObject(text)
                    val inner = obj.optJSONObject("error") ?: obj
                    inner.optString("message", inner.optString("msg", text))
                }.getOrNull() ?: text
                throw IOException("HTTP ${response.code}: ${detail.take(240)}")
            }
            return runCatching { JSONObject(text) }.getOrElse {
                throw IOException("服务器返回非 JSON：${text.take(160)}")
            }
        }
    }

    // ───────────────────────────── OAuth ─────────────────────────────

    /** An authorized session: the polls a token, [authUrl] is where the user goes. */
    data class OAuthSession(
        val region: WorkBuddyConstants.Region,
        val state: String,
        val authUrl: String,
    )

    /** A successfully minted credential. */
    data class AuthResult(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val uid: String,
        val enterpriseId: String,
        val nickname: String?,
        val domain: String,
        val region: String,
    )

    /**
     * Step 1 of the login flow: ask the backend for a `state` plus the URL the
     * user must visit. Mirrors upstream `beginOAuth`.
     */
    suspend fun beginOAuth(region: WorkBuddyConstants.Region): OAuthSession = withContext(Dispatchers.IO) {
        val request = buildRequest(
            "${region.backend}/v2/plugin/auth/state?platform=CLI",
            "POST",
            JSONObject(),
            noAuthHeaders(region),
        )
        val data = unwrap(executeJson(request))
        val state = data.optString("state")
        val authUrl = data.optString("authUrl")
        if (state.isBlank() || authUrl.isBlank()) {
            throw WorkBuddyAuthException("${region.label}登录接口未返回 state/authUrl")
        }
        AppLogger.info(TAG, "OAuth session started for ${region.id}")
        OAuthSession(region, state, authUrl)
    }

    /**
     * Step 2: poll for the token that the browser round-trip yields.
     *
     * While the user is still on the consent page the backend answers
     * `200 {"code":11217,"msg":"…login ing…"}` — a *pending* signal, not a
     * failure. It is decoded explicitly here (rather than falling out of the
     * generic error path) so a genuine rejection can never be mistaken for
     * "keep waiting", which would burn the full five-minute deadline on a
     * session that is already dead.
     *
     * Returns null while pending; a real error propagates as an exception.
     */
    suspend fun pollOAuth(session: OAuthSession): AuthResult? = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(session.state, "UTF-8")
        val request = buildRequest(
            "${session.region.backend}/v2/plugin/auth/token?state=$encoded",
            "GET",
            null,
            noAuthHeaders(session.region),
        )
        val raw = try {
            executeJson(request, allowHttpError = true)
        } catch (e: Exception) {
            // Transport-level hiccup: the user may still be authorizing, so
            // report "not ready" and let the caller's deadline decide.
            AppLogger.info(TAG, "OAuth poll transport error: ${e.message}")
            return@withContext null
        }

        val code = if (raw.has("code")) raw.optInt("code", CODE_UNSET) else WorkBuddyConstants.CODE_OK
        if (code == PENDING_LOGIN_CODE) return@withContext null
        if (code != WorkBuddyConstants.CODE_OK) {
            // Authoritative rejection — surface it instead of polling on.
            throw WorkBuddyAuthException(
                raw.optString("message", raw.optString("msg", "登录失败（code=$code）")),
            )
        }

        val token = unwrap(raw)
        val access = token.optString("accessToken")
        if (access.isBlank()) return@withContext null

        val domain = token.optString("domain").ifBlank { session.region.defaultDomain }
        val expiresAt = resolveExpiry(token)
        // The account block carries the tenant identity every later request
        // must echo; it is folded in here so the caller stores one bundle.
        val account = token.optJSONObject("account") ?: JSONObject()
        AuthResult(
            accessToken = access,
            refreshToken = token.optString("refreshToken"),
            expiresAt = expiresAt,
            uid = account.optString("uid").ifBlank { token.optString("uid") },
            enterpriseId = account.optString("enterpriseId").ifBlank { token.optString("enterpriseId") },
            nickname = account.optString("nickname").takeIf { it.isNotBlank() },
            domain = domain,
            region = session.region.id,
        )
    }

    /**
     * Rotate an expired access token. Mirrors upstream `refreshToken`: the
     * refresh token rides in its own header (`X-Refresh-Token`) alongside the
     * `X-Auth-Refresh-Source: plugin` marker that selects the CLI refresh
     * policy on the backend.
     */
    suspend fun refreshToken(current: WorkBuddyCredentialStore.Tokens): WorkBuddyCredentialStore.Tokens =
        withContext(Dispatchers.IO) {
            if (current.refreshToken.isBlank()) {
                throw WorkBuddyAuthException("账号 ${current.uid} 缺少 refreshToken")
            }
            val region = WorkBuddyConstants.Region.from(current.region)
            val headers = authHeaders(current).toMutableMap()
            headers["X-Refresh-Token"] = current.refreshToken
            headers["X-Auth-Refresh-Source"] = "plugin"

            val request = buildRequest(
                "${region.backend}/v2/plugin/auth/token/refresh",
                "POST",
                JSONObject(),
                headers,
            )
            val fresh = try {
                unwrap(executeJson(request))
            } catch (e: Exception) {
                AppLogger.warning(TAG, "token refresh failed for ${current.uid}: ${e.message}")
                throw WorkBuddyAuthException("刷新登录失败：${e.message?.take(200) ?: "网络错误"}")
            }
            val access = fresh.optString("accessToken")
            if (access.isBlank()) {
                throw WorkBuddyAuthException("刷新登录响应缺少 accessToken")
            }
            // The response may omit identity fields; carry the previous ones
            // forward so the account stays addressable.
            current.copy(
                accessToken = access,
                refreshToken = fresh.optString("refreshToken").ifBlank { current.refreshToken },
                expiresAt = resolveExpiry(fresh, current.expiresAt),
                domain = fresh.optString("domain").ifBlank { current.domain },
            )
        }

    /**
     * Normalize `expiresAt` from a token payload. Upstream stores seconds in
     * some builds and milliseconds in others (`normalizedExpiryMillis`), so a
     * small value is read as seconds. Falls back to a conservative 12h window
     * when the backend reports nothing at all.
     */
    internal fun resolveExpiry(payload: JSONObject, fallback: Long = 0L): Long {
        val raw = payload.optLong("expiresAt", 0L)
        if (raw > 0L) {
            return if (raw < 10_000_000_000L) raw * 1000L else raw
        }
        val expiresIn = payload.optLong("expiresIn", 0L)
        if (expiresIn > 0L) return System.currentTimeMillis() + expiresIn * 1000L
        return if (fallback > System.currentTimeMillis()) {
            fallback
        } else {
            System.currentTimeMillis() + 12 * 60 * 60 * 1000L
        }
    }

    // ───────────────────────────── catalog ─────────────────────────────

    /**
     * Live model catalog for one account (`GET /console/enterprises/personal/models`).
     *
     * The backend returns a `catalog` object whose `models` array carries the
     * account's entitled models with capability flags, plus an `agents` array
     * (a second naming axis — `cli` clients see the models the CLI is allowed
     * to call). Both are folded into one list, mirroring upstream
     * `catalogToModels` + `mergeModels`.
     */
    suspend fun fetchModels(
        tokens: WorkBuddyCredentialStore.Tokens,
        /**
         * Instance `customBaseURL`, when the user routes the data plane
         * through an enterprise proxy or mirror. Sign-in always goes to the
         * region's own host (an interactive browser OAuth cannot be completed
         * through a plain data-plane proxy), so only catalog + chat honour it.
         */
        backendOverride: String? = null,
    ): List<LLMModel> =
        withContext(Dispatchers.IO) {
            val region = WorkBuddyConstants.Region.from(tokens.region)
            val backend = resolveBackend(region, backendOverride)
            val headers = authHeaders(tokens).toMutableMap()
            // The catalog route is served from the web console, so it expects
            // browser-looking origin headers rather than the mobile client UA.
            headers["Origin"] = backend
            headers["Referer"] = "$backend/"
            headers["User-Agent"] = WorkBuddyConstants.BROWSER_UA

            val request = buildRequest(
                "$backend/console/enterprises/personal/models",
                "GET",
                null,
                headers,
            )
            val payload = unwrap(executeJson(request))
            val models = payload.optJSONObject("catalog")?.optJSONArray("models")
                ?: payload.optJSONArray("models")
                ?: throw IOException("${region.label}模型响应缺少 models")

            val agents = payload.optJSONArray("agents") ?: JSONArray()
            toModels(models, agents)
        }

    /**
     * Fold the raw catalog into [LLMModel]s.
     *
     * Capability mapping follows upstream: `supportsToolCall` / `supportsImages`
     * / `supportsReasoning` gate what the model picker offers, `maxInputTokens`
     * is the context window and `maxOutputTokens` the output ceiling. Image-
     * and video-generation entries are dropped ([WorkBuddyConstants.isChatCapable])
     * because they cannot serve a chat request.
     */
    internal fun toModels(catalog: JSONArray, agents: JSONArray = JSONArray()): List<LLMModel> {
        val recommended = linkedSetOf<String>()
        // `agents` names the models the CLI is explicitly allowed to call;
        // union them in so an entitlement that only shows up on that axis is
        // still selectable.
        for (i in 0 until agents.length()) {
            val agent = agents.optJSONObject(i) ?: continue
            if (!agent.optString("name").equals("cli", ignoreCase = true)) continue
            val list = agent.optJSONArray("models") ?: continue
            for (j in 0 until list.length()) {
                val entry = list.opt(j)
                val id = when (entry) {
                    is JSONObject -> entry.optString("modelId", entry.optString("id"))
                    null -> null
                    else -> entry.toString()
                }
                if (!id.isNullOrBlank()) recommended.add(id)
            }
        }

        val out = LinkedHashMap<String, LLMModel>()
        for (i in 0 until catalog.length()) {
            val item = catalog.optJSONObject(i) ?: continue
            // Disabled entries are kept in the payload for accounting but must
            // not be offered.
            if (item.optBoolean("disabled", false)) continue
            val id = item.optString("modelId", item.optString("id"))
            if (id.isBlank()) continue
            if (!WorkBuddyConstants.isChatCapable(id)) continue
            if (out.containsKey(id)) continue

            // `credits` comes as "x0.79 credits" — keep the numeric part for
            // display-free cost annotations (mirrors upstream parseCredits).
            val display = item.optString("name").takeIf { it.isNotBlank() } ?: id
            val supportsImages = item.optBoolean("supportsImages", false)
            val inputModalities = buildList {
                add("text")
                if (supportsImages) add("image")
            }
            out[id] = LLMModel(
                id = id,
                displayName = display,
                provider = "WorkBuddy",
                contextWindow = item.optInt("maxInputTokens", 0).takeIf { it > 0 },
                maxOutputTokens = item.optInt("maxOutputTokens", 0).takeIf { it > 0 },
                // Absent flag = unknown, not "unsupported": reporting null
                // lets the thinking-level catalog fall back to its own rule
                // instead of hard-disabling the toggle.
                supportsReasoning = if (item.has("supportsReasoning")) {
                    item.optBoolean("supportsReasoning", false)
                } else {
                    null
                },
                inputModalities = inputModalities,
                outputModalities = listOf("text"),
            )
        }
        return out.values.toList()
    }

    /**
     * Fallback list used before the first successful catalog fetch. The
     * bundled snapshot only exists for the international tenant (upstream
     * ships `codebuddy-international-models.json`); domestic accounts get an
     * empty list rather than a wrong one, because their entitlement set is
     * tenant-specific and guessing would offer models the account cannot call.
     */
    fun bundledModels(region: WorkBuddyConstants.Region): List<LLMModel> =
        if (region == WorkBuddyConstants.Region.INTERNATIONAL) {
            WorkBuddyConstants.BUNDLED_INTERNATIONAL
        } else {
            emptyList()
        }
}
