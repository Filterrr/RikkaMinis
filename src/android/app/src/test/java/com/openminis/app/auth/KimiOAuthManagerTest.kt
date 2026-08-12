package com.openminis.app.auth

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID


class KimiOAuthManager(
    context: Context,
    instanceId: String,
) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "KimiOAuth"

        
        const val OFFICIAL_CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"

        
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L

        
        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        private fun mutexFor(instanceId: String): Mutex =
            refreshMutexes.getOrPut(instanceId) { Mutex() }

        
        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: ProviderRepository,
            onDeviceCode: (KimiDeviceFlow.DeviceAuthorization) -> Unit,
        ): String {
            val manager = KimiOAuthManager(context, instanceId)
            val token = manager.performDeviceLogin(onDeviceCode)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    
    enum class RefreshOutcome { SUCCESS, INVALID_GRANT, TRANSIENT, NO_TOKEN }

    
    
    override val authURL = KimiDeviceFlow.AUTH_HOST
    override val tokenURL = KimiDeviceFlow.TOKEN_URL
    override val clientId: String
        get() {
            val meta = try {
                context.packageManager
                    .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getString("KimiOAuthClientID")
            } catch (_: Exception) {
                null
            }
            
            return meta ?: OFFICIAL_CLIENT_ID
        }
    override val clientSecret: String? = null
    override val callbackPort = 0
    override val redirectPath = ""
    override val scopes = "" 

    val isLoginAvailable: Boolean get() = clientId.isNotEmpty()

    

    
    private fun postForm(url: String, params: Map<String, String>): Pair<Int, JSONObject> {
        val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        val request = Request.Builder()
            .url(url)
            .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val response = httpClient.newCall(request).execute()
        val code = response.code
        val body = response.body?.string() ?: ""
        response.close()
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        return code to json
    }

    
    suspend fun requestDeviceAuthorization(): KimiDeviceFlow.DeviceAuthorization =
        withContext(Dispatchers.IO) {
            if (!isLoginAvailable) {
                throw Exception("Kimi login unavailable — client id override is empty")
            }
            Log.i(TAG, "Requesting device authorization (instance: $instanceId)")
            
            val (status, json) = postForm(
                KimiDeviceFlow.DEVICE_AUTHORIZATION_URL,
                mapOf("client_id" to clientId),
            )
            if (status !in 200..299) {
                val desc = json.optString("error_description", "")
                    .ifEmpty { json.optString("error", "device authorization failed") }
                Log.e(TAG, "device_authorization failed: $desc")
                throw Exception("Kimi device authorization failed: $desc")
            }
            KimiDeviceFlow.parseDeviceAuthorization(json)
                ?: throw Exception("Kimi device authorization response missing required fields")
                    .also { Log.e(TAG, "device_authorization parse failure: ${sanitizeBody(json.toString())}") }
        }

    
    suspend fun pollForToken(auth: KimiDeviceFlow.DeviceAuthorization): String =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + auth.expiresInSeconds * 1000
            var interval = auth.intervalSeconds
            while (System.currentTimeMillis() < deadline) {
                
                delay(interval * 1000)
                val (status, json) = postForm(
                    tokenURL,
                    mapOf(
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "device_code" to auth.deviceCode,
                        "client_id" to clientId,
                    ),
                )
                when (val result = KimiDeviceFlow.classifyPoll(json, status in 200..299)) {
                    is KimiDeviceFlow.PollResult.Success -> {
                        persistLoginSuccess(result)
                        Log.i(TAG, "Device login success (instance: $instanceId)")
                        return@withContext result.accessToken
                    }
                    KimiDeviceFlow.PollResult.Pending -> Unit 
                    KimiDeviceFlow.PollResult.SlowDown -> {
                        interval = KimiDeviceFlow.bumpedInterval(interval)
                        Log.d(TAG, "slow_down — interval now ${interval}s")
                    }
                    is KimiDeviceFlow.PollResult.Denied ->
                        throw Exception("Kimi login denied: ${result.description}")
                    is KimiDeviceFlow.PollResult.Expired ->
                        throw Exception("Kimi login code expired: ${result.description}")
                    is KimiDeviceFlow.PollResult.Fatal ->
                        throw Exception("Kimi login failed: ${result.description}")
                }
            }
            throw Exception("Kimi login timed out")
        }

    
    suspend fun performDeviceLogin(
        onDeviceCode: (KimiDeviceFlow.DeviceAuthorization) -> Unit,
    ): String {
        val auth = requestDeviceAuthorization()
        Log.i(TAG, "Device code issued — user_code=${auth.userCode} interval=${auth.intervalSeconds}s expires=${auth.expiresInSeconds}s")
        withContext(Dispatchers.Main) { onDeviceCode(auth) }
        return pollForToken(auth)
    }

    private fun persistLoginSuccess(result: KimiDeviceFlow.PollResult.Success) {
        val json = JSONObject().apply {
            put("access_token", result.accessToken)
            result.refreshToken?.let { put("refresh_token", it) }
            result.expiresInSeconds?.let {
                put("expire_at", System.currentTimeMillis() + it * 1000)
            }
            
            
            put("device_id", UUID.randomUUID().toString())
            put("last_refresh", System.currentTimeMillis())
        }
        saveOAuthString("tokens", json.toString())
    }

    

    suspend fun refreshTokenClassified(): RefreshOutcome = withContext(Dispatchers.IO) {
        val mutex = mutexFor(instanceId)
        mutex.withLock {
            val stored = loadStoredTokens() ?: return@withLock RefreshOutcome.NO_TOKEN
            val refreshTokenValue = stored.optString("refresh_token", "")
                .ifEmpty { return@withLock RefreshOutcome.NO_TOKEN }

            
            
            
            val priorExpireAt = stored.optLong("expire_at", 0)
            val freshCheck = loadStoredTokens()?.optLong("expire_at", 0) ?: 0
            if (freshCheck > priorExpireAt && freshCheck > System.currentTimeMillis()) {
                Log.d(TAG, "Refresh skipped — fresher token already present (coalesced)")
                return@withLock RefreshOutcome.SUCCESS
            }

            try {
                val (status, json) = postForm(
                    tokenURL,
                    mapOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshTokenValue,
                        "client_id" to clientId,
                    ),
                )
                if (status in 200..299 && json.optString("access_token", "").isNotEmpty()) {
                    
                    if (!json.has("refresh_token") || json.optString("refresh_token").isEmpty()) {
                        json.put("refresh_token", refreshTokenValue)
                    }
                    val expiresIn = json.optLong("expires_in", 0)
                    if (expiresIn > 0) {
                        json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
                    }
                    
                    json.put("device_id", stored.optString("device_id", UUID.randomUUID().toString()))
                    json.put("last_refresh", System.currentTimeMillis())
                    saveOAuthString("tokens", json.toString())
                    Log.i(TAG, "Token refresh successful (expires in ${expiresIn}s)")
                    return@withLock RefreshOutcome.SUCCESS
                }

                
                
                
                val bodyLower = json.toString().lowercase()
                val isInvalidGrant = status == 400 || status == 401 || status == 403 ||
                    bodyLower.contains("invalid_grant") ||
                    bodyLower.contains("refresh_token_reused")
                if (isInvalidGrant) {
                    
                    
                    
                    
                    
                    val currentStored = loadStoredTokens()?.optString("refresh_token", "")
                    if (!KimiDeviceFlow.shouldDeleteAfterInvalidGrant(refreshTokenValue, currentStored)) {
                        Log.w(TAG, "Stale invalid_grant ignored — refresh token was rotated concurrently; keeping new credentials")
                        return@withLock RefreshOutcome.SUCCESS
                    }
                    Log.e(TAG, "Refresh token invalid — clearing credentials")
                    logout()
                    return@withLock RefreshOutcome.INVALID_GRANT
                }
                Log.w(TAG, "Token refresh transient failure — keeping token")
                return@withLock RefreshOutcome.TRANSIENT
            } catch (e: Exception) {
                Log.w(TAG, "Token refresh transient error — keeping token: ${e.message}")
                return@withLock RefreshOutcome.TRANSIENT
            }
        }
    }

    
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        loadManualBearerToken()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val stored = loadStoredTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        val needsRefresh = stored.optString("refresh_token", "").isNotEmpty() &&
            expireAt > 0 && (expireAt - now) <= REFRESH_BUFFER_MS
        if (!needsRefresh) return@withContext token

        when (refreshTokenClassified()) {
            RefreshOutcome.SUCCESS ->
                loadStoredTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT -> null 
            RefreshOutcome.TRANSIENT ->
                if (expireAt in 1..now) null else token
            RefreshOutcome.NO_TOKEN -> null
        }
    }
}