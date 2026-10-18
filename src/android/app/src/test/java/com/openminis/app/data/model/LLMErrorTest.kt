package com.openminis.app.data.model

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the group-fallback classification contract.
 *
 * [T-fallback-network-errors] ChatViewModel's shouldFallback uses
 * LLMError.isFallbackable directly, consulted ONLY after the bounded same-
 * provider retry loop is exhausted. So the contract is:
 *
 *  - rate limits (429), invalid keys (401), provider errors (4xx/5xx):
 *    fall back to the next group member immediately (retrying same model
 *    is pointless)
 *  - network / transient errors (stream reset, timeouts, DNS, proxy drop):
 *    retry in place first (isRetryable), and FALL BACK once retries are
 *    exhausted — "this endpoint can't help right now" is exactly the group
 *    fallback case
 *  - decoding, cancellation, unknown: surface to the user / respect intent
 *
 * [T-provider-verbatim-errors] The user-facing banner (userMessage) is the
 * PROVIDER'S OWN TEXT, verbatim. App-authored copy survives only as the
 * blank-detail fallback, i.e. when the provider sent nothing at all — and
 * classification must never depend on text the app spliced in, which is why
 * the HTTP status is carried structurally (`httpStatus` / `isServerError`)
 * instead of as a "[503] " prefix.
 */
class LLMErrorTest {

    @Test
    fun `fallbackable - rate limit 429 triggers group fallback`() {
        assertTrue(LLMError.RateLimited().isFallbackable)
    }

    @Test
    fun `fallbackable - invalid API key 401 triggers group fallback`() {
        assertTrue(LLMError.InvalidApiKey("401 Unauthorized").isFallbackable)
    }

    @Test
    fun `fallbackable - provider 5xx triggers group fallback`() {
        assertTrue(LLMError.ProviderError("HTTP 502 Bad Gateway").isFallbackable)
    }

    @Test
    fun `fallbackable - provider 4xx triggers group fallback (per-provider quota, bad request)`() {
        assertTrue(LLMError.ProviderError("403 quota exceeded").isFallbackable)
        assertTrue(LLMError.ProviderError("400 bad request").isFallbackable)
    }

    @Test
    fun `fallbackable - network errors fall back once retries are exhausted`() {
        // The retry loop (AUTO_RETRY_DELAYS_SEC) gets first crack on the same
        // provider; isFallbackable is only consulted after that, so including
        // NetworkError here stops the "retried 3x then hard-stopped" dead end
        // without ever skipping the retry.
        assertTrue(LLMError.NetworkError(IOException("socket closed")).isFallbackable)
        assertTrue(LLMError.NetworkError(IOException("stream was reset: CANCEL")).isFallbackable)
    }

    @Test
    fun `fallbackable - transient errors fall back once retries are exhausted`() {
        assertTrue(LLMError.TransientError("temporary").isFallbackable)
    }

    @Test
    fun `retryable - network and transient errors retry on the same provider`() {
        assertTrue(LLMError.NetworkError(IOException("timeout")).isRetryable)
        assertTrue(LLMError.TransientError("temporary").isRetryable)
        assertFalse(LLMError.RateLimited().isRetryable)
        assertFalse(LLMError.InvalidApiKey().isRetryable)
        assertFalse(LLMError.ProviderError("500").isRetryable)
    }

    @Test
    fun `fallbackable - decoding, cancellation and unknown surface to the user`() {
        assertFalse(LLMError.DecodingError(RuntimeException("json")).isFallbackable)
        assertFalse(LLMError.Cancelled().isFallbackable)
        assertFalse(LLMError.Unknown(null).isFallbackable)
    }

    // ─── [T-provider-verbatim-errors] the provider's text IS the banner ───

    @Test
    fun `userMessage - provider detail is surfaced verbatim, not re-worded`() {
        // The complaint that produced this contract: a 503 reading
        // "The engine is currently overloaded, please try again later"
        // showed up as "Provider returned an error", and a key with no credit
        // ("Your credit balance is too low to access the API") showed up as
        // "API key is out of quota" — both discarded the actionable sentence.
        val overloaded = LLMError.TransientError(
            "The engine is currently overloaded, please try again later",
            httpStatus = 503,
        )
        assertEquals("The engine is currently overloaded, please try again later", overloaded.userMessage)

        val broke = LLMError.QuotaExhausted("Your credit balance is too low to access the API", httpStatus = 400)
        assertTrue(broke.userMessage.contains("credit balance"))

        val badModel = LLMError.ProviderError("The model `gpt-4-turbo` does not exist", httpStatus = 404)
        assertEquals("The model `gpt-4-turbo` does not exist", badModel.userMessage)

        val throttled = LLMError.RateLimited("Rate limit reached for gpt-4o in organization org-xyz", httpStatus = 429)
        assertTrue(throttled.userMessage.contains("org-xyz"))
    }

