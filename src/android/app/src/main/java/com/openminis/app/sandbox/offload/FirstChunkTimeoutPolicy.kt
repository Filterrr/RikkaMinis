package com.openminis.app.sandbox.offload

/**
 * First-chunk timeout policy — pure JVM decision logic extracted from the
 * hard-coded 30s guard in [ModelExecutionService.FIRST_CHUNK_TIMEOUT_MS].
 *
 * Background (2026-08-24, diag/first-chunk-timeout):
 *   The 30s first-chunk guard is CORRECT for direct public endpoints (official
 *   model APIs) but TOO TIGHT for proxy/gateway-type providers: the local
 *   proxy (127.x clash/v2ray) handshake + upstream queueing + SSE first row
 *   routinely approaches 20-60s. A 30s cap on those produces a high-frequency
 *   "provider produced no first chunk" that users experience as failures.
 *
 *   The timeout itself is a safety net (it stops a wedged upstream from
 *   hanging a live worker past the client death grace) — the failure mode was
 *   NOT the guard, it was the CLIENT classifying the resulting
 *   [ModelStreamErrorException] as fatal instead of transient (see
 *   ChatViewModel.workerDiedZeroChunk asymmetry fix). But a policy that can
 *   scale the budget per provider route reduces the false-positive count
 *   without weakening the guard's intent.
 *
 * Rules:
 *   - Official/direct routes get the conservative 30s (STREAM_TTFB_TIMEOUT_MS
 *     in OpenAIProvider is also 30s, so the outer guard stays aligned).
 *   - Proxy/gateway routes (custom base URL that is NOT an official provider
 *     host, or a loopback/proxy-like host) get 45s — matching the inner
 *     STREAM_FIRST_DATA_TIMEOUT_MS so the outer guard does not cut the inner
 *     watchdog short.
 *   - Deterministic, no Android dependencies → JVM-testable. The decision is
 *     purely string-based (customBaseURL) so the pure function can be tested
 *     without dragging in the provider model layer.
 */
object FirstChunkTimeoutPolicy {

    /** Default budget for direct public endpoints (seconds). */
    const val DIRECT_TIMEOUT_SEC = 30

    /** Budget for proxy/gateway routes (seconds) — aligned with the inner
     *  first-data watchdog so the outer guard doesn't preempt it. */
    const val PROXY_TIMEOUT_SEC = 45

    /** Hosts that are "official" direct endpoints (no proxy detour). */
    private val OFFICIAL_HOST_SUFFIXES = listOf(
        "anthropic.com", "googleapis.com", "openai.com", "openrouter.ai",
        "x.ai", "kimi.com", "api.deepseek.com", "moonshot.cn",
        // Additional official/public endpoints (2026-08-24): these otherwise
        // fall into the proxy bucket and get an unnecessarily loose 45s.
        "azure.com",          // Azure OpenAI: <resource>.openai.azure.com
        "aliyuncs.com",       // DashScope / Qwen
        "groq.com",           // Groq
        "minimax.io",         // MiniMax
        "xiaomimimo.com",     // Xiaomi MiMo
    )

    /**
     * Decide whether a route is proxy/gateway-like, purely from the base URL.
     * A custom base URL is the discriminator: most proxy routes mount
     * providers behind a local relay (127.x / 192.168.x / 10.x / a gateway
     * domain or IP), whereas direct routes either use the provider's built-in
     * endpoint (customBaseURL == null) or an official host.
     */
    fun isProxyRoute(customBaseURL: String?): Boolean {
        val base = customBaseURL?.trim()?.trimEnd('/')?.lowercase() ?: return false
        // Loopback / private ranges are unambiguous proxy/gateway indicators.
        // 172.16.0.0–172.31.255.255 is the RFC1918 private block — match the
        // exact second-octet range (16..31) rather than a loose "172." prefix,
        // which would sweep the public 172.32.x–172.255.x space in as well
        // (only loosens the budget, never produces a false failure, but the
        // RFC-exact range is the intended scope; keep this comment so nobody
        // "simplifies" it back to 172.).
        val private172 = (16..31).any { base.startsWith("172.$it.") }
        if (base.startsWith("127.") || base.startsWith("localhost") ||
            base.startsWith("10.") || base.startsWith("192.168.") ||
            private172 || base.startsWith("0.0.0.0")
        ) return true
        // Non-https plain-http endpoints are overwhelmingly local relays.
        if (!base.startsWith("https://")) return true
        // Known official hosts stay "direct" even when overridden.
        val host = base.removePrefix("https://").substringBefore('/')
        return OFFICIAL_HOST_SUFFIXES.none { host == it || host.endsWith(".$it") }
    }

    /**
     * Decide the first-chunk timeout budget in seconds for a route.
     * Deterministic, side-effect free.
     */
    fun decideTimeoutSec(customBaseURL: String?): Int =
        if (isProxyRoute(customBaseURL)) PROXY_TIMEOUT_SEC else DIRECT_TIMEOUT_SEC
}