package com.openminis.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-debugserver-auth] `LLMRequestLog` must redact credential
 * material from request headers BEFORE they are serialized to the wire by
 * `debug.llmRequests` / `debug.agentTrace`. Authorization / x-api-key /
 * cookie (case-insensitive) are replaced with `[redacted:len]`.
 *
 * Tested via the internal [LLMRequestLog.redactHeaders] helper directly
 * (the production serialization path calls it), because `add()` short-circuits
 * when BuildConfig.DEBUG is false (release test variant) and would not retain
 * entries for a `toJSON` round-trip.
 */
class LLMRequestRedactTest {

    @Test
    fun `authorization bearer value is redacted`() {
        val redacted = LLMRequestLog.redactHeaders(mapOf("Authorization" to "Bearer sk-abc123"))
        assertEquals("[redacted:16]", redacted["Authorization"])
        // The raw credential string must not survive anywhere.
        assertFalse(redacted.values.joinToString().contains("sk-abc123"))
    }

    @Test
    fun `lowercase x-api-key is redacted`() {
        val redacted = LLMRequestLog.redactHeaders(mapOf("x-api-key" to "sekrit-key"))
        assertEquals("[redacted:10]", redacted["x-api-key"])
        assertFalse(redacted.values.joinToString().contains("sekrit"))
    }

    @Test
    fun `mixed-case cookie header name is matched case-insensitively`() {
        val redacted = LLMRequestLog.redactHeaders(mapOf("CoOkIe" to "session=abc"))
        assertEquals("[redacted:11]", redacted["CoOkIe"])
        assertFalse(redacted.values.joinToString().contains("abc"))
    }

    @Test
    fun `sensitive and benign headers mixed - only sensitive redacted`() {
        val redacted = LLMRequestLog.redactHeaders(
            mapOf(
                "X-API-KEY" to "k1",
                "Authorization" to "Bearer t2",
                "Content-Type" to "application/json",
                "X-Request-Id" to "req-123",
            ),
        )
        assertEquals("[redacted:2]", redacted["X-API-KEY"])
        assertEquals("[redacted:9]", redacted["Authorization"])
        // Benign headers pass through untouched.
        assertEquals("application/json", redacted["Content-Type"])
        assertEquals("req-123", redacted["X-Request-Id"])
    }

    @Test
    fun `empty headers pass through unchanged`() {
        assertEquals(emptyMap<String, String>(), LLMRequestLog.redactHeaders(emptyMap()))
    }

    @Test
    fun `redacted length is the original value length`() {
        val orig = "sk-tokenwithlength23"
        val out = LLMRequestLog.redactHeaders(mapOf("Authorization" to orig))
        assertEquals("[redacted:${orig.length}]", out["Authorization"])
    }

    @Test
    fun `authorization header survives intact when it is a benign value key mismatch`() {
        // Sanity: a non-sensitive header name is never mis-redacted.
        val out = LLMRequestLog.redactHeaders(mapOf("X-Custom-Hdr" to "Bearer sk-xyz"))
        assertNull(out.keys.firstOrNull { it == "Authorization" })
        assertEquals("Bearer sk-xyz", out["X-Custom-Hdr"])
    }
}
