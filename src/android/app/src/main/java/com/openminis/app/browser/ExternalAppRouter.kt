package com.openminis.app.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.logging.AppLogger
import java.net.URI

/**
 * [OPT-external-domain-router] Routes navigation to selected domains out of
 * the in-app WebView and into the user's real browser (Chrome Custom Tab,
 * falling back to the system handler). The first consumer is
 * top.baidu.com: under some clash/VPN rule sets its TLS is reset mid-
 * handshake (net::ERR_CONNECTION_CLOSED in the WebView), while the user's
 * real browser reaches it fine (different network path / app-level
 * exceptions). Handing those domains to the system browser sidesteps the
 * whole class of WebView-specific TLS blocks.
 *
 * Mirrors [GoogleAuthRouter]'s shape: a static suffix list +
 * shouldRouteExternally() + openExternally(). Kept separate from
 * GoogleAuthRouter because the semantics differ — OAuth is policy-driven
 * (always route), this list is UX/reachability-driven.
 *
 * The list ships with top.baidu.com; the agent's browser_use calls get a
 * clear "opened externally" result so the LLM knows it cannot read the
 * page content and should tell the user to check the external browser.
 */
object ExternalAppRouter {
    private const val TAG = "ExternalAppRouter"

    /**
     * Hosts that always open in the user's real browser instead of the
     * in-app WebView. Suffix match (host == entry or host endsWith .entry).
     * top.baidu.com: TLS reset under common clash/VPN rule sets → WebView
     * shows ERR_CONNECTION_CLOSED even though the site is reachable directly.
     */
    private val EXTERNAL_HOSTS = listOf(
        "top.baidu.com",
    )

    /** Suffix match on host (exact or subdomain). */
    fun shouldRouteExternally(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return EXTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Launch [url] outside the app: Chrome Custom Tab first (keeps the
     * user in a browsing surface with real Chrome's network stack), plain
     * ACTION_VIEW as fallback for devices without Custom Tabs support.
     */
    fun openExternally(context: Context, url: String) {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
                .launchUrl(context, Uri.parse(url))
            AppLogger.info(TAG, "opened externally (Custom Tab): $url")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "Custom Tab failed, falling back to ACTION_VIEW: ${t.message}")
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, url)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.onFailure {
                AppLogger.warning(TAG, "external open failed entirely: ${it.message}")
            }
        }
    }
}
