package com.openminis.app.provider.antigravity

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Secure credential storage + auto-refresh for Antigravity OAuth tokens.
 *
 * Mirrors CLIProxyAPI's auth-file metadata shape (`type`, `access_token`,
 * `refresh_token`, `expires_in`, `timestamp`, `expired`, `email`,
 * `project_id`) so exports stay interoperable, but persists through
 * [EncryptedPrefsFactory] — the same store the app already uses for API
 * keys (`apikey_<instanceId>`).
 *
 * Refresh is single-flighted per process (Mutex) exactly like upstream's
 * singleflight group, so N concurrent chat streams share one rotation.
 */
object AntigravityCredentialStore {

    private const val TAG = "AntigravityCredStore"
    private const val FILE = "antigravity_oauth_secrets"

    const val KEY_ACCESS = "access_token"
    const val KEY_REFRESH = "refresh_token"
    const val KEY_EXPIRES_IN = "expires_in"
    const val KEY_TIMESTAMP = "timestamp"      // epoch ms — upstream parity
    const val KEY_EXPIRED = "expired"          // RFC3339 — upstream parity
    const val KEY_EMAIL = "email"
    const val KEY_PROJECT_ID = "project_id"
    const val KEY_TYPE = "type"
    private const val VALUE_TYPE = "antigravity"

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private val refreshMutex = Mutex()

