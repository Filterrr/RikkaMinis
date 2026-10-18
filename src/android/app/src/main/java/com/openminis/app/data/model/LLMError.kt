package com.openminis.app.data.model

import org.json.JSONObject

/**
 * [T-provider-verbatim-errors] Rule for every provider failure: **what the
 * provider said is what the user sees.**
 *
 * Every variant carries [detail] — the provider's own error text, copied
 * verbatim (SSE `{"error":{"message":...}}`, HTTP body, or the transport
 * exception's message when no body exists). That string is BOTH the exception
 * message and the banner text ([userMessage]); the app no longer substitutes a
 * canned sentence ("Provider returned an error", "Rate limited — try again in
 * a moment") for a provider message the user needs to act on. The provider is
 * the only party that knows whether the key is out of credit, the model was
 * retired, the prompt tripped a content filter, or a region is blocked — so
 * its text is the actionable one, and re-wording it locally destroys the one
 * piece of information the user was supposed to read.
 *
 * A local fallback survives ONLY for the cases where the provider said
 * nothing at all (a 401 with an empty body, a dead socket with a null
 * message). Those see [localFallback], and they are the exception — never the
 * rule.
 *
 * Why the exception message matters as much as [userMessage]: the chat turn
 * streams through the `:modelservice` worker, which serialises the failure as
 * a single string (`ChatStreamJsonl.errorLine`). The typed [LLMError] does not
 * cross that process boundary — only `message` does — so a canned message here
 * would reach the banner even on paths that never consult [userMessage].
 *
 * Classification is deliberately kept OUT of the displayed text: the HTTP
 * status lives in [httpStatus], so retry/fallback decisions no longer depend
 * on an app-injected "[500] " prefix that would also have polluted the
 * banner.
 */
sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * The provider's error text, verbatim. Empty when the provider genuinely
     * supplied nothing (empty body / no response at all) — the only case in
     * which the UI is allowed to show app-authored copy.
     */
    abstract val detail: String

    /**
     * HTTP status of the response that produced this failure, when there was
     * one. Kept structured (instead of being spliced into [detail] as a
     * "[503] " prefix) so error classification never depends on — or leaks
     * into — the text the user reads.
     */
    open val httpStatus: Int? get() = null

    class InvalidApiKey(
        override val detail: String = "",
        override val httpStatus: Int? = null,
    ) : LLMError(fallbackText(detail, "Invalid API key"), null)

    class NetworkError(
        cause: Throwable,
        override val detail: String = cause.message.orEmpty(),
    ) : LLMError(fallbackText(detail, "Connection failed"), cause)

    class ProviderError(
        override val detail: String,
        override val httpStatus: Int? = null,
    ) : LLMError(fallbackText(detail, "Provider returned an error"), null)

    class DecodingError(
        cause: Throwable,
        override val detail: String = cause.message.orEmpty(),
    ) : LLMError(fallbackText(detail, "Unexpected response from provider"), cause)

    class RateLimited(
        override val detail: String = "",
        val retryAfterMs: Long? = null,
        override val httpStatus: Int? = null,
    ) : LLMError(fallbackText(detail, "Rate limited — try again in a moment"), null)

    /**
     * [T-multi-api-key] The credential itself is out of quota / balance
     * (HTTP 402, or 429/403 whose body says so) — as opposed to a per-minute
     * rate limit.
     *
     * The distinction is load-bearing for multi-key routing:
     *
     *  - [RateLimited] is a *time* problem. Waiting fixes it, and the same
     *    credential becomes usable again on its own (free-tier "500 calls /
     *    5h" windows). Cooldown is bounded and self-healing.
     *  - [QuotaExhausted] is a *credential* problem. Waiting does NOT fix it;
     *    the key is spent until the user tops up. Retrying it is pure waste,
     *    and letting it stay in the rotation just adds a guaranteed failure
     *    to every fallback chain.
     *
     * Before this type existed, `insufficient_quota` fell through
     * OpenAIProvider.mapHttpError's three-way split (401/403 → InvalidApiKey,
     * 429 → RateLimited, else → ProviderError) into [ProviderError] →
     * [com.openminis.app.data.routing.RouteOutcome.ServerError], i.e. it was
     * recorded as a *server-side* fault: the circuit opened for
     * CIRCUIT_OPEN_MS (5 min) and then retried the same dead key forever.
     * See [com.openminis.app.data.routing.RouteOutcome.QuotaExhausted].
     *
     * @param detail provider-supplied message, verbatim — this is the banner
     *   text ("余额不足,请充值" is far more actionable than "quota exhausted").
     */
    class QuotaExhausted(
        override val detail: String = "",
        override val httpStatus: Int? = null,
    ) : LLMError(fallbackText(detail, "API key is out of quota"), null)

    class TransientError(
        override val detail: String,
        val retryAfterMs: Long? = null,
        override val httpStatus: Int? = null,
    ) : LLMError(fallbackText(detail, "Service temporarily unavailable"), null)

    /**
     * User-initiated cancellation. The only variant whose text is
     * legitimately app-authored: there is no provider message to carry, so
     * [detail] stays empty until a caller supplies one.
     */
    class Cancelled(override val detail: String = "") : LLMError(fallbackText(detail, "Request was cancelled"), null)

    class Unknown(
        cause: Throwable?,
        override val detail: String = cause?.message.orEmpty(),
        override val httpStatus: Int? = null,
    ) : LLMError(fallbackText(detail, "Something went wrong"), cause)

    /** Pure connectivity failure — the request didn't land at all. */
    val isNetworkError: Boolean get() = this is NetworkError

    /** Worth retrying on the same provider (bounded backoff). */
    val isRetryable: Boolean get() = this is NetworkError || this is TransientError

    /**
     * The provider answered with an HTTP 5xx. Derived from [httpStatus], NOT
     * from pattern-matching digits out of the displayed text — so the banner
     * can show the provider's own wording without the classifier losing the
     * server-vs-client distinction.
     */
    val isServerError: Boolean get() = (httpStatus ?: 0) in 500..599

    /**
     * Should fall back to the next model in the group — same model won't help.
     *
     * [T-fallback-network-errors] NetworkError / TransientError are included:
     * "this endpoint can't help right now" is exactly the group-fallback case
     * (stream reset, connection timeout, DNS failure, proxy drop — the whole
     * OkHttp IOException family the user sees as "模型不可用"). The retry
     * loop still gets first crack at them (bounded backoff on the SAME
     * provider); `isFallbackable` is only consulted AFTER retries are
     * exhausted, so including them here never skips the retry — it just stops
     * the "retried 3×, then hard-stopped with a red banner" dead end.
     *
     * [T-multi-api-key] QuotaExhausted is included for the same reason, one
     * level down: the *credential* can't help, so the routing layer must be
     * free to try the next credential on the SAME instance before it ever
     * considers leaving the model/endpoint.
     */
    val isFallbackable: Boolean get() = this is RateLimited || this is InvalidApiKey || this is ProviderError || this is QuotaExhausted || this is NetworkError || this is TransientError

    /**
     * [T-multi-api-key] Which failures actually implicate the CREDENTIAL (as
     * opposed to the endpoint, the network, or the model).
     *
     * [T-rotate-on-any-error] This is NO LONGER the gate on credential
     * rotation: by explicit product decision the retry loop now rotates on any
     * failure, so a problem attributed to the wrong layer still gets the cheap
     * retry before it costs a model switch. The predicate survives as the
     * single statement of the taxonomy, and the loop still consults the same
     * distinction for the two decisions where it genuinely matters:
     *
     *  1. **Park the key?** Only a credential-scoped failure demotes the slot.
     *     A 5xx says nothing about this key, so marking it demoted would shrink
     *     the rotation pool for a fault the credential had no part in.
     *  2. **Refill the auto-retry budget?** Only a credential-scoped failure
     *     earns the next key a fresh 1s/2s/4s ladder. Granting that on a
     *     network/5xx failure would turn one dead wifi into 3×N requests
     *     (N = key count) before the user saw any error.
     *
     *  - `true`  → RateLimited / InvalidApiKey / QuotaExhausted.
     *  - `false` → ProviderError / TransientError / NetworkError.
     */
    val isKeyRotationEligible: Boolean
        get() = this is RateLimited || this is InvalidApiKey || this is QuotaExhausted

    /**
     * [T-provider-verbatim-errors] What the inline error banner shows: the
     * provider's own message, unmodified.
     *
     * The "technical details" disclosure is reserved for the fallback trail
     * (which model failed with what); it is no longer the only place the real
     * reason could be found, because the reason is now the banner itself.
     *
     * A canned sentence is returned only when [detail] is blank — i.e. the
     * provider sent no text at all. Every branch below is a last resort, not a
     * summary.
     */
    val userMessage: String
        get() = when (this) {
            is RateLimited -> detail.ifBlank { "Rate limited — try again in a moment" }
            is InvalidApiKey -> detail.ifBlank { "API key is invalid or expired" }
            is QuotaExhausted -> detail.ifBlank { "API key is out of quota" }
            is ProviderError -> detail.ifBlank { "Provider returned an error" }
            is NetworkError -> detail.ifBlank { "Connection failed" }
            is TransientError -> detail.ifBlank { "Service temporarily unavailable" }
            is DecodingError -> detail.ifBlank { "Unexpected response from provider" }
            is Cancelled -> detail.ifBlank { "Request was cancelled" }
            is Unknown -> detail.ifBlank { "Something went wrong" }
        }

    /**
     * Short reason shown in the per-model fallback trail (technical details).
     * Also the provider's text when there is one — the trail exists to explain
     * WHY each member was skipped, so a generic "Provider error" there would
     * defeat its purpose.
     */
    val fallbackReason: String get() = userMessage
}

