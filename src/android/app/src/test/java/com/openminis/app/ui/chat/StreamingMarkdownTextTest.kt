package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the public pure-Kotlin functions in
 * [StreamingMarkdownText]:
 *
 * - [splitMarkdownIntoBlockTexts] — parser that splits Markdown text into
 *   paragraph and fenced-code-block fragments.
 * - [coalesceMarkdownFragments] — merge adjacent plain-text fragments back
 *   into larger chunks (keeping code fences standalone).
 */
class StreamingMarkdownTextTest {

    // ── splitMarkdownIntoBlockTexts ────────────────────────────────────────

    @Test fun `split empty string returns empty list`() {
        assertEquals(emptyList<String>(), splitMarkdownIntoBlockTexts(""))
    }

    @Test fun `split single paragraph`() {
        val result = splitMarkdownIntoBlockTexts("Hello world")
        assertEquals(listOf("Hello world"), result)
    }

    @Test fun `split multiple paragraphs separated by blank line`() {
        val input = """
            First paragraph.
            
            Second paragraph.
        """.trimIndent()
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(listOf("First paragraph.", "Second paragraph."), result)
    }

    @Test fun `split paragraph with trailing blank lines`() {
        val result = splitMarkdownIntoBlockTexts("Hello\n\n\n\nWorld")
        assertEquals(listOf("Hello", "World"), result)
    }

    @Test fun `split preserves multi-line paragraph`() {
        val input = "Line one\nLine two\nLine three"
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(listOf("Line one\nLine two\nLine three"), result)
    }

    @Test fun `split fenced code block as single fragment`() {
        val input = """
            Text before.
            
            ```kotlin
            val x = 1
            println(x)
            ```
            
            Text after.
        """.trimIndent()
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(3, result.size)
        assertEquals("Text before.", result[0])
        assertTrue(result[1].startsWith("```kotlin"))
        assertTrue(result[1].contains("val x = 1"))
        assertTrue(result[1].endsWith("```"))
        assertEquals("Text after.", result[2])
    }

    @Test fun `split inline code backticks do not trigger fence`() {
        val input = "Use `code` inline"
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(listOf("Use `code` inline"), result)
    }

    @Test fun `split multiple code blocks`() {
        val input = """
            ```json
            {"a": 1}
            ```
            
            Middle text.
            
            ```bash
            echo hi
            ```
        """.trimIndent()
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(4, result.size)
        assertTrue(result[0].startsWith("```json"))
        assertEquals("Middle text.", result[1])
        assertTrue(result[2].startsWith("```bash"))
        // last is empty due to trailing fence
    }

    @Test fun `split no blank lines returns single fragment`() {
        val input = "Line1\nLine2\nLine3\n```\ncode\n```\nLine4"
        val result = splitMarkdownIntoBlockTexts(input)
        // No blank lines between content and fence, so everything is glued
        // together except the fence fragments.
        // Actually the fence is still detected because it's a separate line.
        // Let me check the logic: fence line starts with ``` -> triggers flush.
        // But there's no blank line before the fence, so "Line1\nLine2\nLine3\n"
        // is in cur, then fence triggers flush() -> "Line1\nLine2\nLine3" is one fragment
        // Then fence opens, code lines accumulate, closing fence triggers flush
        // Then "Line4" is another fragment
        assertEquals(3, result.size)
        assertEquals("Line1\nLine2\nLine3", result[0])
        assertTrue(result[1].startsWith("```\ncode\n```"))
        assertEquals("Line4", result[2])
    }

    @Test fun `split blank lines only returns empty`() {
        assertEquals(emptyList<String>(), splitMarkdownIntoBlockTexts("\n\n\n"))
    }

    @Test fun `split consecutive blank lines between paragraphs`() {
        val input = "A\n\n\n\nB"
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(listOf("A", "B"), result)
    }

    @Test fun `split preserves indentation inside code block`() {
        val input = """
            ```python
            def hello():
                print("world")
            ```
        """.trimIndent()
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(1, result.size)
        assertTrue(result[0].contains("    print"))
    }

    @Test fun `split indented line in paragraph`() {
        val input = "Normal\n    Indented\nNormal again"
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(1, result.size)
        assertTrue(result[0].contains("    Indented"))
    }

