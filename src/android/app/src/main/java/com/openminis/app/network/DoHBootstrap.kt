package com.openminis.app.network

import okhttp3.HttpUrl
import java.net.InetAddress

/**
 * [P1-5-doh-bootstrap] Pinned bootstrap IPs for well-known public DoH
 * endpoints.
 *
 * Why: the DoH resolver's own hostname must be resolved BEFORE encrypted DNS
 * is available — the classic bootstrap problem. OkHttp's default answers it
 * with the system (plaintext) resolver, which is fine on clean networks but
 * defeats the entire feature on the networks DoH exists for: a hijacked or
 * poisoned plaintext resolver can simply refuse to answer
 * `dns.alidns.com`/`one.one.one.one`, silently downgrading every LLM client
 * back to plaintext DNS.
 *
 * The operators below publish their resolver IPs and keep them stable — so
 * for KNOWN endpoints we pin the bootstrap with `bootstrapDnsHosts(...)` and
 * plaintext DNS never enters the path. Custom (user-entered) DoH URLs are
 * NOT pinned (their IPs are unknown) and keep the system-resolver bootstrap.
 *
 * IPs are verifiable against the operators' published records:
 *   - AliDNS (dns.alidns.com)      → 223.5.5.5 / 223.6.6.6 (also the
 *     app-level fallback already used in RootfsManager.refreshDns)
 *   - Cloudflare (one.one.one.one / cloudflare-dns.com) → 1.1.1.1 / 1.0.0.1
 *   - Google (dns.google)          → 8.8.8.8 / 8.8.4.4
 *   - Quad9 (dns.quad9.net)        → 9.9.9.9 / 149.112.112.112
 */
object DoHBootstrap {

    /** One known DoH preset: display name + template URL + pinned IPs. */
    data class Preset(
        val id: String,
        val label: String,
        val url: String,
        val primaryIp: String,
        val secondaryIp: String,
    ) {
        /** Pinned bootstrap addresses, resolved lazily (never on class load). */
        fun bootstrapHosts(): Array<InetAddress> =
            arrayOf(InetAddress.getByName(primaryIp), InetAddress.getByName(secondaryIp))
    }

    /** The built-in presets surfaced by the Network settings UI (功能4). */
    val PRESETS: List<Preset> = listOf(
        Preset("alidns", "AliDNS (阿里)", "https://dns.alidns.com/dns-query", "223.5.5.5", "223.6.6.6"),
        Preset("cloudflare", "Cloudflare", "https://one.one.one.one/dns-query", "1.1.1.1", "1.0.0.1"),
        Preset("cloudflare-doh", "Cloudflare (cloudflare-dns.com)", "https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
        Preset("google", "Google", "https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
        Preset("quad9", "Quad9", "https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112"),
    )

    /** Default preset id — matches the historical DEFAULT_DOH_URL. */
    const val DEFAULT_PRESET_ID = "alidns"

    /**
     * Pinned bootstrap IPs for [template], or null when the URL is not one of
     * the known endpoints (custom URL → system-resolver bootstrap, unchanged
     * behaviour). Matched on host so path variants (/dns-query) still pin.
     */
    fun pinnedIps(template: HttpUrl): Array<InetAddress>? =
        PRESETS.firstOrNull { p ->
            val host = p.url.removePrefix("https://").substringBefore('/')
            host.equals(template.host, ignoreCase = true)
        }?.bootstrapHosts()
}
