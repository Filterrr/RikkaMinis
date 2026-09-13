package com.openminis.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-multi-api-key] Tests for the quota-exhaustion classifier.
 *
 * This predicate decides whether a failure is filed as "this credential is
 * spent" (→ swap keys, park it permanently) or "this member is unwell" (→
 * circuit breaker / cooldown). Getting it wrong in either direction is a
 * real bug:
 *
 *  - a spent key misread as a rate limit gets retried on a 60s timer FOREVER
 *    (the pre-existing behaviour this feature fixes);
 *  - a rate limit misread as exhaustion permanently parks a key that would
 *    have recovered on its own.
 */
class QuotaExhaustionClassificationTest {

    // ─── unambiguous status ────────────────────────────────────────────────

    @Test fun status402_isAlwaysQuotaExhausted() {
        // 402 Payment Required has exactly one meaning on every
        // OpenAI-compatible stack, so the body need not corroborate it.
        assertTrue(isQuotaExhaustedResponse(402, ""))
        assertTrue(isQuotaExhaustedResponse(402, "anything at all"))
    }

    // ─── OpenAI's 429-with-quota-meaning shape ────────────────────────────

    @Test fun openAiInsufficientQuota_429_isExhausted() {
        // OpenAI reports out-of-credit as 429 + insufficient_quota. Without
        // this branch it lands in RateLimited and is retried forever.
        val body = """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota"}}"""
        assertTrue(isQuotaExhaustedResponse(429, body))
    }

    @Test fun caseInsensitiveMarkerMatch() {
        assertTrue(isQuotaExhaustedResponse(429, """{"error":"INSUFFICIENT_QUOTA"}"""))
        assertTrue(isQuotaExhaustedResponse(429, "Insufficient Quota"))
    }

    // ─── relay / non-OpenAI bodies ────────────────────────────────────────

    @Test fun chineseBalanceMarkers_areExhausted() {
        // Several relay gateways fronting OpenAI/Anthropic report a spent key
        // only in prose ("余额不足"). A status-only classifier misses these.
        assertTrue(isQuotaExhaustedResponse(403, """{"error":{"message":"余额不足,请充值"}}"""))
        assertTrue(isQuotaExhaustedResponse(200, "额度不足"))
        assertTrue(isQuotaExhaustedResponse(403, "账户欠费"))
    }

    @Test fun billingMarkerVariants_areExhausted() {
        assertTrue(isQuotaExhaustedResponse(400, "billing_hard_limit_reached"))
        assertTrue(isQuotaExhaustedResponse(400, "credit_balance_too_low"))
        assertTrue(isQuotaExhaustedResponse(400, "insufficient_balance"))
        assertTrue(isQuotaExhaustedResponse(400, "out of credit"))
    }

    // ─── must NOT be misread as exhaustion ────────────────────────────────

    @Test fun plainRateLimit_isNotExhausted() {
        // The critical negative case: a normal 429 carries no quota marker and
        // must stay recoverable.
        val body = """{"error":{"message":"Rate limit reached for gpt-4 in organization org-x on requests per min","type":"requests"}}"""
        assertFalse(isQuotaExhaustedResponse(429, body))
    }

    @Test fun serverError_isNotExhausted() {
        assertFalse(isQuotaExhaustedResponse(500, "internal server error"))
        assertFalse(isQuotaExhaustedResponse(503, "service unavailable"))
    }

    @Test fun authErrorWithoutQuotaMarker_isNotExhausted() {
        // 401 stays an auth problem — the fix is re-login, not top-up.
        assertFalse(isQuotaExhaustedResponse(401, """{"error":{"message":"Invalid API key"}}"""))
    }

    @Test fun emptyBody_isNotExhausted() {
        assertFalse(isQuotaExhaustedResponse(429, ""))
        assertFalse(isQuotaExhaustedResponse(500, ""))
    }

    // ─── the user-facing error stays honest ───────────────────────────────

    @Test fun exhaustedErrorSurfacesActionableCopy() {
        val err = LLMError.QuotaExhausted("insufficient_quota")
        // "Quota exhausted" must not be reported as a generic provider error —
        // the user's remedy (top up / switch key) differs completely.
        assertTrue(err.userMessage.contains("quota", ignoreCase = true))
        assertFalse(err.userMessage == LLMError.ProviderError("x").userMessage)
    }

    // ─── rotation eligibility axis ────────────────────────────────────────

    @Test fun rotationEligibleOnlyForCredentialScopedFailures() {
        // "Spend another key" is only meaningful when the credential is what
        // failed. A 5xx must NOT rotate — that multiplies load on a service
        // that is already unwell and cannot succeed.
        assertTrue(LLMError.QuotaExhausted("q").isKeyRotationEligible)
        assertTrue(LLMError.RateLimited().isKeyRotationEligible)
        assertTrue(LLMError.InvalidApiKey().isKeyRotationEligible)
        assertFalse(LLMError.ProviderError("500").isKeyRotationEligible)
        assertFalse(LLMError.TransientError("503").isKeyRotationEligible)
        assertFalse(LLMError.NetworkError(java.io.IOException("reset")).isKeyRotationEligible)
    }

    @Test fun quotaExhaustedIsFallbackable() {
        // Fallback (leaving the member) must remain available when every
        // credential on the instance is spent — otherwise the turn dead-ends.
        assertTrue(LLMError.QuotaExhausted("q").isFallbackable)
    }
}
