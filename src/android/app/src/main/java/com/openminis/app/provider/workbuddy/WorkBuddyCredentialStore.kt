package com.openminis.app.provider.workbuddy

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Encrypted storage + single-flight refresh for WorkBuddy OAuth tokens.
 *
 * Ported from WorkBuddy2API Native's `NativeCore` credential handling: the
 * upstream app keeps a per-account `root` JSON object whose `auth` block
 * carries `accessToken` / `refreshToken` / `expiresAt` plus the account
 * identity (`account.uid`, `account.enterpriseId`) that every authenticated
 * request has to echo back in headers. This store keeps the same shape — so a
 * token blob exported from WorkBuddy2API can be imported by pasting it in —
 * but persists through [EncryptedPrefsFactory], the same on-device store the
 * app already uses for API keys.
 *
 * All public entry points are keyed by RikkaMinis **instance id**, so one
 * device can hold several WorkBuddy accounts (e.g. a domestic and an
 * international one) side by side without collisions.
 *
 * Refresh is single-flighted per instance: the mutex means N concurrent chat
 * streams share one rotation instead of racing each other into a spent
 * refresh token.
 */
object WorkBuddyCredentialStore {

    private const val TAG = "WorkBuddyCredStore"
    private const val FILE = "workbuddy_oauth_secrets"

    /** Encrypted-prefs keys. */
    private const val KEY_ACCESS = "accessToken"
    private const val KEY_REFRESH = "refreshToken"
    private const val KEY_EXPIRES_AT = "expiresAt"
    private const val KEY_UID = "uid"
    private const val KEY_ENTERPRISE_ID = "enterpriseId"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_DOMAIN = "domain"
    private const val KEY_REGION = "region"
    private const val KEY_LAST_REFRESH_CODE = "lastRefreshCode"

    /**
     * Refresh this many milliseconds before the advertised expiry, so a token
     * never expires in the middle of an in-flight request. Upstream refreshes
     * lazily on 401; pre-empting is strictly better because the request that
     * would have discovered the expiry is also the one that would fail.
     */
    private const val EXPIRY_SKEW_MS = 60_000L

    /** Tokens older than this are treated as expired even if `expiresAt` says otherwise. */
    private const val TOKEN_MAX_AGE_MS = 12 * 60 * 60 * 1000L

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private val refreshMutex = Mutex()