    @Test
    fun `userMessage - network detail is surfaced verbatim`() {
        // A dropped socket is exactly the case where the transport's own words
        // ("stream was reset: CANCEL", "Failed to connect to api.x.com") tell
        // the user which hop broke; a canned "Connection failed" does not.
        val reset = LLMError.NetworkError(IOException("stream was reset: CANCEL"))
        assertEquals("stream was reset: CANCEL", reset.userMessage)

        val refused = LLMError.NetworkError(IOException("Failed to connect to api.deepseek.com/104.18.6.171:443"))
        assertTrue(refused.userMessage.contains("api.deepseek.com"))
    }

    @Test
    fun `userMessage - app copy only when the provider said nothing`() {
        // The exception, not the rule: a 401 with an empty body, a socket that
        // died with a null message. An exception must still carry a non-blank
        // message (several call sites do `e.message ?: "fallback"`, which does
        // not guard against ""), so the app supplies one here — and only here.
        assertEquals("API key is invalid or expired", LLMError.InvalidApiKey().userMessage)
        assertEquals("Rate limited — try again in a moment", LLMError.RateLimited().userMessage)
        assertEquals("API key is out of quota", LLMError.QuotaExhausted().userMessage)
        assertEquals("Provider returned an error", LLMError.ProviderError("").userMessage)
        assertEquals("Connection failed", LLMError.NetworkError(IOException()).userMessage)
        assertEquals("Request was cancelled", LLMError.Cancelled().userMessage)
        assertEquals("Something went wrong", LLMError.Unknown(null).userMessage)

        // Exception message stays non-blank in that case too.
        assertTrue(LLMError.ProviderError("").message!!.isNotBlank())
        assertTrue(LLMError.NetworkError(IOException()).message!!.isNotBlank())
    }

    @Test
    fun `detail - always carries the provider text for every variant`() {
        assertEquals("rate me", LLMError.RateLimited("rate me").detail)
        assertEquals("bad key", LLMError.InvalidApiKey("bad key").detail)
        assertEquals("no money", LLMError.QuotaExhausted("no money").detail)
        assertEquals("boom", LLMError.ProviderError("boom").detail)
        assertEquals("timed out", LLMError.TransientError("timed out").detail)
        assertEquals("reset", LLMError.NetworkError(IOException("reset")).detail)
        assertEquals("garbled", LLMError.DecodingError(RuntimeException("garbled")).detail)
        assertEquals("weird", LLMError.Unknown(RuntimeException("weird")).detail)
    }

    @Test
    fun `fallbackReason - mirrors the provider text`() {
        // The per-model trail (technical details) explains WHY each member was
        // abandoned, so a generic "Provider error" there defeats its purpose.
        assertEquals("503 upstream down", LLMError.ProviderError("503 upstream down").fallbackReason)
        assertEquals("throttled by key", LLMError.RateLimited("throttled by key").fallbackReason)
        assertTrue(LLMError.InvalidApiKey("401 nope").fallbackReason.contains("401 nope"))
        // Blank detail still yields a usable reason for the trail.
        assertEquals("Rate limited — try again in a moment", LLMError.RateLimited().fallbackReason)
        assertEquals("API key is invalid or expired", LLMError.InvalidApiKey().fallbackReason)
    }

    // ─── status is structural, not spliced into the text ───────────────────

    @Test
    fun `isServerError - reads the structured status, not digits in the text`() {
        assertTrue(LLMError.ProviderError("upstream exploded", httpStatus = 500).isServerError)
        assertTrue(LLMError.ProviderError("upstream exploded", httpStatus = 503).isServerError)
        assertTrue(LLMError.TransientError("overloaded", httpStatus = 529).isServerError)
        // A 4xx whose message happens to mention "500" must NOT be a server error.
        assertFalse(LLMError.ProviderError("your limit is 500 tokens", httpStatus = 400).isServerError)
        // No status recorded → not treated as a server fault.
        assertFalse(LLMError.ProviderError("no status").isServerError)
        assertFalse(LLMError.NetworkError(IOException("reset")).isServerError)
    }

    @Test
    fun `httpStatus - carried on every HTTP-derived variant`() {
        assertEquals(401, LLMError.InvalidApiKey("nope", httpStatus = 401).httpStatus)
        assertEquals(429, LLMError.RateLimited("slow down", httpStatus = 429).httpStatus)
        assertEquals(402, LLMError.QuotaExhausted("pay up", httpStatus = 402).httpStatus)
        assertEquals(503, LLMError.TransientError("overloaded", httpStatus = 503).httpStatus)
        assertEquals(404, LLMError.ProviderError("no such model", httpStatus = 404).httpStatus)
        assertNull(LLMError.NetworkError(IOException("reset")).httpStatus)
    }

