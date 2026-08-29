package com.openminis.app.ui.chat

/**
 * Pure helpers for the [T-length-wall-continue] path in ChatViewModel.runAgentLoop.
 *
 * Background: when `finish_reason == "length"`, the loop keeps the truncated
 * partial reply in agentHistory/accumulatedText and issues a fresh API call so
 * the model "picks up where it cut off". Two field-observed failure modes of
 * that design live here, as pure functions (FE-4 route-A style: extract the
 * logic, keep it JVM-testable, never touch UI state):
 *
 *  1. **Seam duplication** — models frequently do NOT resume at the exact cut
 *     point; they back up to an earlier semantic anchor and re-emit a phrase
 *     they already produced. Both halves are kept, so the visible reply ends
 *     up with a mid-sentence repetition like:
 *       `…已经站在一个，是因为它确实已经站在一个一个比较高的位置了…`
 *     [mergeLengthWallSeam] detects the overlap (longest suffix of the
 *     truncated text that is also a prefix of the continuation) and trims it
 *     from the continuation before it is folded into accumulatedText.
 *
 *  2. **No continuation instruction** — the follow-up call presents the
 *     truncated text as the model's own partial reply with no instruction, so
 *     the model treats it as context and rewrites freely. [lengthWallReminder]
 *     builds the `<system-reminder>` user message (same pattern as resume()'s
 *     stop-continue reminder) that tells the model to continue from the last
 *     character and never repeat already-output text.
 *
 * Both functions are deterministic and side-effect free.
 */

/** Minimum seam-overlap length (chars) considered a real duplication.
 *  Shorter overlaps are legitimate language patterns (e.g. "。" + "，")
 *  and must not be trimmed. Mirrors [MINIMUM_STREAMING_OVERLAP_LENGTH] in
 *  StreamingMarkdownText.kt (=3). The original value of 6 was sized for
 *  conservative joins but field data (user-reported "业界几乎" 4-char and
 *  "春天来了。" 5-char repeats, tokenrhythm deepseek/glm/kimi relay) showed
 *  models back up and re-emit SHORT phrases that 6 could not catch — so 3
 *  aligns with the streaming dedup threshold and still leaves 1-2 char
 *  punctuation joins untouched. */
const val LENGTH_WALL_MIN_SEAM_OVERLAP = 3

/** Hard cap for the overlap scan (performance: the scan is O(n*m) worst case
 *  via indexOf; capped so a pathological megabyte turn cannot stall the loop). */
private const val LENGTH_WALL_SEAM_SCAN_CAP = 8192

/**
 * Longest suffix of [truncated] that is also a prefix of [continuation].
 *
 * Returns the overlap length in characters, 0 when there is none. The scan is
 * capped at [LENGTH_WALL_SEAM_SCAN_CAP] characters from each end.
 *
 * Same algorithm family as StreamingMarkdownText's private
 * longestSuffixPrefixOverlap, lifted here as a public top-level pure function
 * so the length-wall path (and its tests) can use it without touching the
 * composable file's visibility surface.
 */
fun lengthWallSeamOverlap(truncated: String, continuation: String): Int {
    if (truncated.isEmpty() || continuation.isEmpty()) return 0
    val maxOverlap = minOf(
        truncated.length,
        continuation.length,
        LENGTH_WALL_SEAM_SCAN_CAP,
    )
    // Walk from the longest candidate down; the first hit is the answer.
    var length = maxOverlap
    while (length > 0) {
        if (continuation.startsWith(truncated.substring(truncated.length - length))) {
            return length
        }
        length--
    }
    return 0
}

/**
 * Merge a length-wall continuation into the truncated text, trimming any
 * seam overlap so no already-output phrase survives twice.
 *
 * Contract:
 *  - Overlap >= [LENGTH_WALL_MIN_SEAM_OVERLAP] chars → trim it from the head
 *    of [continuation] and concatenate. The truncated text is NEVER modified
 *    (it is already in agentHistory and on screen).
 *  - Overlap below the threshold → plain concatenation (the join is assumed
 *    to be legitimate language, not duplication).
 *  - [continuation] empty → return [truncated] unchanged.
 *
 * @return the merged text to use as the accumulated reply text.
 */
fun mergeLengthWallSeam(truncated: String, continuation: String): String {
    if (continuation.isEmpty()) return truncated
    if (truncated.isEmpty()) return continuation
    val overlap = lengthWallSeamOverlap(truncated, continuation)
    return if (overlap >= LENGTH_WALL_MIN_SEAM_OVERLAP) {
        truncated + continuation.substring(overlap)
    } else {
        truncated + continuation
    }
}

/**
 * Build the `<system-reminder>` continuation instruction injected as a
 * synthetic USER message after a truncated (finish_reason="length") turn.
 *
 * Same delivery pattern as resume()'s stop-continue reminder: a user-role
 * message whose single Text part carries the reminder. The reminder tail
 * includes the last few characters of the truncated reply so the model has a
 * concrete anchor of "where I left off" — this measurably reduces the
 * back-up-and-repeat behavior (see test file for the regression cases).
 *
 * The text is intentionally English: system-reminder payloads elsewhere in
 * this codebase are English, and models follow them most reliably in English.
 */
fun lengthWallReminder(truncatedTail: String): String =
    "<system-reminder>Your previous reply was cut off mid-sentence by the output token limit. " +
        "Continue the reply starting from the exact character after: \"$truncatedTail\". " +
        "Do NOT repeat any text you have already output — do not restart the sentence, " +
        "do not re-emit the phrase before the cut point. Continue seamlessly as if writing " +
        "one continuous reply.</system-reminder>"
