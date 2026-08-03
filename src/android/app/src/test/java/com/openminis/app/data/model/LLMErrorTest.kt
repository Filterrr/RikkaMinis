package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the group-fallback classification contract.
 *
 * ChatViewModel's shouldFallback uses LLMError.isFallbackable directly, so the
 * classification must stay exactly: rate limits (429), invalid API keys (401)
 * and provider errors (4xx/5xx) fall back to the next member of the group;
 * network/transient errors only retry in place (isRetryable); decoding,
 * cancellation and unknown errors surface to the user instead.
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
    fun `fallbackable - network and transient errors do NOT fallback immediately`() {
        assertFalse(LLMError.NetworkError(IOException("socket closed")).isFallbackable)
        assertFalse(LLMError.TransientError("temporary").isFallbackable)
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

    @Test
    fun `fallbackReason - every type has a user-facing reason`() {
        assertEquals("Rate limited", LLMError.RateLimited().fallbackReason)
        assertEquals("Invalid API key", LLMError.InvalidApiKey().fallbackReason)
        assertEquals("Provider error", LLMError.ProviderError("x").fallbackReason)
        assertEquals("Network error", LLMError.NetworkError(IOException("x")).fallbackReason)
        assertEquals("Transient error", LLMError.TransientError("x").fallbackReason)
        assertEquals("Decoding error", LLMError.DecodingError(RuntimeException("x")).fallbackReason)
        assertEquals("Cancelled", LLMError.Cancelled().fallbackReason)
        assertEquals("Unknown error", LLMError.Unknown(null).fallbackReason)
    }
}
