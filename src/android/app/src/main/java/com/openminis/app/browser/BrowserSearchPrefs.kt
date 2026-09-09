package com.openminis.app.browser

import android.content.Context
import android.content.SharedPreferences
import java.net.URLEncoder

/**
 * [OPT-browser-search-config] Search engine selection for the browser.
 *
 * Two consumers:
 *  1. BrowserSheet's URL bar — "search terms" input builds a search URL
 *     from the selected engine's template (was hardcoded Google).
 *  2. The agent's `web_search` tool + browser_use tool description — the
 *     model reads the template via [templateOrNull] so it constructs
 *     search URLs for the SAME engine the user picked (was: model
 *     hardcoded google.com, unreachable without a proxy in CN).
 *
 * Templates are printf-style with a single `%s` replaced by the
 * URL-encoded query. `hint` is a compact string embedded in the agent
 * tool description — kept short because it ships in EVERY request.
 *
 * Baidu is the default: reachable without a proxy, most tolerant of
 * WebView UAs (Google serves consent/captcha walls to WebView-class
 * clients; the project already routes Google sign-in to Custom Tabs for
 * the same reason).
 */
object BrowserSearchPrefs {
    const val PREFS = "browser_prefs"
    const val KEY_ENGINE_ID = "search_engine_id"
    const val KEY_CUSTOM_TEMPLATE = "search_engine_custom_template"

    /** Prefs key for the engine used by web_search's auto template override. */
    const val DEFAULT_ENGINE_ID = "baidu"

    data class Engine(
        val id: String,
        val displayName: String,
        /** Query URL template with %s for the URL-encoded query. */
        val template: String,
        /** Region note shown in the settings UI. */
        val note: String,
    )

    val engines: List<Engine> = listOf(
        Engine("baidu", "Baidu 百度", "https://www.baidu.com/s?wd=%s", "CN-direct, WebView-tolerant"),
        Engine("bing", "Bing", "https://www.bing.com/search?q=%s", "CN-direct (cn.bing.com redirect)"),
        Engine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s", "Global; may need proxy in CN"),
        Engine("google", "Google", "https://www.google.com/search?q=%s", "Proxy required in CN; captcha-prone for WebView"),
    )

    fun byId(id: String?): Engine? = engines.firstOrNull { it.id == id }

    /**
     * The effective engine: stored selection, falling back to Baidu when
     * the stored id is unknown (e.g. from an older build). Never null.
     */
    fun effective(context: Context): Engine {
        val prefs = prefs(context)
        val id = prefs.getString(KEY_ENGINE_ID, DEFAULT_ENGINE_ID) ?: DEFAULT_ENGINE_ID
        return byId(id) ?: byId(DEFAULT_ENGINE_ID)!!
    }

    /** Custom template override, or null when unset/blank. */
    fun customTemplate(context: Context): String? =
        prefs(context).getString(KEY_CUSTOM_TEMPLATE, null)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The template actually used for query building: custom override wins,
     * else the selected engine's template. Null when the template is
     * malformed (no %s placeholder) — callers fall back to a safe default.
     */
    fun templateOrNull(context: Context): String? {
        val custom = customTemplate(context)
        val t = custom ?: effective(context).template
        return if (t.contains("%s")) t else null
    }

    /** Build the search URL for a raw query (URL-encoded). */
    fun buildSearchUrl(context: Context, query: String): String? {
        val t = templateOrNull(context) ?: return null
        return t.replace("%s", URLEncoder.encode(query.trim(), "UTF-8"))
    }

    /**
     * Compact one-liner for embedding in agent tool descriptions. Includes
     * the custom template when set, so the model always constructs URLs
     * that match what the user's URL bar produces.
     */
    fun agentHint(context: Context): String {
        val custom = customTemplate(context)
        return if (custom != null) {
            "Search URL template (user-configured): $custom"
        } else {
            val e = effective(context)
            "Search engine: ${e.displayName} (${e.note}). Search URL template: ${e.template}"
        }
    }

    fun setEngine(context: Context, engineId: String) {
        prefs(context).edit().putString(KEY_ENGINE_ID, engineId).apply()
    }

    fun setCustomTemplate(context: Context, template: String?) {
        prefs(context).edit()
            .putString(KEY_CUSTOM_TEMPLATE, template?.trim()?.takeIf { it.isNotEmpty() })
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