/**
 * [T-provider-verbatim-errors] Exception message = provider text when there is
 * one, app copy only when the provider sent nothing (and an exception must
 * still carry a non-blank message — several call sites do
 * `e.message ?: "fallback"`, which does not guard against "").
 */
private fun fallbackText(detail: String, fallback: String): String =
    detail.ifBlank { fallback }

/**
 * [T-provider-verbatim-errors] Pull the provider's own human-readable error
 * message out of a raw response body.
 *
 * This SELECTS the provider's text; it never rewrites it. Every
 * OpenAI-compatible stack, Anthropic, Gemini and the Antigravity relay put the
 * actionable sentence in one of a handful of well-known places, so one helper
 * covers them all:
 *
 *  - `{"error":{"message":"..."}}`        — OpenAI / Anthropic / most relays
 *  - `{"error":{"msg":"..."}}`            — some Chinese relays
 *  - `{"error":"..."}`                    — string-form error
 *  - `{"message":"..."}` / `{"msg":"..."}`— flat shapes, Gemini-style bodies
 *
 * When none of them parses (HTML error page, plain text, empty body) the raw
 * body is returned trimmed — verbatim, because a proxy's HTML or a CDN's
 * one-liner is still more informative than a canned sentence. An empty/blank
 * body yields "" so the caller falls back to app copy, which is the only
 * honest option left.
 *
 * @param body raw response body (caller may pre-truncate).
 * @param limit hard cap so a multi-MB error page cannot be pasted into the UI.
 */
fun providerErrorMessage(body: String, limit: Int = 500): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return ""
    val extracted = runCatching {
        val json = JSONObject(trimmed)
        val err = json.optJSONObject("error")
        sequenceOf(
            err?.safeString("message"),
            err?.safeString("msg"),
            err?.safeString("detail"),
            err?.safeString("description"),
            json.safeString("message"),
            json.safeString("msg"),
            json.safeString("detail"),
            // Google OAuth puts the actionable sentence in `error_description`
            // and leaves a terse code in `error` ("invalid_grant"). Without
            // this, the Antigravity re-login path showed only the code — the
            // one case where the reason (expired vs revoked) is the whole
            // point.
            json.safeString("error_description"),
            json.safeString("error_message"),
            // `error` may itself be a plain string ({"error":"boom"}). Only
            // that shape is accepted here: an error OBJECT must never be
            // stringified, or an empty {"error":{}} would be returned as the
            // literal "{}" and — being non-blank — would shadow a perfectly
            // good flat `message` sitting right next to it. Tried last because
            // it is usually the terse code rather than a sentence.
            stringError(json),
        ).firstOrNull { !it.isNullOrBlank() }
    }.getOrNull()
    val text = extracted?.trim().orEmpty()
    val usable = text.takeIf { it.isNotBlank() && !looksLikeRawJson(it) }
    return usable?.take(limit) ?: rawBodyText(trimmed, limit)
}

/**
 * The top-level `error` field when it is a JSON *string*; null when it is an
 * object/array/null (those are handled by the nested lookups above).
 */
private fun stringError(json: JSONObject): String? {
    if (!json.has("error") || json.isNull("error")) return null
    val value = json.opt("error")
    return (value as? String)?.takeIf { it.isNotBlank() }
}

/**
 * A whole JSON document (object or array) is never the provider's
 * human-readable message — it means the body was an *error envelope* whose
 * inner text we failed to locate. Showing "{...}" tells the user nothing, so
 * such a value is rejected and the caller falls back to app copy.
 *
 * String-form bodies stay usable: a bare `"rate limited"` is a real sentence.
 */