    @Test fun `split unclosed fence includes everything`() {
        val input = """
            Start.
            ```unclosed
            still inside
            no closing
        """.trimIndent()
        val result = splitMarkdownIntoBlockTexts(input)
        assertTrue(result[0].startsWith("Start."))
        assertTrue(result[1].startsWith("```unclosed"))
        assertTrue(result[1].contains("no closing"))
        // No closing fence, so the fragment stays open and gets flushed
        // at the end.
    }

    // ── coalesceMarkdownFragments ──────────────────────────────────────────

    @Test fun `coalesce empty list returns empty`() {
        assertEquals(emptyList<String>(), coalesceMarkdownFragments(emptyList()))
    }

    @Test fun `coalesce single fragment returns same`() {
        val input = listOf("Hello")
        assertEquals(input, coalesceMarkdownFragments(input))
    }

    @Test fun `coalesce merges adjacent plain fragments`() {
        val input = listOf("A", "B", "C")
        val result = coalesceMarkdownFragments(input)
        assertEquals(1, result.size)
        assertEquals("A\n\nB\n\nC", result[0])
    }

    @Test fun `coalesce keeps code fence fragments standalone`() {
        val input = listOf("A", "```\ncode\n```", "B")
        val result = coalesceMarkdownFragments(input)
        assertEquals(3, result.size)
        assertEquals("A", result[0])
        assertEquals("```\ncode\n```", result[1])
        assertEquals("B", result[2])
    }

    @Test fun `coalesce respects maxChars`() {
        val input = listOf("A" * 1500, "B" * 1500, "C" * 1500)
        val result = coalesceMarkdownFragments(input, maxChars = 2000)
        // Each fragment is 1500 chars, so "A" + "\n\n" + "B" exceeds 2000
        // So A is alone, then B + "\n\n" + C = 3002 > 2000, so B is alone, then C alone
        assertEquals(3, result.size)
    }

    @Test fun `coalesce single oversized paragraph still gets its own row`() {
        val big = "X" * 5000
        val result = coalesceMarkdownFragments(listOf(big), maxChars = 2000)
        assertEquals(1, result.size)
        assertEquals(big, result[0])
    }

    @Test fun `coalesce multiple code fences with plain text between`() {
        val input = listOf("Intro", "```\na\n```", "Middle", "```\nb\n```", "Outro")
        val result = coalesceMarkdownFragments(input)
        assertEquals(5, result.size)
        assertEquals("Intro", result[0])
        assertEquals("```\na\n```", result[1])
        assertEquals("Middle", result[2])
        assertEquals("```\nb\n```", result[3])
        assertEquals("Outro", result[4])
    }

    @Test fun `coalesce merges fragments before and after code fence`() {
        val input = listOf("A", "B", "```\ncode\n```", "C", "D", "E")
        val result = coalesceMarkdownFragments(input)
        assertEquals(3, result.size)
        assertEquals("A\n\nB", result[0])
        assertEquals("```\ncode\n```", result[1])
        assertEquals("C\n\nD\n\nE", result[2])
    }

    @Test fun `coalesce preserves maxChars for merged chunk`() {
        val small = "a"
        val big = "b" * 1500
        // small + "\n\n" + big = 1503, within 2000
        // But adding another big would exceed
        val input = listOf(small, big, "c" * 1500)
        val result = coalesceMarkdownFragments(input, maxChars = 2000)
        assertEquals(2, result.size)
        assertTrue(result[0].contains("a"))
        assertTrue(result[0].contains("b"))
        assertTrue(result[1].contains("c"))
    }

    @Test fun `coalesce code fence line with leading spaces is still fence`() {
        val input = listOf("A", "  ```\ncode\n  ```", "B")
        val result = coalesceMarkdownFragments(input)
        assertEquals(3, result.size)
        assertEquals("A", result[0])
        // Leading spaces: the fence fragment check looks at first non-blank line
        // and checks if it starts with ```. "  ```" starts with spaces, then ```.
        // trimStart() is called on the first line, so it becomes "```" -> fence detected.
        assertEquals("  ```\ncode\n  ```", result[1])
        assertEquals("B", result[2])
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private operator fun String.times(n: Int): String = buildString {
        repeat(n) { append(this@times) }
    }
}