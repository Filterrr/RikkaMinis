package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-ui-ansi] Unit tests for the ANSI→span parser that drives
 * colored tool output in SubagentDetailScreen. Pure JVM — no Compose.
 */
class SubagentAnsiTextTest {

    private val ESC = "\u001B"

    @Test
    fun `plain text without escapes yields single span`() {
        val spans = SubagentAnsiText.parse("hello world")
        assertEquals(1, spans.size)
        assertEquals("hello world", spans[0].text)
        assertNull(spans[0].color)
        assertTrue(!spans[0].bold && !spans[0].italic && !spans[0].underline)
    }

    @Test
    fun `empty input yields no spans`() {
        assertTrue(SubagentAnsiText.parse("").isEmpty())
    }

    @Test
    fun `basic fg color then reset`() {
        val spans = SubagentAnsiText.parse("${ESC}[31mred${ESC}[0m plain")
        assertEquals(2, spans.size)
        assertEquals("red", spans[0].text)
        assertEquals(0xFFCD3131.toInt(), spans[0].color)
        assertEquals(" plain", spans[1].text)
        assertNull(spans[1].color)
    }

    @Test
    fun `combined bold and color`() {
        val spans = SubagentAnsiText.parse("${ESC}[1;32mgreen bold${ESC}[0m")
        assertEquals(1, spans.size)
        assertTrue(spans[0].bold)
        assertEquals(0xFF0DBC79.toInt(), spans[0].color)
    }

    @Test
    fun `bright fg colors map to palette 8-15`() {
        val spans = SubagentAnsiText.parse("${ESC}[91mbright red${ESC}[0m")
        assertEquals(0xFFF14C4C.toInt(), spans.single().color)
    }

    @Test
    fun `bg color parsed`() {
        val spans = SubagentAnsiText.parse("${ESC}[41mon red${ESC}[0m")
        assertEquals(0xFFCD3131.toInt(), spans.single().bgColor)
        assertNull(spans.single().color)
    }

    @Test
    fun `256 palette cube index 196 is pure red`() {
        val spans = SubagentAnsiText.parse("${ESC}[38;5;196mX${ESC}[0m")
        // 196-16=180 → r=5,g=0,b=0 → CUBE_STEPS[5]=255
        assertEquals(0xFFFF0000.toInt(), spans.single().color)
    }

    @Test
    fun `256 palette gray band 232-255`() {
        val spans = SubagentAnsiText.parse("${ESC}[38;5;244mX${ESC}[0m")
        // 244-232=12 → 8+120=128
        assertEquals(0xFF808080.toInt(), spans.single().color)
    }

    @Test
    fun `truecolor rgb`() {
        val spans = SubagentAnsiText.parse("${ESC}[38;2;255;100;50mX${ESC}[0m")
        assertEquals((255 shl 16) or (100 shl 8) or 50 or (0xFF shl 24), spans.single().color)
    }

    @Test
    fun `default restore codes 39 and 49`() {
        val spans = SubagentAnsiText.parse("${ESC}[31;41mboth${ESC}[39mfg-only${ESC}[49mnone")
        assertEquals(3, spans.size)
        assertEquals(0xFFCD3131.toInt(), spans[0].color)
        assertEquals(0xFFCD3131.toInt(), spans[0].bgColor)
        assertNull(spans[1].color)
        assertEquals(0xFFCD3131.toInt(), spans[1].bgColor)
        assertNull(spans[2].color)
        assertNull(spans[2].bgColor)
    }

    @Test
    fun `reset clears attributes`() {
        val spans = SubagentAnsiText.parse("${ESC}[1;4;33;44mstyled${ESC}[0mplain")
        assertTrue(spans[0].bold && spans[0].underline)
        assertNull(spans[1].color)
        assertTrue(!spans[1].bold && !spans[1].underline)
    }

    @Test
    fun `non-SGR sequences dropped without splitting style`() {
        // EL (erase line) between color set and text must NOT flush a span.
        val spans = SubagentAnsiText.parse("${ESC}[32m${ESC}[2Kok${ESC}[0m")
        assertEquals(1, spans.size)
        assertEquals("ok", spans.single().text)
        assertEquals(0xFF0DBC79.toInt(), spans.single().color)
    }

    @Test
    fun `dangling escape keeps literal tail`() {
        val spans = SubagentAnsiText.parse("abc${ESC}[3")
        assertEquals(1, spans.size)
        assertEquals("abc${ESC}[3", spans.single().text)
    }

    @Test
    fun `italic and underline flags`() {
        val spans = SubagentAnsiText.parse("${ESC}[3;4mboth${ESC}[23munderline-only${ESC}[24mnone")
        assertTrue(spans[0].italic && spans[0].underline)
        assertTrue(!spans[1].italic && spans[1].underline)
        assertTrue(!spans[2].underline)
    }

    @Test
    fun `style persists across plain text until next SGR`() {
        val spans = SubagentAnsiText.parse("${ESC}[36mA B C${ESC}[0m")
        assertEquals("A B C", spans.single().text)
        assertEquals(0xFF11A8CD.toInt(), spans.single().color)
    }

    @Test
    fun `span cap emits single unstyled tail without escapes`() {
        val spam = buildString {
            repeat(3000) { append("${ESC}[3${it % 8}mX") }
        }
        val spans = SubagentAnsiText.parse(spam)
        assertTrue("spans=${spans.size}", spans.size <= SubagentAnsiText.MAX_RENDER_SPANS + 1)
        assertTrue(spans.last().text.none { it == '' })
    }

    @Test
    fun `malformed extended color keeps previous style`() {
        // 38 with missing payload → ignore that param, keep prior fg.
        val spans = SubagentAnsiText.parse("${ESC}[31m${ESC}[38;5mX${ESC}[0m")
        assertEquals(1, spans.size)
        assertEquals(0xFFCD3131.toInt(), spans.single().color)
    }

    @Test
    fun `tailLines keeps styling across the cut`() {
        val spans = SubagentAnsiText.parse("${ESC}[31mline1\nline2\nline3${ESC}[0m\nplain tail")
        val tail = SubagentAnsiText.tailLines(spans, 2)
        assertEquals("line3\nplain tail", tail.joinToString("") { it.text })
        assertEquals(0xFFCD3131.toInt(), tail.first().color)
        assertNull(tail.last().color)
    }

    @Test
    fun `tailLines no-op when output shorter than request`() {
        val spans = SubagentAnsiText.parse("${ESC}[32ma\nb\nc${ESC}[0m")
        assertEquals(spans, SubagentAnsiText.tailLines(spans, 10))
    }
}