private fun looksLikeRawJson(text: String): Boolean = when (text.first()) {
    '{' -> runCatching { JSONObject(text) }.isSuccess
    '[' -> runCatching { org.json.JSONArray(text) }.isSuccess
    else -> false
}

/**
 * Last resort: when the body is not JSON at all it is usually still
 * informative (a proxy's HTML page, a CDN's one-liner), so it is quoted —
 * truncated to [limit] so a multi-MB page cannot reach the banner.
 *
 * A JSON-looking body that yielded no message is deliberately reduced to "":
 * it is an envelope we could not read, and no sentence in it is addressable.
 * "" lets the caller fall back to app copy for the blank-detail cases, which
 * is strictly more useful than "{...}".
 */
private fun rawBodyText(trimmed: String, limit: Int): String {
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) return ""
    return trimmed.take(limit)
}

/**
 * `optString` returns the literal "null" for a JSON null in some Android
 * versions, and "" is indistinguishable from a missing key; both must be
 * treated as "not present" so the next candidate shape is consulted.
 */
private fun JSONObject.safeString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").takeIf { it.isNotBlank() && it != "null" }
}

/**
 * [T-multi-api-key] Classify a provider error BODY as out-of-quota rather than
 * a generic provider failure.
 *
 * Providers disagree on how to report "this key is spent", so the status code
 * is not sufficient — every shape below has been observed in the wild:
 *
 *  - **402 Payment Required** — OpenAI-compatible relays, DeepSeek.
 *  - **429 with `insufficient_quota`** — OpenAI's documented out-of-credit
 *    code (a 429 status carrying a non-rate-limit meaning).
 *  - **403 with a balance message** — several relays (e.g. "余额不足").
 *
 * Matching is case-insensitive on the body so a relay that re-cases the code
 * still classifies. Kept as a pure function (no provider deps) so the whole
 * taxonomy is JVM-testable without an HTTP stack.
 *
 * @param statusCode HTTP status from the response.
 * @param body raw response body (already truncated by the caller if huge).
 * @return true when the failure is a spent credential, not a transient fault.
 */
fun isQuotaExhaustedResponse(statusCode: Int, body: String): Boolean {
    val lower = body.lowercase()
    // Unambiguous status: 402 means "pay up" on every OpenAI-compatible stack.
    if (statusCode == 402) return true
    val quotaMarkers = listOf(
        "insufficient_quota",
        "insufficient quota",
        "quota_exceeded",
        "quota exceeded",
        "exceeded your current quota",
        "billing_hard_limit_reached",
        "credit_balance_too_low",
        "insufficient_balance",
        "insufficient balance",
        "no credit",
        "out of credit",
        "balance is too low",
        "余额不足",
        "额度不足",
        "欠费",
    )
    if (quotaMarkers.any { lower.contains(it) }) return true
    return false
}

/**
 * [T-error-no-permanent-scars] Thrown when the group-fallback chain is fully
 * exhausted (every member failed). Carries the provider's verbatim failure text
 * as `summary` (what the error banner shows) separately from the raw `detail`
 * (per-model failure trail, shown behind the "technical details" disclosure).
 * Splitting the two at the throw site keeps the banner free of the trail
 * without replacing the one message the provider actually wrote.
 */
class FallbackExhaustedError(
    val summary: String,
    val detail: String,
) : Exception(summary)

/**
 * Parse a `Retry-After` HTTP header into a cooldown duration in milliseconds.
 * Returns null when absent / unparseable — the caller falls back to
 * [com.openminis.app.data.routing.GroupRouter.RATE_LIMIT_COOLDOWN_DEFAULT_MS].
 *
 * RFC 7231 §7.1.3 allows two forms; both are handled:
 *  - delay-seconds: `Retry-After: 120`
 *  - HTTP-date:     `Retry-After: Fri, 31 Dec 1999 23:59:59 GMT`
 *
 * A delay of 0 is valid (retry immediately) — coerced to 0 ms.
 */
fun parseRetryAfterMs(headerValue: String?, nowMs: Long): Long? {
    if (headerValue.isNullOrBlank()) return null
    val trimmed = headerValue.trim()
    trimmed.toLongOrNull()?.let { return it.coerceAtLeast(0L) * 1000L }
    return try {
        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
            .parse(trimmed) ?: return null
        (date.time - nowMs).coerceAtLeast(0L)
    } catch (_: Exception) {
        null
    }
}
