package com.openminis.app.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * [feat4-doh-presets] Minimal RFC 8484 wire-format DoH tester used by the
 * Network settings "Test" button: builds a fixed DNS query for
 * example.org/A, base64url-encodes it (RFC 4648 §5, no padding), and times
 * an HTTPS GET `?dns=` round trip against each preset endpoint.
 *
 * Deliberately independent from the production resolver (DnsOverHttps):
 * this measures REACHABILITY + latency of each endpoint so the user can
 * pick a fast one before enabling DoH. It never touches app credentials.
 */
object DoHTester {

    /**
     * One A query for example.org, id=0x1a2b, RD=1 — deterministic so the
     * base64url string is a constant and the code stays testable.
     * Hand-assembled wire format (12B header + 1 label + root + 2 fixed bytes).
     */
    private val QUERY: ByteArray = byteArrayOf(
        0x1a, 0x2b, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // header
        0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),                  // "example"
        0x03, 'o'.code.toByte(), 'r'.code.toByte(), 'g'.code.toByte(),            // "org"
        0x00,                                                                     // root
        0x00, 0x01, 0x00, 0x01,                                                   // QTYPE=A QCLASS=IN
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Timed probe of a DoH endpoint. Returns null on any failure (offline,
     * blocked, malformed) — the UI shows "—" rather than a fake number.
     */
    fun probeLatencyMs(templateUrl: String): Long? {
        return try {
            val url = templateUrl.toHttpUrl().newBuilder()
                .addQueryParameter("dns", base64Url(QUERY))
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-message")
                .build()
            val t0 = System.nanoTime()
            client.newCall(request).execute().use { resp ->
                resp.body?.bytes() // consume so keep-alive and full read are measured
                if (!resp.isSuccessful) return null
                (System.nanoTime() - t0) / 1_000_000L
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun base64Url(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
}
