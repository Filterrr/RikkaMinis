package com.openminis.app.provider.antigravity

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [fix-antigravity-retry-flags-per-request] + [fix-antigravity-refresh-failure-classify]
 * Behavior tests against a MockWebServer standing in for cloudcode-pa.
 *  1. A 401 triggers exactly one refresh + one replay (Bearer rotation
 *     visible on the wire).
 *  2. The retry flags RESET per request — a long conversation self-heals on
 *     EVERY token expiry, not just the first.
 *  3. Refresh classification: a TRANSIENT refresh failure surfaces as
 *     [LLMError.NetworkError] (credential intact), a FATAL one as the
 *     honest re-login error ([LLMError.InvalidApiKey]).
 *
 * Uses an ephemeral-port MockWebServer and a provider whose basePath points
 * at it — the exact production code path (no seams beyond the two the
 * provider already exposes: basePath + accessTokenRefresher).
 */
class AntigravityProviderRetryTest {

    private lateinit var server: MockWebServer

    private val model = LLMModel("gemini-3-flash", "Gemini 3 Flash", "Antigravity")

    private fun okBody(text: String = "hello") = MockResponse().setBody(
        """{"response":{"candidates":[{"content":{"parts":[{"text":"$text"}]},
           "finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":1}}}""",
    )

    private fun unauthorized(body: String = """{"error":"invalid credential"}""") =
        MockResponse().setResponseCode(401).setBody(body)

    private fun sseBody(text: String = "chunk") = MockResponse().setBody(
        """data: {"response":{"candidates":[{"content":{"parts":[{"text":"$text"}]}},
           "finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":1}}}""" + "\n\n",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        AntigravityCredentialStore.lastRefreshFailure = null
    }

    @After
    fun tearDown() {
        server.shutdown()
        AntigravityCredentialStore.lastRefreshFailure = null
    }

    private fun provider(
        refreshToken: (suspend () -> String?)? = { "fresh-token" },
    ): AntigravityProvider = AntigravityProvider(
        accessToken = "stale-token",
        model = model,
        basePath = server.url("/").toString().trimEnd('/'),
        accessTokenRefresher = refreshToken,
    )

    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi"))

    private suspend fun AntigravityProvider.sendOrThrow(): String {
        // Errors inside the flow surface at collect time.
        val chunks = mutableListOf<LLMStreamChunk>()
        streamMessageClamped(
            messages = messages,
            systemPrompt = null,
            maxTokens = 64,
            temperature = null,
            imageParts = emptyList(),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        ).collect { chunks.add(it) }
        val text = chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }
        if (text.isEmpty()) fail("expected text chunk, got $chunks")
        return text
    }

    // ── 1: 401 → refresh → replay once (non-stream) ──

    @Test
    fun `non-stream 401 rotates bearer token and replays once`() = runBlocking {
        server.enqueue(unauthorized())
        server.enqueue(okBody())

        val text = provider().sendOrThrow()

        assertEquals("hello", text)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("Bearer stale-token", first.getHeader("Authorization"))
        assertEquals("Bearer fresh-token", second.getHeader("Authorization"))
        // Exactly two requests — no infinite retry.
        assertTrue(server.requestCount == 2)
    }

    // ── 2: flags reset per request (stream path, same provider instance) ──

    @Test
    fun `stream 401 self-heals on EVERY request - flags reset per call`() = runBlocking {
        val p = provider()
        for (round in 1..2) {
            server.enqueue(unauthorized())
            server.enqueue(sseBody("r$round"))
        }

        assertEquals("r1", p.sendOrThrow())
        // Round 2 on the SAME instance: the previous fix died here with
        // "登录已过期" because authRetryTried stayed consumed.
        assertEquals("r2", p.sendOrThrow())

        val authHeaders = (1..4).map {
            val r = server.takeRequest()
            r.getHeader("Authorization")
        }
        assertEquals(
            listOf("Bearer stale-token", "Bearer fresh-token",
                "Bearer stale-token", "Bearer fresh-token"),
            authHeaders,
        )
    }

    // ── 3a: transient refresh failure → NetworkError, credential intact ──

    @Test
    fun `transient refresh failure surfaces NetworkError not re-login`() = runBlocking {
        server.enqueue(unauthorized())
        server.enqueue(okBody()) // unused — flow aborts before replay

        val p = provider(refreshToken = {
            // Simulate the store's classified outcome for a network blip.
            AntigravityCredentialStore.lastRefreshFailure =
                AntigravityCredentialStore.RefreshFailure.Transient(java.io.IOException("timeout"))
            null
        })
        try {
            p.sendOrThrow()
            fail("expected NetworkError")
        } catch (e: LLMError.NetworkError) {
            assertTrue(
                "cause should be the classified refresh failure",
                e.cause is AntigravityCredentialStore.RefreshFailure.Transient,
            )
        }
    }

    // ── 3b: fatal refresh failure → honest re-login error ──

    @Test
    fun `fatal refresh failure surfaces InvalidApiKey`() = runBlocking {
        server.enqueue(unauthorized())
        server.enqueue(okBody()) // unused

        val p = provider(refreshToken = {
            AntigravityCredentialStore.lastRefreshFailure =
                AntigravityCredentialStore.RefreshFailure.Fatal(400, "invalid_grant")
            null
        })
        try {
            p.sendOrThrow()
            fail("expected InvalidApiKey")
        } catch (e: LLMError.InvalidApiKey) {
            assertTrue("should instruct re-login", e.message!!.contains("重新登录"))
        }
    }
}

/**
 * [fix-antigravity-refresh-failure-classify] Pure classifier coverage —
 * pins the transient-vs-fatal split without any I/O.
 */
class AntigravityRefreshClassificationTest {

    @Test
    fun `oauth 4xx status classifies fatal`() {
        val e = AntigravityOAuth.OAuthException(
            "antigravity token exchange: request failed: status 400: {\"error\":\"invalid_grant\"}",
        )
        val out = AntigravityCredentialStore.classifyRefreshFailure(e)
        assertTrue(out is AntigravityCredentialStore.RefreshFailure.Fatal)
        assertEquals(400, (out as AntigravityCredentialStore.RefreshFailure.Fatal).httpCode)
    }

    @Test
    fun `oauth 5xx status classifies transient`() {
        val e = AntigravityOAuth.OAuthException(
            "antigravity token exchange: request failed: status 503: backend error",
        )
        val out = AntigravityCredentialStore.classifyRefreshFailure(e)
        assertTrue(out is AntigravityCredentialStore.RefreshFailure.Transient)
    }

    @Test
    fun `io exception classifies transient`() {
        val out = AntigravityCredentialStore.classifyRefreshFailure(java.io.IOException("DNS failure"))
        assertTrue(out is AntigravityCredentialStore.RefreshFailure.Transient)
    }

    @Test
    fun `unparseable oauth message defaults to transient`() {
        val e = AntigravityOAuth.OAuthException("antigravity token exchange: execute request: null")
        val out = AntigravityCredentialStore.classifyRefreshFailure(e)
        assertTrue(out is AntigravityCredentialStore.RefreshFailure.Transient)
    }
}
