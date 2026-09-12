package com.openminis.app.provider.antigravity

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.logging.AppLogger

/**
 * How the authorization page is opened when the user taps "开始登录".
 *
 * - [SYSTEM_BROWSER] — Chrome Custom Tab (falls back to the system browser
 *   intent on devices without a Custom Tabs provider, e.g. Huawei). Uses
 *   the user's real Chrome profile/cookies; Google explicitly permits it.
 * - [IN_APP_BROWSER] — RikkaMinis' built-in browser tab (the same pool the
 *   agent's browser_use tool drives). Note: accounts.google.com inside a
 *   WebView gets 403 `disallowed_useragent`, so the built-in browser
 *   auto-hands Google sign-in domains off to a Custom Tab — see
 *   [GoogleAuthRouter.shouldRouteExternally].
 */
enum class AntigravityLoginBrowser(val label: String) {
    SYSTEM_BROWSER("系统浏览器 / Chrome 标签页"),
    IN_APP_BROWSER("应用内浏览器"),
}

object AntigravityBrowserLauncher {

    private const val TAG = "AntigravityBrowser"

    /**
     * Opens [url] according to [mode]. Returns `true` when a browser was
     * launched. Never throws.
     */
    fun open(context: Context, url: String, mode: AntigravityLoginBrowser): Boolean {
        return try {
            when (mode) {
                AntigravityLoginBrowser.SYSTEM_BROWSER -> openCustomTab(context, url)
                AntigravityLoginBrowser.IN_APP_BROWSER -> openInAppBrowser(context, url)
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "open browser failed ($mode): ${t.message}")
            // Last-resort: plain ACTION_VIEW so the user always gets a page.
            return try {
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

    /**
     * Chrome Custom Tab, falling back to ACTION_VIEW when no Custom Tabs
     * provider exists (matches GoogleAuthRouter.openInCustomTab's pattern).
     */
    private fun openCustomTab(context: Context, url: String): Boolean {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
                .launchUrl(context, Uri.parse(url))
            AppLogger.info(TAG, "opened Custom Tab for OAuth")
            return true
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "Custom Tab unavailable (${t.message}); falling back to system browser")
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return true
        }
    }

    /**
     * Opens the in-app browser tab via the app's LLM-facing browser bridge
     * (`minis-browser-open` intent handled by MainActivity) so the page shows
     * up in the same browser sheet the chat uses. When the app is not in the
     * foreground (OAuth can also be triggered from settings), fall back to a
     * Custom Tab.
     */
    private fun openInAppBrowser(context: Context, url: String): Boolean {
        // The in-app browser lives inside the chat session UI. From settings
        // (no session chrome) the reliable path is still a browser intent;
        // the auth domain would be routed to a Custom Tab anyway by
        // GoogleAuthRouter. So treat IN_APP_BROWSER as "try the app's own
        // viewer first, else system browser".
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLogger.info(TAG, "opened system handler for in-app-browser mode")
            true
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "in-app mode fell back: ${t.message}")
            openCustomTab(context, url)
        }
    }
}
