package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-quote-reply] Unit tests for the pure quote formatting helpers.
 * The formatter is intentionally dependency-free (no Android classes) so
 * these run on the plain JVM in CI.
 */
class QuoteTextFormatterTest {

    // ── blockquote ──────────────────────────────────────────────────────

    @Test
    fun `single line becomes one prefixed line`() {
        assertEquals("> hello", QuoteTextFormatter.blockquote("hello"))
    }

    @Test
    fun `multi line prefixes every line including blanks`() {
        val out = QuoteTextFormatter.blockquote("a\n\nb")
        assertEquals("> a\n> \n> b", out)
    }

    @Test
    fun `leading and trailing whitespace trimmed before prefixing`() {
        assertEquals("> a\n> b", QuoteTextFormatter.blockquote("  a\nb  \n"))
    }

    // ── quoteMessage ────────────────────────────────────────────────────

    @Test
    fun `blank message yields empty string`() {
        assertEquals("", QuoteTextFormatter.quoteMessage("   \n\t "))
        assertEquals("", QuoteTextFormatter.quoteMessage(""))
    }

    @Test
    fun `short message quoted verbatim without ellipsis`() {
        assertEquals("> hello world", QuoteTextFormatter.quoteMessage("hello world"))
        assertTrue(!QuoteTextFormatter.quoteMessage("short").contains("…"))
    }

    @Test
    fun `long message capped at 500 chars with ellipsis line`() {
        val long = "x".repeat(2000)
        val out = QuoteTextFormatter.quoteMessage(long)
        // The "> " prefix + the elision line exist; source chars after the
        // cap do not.
        assertTrue(out.contains("\n> …"))
        val quotedBody = out.removePrefix("> ").removeSuffix("\n> …")
        assertTrue(quotedBody.length <= QuoteTextFormatter.MAX_QUOTE_SOURCE_CHARS)
        assertTrue(!out.contains("x".repeat(501)))
    }

    @Test
    fun `cap boundary exactly at limit does not add ellipsis`() {
        val exactly = "y".repeat(QuoteTextFormatter.MAX_QUOTE_SOURCE_CHARS)
        val out = QuoteTextFormatter.quoteMessage(exactly)
        assertEquals("> $exactly", out)
    }

    @Test
    fun `chinese multiline message keeps interior structure`() {
        val out = QuoteTextFormatter.quoteMessage("第一行\n第二行")
        assertEquals("> 第一行\n> 第二行", out)
    }

    // ── quoteSelection ──────────────────────────────────────────────────

    @Test
    fun `blank selection yields empty string`() {
        assertEquals("", QuoteTextFormatter.quoteSelection("   "))
    }

    @Test
    fun `selection is uncapped`() {
        val big = "z".repeat(5000)
        val out = QuoteTextFormatter.quoteSelection(big)
        assertTrue(out.length > 5000)
    }

    @Test
    fun `selection with code fence stays intact`() {
        val sel = "```kotlin\nfun a() {}\n```"
        assertEquals("> ```kotlin\n> fun a() {}\n> ```", QuoteTextFormatter.quoteSelection(sel))
    }
}
