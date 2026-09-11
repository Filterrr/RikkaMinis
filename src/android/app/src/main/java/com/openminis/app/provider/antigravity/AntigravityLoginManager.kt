package com.openminis.app.provider.antigravity

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Orchestrates the full Antigravity login: callback server → browser →
 * code exchange → userinfo → project id → persisted tokens.
 *
 * Mirrors CLIProxyAPI's management handler `RequestAntigravityToken`
 * goroutine (auth_files_provider_oauth.go): open browser, wait for the
 * loopback callback (there: a wait-file poll; here: the server result
 * directly), exchange the code, fetch the email, best-effort fetch the
 * project id, persist, report.
 */
object AntigravityLoginManager {

    private const val TAG = "AntigravityLogin"
    private const val TIMEOUT_MS = 5 * 60 * 1000L

    sealed class Result {
        /** Login succeeded; [email]/[projectId] may be null when the upstream call failed (both are best-effort). */
        data class Success(val email: String?, val projectId: String?) : Result()
        object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * Runs the whole OAuth round-trip. [openBrowser] is invoked right after
     * the loopback server is listening, so the redirect can never race the
     * bind. 5-minute deadline — same as upstream.
     */
    suspend fun login(
        context: Context,
        browser: AntigravityLoginBrowser,
        openBrowser: (url: String) -> Unit,
        instanceId: String,
    ): Result = withContext(Dispatchers.IO) {
        val state = AntigravityOAuth.generateState()
        val redirectUri = AntigravityOAuth.loopbackRedirect()
        val authUrl = AntigravityOAuth.buildAuthUrl(state, redirectUri)

        AppLogger.info(TAG, "starting antigravity OAuth flow (browser=$browser)")

        // Start listening BEFORE opening the browser — the redirect may
        // arrive within milliseconds of consent on a signed-in device.
        val server = AntigravityCallbackServer(AntigravityOAuth.CALLBACK_PORT)
        if (!server.start()) {
            return@withContext Result.Failed(
                "无法监听本地回调端口 ${AntigravityOAuth.CALLBACK_PORT}（可能被其他应用占用）",
            )
        }

        val callback: Pair<String, String>? = try {
            openBrowser(authUrl)
            AppLogger.info(TAG, "browser opened; waiting for loopback callback (≤5 min)")

            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    server.onResult = { code, st ->
                        if (cont.isActive) cont.resume(code to st)
                    }
                    server.onTimeoutTick = {
                        // No-op; the withTimeoutOrNull owns the deadline.
                    }
                    cont.invokeOnCancellation { server.stop() }
                }
            }
        } finally {
            server.stop()
        }

        if (callback == null) {
            return@withContext Result.Failed("登录超时或被取消（5 分钟内未收到授权回调）")
        }

        val (code, returnedState) = callback
        if (returnedState != null && returnedState != state) {
            AppLogger.warning(TAG, "state mismatch: expected=${state.take(8)} got=${returnedState.take(8)}")
            return@withContext Result.Failed("state 校验失败，请重试登录")
        }

        // Step 3: exchange (upstream ExchangeCodeForTokens).
        val tokens = try {
            AntigravityOAuth.exchangeCodeForTokens(code, redirectUri)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "token exchange failed: ${e.message}")
            return@withContext Result.Failed("换取令牌失败：${e.message?.take(200) ?: "网络错误"}")
        }
        if (tokens.accessToken.isEmpty()) {
            return@withContext Result.Failed("换取令牌失败：上游返回空 access_token")
        }

        // Step 4: email. Upstream aborts the flow on failure, but the email
        // is only a label here — keep the session and continue instead.
        var email: String? = null
        try {
            email = AntigravityOAuth.fetchUserInfo(tokens.accessToken)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "userinfo failed (continuing): ${e.message}")
        }

        // Step 5: project id (upstream logs a warning and continues).
        var projectId: String? = null
        try {
            projectId = AntigravityOAuth.fetchProjectId(tokens.accessToken)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "project id failed (continuing): ${e.message}")
        }

        AntigravityCredentialStore.saveTokens(context, instanceId, tokens, email, projectId)
        AppLogger.info(TAG, "antigravity OAuth complete for $instanceId (email=${email != null}, projectId=${projectId != null})")
        Result.Success(email, projectId)
    }
}
