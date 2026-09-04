package com.openminis.app.ui.chat

/**
 * [OPT2-jitter-retry-after] Pure decision logic for the chat auto-retry
 * countdown. Extracted from ChatViewModel's stream loop so the schedule can
 * be JVM-tested (ChatViewModel is Android-bound).
 *
 * Two upgrades over the old fixed `intArrayOf(1, 2, 4)` schedule:
 *
 *  1. **Retry-After aware.** When the provider sent a `Retry-After` header
 *     (surfaced on `LLMError.RateLimited` / `LLMError.TransientError`), the
 *     retry delay honors it (capped to [RETRY_AFTER_CAP_SEC]) instead of the
 *     exponential ladder — no more burning attempts into a window the server
 *     already told us to wait out, and no more retrying before the window
 *     ends (which just manufactures another 429).
 *
 *  2. **Full jitter.** All users hitting the same dead endpoint share the
 *     same fixed 1s/2s/4s ladder and re-strike in lockstep (thundering herd
 *     through a recovering relay). Exponential backoff + full jitter
 *     (`rand(0, backoff)`) spreads the retries while keeping the same
 *     expected schedule.
 *
 * Mirrors the AWS exponential-backoff-with-full-jitter shape. Deterministic
 * given (attempt, retryAfterSec, rng) — tests inject the rng.
 */
object AutoRetryScheduler {

    /** Max Retry-After we are willing to wait in the auto-retry loop. */
    const val RETRY_AFTER_CAP_SEC = 30L

    /**
     * The delay (in seconds) before auto-retry attempt #[attempt] (0-based).
     *
     * @param attempt        0-based index of the retry being scheduled
     * @param retryAfterSec  provider-suggested delay, if any (whole seconds;
     *                       null when absent). Honored up to [RETRY_AFTER_CAP_SEC].
     * @param rng            random source for jitter (injectable for tests)
     */
    fun delaySec(
        attempt: Int,
        retryAfterSec: Long? = null,
        rng: kotlin.random.Random = kotlin.random.Random.Default,
    ): Long {
        // Provider hint wins — capped so a 5-hour free-tier window doesn't
        // wedge the auto-retry loop for the whole cap duration.
        // [FIX-audit-P2-semantics] The HARD cap applies to the FINAL delay
        // (hint + jitter), so "capped at 30s" is now literally true instead
        // of "30s + up to 1s jitter".
        val hinted = retryAfterSec?.coerceIn(0L, RETRY_AFTER_CAP_SEC)
        if (hinted != null) {
            // Positive jitter floor (0..1s) de-synchronizes two clients that
            // share a key; min() keeps the total inside the documented cap.
            return minOf(RETRY_AFTER_CAP_SEC, hinted + rng.nextLong(0, 2))
        }
        // Exponential backoff with POSITIVE full jitter: base = 2^attempt
        // seconds (1, 2, 4 — the old ladder becomes the upper bound), delay
        // = rand(1, base]. (rand(1, base] rather than the textbook rand(0,
        // base): a 0s retry strikes immediately, which reads as a glitch in
        // the UI; the de-synchronization property is identical.)
        val base = 1L shl attempt.coerceIn(0, 30)
        return rng.nextLong(1, base + 1)
    }
}
