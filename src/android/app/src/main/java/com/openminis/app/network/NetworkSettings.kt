package com.openminis.app.network

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * App-level network settings, backed by SharedPreferences (the same store the
 * config bridge's Prefs* fields use — register the fields in ConfigBuiltins to
 * expose them to minis-config / the agent-facing surface later).
 *
 * Carried by [NetworkMonitor] as process-wide state so every LLM client
 * builder can read the CURRENT value at build time without a Context hop:
 *
 *  - [dohEnabled] / [dohUrl]: DNS-over-HTTPS for all LLM clients. TTFB
 *    variance killer for direct connections (bypasses plaintext/cleartext
 *    DNS hijack); harmless through a local VPN/proxy that remotes DNS
 *    (bootstrap resolve of the DoH host still goes through the system).
 *
 * All values are safe defaults if never written (DoH off) — behaviour is
 * identical to before this object existed.
 *
 * NOTE [OPT-restore-doh]: this is the DoH-only slice of the former
 * TTFB-pack store. The proxy fields (per-instance / app-level / WebView /
 * master switch) and the pool-capacity knob were removed with the proxy
 * system (2867abc) and stay removed.
 */
object NetworkSettings {
    private const val PREFS = "network_settings"
    private const val KEY_DOH_ENABLED = "doh.enabled"
    private const val KEY_DOH_URL = "doh.url"

    private const val DEFAULT_DOH_URL = "https://dns.alidns.com/dns-query"

    @Volatile var dohEnabled: Boolean = false
        private set

    /** Raw user-entered DoH template URL (RFC 8484 dns-query endpoint). */
    @Volatile var dohUrl: String = DEFAULT_DOH_URL
        private set

    fun load(context: Context) {
        val p = prefs(context)
        dohEnabled = p.getBoolean(KEY_DOH_ENABLED, false)
        dohUrl = p.getString(KEY_DOH_URL, null)?.ifBlank { null } ?: DEFAULT_DOH_URL
    }

    fun setDoh(context: Context, enabled: Boolean, url: String?) {
        val newUrl = url?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_DOH_URL
        dohEnabled = enabled
        dohUrl = newUrl
        prefs(context).edit()
            .putBoolean(KEY_DOH_ENABLED, enabled)
            .putString(KEY_DOH_URL, newUrl)
            .apply()
        NetworkMonitor.refreshDoh()
    }

    /**
     * Parsed DoH endpoint, or null when disabled/malformed. Callers treat
     * null as "keep the system DNS" — a malformed URL must never wedge
     * every LLM client into instant failure. https-only: DoH credentials
     * (none, but the resolver's queries) must not ride plaintext.
     */
    fun dohTemplateUrl(): HttpUrl? =
        if (!dohEnabled) null else dohUrl.takeIf { it.startsWith("https://", ignoreCase = true) }?.toHttpUrlOrNull()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
