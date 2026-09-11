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

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, FILE)
                .also { prefsRef = it }
        }

    // -- key namespacing: one credential per provider instance --

    private fun key(instanceId: String, field: String) = "$field::$instanceId"

    fun loadTokens(context: Context, instanceId: String): Tokens? {
        val p = prefs(context)
        val access = p.getString(key(instanceId, KEY_ACCESS), null) ?: return null
        if (access.isEmpty()) return null
        return Tokens(
            accessToken = access,
            refreshToken = p.getString(key(instanceId, KEY_REFRESH), null).orEmpty(),
            expiresIn = p.getLong(key(instanceId, KEY_EXPIRES_IN), 3600L),
            tokenType = null,
        )
    }

    fun loadEmail(context: Context, instanceId: String): String? =
        prefs(context).getString(key(instanceId, KEY_EMAIL), null)?.takeIf { it.isNotEmpty() }

    fun loadProjectId(context: Context, instanceId: String): String? =
        prefs(context).getString(key(instanceId, KEY_PROJECT_ID), null)?.takeIf { it.isNotEmpty() }

    fun saveTokens(
        context: Context,
        instanceId: String,
        tokens: Tokens,
        email: String?,
        projectId: String?,
    ) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putString(key(instanceId, KEY_TYPE), VALUE_TYPE)
            .putString(key(instanceId, KEY_ACCESS), tokens.accessToken)
            .putString(key(instanceId, KEY_REFRESH), tokens.refreshToken)
            .putLong(key(instanceId, KEY_EXPIRES_IN), tokens.expiresIn)
            .putLong(key(instanceId, KEY_TIMESTAMP), now)
            .putString(key(instanceId, KEY_EXPIRED), rfc3339(now + tokens.expiresIn * 1000))
            .putString(key(instanceId, KEY_EMAIL), email.orEmpty())
            .putString(key(instanceId, KEY_PROJECT_ID), projectId.orEmpty())
            .commit()
        AppLogger.info(TAG, "saved antigravity tokens for $instanceId (email=${email != null}, projectId=${projectId != null})")
    }

    fun updateEmail(context: Context, instanceId: String, email: String) {
        prefs(context).edit().putString(key(instanceId, KEY_EMAIL), email).commit()
    }

    fun updateProjectId(context: Context, instanceId: String, projectId: String) {
        prefs(context).edit().putString(key(instanceId, KEY_PROJECT_ID), projectId).commit()
    }

    fun clear(context: Context, instanceId: String) {
        val p = prefs(context)
        p.edit()
            .remove(key(instanceId, KEY_TYPE))
            .remove(key(instanceId, KEY_ACCESS))
            .remove(key(instanceId, KEY_REFRESH))
            .remove(key(instanceId, KEY_EXPIRES_IN))
            .remove(key(instanceId, KEY_TIMESTAMP))
            .remove(key(instanceId, KEY_EXPIRED))
            .remove(key(instanceId, KEY_EMAIL))
            .remove(key(instanceId, KEY_PROJECT_ID))
            .commit()
    }

    /** Human-readable expiry line for the settings UI ("有效期至 …"), or null. */
    fun loadExpiredText(context: Context, instanceId: String): String? {
        val raw = prefs(context).getString(key(instanceId, KEY_EXPIRED), null) ?: return null
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
    fun isExpired(context: Context, instanceId: String): Boolean {
        val p = prefs(context)
        val timestamp = p.getLong(key(instanceId, KEY_TIMESTAMP), 0L)
        val expiresIn = p.getLong(key(instanceId, KEY_EXPIRES_IN), 0L)
        if (timestamp == 0L) return true
        // 60s clock-skew guard, mirrors upstream's proactive-refresh margin.
        val expiresAtMs = timestamp + expiresIn * 1000
        return System.currentTimeMillis() >= expiresAtMs - 60_000
    }

    /**
     * A valid access token, refreshed transparently when expired. Returns
     * null when no credential is stored or the refresh fails.
     */
    suspend fun validAccessToken(context: Context, instanceId: String): String? {
        val tokens = loadTokens(context, instanceId) ?: return null
        if (!isExpired(context, instanceId)) return tokens.accessToken
        if (tokens.refreshToken.isEmpty()) return null

        return refreshMutex.withLock {
            // Re-check after acquiring: another coroutine may have refreshed.
            val latest = loadTokens(context, instanceId) ?: return@withLock null
            if (!isExpired(context, instanceId)) return@withLock latest.accessToken
            try {
                val refreshed = withContext(Dispatchers.IO) { AntigravityOAuth.refreshTokens(latest.refreshToken) }
                saveTokens(
                    context,
                    instanceId,
                    refreshed,
                    loadEmail(context, instanceId),
                    loadProjectId(context, instanceId),
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
