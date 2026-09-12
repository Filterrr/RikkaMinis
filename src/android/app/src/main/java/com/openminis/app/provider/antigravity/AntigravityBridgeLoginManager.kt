package com.openminis.app.provider.antigravity

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.delay

/**
 * Orchestrates the bridge-mode Antigravity login — the EasyCLIProxyAPI
 * experience, embedded.
 *
 * Instead of running the OAuth dance in-process (see
 * [AntigravityLoginManager] for that path), this coordinator delegates the
 * token round-trip to a CLIProxyAPI core and only choreographs it:
 *
 *   1. Ask the core for the authorization URL (`antigravity-auth-url` with
 *      `is_webui=true` → the core starts its 51121 callback forwarder,
 *      exactly like the desktop GUI does).
 *   2. Open that URL in the user's chosen browser via
 *      [AntigravityBrowserLauncher] — same browser-choice UX as in-process
 *      mode. After consent Google redirects to localhost:51121, which the
 *      core's forwarder 302s to its own `/antigravity/callback`, which
 *      writes the wait-file the pending goroutine consumes.
 *   3. Poll `get-auth-status` at 1 Hz for ≤5 min (upstream cadence and
 *      deadline). On "error" the message surfaces verbatim.
 *   4. On "ok", pull the newest antigravity auth-file metadata from the
 *      core ([finishBySync]) and mirror it into
 *      [AntigravityCredentialStore] — the local provider runtime then uses
 *      exactly the tokens the core holds.
 *
 * Fallback (mirrors the desktop GUI's paste path): if the redirect cannot
 * reach the core's forwarder — core on another machine, emulator without
 * `adb reverse tcp:51121 tcp:51121` — the user pastes the callback URL from
 * the browser's address bar and the UI calls [submitPastedCallback]. That
 * POST makes the core resume the very same pending session (matched by the
 * URL's `state`), so the poll loop inside [login] simply observes "ok" on a
 * later tick — the two paths converge without extra coupling.
 *
 * A third entry, [finishBySync], is exposed directly: when the core already
 * holds antigravity credentials (logged in from the desktop GUI before),
 * the settings UI can mirror them into this device without any browser
 * round-trip.
 */
object AntigravityBridgeLoginManager {

    private const val TAG = "AntigravityBridgeLogin"

    private const val TIMEOUT_MS = 5 * 60 * 1000L   // upstream: 5-minute deadline
    private const val POLL_INTERVAL_MS = 1000L      // upstream: ~1 Hz status polling

    sealed class Result {
        data class Success(val email: String?, val projectId: String?) : Result()
        object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * Full bridge login: auth URL → browser → poll → credential sync.
     * Safe to cancel — cancellation surfaces as a [Result.Failed] with the
     * timeout text on the next poll tick (the core session expires on its
     * own 5-minute deadline, matching upstream).
     */
    suspend fun login(
        context: Context,
        config: AntigravityCliProxyBridge.Config,
        browser: AntigravityLoginBrowser,
        openBrowser: (url: String) -> Unit,
        instanceId: String,
    ): Result {
        AppLogger.info(TAG, "bridge login start (instance=$instanceId, core=${config.host}:${config.port})")

        // Step 1: authorization URL from the core.
        val start = try {
            AntigravityCliProxyBridge.fetchAuthUrl(config)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "fetchAuthUrl failed: ${e.message}")
            return Result.Failed("获取授权链接失败：${e.message?.take(200) ?: "网络错误"}")
        }
        if (start.state.isEmpty()) {
            return Result.Failed("内核未返回 OAuth 会话 state，无法继续（请升级内核）")
        }

        // Step 2: open the consent page in the user's browser.
        openBrowser(start.url)
        AppLogger.info(TAG, "browser opened for core-mediated antigravity OAuth (state=${start.state.take(8)})")

        // Step 3: poll ≤5 min at 1 Hz. Transient network blips skip a tick
        // instead of killing the flow — the core owns the real deadline.
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var pollCount = 0
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            pollCount++
            val status = try {
                AntigravityCliProxyBridge.pollAuthStatus(config, start.state)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "poll #$pollCount failed: ${e.message}")
                continue
            }
            when (status.status) {
                "ok" -> return finishBySync(context, config, instanceId)
                "error" -> return Result.Failed(status.error ?: "内核报告登录失败")
                else -> Unit // "wait" (or unrecognized) — keep polling
            }
        }
        return Result.Failed("登录超时或被取消（5 分钟内内核未完成授权）")
    }

    // ── Paste path (fallback when localhost:51121 can't reach the core) ──

    /**
     * Submits a pasted callback/redirect URL to the core (POST
     * /oauth-callback). The core matches the pending session via the URL's
     * `state` and continues to token exchange; the poll loop inside a
     * concurrent [login] then observes "ok" and syncs the credential.
     * Returns a user-facing error string, or null on acceptance.
     */
    suspend fun submitPastedCallback(
        config: AntigravityCliProxyBridge.Config,
        redirectUrl: String,
    ): String? {
        return try {
            AntigravityCliProxyBridge.submitCallback(config, redirectUrl)
            AppLogger.info(TAG, "pasted callback accepted by core")
            null
        } catch (e: Exception) {
            AppLogger.warning(TAG, "pasted callback rejected: ${e.message}")
            "回调提交失败：${e.message?.take(200) ?: "网络错误"}"
        }
    }

    // ── Credential sync (shared completion step) ──

    /**
     * Pulls the newest antigravity credential from the core and mirrors it
     * into the local encrypted store. Called after a successful poll, and
     * directly by the UI's "从内核同步已有凭证" action.
     */
    suspend fun finishBySync(
        context: Context,
        config: AntigravityCliProxyBridge.Config,
        instanceId: String,
    ): Result {
        val credential = try {
            AntigravityCliProxyBridge.fetchLatestAntigravityCredential(config)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "credential sync failed: ${e.message}")
            null
        } ?: return Result.Failed(
            "内核已完成授权，但未找到可用的 antigravity 凭证。请确认内核已保存凭证文件后重试。",
        )

        AntigravityCredentialStore.saveTokens(
            context = context,
            instanceId = instanceId,
            tokens = AntigravityOAuth.Tokens(
                accessToken = credential.accessToken,
                refreshToken = credential.refreshToken,
                expiresIn = credential.expiresIn,
                tokenType = null,
            ),
            email = credential.email,
            projectId = credential.projectId,
        )
        AppLogger.info(
            TAG,
            "bridge login complete for $instanceId " +
                "(email=${credential.email != null}, projectId=${credential.projectId != null})",
        )
        return Result.Success(credential.email, credential.projectId)
    }
}