    // ─── the provider-body reader ─────────────────────────────────────────

    @Test
    fun `providerErrorMessage - extracts the message from every known shape`() {
        assertEquals(
            "The model does not exist",
            providerErrorMessage("""{"error":{"message":"The model does not exist","type":"invalid_request_error"}}"""),
        )
        // Chinese relays frequently use `msg`.
        assertEquals(
            "余额不足",
            providerErrorMessage("""{"error":{"msg":"余额不足","code":"insufficient_quota"}}"""),
        )
        // String-form error.
        assertEquals("invalid credential", providerErrorMessage("""{"error":"invalid credential"}"""))
        // Flat shapes (Gemini-style bodies).
        assertEquals("quota exceeded", providerErrorMessage("""{"message":"quota exceeded"}"""))
        assertEquals("bad request", providerErrorMessage("""{"msg":"bad request"}"""))
        // A JSON null must not be reported as the literal string "null" — the
        // next candidate shape (here the flat `message`) takes over instead.
        assertEquals("real one", providerErrorMessage("""{"error":null,"message":"real one"}"""))
    }

    @Test
    fun `providerErrorMessage - falls back to the raw body verbatim`() {
        // An HTML error page or a CDN one-liner is still more informative than
        // a canned sentence — that IS what the provider returned.
        val html = "<html><body><h1>502 Bad Gateway</h1></body></html>"
        assertEquals(html, providerErrorMessage(html))
        assertEquals("upstream says no", providerErrorMessage("upstream says no"))
        // A JSON body with no recognisable message field keeps the raw text.
        assertEquals("""{"status":"failed"}""", providerErrorMessage("""{"status":"failed"}"""))
    }

    @Test
    fun `providerErrorMessage - blank body yields empty so app copy can apply`() {
        assertEquals("", providerErrorMessage(""))
        assertEquals("", providerErrorMessage("   "))
        assertEquals("", providerErrorMessage("\n\t"))
    }

    @Test
    fun `providerErrorMessage - long bodies are capped`() {
        val huge = "x".repeat(5000)
        assertEquals(500, providerErrorMessage(huge).length)
        assertEquals(200, providerErrorMessage(huge, limit = 200).length)
    }

    // ─── exhausted chain carries provider text separately from the trail ──

    @Test
    fun `fallbackExhaustedError - summary is provider text, detail is the trail`() {
        val e = FallbackExhaustedError(
            summary = "Your credit balance is too low to access the API",
            detail = "⚠️ deepseek-v4-flash: stream was reset: CANCEL\nHTTP 502",
        )
        assertEquals("Your credit balance is too low to access the API", e.summary)
        assertTrue(e.detail.contains("stream was reset: CANCEL"))
        // Exception.message is what the banner shows — it must be the provider's
        // sentence, not a per-model trail and not an app-authored summary.
        assertEquals("Your credit balance is too low to access the API", e.message)
    }

    // ─── Retry-After parsing (RFC 7231 §7.1.3) ─────────────────────────────

    @Test
    fun `parseRetryAfter - delay seconds form`() {
        assertEquals(120_000L, parseRetryAfterMs("120", nowMs = 1000L))
        assertEquals(0L, parseRetryAfterMs("0", nowMs = 1000L))
    }

    @Test
    fun `parseRetryAfter - http date form`() {
        // "Mon, 01 Jan 2024 00:00:10 GMT" = 10s after "Mon, 01 Jan 2024 00:00:00 GMT"
        // epoch for 2024-01-01 00:00:00 GMT = 1704067200 seconds = 1704067200000 ms
        assertEquals(10_000L, parseRetryAfterMs("Mon, 01 Jan 2024 00:00:10 GMT", nowMs = 1704067200000L))
    }

    @Test
    fun `parseRetryAfter - date already in the past clamps to zero`() {
        assertEquals(0L, parseRetryAfterMs("Mon, 01 Jan 2024 00:00:00 GMT", nowMs = 1704067200000L + 1000L))
    }

    @Test
    fun `parseRetryAfter - garbage or blank returns null`() {
        assertNull(parseRetryAfterMs(null, 1000L))
        assertNull(parseRetryAfterMs("", 1000L))
        assertNull(parseRetryAfterMs("   ", 1000L))
        assertNull(parseRetryAfterMs("not-a-date", 1000L))
        assertNull(parseRetryAfterMs("12abc", 1000L))
    }
}
