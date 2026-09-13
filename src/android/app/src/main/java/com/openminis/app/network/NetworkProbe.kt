package com.openminis.app.network

import android.util.Log
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [feat1-network-diagnostics] One-shot staged probe of a provider origin:
 * DNS → TCP → TLS → first response byte, each leg timed independently.
 *
 * Why: "my relay is slow" is 90% of network complaints, and the existing
 * diagnostics (OkHttpNetTrace log lines, ProviderHealthTracker P95s) are
 * write-only for users. This probe answers, in one tappable action, WHERE
 * the time (or the failure) sits:
 *   - dnsMs high      → resolution problem (DoH off? hijacked resolver?)
 *   - tcpMs high      → routing/RTT problem (or a dead relay IP)
 *   - tlsMs high      → handshake problem (proxy interception, far region)
 *   - ttfbMs high     → server-side queueing (the relay/upstream is slow)
 *   - a leg FAILED    → that's the broken leg, everything after is "—"
 *
 * Method: one HEAD / on a bare client (no credentials, no body) with an
 * EventListener capturing per-leg milestones — the same shape
 * ConnectionWarmer already uses, so behaviour is representative of real
 * provider traffic (shared DoH resolver, same routing inputs).
 *
 * Pure JVM-testable scheduling is avoided here on purpose: the probe is
 * inherently network-bound; tests cover URL normalization and the result
 * shaping instead.
 */
object NetworkProbe {

    private const val TAG = "NetworkProbe"

    /** Per-leg budget; the whole probe is bounded by ~3× this. */
    private const val LEG_TIMEOUT_MS = 6_000L

    /** One staged probe result. Failed legs are null; [error] explains. */
    data class ProbeResult(
        val origin: String,
        val dnsMs: Long?,
        val tcpMs: Long?,
        val tlsMs: Long?,
        val ttfbMs: Long?,
        val totalMs: Long,
        val httpCode: Int?,
        val error: String?,
    ) {
        /** True when every leg completed (success or an HTTP-level answer). */
        val reachedServer: Boolean get() = dnsMs != null && tcpMs != null && tlsMs != null && ttfbMs != null

        /** Human-readable one-line verdict for the UI row. */
        fun verdict(): String = when {
            error == null && httpCode != null -> "HTTP $httpCode"
            error != null -> error
            else -> "?"
        }
    }

    /**
     * Probe [baseUrl] synchronously (call from Dispatchers.IO). Malformed
     * URLs return an immediate failed result rather than throwing.
     */
    fun probe(baseUrl: String): ProbeResult {
        val url = baseUrl.trim().toHttpUrlOrNull()
            ?: return ProbeResult(normalizeKey(baseUrl), null, null, null, null, 0, null, "invalid URL")
        val origin = "${url.scheme}://${url.host}:${url.port}"

        class Marks {
            var dnsStart = 0L; var dns = 0L
            var connectStart = 0L; var connect = 0L
            var tlsStart = 0L; var tls = 0L
            var headersStart = 0L
            var failure: String? = null
        }
        val marks = Marks()
        val t0 = System.nanoTime()

        fun msSince(tNanos: Long): Long = (System.nanoTime() - tNanos) / 1_000_000L

        val listener = object : okhttp3.EventListener() {
            override fun dnsStart(call: Call, domainName: String) { marks.dnsStart = System.nanoTime() }
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
                marks.dns = msSince(marks.dnsStart)
            }
            override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                marks.connectStart = System.nanoTime()
            }
            override fun secureConnectStart(call: Call) { marks.tlsStart = System.nanoTime() }
            override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
                marks.tls = msSince(marks.tlsStart)
            }
            override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) {
                marks.connect = msSince(marks.connectStart)
            }
            override fun responseHeadersStart(call: Call) { marks.headersStart = System.nanoTime() }
            override fun callFailed(call: Call, ioe: IOException) {
                marks.failure = ioe.javaClass.simpleName
            }
            override fun canceled(call: Call) {
                if (marks.failure == null) marks.failure = "canceled"
            }
        }

        val client = OkHttpClient.Builder()
            .connectionPool(NetworkMonitor.sharedLLMConnectionPool)
            .dns(NetworkMonitor.buildDns())
            .eventListenerFactory { listener }
            .connectTimeout(LEG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(LEG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(LEG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(url.newBuilder().encodedPath("/").query(null).fragment(null).build())
            .method("HEAD", null)
            .build()

        var code: Int? = null
        var err: String? = null
        try {
            client.newCall(request).execute().use { resp: Response ->
                code = resp.code
                // HTTP-level answers (401/404) still prove full connectivity.
            }
        } catch (e: IOException) {
            err = marks.failure ?: e.javaClass.simpleName
        } catch (t: Throwable) {
            err = t.javaClass.simpleName
        }

        val total = msSince(t0)
        val headers = if (marks.headersStart != 0L) msSince(marks.headersStart) else null
        val result = ProbeResult(
            origin = origin,
            dnsMs = marks.dns.takeIf { it > 0 },
            tcpMs = marks.connect.takeIf { it > 0 },
            tlsMs = marks.tls.takeIf { it > 0 },
            ttfbMs = headers,
            totalMs = total,
            httpCode = code,
            error = err,
        )
        Log.d(TAG, "probe $origin → ${result.verdict()} dns=${result.dnsMs} tcp=${result.tcpMs} tls=${result.tlsMs} ttfb=${result.ttfbMs}")
        return result
    }

    /** Stable list key for a base URL (origin only). */
    fun normalizeKey(baseUrl: String): String {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return baseUrl.trim()
        return "${url.scheme}://${url.host}:${url.port}"
    }
}
