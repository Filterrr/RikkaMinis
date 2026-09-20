package com.openminis.app.provider.workbuddy

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orchestrates the WorkBuddy sign-in.
 *
 * Unlike Antigravity / Kimi, WorkBuddy's flow is **poll-based, not
 * redirect-based**: the backend hands out a `state` and a hosted auth URL, the
 * user completes the round-trip in a browser, and the client polls
 * `/v2/plugin/auth/token?state=…` until the token appears (upstream
 * `beginOAuth` → `pollOAuth`). There is no loopback callback to listen for and
 * no custom scheme to register — which also makes the flow robust on devices
 * where a browser cannot hand control back to the app at all.
 */
object WorkBuddyLoginManager {

    private const val TAG = "WorkBuddyLogin"

    /** Upstream gives the user five minutes; matched here. */
    private const val TIMEOUT_MS = 5 * 60 * 1000L

    /**
     * Poll cadence. The backend mints the token as soon as the browser leg
     * finishes; a 3s poll keeps perceived latency low without hammering the
     * auth route.
     */
    private const val POLL_INTERVAL_MS = 3_000L

    sealed class Result {
        /**
         * Signed in. [auth] carries the freshly minted bundle for the caller
         * to persist (this object deliberately owns no storage), and [account]
         * is the human-readable label to show.
         */
        data class Success(val account: String, val auth: WorkBuddyApi.AuthResult) : Result()

        /** The user gave up, or the deadline passed. */
        data class Failed(val message: String) : Result()
    }

    /**
     * Runs the whole flow: request a session → open the browser → poll until
     * the token lands. [openBrowser] receives the auth URL and returns whether
     * a browser was actually launched; false aborts immediately so the caller
     * can report "no browser available" instead of polling into the void.
     */
    suspend fun login(
        region: WorkBuddyConstants.Region,
        openBrowser: suspend (url: String) -> Boolean,
    ): Result = withContext(Dispatchers.IO) {
        val session = try {
            WorkBuddyApi.beginOAuth(region)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "beginOAuth failed: ${e.message}")
            return@withContext Result.Failed("获取登录地址失败：${e.message?.take(200) ?: "网络错误"}")
        }

        if (!openBrowser(session.authUrl)) {
            return@withContext Result.Failed("无法打开浏览器，请检查系统浏览器后重试")
        }
        AppLogger.info(TAG, "browser opened; polling for authorization (≤5 min)")

        val auth: WorkBuddyApi.AuthResult? = withTimeoutOrNull(TIMEOUT_MS) {
            while (true) {
                delay(POLL_INTERVAL_MS)
                val result = try {
                    WorkBuddyApi.pollOAuth(session)
                } catch (e: Exception) {
                    // A transport hiccup mid-poll is not fatal — the user may
                    // still be on the consent page, so keep waiting until the
                    // deadline rather than failing the whole sign-in.
                    AppLogger.info(TAG, "poll error (continuing): ${e.message}")
                    null
                }
                if (result != null && result.accessToken.isNotBlank()) return@withTimeoutOrNull result
            }
            @Suppress("UNREACHABLE_CODE") null
        }

        if (auth == null) {
            return@withContext Result.Failed("登录超时或被取消（5 分钟内未完成授权）")
        }
        val label = auth.nickname?.takeIf { it.isNotBlank() } ?: auth.uid
        AppLogger.info(TAG, "authorized for ${auth.uid.take(6)}… (region=${auth.region})")
        Result.Success(label, auth)
    }

    /**
     * Open [url] in a Chrome Custom Tab, falling back to the plain system
     * handler. Custom Tabs keep the user's real browser cookies, which the
     * hosted Tencent login page needs in order to offer an already-signed-in
     * account for one-tap consent. Never throws.
     */
    fun openInBrowser(context: Context, url: String): Boolean = try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(false)
            .build()
            .launchUrl(context, Uri.parse(url))
        true
    } catch (t: Throwable) {
        AppLogger.warning(TAG, "Custom Tab unavailable (${t.message}); falling back to ACTION_VIEW")
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (t2: Throwable) {
            AppLogger.error(TAG, "ACTION_VIEW fallback failed: ${t2.message}")
            false
        }
    }
}
