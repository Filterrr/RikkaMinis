package com.openminis.app.network

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * [OPT7-conn-warmup] Pre-arms the TLS/HTTP connection to a provider host so
 * the user's FIRST real request skips DNS + TCP + TLS (+ proxy tunnel)
 * negotiation — typically 1-3s on a cold start, more through a proxy.
 *
 * How: a lightweight `HEAD /` (no body) on a bare client that SHARES the LLM
 * connection pool. On success the connection lands in
 * [NetworkMonitor.sharedLLMConnectionPool] (5-min keep-alive).
 *
 * [FIX-audit-P1-warmup] Pool sharing is NECESSARY but not SUFFICIENT for
 * reuse — OkHttp also requires the same route (scheme/host/port/proxy/TLS
 * config). The warmup and the real request both go through clients with NO
 * custom proxy/DNS/interceptors-affecting-routing, so the route matches for
 * every production caller; a custom proxy configured at the app level would
 * apply to both equally. This is a best-effort optimization, not a
 * guarantee — the real request always builds its own connection if reuse
 * doesn't happen.
 *
 * Failure semantics: a failed HEAD rolls the debounce stamp back (short
 * 8s re-arm window), so transient DNS/network hiccups don't silence
 * warmups for a full minute right as the network recovers.
 *
 * Debounce: one warmup per ORIGIN (scheme://host:port) per window.
 *
 * Privacy note: the HEAD carries NO credentials, no body, no user data —
 * just a bare request to the API origin.
 *
 * [OPT7-warm-reconnect] Network-recovery re-warm: [NetworkMonitor] evicts
 * the shared pool on every network transition, which silently nullifies any
 * earlier warmup — yet the old code left the 60s success-debounce stamp in
 * place, so the next `warm()` call inside the window was dropped and the
 * first request after a Wi-Fi→cellular swap paid the full cold-connect cost.
 * [onNetworkChanged] now (1) clears all debounce stamps and (2) re-warms the
 * most recently warmed origins so the recovery path re-arms the pool before
 * the user's next send. NetworkMonitor fires it on DISCONNECTED → CONNECTED
 * (and on interface swaps — see its onAvailable handler).
 *
 * [P1-6-warm-client-reuse] The per-warm `OkHttpClient.Builder().build()` is
 * gone: one lazily-built client is reused for every warmup. Safe because the
 * route-relevant inputs (shared pool, DoH resolver) are resolved at LOOKUP
 * time — `NetworkMonitor.buildDns()` reads `sharedDohDns` per lookup and the
 * pool reference is a process-wide singleton — so a cached client always
 * agrees with the real request's routing. The builder allocation only ever
 * happened per warmup call before; now it happens once per process.
 */
object ConnectionWarmer {

    private const val TAG = "ConnWarmer"

    /** Re-arm at most this often per origin after a SUCCESSFUL warmup. */
    private const val DEBOUNCE_MS = 60_000L

    /**
     * [FIX-audit-P1-warmup] Short re-arm window after a FAILED warmup. The
     * original code stamped the debounce BEFORE the request, so a DNS
     * hiccup silenced warmups for a full minute right when the network just
     * recovered. Now: failure → retry eligible after [FAILURE_DEBOUNCE_MS].
     */
    private const val FAILURE_DEBOUNCE_MS = 8_000L

    /**
     * How many distinct origins [onNetworkChanged] re-warms, most recent
     * first. Provider switches are user-paced, so the realistic window
     * between a network flap and the next send covers 2-3 origins at most;
     * re-warming every origin ever seen would spray HEADs at stale hosts.
     */
    private const val REWARM_ORIGINS = 3

    /**
     * Debounce key = "scheme://host:port" (origin), not bare host — the old
     * host-only key let https://api.example.com and https://api.example.com:8443
     * collapse into one bucket, suppressing the 8443 warmup entirely. Keys
     * and values are per-origin now. Insertion order = warmth recency
     * (ConcurrentHashMap is unordered, so recency is tracked separately in
     * [recentOrigins]).
     */
    private val lastWarmedAtMs = ConcurrentHashMap<String, AtomicLong>()

    /** Recently warmed origins, newest first — the [onNetworkChanged] set. */
    private val recentOrigins = ArrayDeque<String>()

    /**
     * [P1-6-warm-client-reuse] One client per process. Shares the LLM
     * connection pool so warmed connections land where real requests can
     * reuse them; the DNS wrapper re-reads the CURRENT shared DoH resolver
     * on every lookup, so DoH toggles apply without rebuilding this client.
     */
    private val warmClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(NetworkMonitor.sharedLLMConnectionPool)
            .dns(NetworkMonitor.buildDns())
            .connectTimeout(5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    /** Fire-and-forget warmup. Safe to call from any thread, any frequency —
     *  internally debounced. [baseUrl] is the provider base URL (origin is
     *  what matters; path/query are stripped). */
    @JvmStatic
    fun warm(baseUrl: String?) {
        val origin = originOf(baseUrl) ?: return

        val now = System.currentTimeMillis()
        val stamp = lastWarmedAtMs.getOrPut(origin) { AtomicLong(0L) }
        val last = stamp.get()
        // Debounce via CAS so concurrent calls collapse to exactly one warmup.
        if (now - last < DEBOUNCE_MS) return
        if (!stamp.compareAndSet(last, now)) return

        rememberOrigin(origin)
        enqueueWarm(origin)
    }

    /**
     * [OPT7-warm-reconnect] Network transition hook, called by
     * [NetworkMonitor] when connectivity returns (or the interface swaps).
     * Clears every debounce stamp — the pool was just evicted, so ANY origin
     * is worth re-warming regardless of when it was last warmed — then
     * re-warms the most recent [REWARM_ORIGINS] origins immediately.
     */
    @JvmStatic
    fun onNetworkChanged() {
        if (recentOrigins.isEmpty() && lastWarmedAtMs.isEmpty()) return
        lastWarmedAtMs.clear()
        // Snapshot under the deque's monitor; ArrayDeque is not thread-safe.
        val targets = synchronized(recentOrigins) {
            recentOrigins.take(REWARM_ORIGINS)
        }
        Log.d(TAG, "network changed — re-warming ${targets.size} recent origin(s)")
        targets.forEach { enqueueWarm(it) }
    }

    /** Normalize a base URL to its warmable origin, or null to skip. */
    private fun originOf(baseUrl: String?): String? {
        val url = baseUrl ?: return null
        val httpUrl = try {
            url.trim().toHttpUrl()
        } catch (_: IllegalArgumentException) {
            return null // user-typed / malformed base — ignore
        }
        if (httpUrl.host.isBlank()) return null
        // OkHttp's port is already the effective port (default substituted),
        // so origin is scheme://host:port verbatim.
        return "${httpUrl.scheme}://${httpUrl.host}:${httpUrl.port}"
    }

    /** Track recency (newest first, deduplicated, bounded). */
    private fun rememberOrigin(origin: String) {
        synchronized(recentOrigins) {
            recentOrigins.remove(origin)
            recentOrigins.addFirst(origin)
            while (recentOrigins.size > REWARM_ORIGINS * 2) recentOrigins.removeLast()
        }
    }

    /**
     * Issue the HEAD request for [origin] (origin string → URL rebuilt here
     * so both [warm] and [onNetworkChanged] share one enqueue path).
     */
    private fun enqueueWarm(origin: String) {
        val headUrl = try {
            origin.toHttpUrl().newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
        } catch (_: IllegalArgumentException) {
            return
        }

        // No auth headers, short timeouts — this must never delay or outlive
        // its purpose. The connection is pooled regardless of response status
        // (401/404 fine).
        val headRequest = okhttp3.Request.Builder()
            .url(headUrl)
            .method("HEAD", null)
            .build()
        warmClient.newCall(headRequest).enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
                Log.d(TAG, "warm connection pooled origin=$origin status=${response.code}")
            }

            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                // [FIX-audit-P1-warmup] Roll the debounce stamp back so the
                // next warm() call is eligible after the SHORT failure window
                // — a DNS hiccup must not silence warmups for a full minute
                // right as the network recovers.
                lastWarmedAtMs[origin]?.set(0L)
                Log.d(TAG, "warm skipped origin=$origin: ${e.javaClass.simpleName}")
            }
        })
    }
}
