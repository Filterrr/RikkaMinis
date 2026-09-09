package com.openminis.app.network

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetSocketAddress
import java.net.Proxy

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
 *  - [proxyUrl]: per-instance proxy configs fall back to this when
 *    [NetworkMonitor.resolveProxy] finds no instance-level override.
 *  - [llmMaxIdleConnections]: shared-pool idle capacity. Default 5 was sized
 *    before the MULTI_SESSION (5 parallel sessions) perf scenario — during
 *    streaming each route holds one connection, and a 5-conn idle ceiling
 *    evicts the connection another session is about to reuse. Live-tunable:
 *    the pool object is replaced on write; existing clients keep the old
 *    pool until rebuilt.
 *
 * All values are safe defaults if never written (DoH off, no proxy, pool 5) —
 * behaviour is byte-identical to before this object existed.
 */
object NetworkSettings {
    private const val PREFS = "network_settings"
    private const val KEY_DOH_ENABLED = "doh.enabled"
    private const val KEY_DOH_URL = "doh.url"
    private const val KEY_PROXY_URL = "proxy.url"
    private const val KEY_POOL_IDLE = "pool.max_idle"

    private const val DEFAULT_DOH_URL = "https://dns.alidns.com/dns-query"

    /** Mirrors NetworkMonitor.sharedLLMConnectionPool's original capacity. */
    const val DEFAULT_POOL_IDLE = 5
    const val MAX_POOL_IDLE = 32

    @Volatile var dohEnabled: Boolean = false
        private set

    /** Raw user-entered DoH template URL (RFC 8484 dns-query endpoint). */
    @Volatile var dohUrl: String = DEFAULT_DOH_URL
        private set

    /** Raw user-entered proxy URL (http://host:port), empty = none. */
    @Volatile var proxyUrl: String = ""
        private set

    @Volatile var llmMaxIdleConnections: Int = DEFAULT_POOL_IDLE
        private set

    fun load(context: Context) {
        val p = prefs(context)
        dohEnabled = p.getBoolean(KEY_DOH_ENABLED, false)
        dohUrl = p.getString(KEY_DOH_URL, null)?.ifBlank { null } ?: DEFAULT_DOH_URL
        proxyUrl = p.getString(KEY_PROXY_URL, null)?.ifBlank { null } ?: ""
        llmMaxIdleConnections = p.getInt(KEY_POOL_IDLE, DEFAULT_POOL_IDLE)
            .coerceIn(1, MAX_POOL_IDLE)
    }

    fun setDoh(context: Context, enabled: Boolean, url: String?) {
        val newUrl = url?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_DOH_URL
        dohEnabled = enabled
        dohUrl = newUrl
        prefs(context).edit()
            .putBoolean(KEY_DOH_ENABLED, enabled)
            .putString(KEY_DOH_URL, newUrl)
            .apply()
        NetworkMonitor.onNetworkSettingsChanged()
    }

    fun setProxy(context: Context, url: String?) {
        proxyUrl = url?.trim().takeUnless { it.isNullOrEmpty() } ?: ""
        prefs(context).edit().putString(KEY_PROXY_URL, proxyUrl).apply()
        NetworkMonitor.onNetworkSettingsChanged()
    }

    /**
     * Returns the validated pool capacity, or null when the input is not a
     * plausible number (callers keep the old value). Clamped to
     * [DEFAULT_POOL_IDLE]..[MAX_POOL_IDLE] — values below 5 lose the
     * MULTI_SESSION headroom this knob exists to provide.
     */
    fun sanitizePoolIdle(raw: String): Int? =
        raw.trim().toIntOrNull()?.coerceIn(DEFAULT_POOL_IDLE, MAX_POOL_IDLE)

    fun setPoolIdle(context: Context, value: Int) {
        llmMaxIdleConnections = value.coerceIn(DEFAULT_POOL_IDLE, MAX_POOL_IDLE)
        prefs(context).edit().putInt(KEY_POOL_IDLE, llmMaxIdleConnections).apply()
        NetworkMonitor.onNetworkSettingsChanged()
    }

    /**
     * Parsed DoH endpoint, or null when disabled/malformed. Callers treat
     * null as "keep the system DNS" — a malformed URL must never wedge
     * every LLM client into instant failure.
     */
    fun dohTemplateUrl(): HttpUrl? =
        if (!dohEnabled) null else dohUrl.takeIf { it.startsWith("https://", ignoreCase = true) }?.toHttpUrlOrNull()

    /**
     * Parsed app-level proxy, or null when unset/malformed. Same null-means-
     * fallback contract as [dohTemplateUrl].
     */
    fun appProxy(): Proxy? {
        val raw = proxyUrl.trim().ifEmpty { return null }
        return parseProxyUrl(raw)
    }

    /**
     * Parse a user-entered proxy URL into an OkHttp [Proxy]. Accepts
     * http://host:port (the only proxy type TLS-streaming LLM traffic
     * should use — SOCKS would bypass the HTTP CONNECT tunnel OkHttp's
     * h2 ping/TTFB watchdogs reason about). Port is REQUIRED (no silent
     * 80/1080 default — a typo must fail loudly as "no proxy", not as a
     * connection to a random port). Returns null when malformed.
     */
    fun parseProxyUrl(raw: String): Proxy? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val withoutScheme = when {
            s.startsWith("http://", ignoreCase = true) -> s.substring(7)
            s.startsWith("https://", ignoreCase = true) -> s.substring(8)
            // Scheme-less input is accepted for convenience: "127.0.0.1:7890".
            else -> s
        }
        val host = withoutScheme.substringBefore(':').trim()
        val portPart = withoutScheme.substringAfter(':', missingDelimiterValue = "")
            .substringBefore('/').trim()
        val port = portPart.toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
