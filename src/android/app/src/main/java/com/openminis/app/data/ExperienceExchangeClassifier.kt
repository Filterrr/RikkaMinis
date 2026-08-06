package com.openminis.app.data

/**
 * [ExpMem-outcome] Pure classifier for a finished agent exchange.
 *
 * Deliberately free of Android / UI types so JVM unit tests can pin the
 * semantics. The ChatViewModel call site computes the two booleans from the
 * loop's post-state (accumulated text + tool blocks) and delegates here.
 *
 * Outcome table (mirrors the plan's success semantics):
 * - TURN_LIMIT:      loop exited because MAX_AGENT_TURNS was hit (never a
 *                    clean finish) → -1 feedback.
 * - EMPTY_RESPONSE:  clean finish but NOTHING visible was produced (no text,
 *                    no tool calls) → -1 feedback.
 * - PARTIAL:         a reply exists but at least one tool FAILED/timed out →
 *                    NO feedback (no ground truth — the reply may still be
 *                    useful) and never +1.
 * - SUCCESS:         clean finish, visible output, no failed tools → +1.
 */
object ExperienceExchangeClassifier {

    fun classify(
        loopExitedNormally: Boolean,
        hasVisibleContent: Boolean,
        hasToolFailure: Boolean,
    ): Outcome {
        if (!loopExitedNormally) return Outcome.TURN_LIMIT
        if (!hasVisibleContent) return Outcome.EMPTY_RESPONSE
        return if (hasToolFailure) Outcome.PARTIAL else Outcome.SUCCESS
    }

    /**
     * Convenience: derive [hasVisibleContent] from raw loop state. Mirrors the
     * loop's own hasVisibleContent check (text or tool_use block non-blank).
     */
    fun hasVisibleContent(accumulatedText: String, hasToolUse: Boolean): Boolean =
        accumulatedText.isNotBlank() || hasToolUse
}
