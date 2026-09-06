package com.openminis.app.ui.chat

/**
 * [T-quote-reply] Pure helpers for the composer quote-reply feature.
 *
 * Quoting is deliberately **text-only**: the quoted excerpt becomes part of
 * the composer buffer as a Markdown blockquote, so it flows through every
 * downstream path for free — DB persistence (parts_json), the model's view
 * of the conversation, JSON / plain-text export, and bubble rendering via
 * the existing blockquote style in the Markdown pipeline. No new state, no
 * schema change, no dedicated "quoted bubble" UI.
 *
 * Two entry points exist:
 *  - Whole-message quote (user-bubble long-press menu) → [quoteMessage]
 *    with a 500-char cap. A quote is a *context pointer* for the reader and
 *    the model, not a full-text archive — unbounded quotes of huge tool
 *    transcripts would blow up the composer and the context budget alike.
 *  - Selection quote (text-selection toolbar) → [quoteSelection], uncapped:
 *    the user explicitly framed the excerpt.
 */
internal object QuoteTextFormatter {

    /** Hard cap for whole-message quotes (characters of source text). */
    internal const val MAX_QUOTE_SOURCE_CHARS = 500

    /**
     * Format [text] as a Markdown blockquote for the composer. Every line is
     * prefixed with "> " (including blank lines — an empty quote line renders
     * as a blockquote paragraph break, which is what the source contained).
     *
     * Leading/trailing whitespace of the whole text is trimmed first so the
     * blockquote starts flush on the composer line; interior formatting is
     * preserved verbatim.
     */
    internal fun blockquote(text: String): String =
        text.trim().lines().joinToString(separator = "\n") { line -> "> $line" }

    /**
     * Quote a whole message: cap the source at [MAX_QUOTE_SOURCE_CHARS] and
     * mark the elision with a trailing "…" line when truncation happened.
     * Blank input returns an empty string — callers treat that as "nothing
     * to quote" and no-op.
     */
    internal fun quoteMessage(text: String): String {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return ""
        val capped = if (cleaned.length > MAX_QUOTE_SOURCE_CHARS) {
            cleaned.take(MAX_QUOTE_SOURCE_CHARS).trimEnd() + "\n> …"
        } else {
            cleaned
        }
        return blockquote(capped)
    }

    /**
     * Quote an explicit user selection. No length cap (the user framed it),
     * but blank/whitespace-only selections still no-op so stray taps on the
     * toolbar don't insert empty blockquotes.
     */
    internal fun quoteSelection(text: String): String {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return ""
        return blockquote(cleaned)
    }
}
