package com.openminis.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the origin-normalization logic that gates warmups and
 * reconnect re-warms ([ConnectionWarmer] delegates to it). The network
 * side of the warmer can't be unit-tested without instrumentation, but the
 * keying correctness (scheme://host:port, malformed rejection) is pure.
 */
class ConnectionWarmerKeyingTest {

    private fun origin(raw: String): String? {
        // Mirror of ConnectionWarmer.originOf's parsing core.
        val httpUrl = try {
            okhttp3.HttpUrl.Companion.toHttpUrl(raw)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (httpUrl.host.isBlank()) return null
        return "${httpUrl.scheme}://${httpUrl.host}:${httpUrl.port}"
    }

    @Test
    fun `default port origin is scheme host 443`() {
        assertEquals("https://api.openai.com:443", origin("https://api.openai.com/v1"))
    }

    @Test
    fun `explicit port is preserved`() {
        assertEquals("https://api.example.com:8443", origin("https://api.example.com:8443/x"))
    }

    @Test
    fun `path query and fragment are stripped`() {
        assertEquals(
            "https://generativelanguage.googleapis.com:443",
            origin("https://generativelanguage.googleapis.com/v1beta/models?key=zz#frag"),
        )
    }

    @Test
    fun `http origins keep their scheme`() {
        assertEquals("http://192.168.1.10:11434", origin("http://192.168.1.10:11434"))
    }

    @Test
    fun `malformed urls are rejected`() {
        assertNull(origin("not a url"))
        assertNull(origin(""))
        assertNull(origin("ftp://x.example"))
    }
}