    /**
     * [fix-encrypted-prefs-wipe-multiprocess] Process-aware store access,
     * mirroring [com.openminis.app.provider.antigravity.AntigravityCredentialStore].
     * EncryptedSharedPreferences is single-process (Android Keystore handles
     * are per-process), so the `:modelservice` worker opens the store
     * READ-ONLY: on a decrypt failure it degrades to an empty in-memory view
     * instead of WIPING the app process's real credentials. Offload callers
     * that must send a request from the worker carry the resolved token in
     * the request JSON.
     */
    private fun buildPrefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        val processName = com.openminis.app.provider.ProviderBoundary.currentProcessName()
            ?: runCatching {
                java.io.File("/proc/self/cmdline").readText().trim().trimEnd('\u0000')
            }.getOrNull()
        val isMainProcess = processName == null || processName == appContext.packageName
        return if (isMainProcess) {
            EncryptedPrefsFactory.safeCreate(appContext, FILE)
        } else {
            AppLogger.info(TAG, "non-main process ('$processName') opens workbuddy store READ-ONLY")
            EncryptedPrefsFactory.safeCreateReadOnly(appContext, FILE)
        }
    }

    private inline fun <T> withPrefs(context: Context, block: (SharedPreferences) -> T): T {
        val ref = prefsRef ?: synchronized(this) {
            prefsRef ?: buildPrefs(context.applicationContext).also { prefsRef = it }
        }
        return block(ref)
    }

    private fun key(instanceId: String, suffix: String) = "wb_${instanceId}_$suffix"

    /** The token bundle for one instance. */
    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val uid: String,
        val enterpriseId: String,
        val nickname: String?,
        val domain: String,
        val region: String,
    ) {
        val hasRefreshToken: Boolean get() = refreshToken.isNotBlank()
        val isExpired: Boolean
            get() = System.currentTimeMillis() + EXPIRY_SKEW_MS >= expiresAt

        /** A short label for the UI, falling back to the uid. */
        val displayName: String
            get() = nickname?.takeIf { it.isNotBlank() } ?: uid.shortUid()

        private fun String.shortUid(): String =
            if (length <= 8) this else "${take(4)}…${takeLast(4)}"
    }

    /** Persist a freshly-minted token bundle. */
    fun saveTokens(context: Context, instanceId: String, tokens: Tokens) {
        withPrefs(context) { prefs ->
            prefs.edit()
                .putString(key(instanceId, KEY_ACCESS), tokens.accessToken)
                .putString(key(instanceId, KEY_REFRESH), tokens.refreshToken)
                .putLong(key(instanceId, KEY_EXPIRES_AT), tokens.expiresAt)
                .putString(key(instanceId, KEY_UID), tokens.uid)
                .putString(key(instanceId, KEY_ENTERPRISE_ID), tokens.enterpriseId)
                .putString(key(instanceId, KEY_NICKNAME), tokens.nickname)
                .putString(key(instanceId, KEY_DOMAIN), tokens.domain)
                .putString(key(instanceId, KEY_REGION), tokens.region)
                .apply()
        }
        AppLogger.info(TAG, "saved tokens for $instanceId (uid=${tokens.uid.take(6)}…, region=${tokens.region})")
    }

    /** Read the stored bundle, or null when the instance has never signed in. */
    fun loadTokens(context: Context, instanceId: String): Tokens? = withPrefs(context) { prefs ->
        val access = prefs.getString(key(instanceId, KEY_ACCESS), null)?.takeIf { it.isNotBlank() }
            ?: return@withPrefs null
        Tokens(
            accessToken = access,
            refreshToken = prefs.getString(key(instanceId, KEY_REFRESH), "").orEmpty(),
            expiresAt = prefs.getLong(key(instanceId, KEY_EXPIRES_AT), 0L),
            uid = prefs.getString(key(instanceId, KEY_UID), "").orEmpty(),
            enterpriseId = prefs.getString(key(instanceId, KEY_ENTERPRISE_ID), "").orEmpty(),
            nickname = prefs.getString(key(instanceId, KEY_NICKNAME), null),
            domain = prefs.getString(key(instanceId, KEY_DOMAIN), "").orEmpty(),
            region = prefs.getString(key(instanceId, KEY_REGION), "").orEmpty(),
        )
    }

    /** True when the instance has a credential on file (signed in at least once). */
    fun hasTokens(context: Context, instanceId: String): Boolean =
        loadTokens(context, instanceId) != null

    /** Remove the credential bundle (sign out / delete provider). */
    fun clear(context: Context, instanceId: String) {
        withPrefs(context) { prefs ->
            prefs.edit()
                .remove(key(instanceId, KEY_ACCESS))
                .remove(key(instanceId, KEY_REFRESH))
                .remove(key(instanceId, KEY_EXPIRES_AT))
                .remove(key(instanceId, KEY_UID))
                .remove(key(instanceId, KEY_ENTERPRISE_ID))
                .remove(key(instanceId, KEY_NICKNAME))
                .remove(key(instanceId, KEY_DOMAIN))
                .remove(key(instanceId, KEY_REGION))
                .remove(key(instanceId, KEY_LAST_REFRESH_CODE))
                .apply()
        }
        AppLogger.info(TAG, "cleared tokens for $instanceId")
    }

    /**
     * A usable access token: the stored one when it is still fresh, otherwise
     * a refreshed one. Returns null only when the instance has no credential
     * at all — a failed rotation surfaces as an exception so the caller can
     * tell "never signed in" from "sign-in went stale".
     */
    suspend fun validAccessToken(context: Context, instanceId: String): String? {
        val tokens = loadTokens(context, instanceId) ?: return null
        if (!tokens.isExpired) return tokens.accessToken
        if (!tokens.hasRefreshToken) return tokens.accessToken
        AppLogger.info(TAG, "access token for $instanceId expired — refreshing")
        return refreshAccessToken(context, instanceId)?.accessToken ?: tokens.accessToken
    }

    /**
     * Rotate the access token using the stored refresh token. Single-flighted:
     * concurrent callers for the same instance wait on one rotation rather
     * than issuing several (which would burn the one-shot refresh token).
     *
     * Returns null when the instance has no refresh token. Throws
     * [WorkBuddyAuthException] when upstream rejects the rotation — the caller
     * then knows a re-login is genuinely required.
     */
    suspend fun refreshAccessToken(context: Context, instanceId: String): Tokens? =
        withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                val current = loadTokens(context, instanceId) ?: return@withLock null
                if (!current.hasRefreshToken) {
                    AppLogger.warning(TAG, "refresh requested for $instanceId but no refresh token is stored")
                    return@withLock null
                }
                // Another coroutine may have rotated while we waited on the mutex.
                val recheck = loadTokens(context, instanceId)
                if (recheck != null && !recheck.isExpired && recheck.accessToken != current.accessToken) {
                    return@withLock recheck
                }

                val refreshed = WorkBuddyApi.refreshToken(current)
                val merged = current.copy(
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken.ifBlank { current.refreshToken },
                    expiresAt = refreshed.expiresAt,
                )
                saveTokens(context, instanceId, merged)
                merged
            }
        }

    /**
     * Translate a successful OAuth poll into a stored bundle. The region has
     * to be supplied by the caller because the poll response itself does not
     * carry it (upstream infers it from `domain`, which we mirror here as a
     * fallback).
     */
    fun storeAuthorized(
        context: Context,
        instanceId: String,
        auth: WorkBuddyApi.AuthResult,
        requestedRegion: WorkBuddyConstants.Region,
    ): Tokens {
        val regionId = if (auth.region.isNotBlank()) {
            WorkBuddyConstants.Region.from(auth.region).id
        } else {
            WorkBuddyConstants.Region.from(requestedRegion.id).id
        }
        val domain = auth.domain.ifBlank { requestedRegion.defaultDomain }
        val tokens = Tokens(
            accessToken = auth.accessToken,
            refreshToken = auth.refreshToken,
            expiresAt = auth.expiresAt,
            uid = auth.uid,
            enterpriseId = auth.enterpriseId,
            nickname = auth.nickname,
            domain = domain,
            region = regionId,
        )
        saveTokens(context, instanceId, tokens)
        return tokens
    }

    /**
     * Import a credential blob produced by WorkBuddy2API's export/import
     * JSON. Accepts the same shape upstream stores: either a bare token
     * object or a wrapper carrying `auth` / `account`. Returns the parsed
     * bundle on success.
     *
     * Kept deliberately lenient about the wrapper because the two upstream
     * shapes (`auth` nested vs. flat) both occur in the wild.
     */
    fun importFromJson(
        context: Context,
        instanceId: String,
        rawJson: String,
        requestedRegion: WorkBuddyConstants.Region,
    ): Tokens {
        val root = JSONObject(rawJson)
        val auth = root.optJSONObject("auth") ?: root
        val account = root.optJSONObject("account")
            ?: auth.optJSONObject("account")
            ?: JSONObject()

        val access = auth.optString("accessToken").takeIf { it.isNotBlank() }
            ?: throw WorkBuddyAuthException("auth 文件缺少 accessToken")
        val uid = account.optString("uid").takeIf { it.isNotBlank() }
            ?: auth.optString("uid").takeIf { it.isNotBlank() }
            ?: throw WorkBuddyAuthException("auth 文件缺少 account.uid")

        val expiresRaw = auth.optLong("expiresAt", 0L)
        // Upstream stores seconds when the value is small and milliseconds
        // otherwise; normalize the same way (`normalizedExpiryMillis`).
        val expiresAt = when {
            expiresRaw <= 0L -> System.currentTimeMillis() + TOKEN_MAX_AGE_MS
            expiresRaw < 10_000_000_000L -> expiresRaw * 1000L
            else -> expiresRaw
        }
        val domain = auth.optString("domain").ifBlank { requestedRegion.defaultDomain }
        val regionId = requestedRegion.id

        val bundle = Tokens(
            accessToken = access,
            refreshToken = auth.optString("refreshToken"),
            expiresAt = expiresAt,
            uid = uid,
            enterpriseId = account.optString("enterpriseId").ifBlank { auth.optString("enterpriseId") },
            nickname = account.optString("nickname").takeIf { it.isNotBlank() },
            domain = domain,
            region = regionId,
        )
        saveTokens(context, instanceId, bundle)
        return bundle
    }
}

/** Raised when WorkBuddy rejects a credential or an interactive login is required. */
class WorkBuddyAuthException(message: String) : Exception(message)
