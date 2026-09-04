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
     * Debounce key = "scheme://host:port" (origin), not bare host — the old
     * host-only key let https://api.example.com and https://api.example.com:8443
     * collapse into one bucket, suppressing the 8443 warmup entirely. Keys
     * and values are per-origin now.
     */
    private val lastWarmedAtMs = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Fire-and-forget warmup. Safe to call from any thread, any frequency —
     * internally debounced. [baseUrl] is the provider base URL (origin is
     * what matters; path/query are stripped).
     */
    fun warm(baseUrl: String?) {
        val url = baseUrl ?: return
        val httpUrl = try {
            url.trim().toHttpUrl()
        } catch (_: IllegalArgumentException) {
            return // user-typed / malformed base — ignore
        }
        // OkHttp's port is already the effective port (default substituted),
        // so origin is scheme://host:port verbatim.
        val origin = "${httpUrl.scheme}://${httpUrl.host}:${httpUrl.port}"
        if (httpUrl.host.isBlank()) return

        val now = System.currentTimeMillis()
        val stamp = lastWarmedAtMs.getOrPut(origin) { AtomicLong(0L) }
        val last = stamp.get()
        // Debounce via CAS so concurrent calls collapse to exactly one warmup.
        if (now - last < DEBOUNCE_MS) return
        if (!stamp.compareAndSet(last, now)) return

        val headUrl = httpUrl.newBuilder()
            .scheme(httpUrl.scheme)     // preserve http/https as configured
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()

        // Use a bare client that SHARES the LLM connection pool (OkHttp
        // explicitly supports sharing pools across clients). No auth headers,
        // short timeouts — this must never delay or outlive its purpose.
        val client = OkHttpClient.Builder()
            .connectionPool(NetworkMonitor.sharedLLMConnectionPool)
            .connectTimeout(5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        val headRequest = okhttp3.Request.Builder()
            .url(headUrl)
            .method("HEAD", null)
            .build()
        val call = client.newCall(headRequest)
        call.enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                // Connection is now pooled regardless of status (401/404 fine).
                response.close()
                Log.d(TAG, "warm connection pooled origin=$origin status=${response.code}")
            }

            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                // [FIX-audit-P1-warmup] Roll the debounce stamp back so the
                // next warm() call is eligible after the SHORT failure window
                // — a DNS hiccup must not silence warmups for a full minute
                // right as the network recovers.
                stamp.set(0L)
                Log.d(TAG, "warm skipped origin=$origin: ${e.javaClass.simpleName}")
            }
        })
    }
}
