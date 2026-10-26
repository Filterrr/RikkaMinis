package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-journal-redaction] The journal is a durable, `file_read`-able
 * record. A secret that the shell path already masked must not survive there
 * in the clear.
 */
class SubagentJournalRedactionTest {

    @Test
    fun `a secret in the report is masked`() {
        val out = SubagentJournalRedaction.redact(
            "the key is sk-secret123",
            listOf("sk-secret123"),
        )
        assertFalse(out.contains("sk-secret123"))
        assertTrue(out.contains("sk"))
    }

    @Test
    fun `text without a secret is returned unchanged`() {
        val text = "nothing sensitive here"
        assertEquals(text, SubagentJournalRedaction.redact(text, listOf("sk-secret123")))
    }

    @Test
    fun `an empty secret list changes nothing`() {
        val text = "anything"
        assertEquals(text, SubagentJournalRedaction.redact(text, emptyList()))
    }

    @Test
    fun `blank text stays blank`() {
        assertEquals("", SubagentJournalRedaction.redact("", listOf("sk-secret123")))
    }

    @Test
    fun `values below the match threshold are not masked - they would mangle unrelated text`() {
        // EnvVarRedactor skips values shorter than its minimum match length
        // (5). The journal uses the same threshold so it does not corrupt
        // ordinary words that happen to match a short env var.
        val text = "true null abc"
        assertEquals(text, SubagentJournalRedaction.redact(text, listOf("true", "null", "abc")))
    }

    @Test
    fun `a value at the match threshold is masked like the shell path does`() {
        // "false" is exactly 5 chars — at the threshold, so it IS masked.
        // The journal must agree with the shell path's rule exactly, or the
        // same secret would be masked in one place and visible in the other.
        val out = SubagentJournalRedaction.redact("value is false", listOf("false"))
        assertFalse(out.contains("false"))
    }

    @Test
    fun `every occurrence is masked not just the first`() {
        val out = SubagentJournalRedaction.redact(
            "sk-secret123 and again sk-secret123",
            listOf("sk-secret123"),
        )
        assertFalse(out.contains("sk-secret123"))
    }
}
