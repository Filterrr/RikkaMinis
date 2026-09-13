package com.openminis.app.network

import okhttp3.Protocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [T-llm-prefer-http11] JVM tests for the LLM ALPN protocol offer.
 *
 * Pure unit tests (no MockWebServer, no Robolectric — like every other suite
 * under src/test): what is under test is what a client BUILDS, and ALPN
 * negotiation cannot be observed over MockWebServer's cleartext sockets
 * anyway. The wire-level premise — these endpoints ignore client ALPN
 * preference, so h2 must be EXCLUDED from the offer rather than merely
 * reordered — was measured offline against the shipped provider set and is
 * documented on [NetworkMonitor.llmProtocols].
 *
 * NetworkSettings.llmHttp11Only has a private setter (only the
 * Context-based writer may change it), so tests reach the backing field
 * reflectively and restore it afterwards: a leaked false would silently
 * disable the feature for the rest of the suite.
 */
class LlmProtocolSelectionTest {

    private companion object {
        val FIELD = NetworkSettings::class.java.getDeclaredField("llmHttp11Only")
            .apply { isAccessible = true }
    }

    private val original: Boolean = FIELD.getBoolean(NetworkSettings)

    private fun setFlag(value: Boolean) = FIELD.setBoolean(NetworkSettings, value)

    @After
    fun tearDown() = setFlag(original)

    @Test
    fun `default offer excludes http2 entirely`() {
        setFlag(true)
        val protocols = NetworkMonitor.llmProtocols()
        assertEquals(listOf(Protocol.HTTP_1_1), protocols)
        // The regression this guards: keeping h2 in the list (even second)
        // still negotiates h2 against Cloudflare-fronted relays, which pick
        // h2 whenever it is offered at all.
        assertFalse(
            "h2 must be absent from the offer, not merely demoted",
            protocols.contains(Protocol.HTTP_2),
        )
    }

    @Test
    fun `kill switch restores the okhttp default order`() {
        setFlag(false)
        val protocols = NetworkMonitor.llmProtocols()
        // OkHttp's own default, verbatim — h2 first so servers that do honour
        // client preference still upgrade.
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), protocols)
    }

    @Test
    fun `offer is read per call so a toggle applies to the next client build`() {
        setFlag(true)
        assertEquals(listOf(Protocol.HTTP_1_1), NetworkMonitor.llmProtocols())
        setFlag(false)
        assertEquals(
            listOf(Protocol.HTTP_2, Protocol.HTTP_1_1),
            NetworkMonitor.llmProtocols(),
        )
    }
}
