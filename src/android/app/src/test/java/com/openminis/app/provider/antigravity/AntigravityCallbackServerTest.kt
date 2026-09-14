package com.openminis.app.provider.antigravity

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [fix-antigravity-oauth-error-sentinel] The callback server signals
 * Google-side OAuth failures through the single result slot using the
 * "oauth-error:" sentinel prefix; AntigravityLoginManager must consume it
 * instead of feeding it to the token endpoint.
 *
 * [fix-antigravity-callback-hardening] The loopback server must reject
 * malformed/oversized requests instead of hanging the accept thread.
 */
class AntigravityCallbackServerTest {

    private lateinit var server: AntigravityCallbackServer

    @Before
    fun setUp() {
        // Port 0 → kernel-assigned ephemeral port; no cross-test conflicts.
        server = AntigravityCallbackServer(0)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun startOnEphemeralPort(): Int {
        assertTrue("server should bind", server.start())
        val port = server.boundPort() ?: error("no bound port")
        assertTrue(port > 0)
        return port
    }

    // ── wire behavior: code path ──

    @Test
    fun `success callback sinks authorization code and state`() {
        val port = startOnEphemeralPort()
        var received: Pair<String, String?>? = null
        server.onResult = { code, state -> received = code to state }

        val (status, body) = httpGet(
            "http://127.0.0.1:$port/oauth-callback?code=SplxlOBeZQQYbYS6WxSbIA&state=xyz123",
        )
        assertEquals(200, status)
        assertTrue(body.contains("Login successful"))

        val (code, state) = received ?: fail("onResult not invoked")
        assertEquals("SplxlOBeZQQYbYS6WxSbIA", code)
        assertEquals("xyz123", state)
    }

    // ── wire behavior: error sentinel path ──

    @Test
    fun `error redirect sinks sentinel with real state preserved`() {
        val port = startOnEphemeralPort()
        var received: Pair<String, String?>? = null
        server.onResult = { code, state -> received = code to state }

        val (status, body) = httpGet(
            "http://127.0.0.1:$port/oauth-callback?error=access_denied&error_subtype=access_denied_user&state=st1",
        )
        assertEquals(200, status)
        assertTrue(body.contains("Authorization failed"))
        assertTrue(body.contains("access_denied"))

        val (code, state) = received ?: fail("onResult not invoked")
        assertEquals(
            "error redirect must surface through the sentinel prefix",
            "oauth-error:access_denied",
            code,
        )
        assertEquals("state must ride through untouched", "st1", state)
    }

    @Test
    fun `sentinel is delivered exactly once - first callback wins`() {
        val port = startOnEphemeralPort()
        var count = 0
        server.onResult = { _, _ -> count++ }

        httpGet("http://127.0.0.1:$port/oauth-callback?error=access_denied")
        httpGet("http://127.0.0.1:$port/oauth-callback?error=server_error")

        assertEquals("single-slot sink guards against double delivery", 1, count)
    }

    // ── [fix-antigravity-callback-hardening] ──

    @Test
    fun `oversized request line is rejected not hung`() {
        val port = startOnEphemeralPort()
        server.onResult = { _, _ -> fail("must not sink on an oversized request") }

        val junk = "A".repeat(32 * 1024)
        val (status, _) = rawGet("127.0.0.1", port, "GET /oauth-callback?code=$junk HTTP/1.1\r\n\r\n")
        assertEquals("413", status.substringBefore(' '))
    }

    @Test
    fun `too many header lines are rejected`() {
        val port = startOnEphemeralPort()
        server.onResult = { _, _ -> fail("must not sink on a header flood") }

        val headers = (1..300).joinToString("") { "X-Pad-$it: v\r\n" }
        val (status, _) = rawGet("127.0.0.1", port, "GET /oauth-callback HTTP/1.1\r\n$headers\r\n")
        assertEquals("431", status.substringBefore(' '))
    }

    @Test
    fun `garbage request line gets a 400 and no sink`() {
        val port = startOnEphemeralPort()
        server.onResult = { _, _ -> fail("must not sink on garbage") }

        val (status, _) = rawGet("127.0.0.1", port, "NONSENSE\r\n\r\n")
        assertEquals("400", status.substringBefore(' '))
    }

    @Test
    fun `header-less request still parses query params`() {
        // Google sends a short GET; some minimal clients send the request
        // line and immediately the blank line. The parser must not depend
        // on headers being present.
        val port = startOnEphemeralPort()
        var received: Pair<String, String?>? = null
        server.onResult = { code, state -> received = code to state }

        val (status, _) = rawGet("127.0.0.1", port, "GET /oauth-callback?code=abc&state=s9\r\n\r\n")
        assertEquals(200, status.substringBefore(' ').toInt())
        assertEquals("abc", received?.first)
        assertEquals("s9", received?.second)
    }

    // ── minimal HTTP helpers (raw sockets — no client dependency needed) ──

    private fun httpGet(url: String): Pair<Int, String> {
        val uri = java.net.URI(url)
        val host = if (uri.host == "localhost") "127.0.0.1" else uri.host
        val socket = java.net.Socket(host, uri.port)
        socket.soTimeout = 5_000
        socket.use { s ->
            val path = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val resp = s.getInputStream().bufferedReader().readText()
            val statusLine = resp.lineSequence().firstOrNull() ?: ""
            val code = statusLine.substringAfter(' ').substringBefore(' ').toIntOrNull() ?: 0
            val bodyStart = resp.indexOf("\r\n\r\n")
            return code to resp.substring(bodyStart + 4)
        }
    }

    /** Sends an arbitrary raw request line + headers; returns the status line + body. */
    private fun rawGet(host: String, port: Int, raw: String): Pair<String, String> {
        val socket = java.net.Socket(host, port)
        socket.soTimeout = 5_000
        socket.use { s ->
            s.getOutputStream().write(raw.toByteArray())
            s.getOutputStream().flush()
            val resp = s.getInputStream().bufferedReader().readText()
            val statusLine = resp.lineSequence().firstOrNull() ?: ""
            val bodyStart = resp.indexOf("\r\n\r\n")
            return statusLine to resp.substring(bodyStart + 4)
        }
    }
}

/**
 * [fix-antigravity-oauth-error-sentinel] Login-manager-level consumption of
 * the sentinel: an OAuth error redirect must map to a Failed result with
 * the upstream reason, and only a state-matching, non-sentinel payload may
 * proceed to token exchange. Exercises the extracted pure mapper
 * [AntigravityLoginManager.mapCallbackToResult] directly.
 */
class AntigravityLoginManagerErrorPathTest {