    /**
     * [fix-antigravity-refresh-failure-classify] Probe for the most recent
     * refresh outcome, read by AntigravityProvider's 401 self-heal path to
     * distinguish "transient network failure — surface NetworkError" from
     * "credential rejected — surface re-login". Null after a successful
     * refresh. Instance-level because the store is a singleton.
     */
    @Volatile
    var lastRefreshFailure: RefreshFailure? = null
        internal set // internal: AntigravityProviderRetryTest injects outcomes directly

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: buildPrefs(context).also { prefsRef = it }
        }

    /**
     * [fix-encrypted-prefs-wipe-multiprocess] Process-aware store access.
     * EncryptedSharedPreferences is single-process (per-process Android
     * Keystore). In the :modelservice worker process create() cannot decrypt
     * the app process's keyset — and the factory's old self-healing path
     * responded to that by WIPING the store, destroying freshly OAuthed
     * credentials. The worker therefore opens the store READ-ONLY (no wipe
     * on failure, empty in-memory fallback) and resolves tokens through the
     * inline oauth_access_token carried in the request JSON.
     */
    private fun buildPrefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        val processName = com.openminis.app.provider.ProviderBoundary.currentProcessName()
            ?: java.io.File("/proc/self/cmdline").readText().trim().trimEnd('\u0000')
        val isMainProcess = processName == appContext.packageName
        return if (isMainProcess) {
            EncryptedPrefsFactory.safeCreate(appContext, FILE)
        } else {
            android.util.Log.i(TAG, "non-main process ('$processName') opens antigravity credential store READ-ONLY")
            EncryptedPrefsFactory.safeCreateReadOnly(appContext, FILE)
        }
    }

    // -- key namespacing: one credential per provider instance --
    //
    // [T-antigravity-credential-pool] `slot` addresses one OAuth credential
    // of a multi-account instance. Slot 0 keeps the historical key shape
    // (`field::instanceId`) so every pre-existing store survives untouched;
    // additional accounts land under `field::instanceId#s<i>` — the same
    // slots the T-multi-api-key rotation machinery already addresses via
    // `apikey_<id>_<i>` markers in ProviderRepository.

    private fun key(instanceId: String, field: String, slot: Int = 0): String =
        if (slot <= 0) "$field::$instanceId" else "$field::$instanceId#s$slot"

    fun loadTokens(context: Context, instanceId: String, slot: Int = 0): AntigravityOAuth.Tokens? {
        val p = prefs(context)
        val access = p.getString(key(instanceId, KEY_ACCESS, slot), null) ?: return null
        if (access.isEmpty()) return null
        return AntigravityOAuth.Tokens(
            accessToken = access,
            refreshToken = p.getString(key(instanceId, KEY_REFRESH, slot), null).orEmpty(),
            expiresIn = p.getLong(key(instanceId, KEY_EXPIRES_IN, slot), 3600L),
            tokenType = null,
        )
    }

    fun loadEmail(context: Context, instanceId: String, slot: Int = 0): String? =
        prefs(context).getString(key(instanceId, KEY_EMAIL, slot), null)?.takeIf { it.isNotEmpty() }

    fun loadProjectId(context: Context, instanceId: String, slot: Int = 0): String? =
        prefs(context).getString(key(instanceId, KEY_PROJECT_ID, slot), null)?.takeIf { it.isNotEmpty() }

    fun saveTokens(
        context: Context,
        instanceId: String,
        tokens: AntigravityOAuth.Tokens,
        email: String?,
        projectId: String?,
        slot: Int = 0,
    ) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putString(key(instanceId, KEY_TYPE, slot), VALUE_TYPE)
            .putString(key(instanceId, KEY_ACCESS, slot), tokens.accessToken)
            .putString(key(instanceId, KEY_REFRESH, slot), tokens.refreshToken)
            .putLong(key(instanceId, KEY_EXPIRES_IN, slot), tokens.expiresIn)
            .putLong(key(instanceId, KEY_TIMESTAMP, slot), now)
            .putString(key(instanceId, KEY_EXPIRED, slot), rfc3339(now + tokens.expiresIn * 1000))
            .putString(key(instanceId, KEY_EMAIL, slot), email.orEmpty())
            .putString(key(instanceId, KEY_PROJECT_ID, slot), projectId.orEmpty())
            .commit()
        AppLogger.info(TAG, "saved antigravity tokens for $instanceId slot=$slot (email=${email != null}, projectId=${projectId != null})")
    }

    fun updateEmail(context: Context, instanceId: String, email: String, slot: Int = 0) {
        prefs(context).edit().putString(key(instanceId, KEY_EMAIL, slot), email).commit()
    }

    fun updateProjectId(context: Context, instanceId: String, projectId: String, slot: Int = 0) {
        prefs(context).edit().putString(key(instanceId, KEY_PROJECT_ID, slot), projectId).commit()
    }

    /**
     * [T-antigravity-credential-pool] Clears the given slot; [clearAll]
     * walks the declared credential count so multi-account teardown never
     * strands encrypted siblings (mirrors deleteAllApiKeys semantics).
     */
    fun clear(context: Context, instanceId: String, slot: Int = 0) {
        val p = prefs(context)
        p.edit()
            .remove(key(instanceId, KEY_TYPE, slot))
            .remove(key(instanceId, KEY_ACCESS, slot))
            .remove(key(instanceId, KEY_REFRESH, slot))
            .remove(key(instanceId, KEY_EXPIRES_IN, slot))
            .remove(key(instanceId, KEY_TIMESTAMP, slot))
            .remove(key(instanceId, KEY_EXPIRED, slot))
            .remove(key(instanceId, KEY_EMAIL, slot))
            .remove(key(instanceId, KEY_PROJECT_ID, slot))
            .commit()
    }

    fun clearAll(context: Context, instanceId: String, declaredCount: Int) {
        for (slot in 0 until declaredCount.coerceAtLeast(1)) clear(context, instanceId, slot)
    }

    /** Human-readable expiry line for the settings UI ("有效期至 …"), or null. */
    fun loadExpiredText(context: Context, instanceId: String, slot: Int = 0): String? {
        val raw = prefs(context).getString(key(instanceId, KEY_EXPIRED, slot), null) ?: return null
        if (raw.isEmpty()) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val date = fmt.parse(raw)
            val out = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            out.timeZone = TimeZone.getDefault()
            date?.let { out.format(it) }
        } catch (_: Exception) {
            raw
        }
    }

    /** `true` when the stored access token is at/near expiry. */
    fun isExpired(context: Context, instanceId: String, slot: Int = 0): Boolean {
        val p = prefs(context)
        val timestamp = p.getLong(key(instanceId, KEY_TIMESTAMP, slot), 0L)
        val expiresIn = p.getLong(key(instanceId, KEY_EXPIRES_IN, slot), 0L)
        if (timestamp == 0L) return true
        // 60s clock-skew guard, mirrors upstream's proactive-refresh margin.
        val expiresAtMs = timestamp + expiresIn * 1000
        return System.currentTimeMillis() >= expiresAtMs - 60_000
    }

    // ── Refresh classification [fix-antigravity-refresh-failure-classify] ──

    /**
     * Why a refresh attempt failed. [Transient] failures (timeouts, DNS,
     * socket resets) leave the credential untouched — the refresh token is
     * still valid and the next attempt may succeed. [Fatal] failures mean
     * Google rejected the refresh token itself (invalid_grant / revoked /
     * password change) — only re-login can fix those.
     */
    sealed class RefreshFailure(val message: String) : Exception(message) {
        class Transient(cause: Throwable) : RefreshFailure("antigravity token refresh: transient network failure: ${cause.message}")
        class Fatal(val httpCode: Int, body: String) : RefreshFailure("antigravity token refresh: credential rejected (HTTP $httpCode): ${body.take(200)}")
        class Malformed(detail: String) : RefreshFailure("antigravity token refresh: malformed response: $detail")
    }

    /**
     * Non-suspending-refresh convenience wrapper: refreshes when needed and
     * returns null on ANY failure (legacy silent-null contract for the many
     * existing call sites). New call sites that must distinguish "network
     * blip — keep the credential" from "refresh token dead — force
     * re-login" should call [refreshAccessTokenOrThrow] instead.
     */
    suspend fun refreshAccessTokenOrNull(context: Context, instanceId: String, slot: Int = 0): String? =
        try {
            refreshAccessTokenOrThrow(context, instanceId, slot)
        } catch (e: RefreshFailure) {
            AppLogger.warning(TAG, "antigravity token refresh failed: ${e.message}")
            null
        } catch (e: Exception) {
            AppLogger.warning(TAG, "antigravity token refresh failed: ${e.message}")
            null
        }

    /**
     * Refreshes the stored access token via the refresh token and persists
     * the rotation. Returns the fresh access token.
     *
     * [fix-antigravity-refresh-failure-classify] Throws [RefreshFailure]
     * subclasses instead of a blanket null: [RefreshFailure.Transient] for
     * network-class errors (the stored credential stays valid — do NOT
     * clear it), [RefreshFailure.Fatal] when Google rejects the refresh
     * token (HTTP 4xx with invalid_grant-class bodies — re-login is the
     * only remedy). The old code collapsed both into "登录已过期", sending
     * users through a full re-login during a mere network blip.
     */
    /**
     * [fix-antigravity-refresh-failure-classify] Pure classification of a
     * refresh failure. Internal + side-effect-free for direct unit testing:
     * OAuthException messages embed "status <code>" from the token endpoint
     * — 4xx means Google rejected the refresh token itself (Fatal:
     * invalid_grant / revoked / password change); everything else (5xx,
     * timeouts, DNS, socket resets) is Transient and must NOT clear the
     * stored credential.
     */
    internal fun classifyRefreshFailure(e: Throwable): RefreshFailure = when (e) {
        is RefreshFailure -> e
        is AntigravityOAuth.OAuthException -> {
            val codeMatch = Regex("status (\\d{3})").find(e.message ?: "")
            val status = codeMatch?.groupValues?.get(1)?.toIntOrNull()
            if (status != null && status in 400..499) {
                RefreshFailure.Fatal(status, e.message ?: "")
            } else {
                RefreshFailure.Transient(e)
            }
        }
        else -> RefreshFailure.Transient(e)
    }

    suspend fun refreshAccessTokenOrThrow(context: Context, instanceId: String, slot: Int = 0): String {
        val tokens = loadTokens(context, instanceId, slot)
            ?: throw RefreshFailure.Transient(IllegalStateException("no stored credential"))
        if (tokens.refreshToken.isEmpty()) {
            throw RefreshFailure.Fatal(httpCode = 0, body = "no refresh token on file")
        }
        return refreshMutex.withLock {
            // Re-check after acquiring: another coroutine may have refreshed.
            val latest = loadTokens(context, instanceId, slot)
            if (latest != null && !isExpired(context, instanceId, slot)) {
                lastRefreshFailure = null
                return@withLock latest.accessToken
            }
            val current = latest ?: tokens
            try {
                val refreshed = withContext(Dispatchers.IO) { AntigravityOAuth.refreshTokens(current.refreshToken) }
                saveTokens(
                    context,
                    instanceId,
                    refreshed,
                    loadEmail(context, instanceId, slot),
                    loadProjectId(context, instanceId, slot),
                    slot,
                )
                lastRefreshFailure = null
                refreshed.accessToken
            } catch (e: Exception) {
                val failure = classifyRefreshFailure(e)
                lastRefreshFailure = failure
                throw failure
            }
        }
    }

    /**
     * [fix-antigravity-prewarm-refresh] Proactive refresh for task starts:
     * if the stored access token is expired (or within the prewarm margin),
     * rotate it BEFORE the first request of an agent task, so a long task
     * doesn't open with a 401 round-trip. Safe to call concurrently and
     * from any process view of the store — single-flighted via the same
     * mutex, no-op when the token is still fresh. Returns the (possibly
     * just-refreshed) access token, or null when no credential is stored.
     */
    suspend fun prewarmAccessToken(context: Context, instanceId: String, slot: Int = 0): String? {
        val tokens = loadTokens(context, instanceId, slot) ?: return null
        if (!isExpired(context, instanceId, slot)) return tokens.accessToken
        return refreshAccessTokenOrNull(context, instanceId, slot)
    }

    /**
     * A valid access token, refreshed transparently when expired. Returns
     * null when no credential is stored or the refresh fails.
     *
     * Legacy silent-null behavior preserved (ProviderRepository /
     * ModelExecutionService / ProviderExecutionGateway depend on it) —
     * classified failures are available via [refreshAccessTokenOrThrow].
     */
    suspend fun validAccessToken(context: Context, instanceId: String, slot: Int = 0): String? {
        val tokens = loadTokens(context, instanceId, slot) ?: return null
        if (!isExpired(context, instanceId, slot)) return tokens.accessToken
        if (tokens.refreshToken.isEmpty()) return null

        return refreshMutex.withLock {
            // Re-check after acquiring: another coroutine may have refreshed.
            val latest = loadTokens(context, instanceId, slot) ?: return@withLock null
            if (!isExpired(context, instanceId, slot)) return@withLock latest.accessToken
            try {
                val refreshed = withContext(Dispatchers.IO) { AntigravityOAuth.refreshTokens(latest.refreshToken) }
                saveTokens(
                    context,
                    instanceId,
                    refreshed,
                    loadEmail(context, instanceId, slot),
                    loadProjectId(context, instanceId, slot),
                    slot,
                )
                refreshed.accessToken
            } catch (e: Exception) {
                AppLogger.warning(TAG, "antigravity token refresh failed: ${e.message}")
                // A dead refresh token usually means re-login is required.
                null
            }
        }
    }

    /** RFC3339 UTC formatting — upstream metadata `expired` field parity. */
    private fun rfc3339(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }
}
