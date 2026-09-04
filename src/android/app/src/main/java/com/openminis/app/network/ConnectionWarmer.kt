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
 * How: a lightweight `HEAD /` (1-byte-class response, no body) on the SHARED
 * LLM client. OkHttp routes it through the normal connection path; on
 * success the connection lands in [NetworkMonitor.sharedLLMConnectionPool]
 * (5-min keep-alive) and the next request to the same host reuses it.
 * A HEAD that fails (no network / cold DNS) is harmless — the warmup is
 * best-effort and the real request builds its own connection as before.
 *
 * Debounce: one warmup per host per [DEBOUNCE_MS] window, tracked per host —
 * selectModel / selectGroup fire in bursts during settings browsing, and
 * every stream turn re-selects the provider.
 *
 * Privacy note: the HEAD carries NO credentials, no body, no user data —
 * just a bare request to the API origin.
 */
object ConnectionWarmer {

    private const val TAG = "ConnWarmer"

    /** Re-arm at most this often per host (also covers pool keep-alive 5min). */
    private const val DEBOUNCE_MS = 60_000L

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
        val host = httpUrl.host
        if (host.isBlank()) return

        val now = System.currentTimeMillis()
        val stamp = lastWarmedAtMs.getOrPut(host) { AtomicLong(0L) }
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
                Log.d(TAG, "warm connection pooled host=$host status=${response.code}")
            }

            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.d(TAG, "warm skipped host=$host: ${e.javaClass.simpleName}")
            }
        })
    }
}
