package com.openminis.app.tools

/**
 * [port/upstream-subagent-error-kind] Turn a run's free-form error text into
 * the ONE decision the parent actually has to make about it.
 *
 * Upstream (OpenMinis `HelperRunner.ErrorKind`) exists because "Failed" is not
 * actionable: a rate limit means wait and retry, a context overflow means
 * re-spawn narrower, a revoked key means nothing will work until the user
 * fixes it. RikkaMinis stored the raw exception string and left the parent —
 * and the user — to guess which of those it was.
 *
 * TWO RULES, both load-bearing:
 *
 *  1. **Never invent a category.** Anything unrecognised returns null and the
 *     surface keeps the plain text. A WRONG attribution is worse than none,
 *     because the reader acts on it (upstream's words, and they are right).
 *  2. **Most specific first.** "context length exceeded" also contains
 *     "exceeded"; a 529 is usually described with the word "rate", and a
 *     revoked key is often served as a 429 by proxies. The order below is the
 *     contract, and the tests pin the pairs that actually collide.
 *
 * Pure: no Android, no clock, no I/O.
 */
object SubagentErrorKind {

    /** Stable wire values — the parent may branch on them. */
    enum class Kind(val wire: String, val advice: String) {
        CONTEXT_LIMIT(
            "context_limit",
            "the model's context window ran out — re-spawn with a narrower task or a shorter brief",
        ),
        QUOTA_EXHAUSTED(
            "quota_exhausted",
            "the account is out of quota or credit — retrying will not help until that changes",
        ),
        AUTH_FAILED(
            "auth_failed",
            "the provider rejected the credentials — fix the provider configuration first",
        ),
        PROVIDER_OVERLOADED(
            "provider_overloaded",
            "the provider is overloaded — a retry later, or another model, may succeed",
        ),
        RATE_LIMITED(
            "rate_limited",
            "rate limited — wait before retrying, or delegate to a different model",
        ),
        TIMED_OUT(
            "timed_out",
            "the run hit its wall clock — the partial report above is what it had",
        ),
        NETWORK_ERROR(
            "network_error",
            "a transport failure — usually transient; a retry is reasonable",
        ),
        CANCELLED(
            "cancelled",
            "the run was stopped before it finished",
        ),
    }

    /**
     * Classify [error]. Null means "do not classify" — the caller must then
     * show the raw text rather than a category it invented.
     */
    fun classify(error: String?): Kind? {
        val s = error?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null

        // 1. Explicit stop outranks everything: the run did not fail, it was
        //    stopped, and the reason string is ours, not the provider's.
        if (hasAny(s, "cancel")) return Kind.CANCELLED

        // 2. Context overflow BEFORE any generic "exceeded"/"limit" rule —
        //    the run-budget message also says "exceeded".
        if (hasAny(
                s, "context length", "context window", "maximum context", "context_length",
                "too many tokens", "prompt is too long", "input is too long",
                "reduce the length", "token limit exceeded", "max_tokens",
            )
        ) return Kind.CONTEXT_LIMIT

        // 3. Quota/billing: a permanent condition, so it must not be swallowed
        //    by the 429 rate-limit rule below (proxies serve both as 429).
        if (hasAny(
                s, "quota", "insufficient", "credit", "billing", "payment",
                "exceeded your current", "out of budget",
            )
        ) return Kind.QUOTA_EXHAUSTED

        // 4. Credentials.
        if (hasAny(
                s, "unauthorized", "invalid api key", "incorrect api key", "authentication",
                "auth failed", "forbidden", "permission denied", "401 ", "403 ", "invalid_api_key",
            )
        ) return Kind.AUTH_FAILED

        // 5. Overload beats rate-limit: a 529 is routinely described with the
        //    word "rate", and "wait and retry the same model" is wrong advice
        //    for it.
        if (hasAny(
                s, "overloaded", "overload", "service unavailable", "temporarily unavailable",
                "529", "503 ", "capacity",
            )
        ) return Kind.PROVIDER_OVERLOADED

        if (hasAny(s, "rate limit", "rate_limit", "too many requests", "429", "throttl")) {
            return Kind.RATE_LIMITED
        }

        // 6. Our own run-budget message: "exceeded the N-second run budget".
        if (hasAny(s, "timed out", "timeout", "run budget", "deadline", "took too long")) {
            return Kind.TIMED_OUT
        }

        if (hasAny(
                s, "network", "connect", "connection", "socket", "ssl", "tls", "dns",
                "unknown host", "unreachable", "broken pipe", "reset by peer", "eof",
            )
        ) return Kind.NETWORK_ERROR

        return null
    }

    /** "cancelled (the run was stopped …)", or null when unclassifiable. */
    fun annotation(error: String?): String? =
        classify(error)?.let { "${it.wire} — ${it.advice}" }

    private fun hasAny(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }
}