    private val expectedState = "expected-state-01"

    @Test
    fun `oauth error sentinel maps to Failed carrying upstream reason`() {
        val result = AntigravityLoginManager.mapCallbackToResult(
            code = "oauth-error:access_denied",
            returnedState = expectedState,
            expectedState = expectedState,
        )
        assertTrue(result is AntigravityLoginManager.Result.Failed)
        val failed = result as AntigravityLoginManager.Result.Failed
        assertTrue("must carry the upstream reason", failed.message.contains("access_denied"))
        assertTrue("must instruct the user to retry", failed.message.contains("重试") || failed.message.contains("允许"))
    }

    @Test
    fun `valid code with matching state maps to null - flow proceeds to exchange`() {
        val result = AntigravityLoginManager.mapCallbackToResult(
            code = "4/0AQSTgQF-valid-code",
            returnedState = expectedState,
            expectedState = expectedState,
        )
        assertNull("null = proceed to token exchange", result)
    }

    @Test
    fun `state mismatch is rejected before sentinel inspection`() {
        val result = AntigravityLoginManager.mapCallbackToResult(
            code = "oauth-error:access_denied",
            returnedState = "attacker-state",
            expectedState = expectedState,
        )
        assertTrue(result is AntigravityLoginManager.Result.Failed)
        val failed = result as AntigravityLoginManager.Result.Failed
        assertTrue("state failure must be reported as state failure", failed.message.contains("state"))
    }

    @Test
    fun `null returned state tolerated - mapper contract`() {
        // null = "no state arrived" (defensive branch; login() itself always
        // passes a non-null string since the server defaults it to "").
        assertNull(
            AntigravityLoginManager.mapCallbackToResult(
                code = "4/0AQSTgQF-valid-code",
                returnedState = null,
                expectedState = expectedState,
            ),
        )
    }

    @Test
    fun `empty returned state is REJECTED - state echo is mandatory`() {
        // Google always echoes the state we sent. An empty state means the
        // redirect did NOT come from the consent flow we started — reject it
        // (authorization-code injection defense). This pins the ORIGINAL
        // behavior of login() ("" != expectedState → Failed), which the old
        // comment claimed was "treated like null" — it never was.
        val result = AntigravityLoginManager.mapCallbackToResult(
            code = "4/0AQSTgQF-valid-code",
            returnedState = "",
            expectedState = expectedState,
        )
        assertTrue(result is AntigravityLoginManager.Result.Failed)
    }
}
