package com.openminis.app.provider.antigravity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.openminis.app.mcp.oauth.OAuthCallbackServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * [T-antigravity-oauth] Drives the Antigravity OAuth login flow:
 *
 * 1. [startLogin] builds the Google authorization URL (state-tagged,
 *    response_type=code without PKCE — matching CLIProxyAPI's installed-client
 *    flow) and starts the loopback callback server on port 51121.
 * 2. The user consents in the browser; Google redirects to
 *    `http://localhost:51121/oauth-callback?code=…&state=…` which lands in
 *    this app (PRoot shares the device network namespace).
 * 3. [exchange] swaps the code for tokens, fetches the user email, and runs
 *    Cloud Code project discovery — producing the credential bundle string
 *    that the Add-Provider flow stores into the apiKey slot.
 *
 * Runs against a PENDING instance id (the instance does not exist yet when
 * login starts); [AntigravityOAuthStore.migrateMeta] moves the discovered
 * project/base-URL onto the real instance id at save time — callers pass
 * their Context to [rememberMetaContext] so the store can persist there.
 */
object AntigravityLoginManager {
    private const val TAG = "AntigravityLogin"

    /** Fixed pending id used before a ProviderInstance exists. */
    const val PENDING_INSTANCE_ID = "antigravity-pending"

    data class LoginResult(
        /** Credential bundle JSON to store into the apiKey slot. */
        val credential: String,
        val email: String?,
        val projectId: String?,
        val baseURL: String?,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Context captured by the UI for meta persistence during exchange. */
    @Volatile private var metaContext: Context? = null

    fun rememberMetaContext(context: Context) { metaContext = context.applicationContext }

    /**
     * Launch the browser auth flow. [onResult] fires on the main thread:
     * success = [LoginResult], failure = null (state mismatch / exchange
     * error / user aborted by never redirecting).
     *
     * The returned [OAuthCallbackServer] is owned by the caller — keep a
     * reference and call [OAuthCallbackServer.stop] on screen dispose to
     * avoid leaking the loopback listener.
     */
    fun startLogin(
        context: Context,
        onResult: (LoginResult?) -> Unit,
    ): OAuthCallbackServer {
        rememberMetaContext(context)
        val state = generateState()
        val server = OAuthCallbackServer(AntigravityOAuthStore.CALLBACK_PORT) { code, callbackState ->
            if (callbackState != null && callbackState != state) {
                Log.w(TAG, "OAuth state mismatch — aborting")
                onResult(null)
                return@OAuthCallbackServer
            }
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                val result = exchange(code)
                withContext(Dispatchers.Main) { onResult(result) }
            }
        }
        server.start()

        val url = AntigravityOAuthStore.AUTH_URL + "?" + listOf(
            "access_type=offline",
            "client_id=${AntigravityOAuthStore.CLIENT_ID}",
            "prompt=consent",
            "redirect_uri=${Uri.encode(AntigravityOAuthStore.REDIRECT_URI)}",
            "response_type=code",
            "scope=${Uri.encode(AntigravityOAuthStore.SCOPES)}",
            "state=$state",
        ).joinToString("&")

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return server
    }

    /** Code → tokens → email → project discovery. */
    private suspend fun exchange(code: String): LoginResult? = withContext(Dispatchers.IO) {
        try {
            val form = mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "client_id" to AntigravityOAuthStore.CLIENT_ID,
                "client_secret" to AntigravityOAuthStore.CLIENT_SECRET,
                "redirect_uri" to AntigravityOAuthStore.REDIRECT_URI,
            ).entries.joinToString("&") {
                "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
            }
            val request = Request.Builder()
                .url(AntigravityOAuthStore.TOKEN_URL)
                .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "token exchange HTTP ${resp.code}")
                    return@withContext null
                }
                val obj = JSONObject(resp.body?.string() ?: return@withContext null)
                val access = obj.optString("access_token")
                if (access.isEmpty()) return@withContext null
                val refresh = obj.optString("refresh_token").ifEmpty { null }
                val expiresIn = obj.optLong("expires_in", 0L)
                val bundle = AntigravityOAuthStore.TokenBundle(
                    accessToken = access,
                    refreshToken = refresh,
                    expireAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
                )

                // Best-effort email (userinfo scope was requested).
                var email: String? = null
                runCatching {
                    http.newCall(
                        Request.Builder()
                            .url(AntigravityOAuthStore.USERINFO_URL)
                            .header("Authorization", "Bearer $access")
                            .build()
                    ).execute().use { ui ->
                        if (ui.isSuccessful) {
                            email = JSONObject(ui.body?.string() ?: "").optString("email").ifEmpty { null }
                        }
                    }
                }

                // Project discovery — persist under the pending id; migrated
                // onto the real instance when the provider is saved.
                var projectId: String? = null
                var baseURL: String? = null
                AntigravityOAuthStore.discoverProject(access)?.let { (pid, base) ->
                    projectId = pid
                    baseURL = base
                }
                metaContext?.let { ctx ->
                    AntigravityOAuthStore.saveMeta(ctx, PENDING_INSTANCE_ID, projectId, baseURL, email)
                }
                LoginResult(AntigravityOAuthStore.serialize(bundle), email, projectId, baseURL)
            }
        } catch (e: Exception) {
            Log.e(TAG, "token exchange failed", e)
            null
        }
    }

    private fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
