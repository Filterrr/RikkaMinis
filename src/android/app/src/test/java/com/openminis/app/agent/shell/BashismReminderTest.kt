package com.openminis.app.agent.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BashismReminderTest {

    @Test
    fun sanitize_shouldReplaceAngleBrackets() {
        val result = BashismReminder.sanitize("a < b > c")
        assertEquals("a ‹ b › c", result)
    }

    @Test
    fun sanitize_shouldFilterControlCharacters() {
        val result = BashismReminder.sanitize("hello\u0000world\u0001test")
        assertEquals("helloworldtest", result)
    }

    @Test
    fun sanitize_shouldKeepSpace() {
        val result = BashismReminder.sanitize("a b c")
        assertEquals("a b c", result)
    }

    @Test
    fun sanitize_shouldTruncateLongLines() {
        val longLine = "a".repeat(150)
        val result = BashismReminder.sanitize(longLine)
        assertEquals(121, result.length)
        assertEquals("…", result.last().toString())
    }

    @Test
    fun sanitize_shouldNotTruncateShortLines() {
        val result = BashismReminder.sanitize("short line")
        assertEquals("short line", result)
    }

    @Test
    fun build_shouldReturnNullForEmptyHits() {
        val result = BashismReminder.build(emptyList(), null)
        assertNull(result)
    }

    @Test
    fun build_shouldIncludeInstallFailureMessage() {
        val hits = listOf(
            BashismDetector.Hit(1, "rule1", "matched", "behavior note", "fix hint")
        )
        val result = BashismReminder.build(hits, "network error")
        assertEquals(true, result?.contains("because bash installation failed: network error"))
    }

    @Test
    fun build_shouldIncludeDefaultMessageWhenNoInstallFailure() {
        val hits = listOf(
            BashismDetector.Hit(1, "rule1", "matched", "behavior note", "fix hint")
        )
        val result = BashismReminder.build(hits, null)
        assertEquals(true, result?.contains("This command was executed by busybox sh (NOT bash)."))
    }

    @Test
    fun build_shouldDeduplicateHits() {
        val hits = listOf(
            BashismDetector.Hit(1, "rule1", "matched", "behavior note", "fix hint"),
            BashismDetector.Hit(1, "rule1", "matched", "behavior note", "fix hint")
        )
        val result = BashismReminder.build(hits, null)
        assertEquals(true, result?.contains("  - line 1:"))
        assertEquals(false, result?.split("  - line 1:").size!! > 2)
    }

    @Test
    fun build_shouldLimitTo8ShownHits() {
        val hits = (1..12).map { i ->
            BashismDetector.Hit(i, "rule$i", "matched$i", "behavior note $i", "fix hint $i")
        }
        val result = BashismReminder.build(hits, null)
        val lines = result?.split("\n")?.filter { it.trimStart().startsWith("- line") } ?: emptyList()
        assertEquals(8, lines.size)
        assertEquals(true, result?.contains("… and 4 more."))
    }

    @Test
    fun build_shouldFormatHitCorrectly() {
        val hits = listOf(
            BashismDetector.Hit(5, "testRule", "some text", "some behavior", "some fix")
        )
        val result = BashismReminder.build(hits, null)
        assertEquals(true, result?.contains("  - line 5: `some text`"))
        assertEquals(true, result?.contains("    rule: testRule — some behavior"))
        assertEquals(true, result?.contains("    fix:  some fix"))
    }

    @Test
    fun build_shouldContainSystemReminderTags() {
        val hits = listOf(
            BashismDetector.Hit(1, "rule1", "matched", "behavior note", "fix hint")
        )
        val result = BashismReminder.build(hits, null)
        assertEquals(true, result?.startsWith("<system-reminder>"))
        assertEquals(true, result?.endsWith("\n</system-reminder>"))
    }

    @Test
    fun build_shouldSanitizeInstallFailureMessage() {
        val hits = listOf(
            BashismDetector.Hit(1, "rule1", "matched", "behavior note", "fix hint")
        )
        val result = BashismReminder.build(hits, "error <failed>")
        assertEquals(true, result?.contains("error ‹failed›"))
    }
}