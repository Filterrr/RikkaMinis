package com.openminis.app.tools

import com.openminis.app.data.EnvVarRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/tool-output-env-redaction] The masking RULE for tool output.
 *
 * Before this, only the shell executors masked their output. Every other
 * tool — file_read above all — returned its bytes verbatim, so a file
 * holding an env var's value reached the model in plaintext and was then
 * persisted in the chat DB, the agent trace and the sub-agent journal.
 *
 * The rule itself is the same one the shell path uses
 * ([EnvVarRedactor]): first 2 + stars + last 2, values below the minimum
 * match length skipped. This test pins the rule AND the fact that the two
 * paths agree — a disagreement would mean the same secret is masked in one
 * place and visible in another.
 */
class ToolOutputRedactionTest {

    @Test
    fun `a secret in a file_read style output is masked`() {
        val out = ToolOutputRedaction.mask(
            "[/etc/config | 40 bytes | 1 lines | showing 1-1 of 1]\nghp_secret1234567890abcd",
            listOf("ghp_secret1234567890abcd"),
        )
        assertFalse(out.contains("ghp_secret1234567890abcd"))
        assertTrue(out.contains("gh"))
    }

    @Test
    fun `the mask rule matches the shell path exactly`() {
        val value = "sk-abcdefghijklmnop"
        val viaTool = ToolOutputRedaction.mask("x $value y", listOf(value))
        val viaShell = EnvVarRedactor.redact("x $value y", listOf(value)).first
        assertEquals(viaShell, viaTool)
    }

    @Test
    fun `text without a secret is returned unchanged`() {
        val text = "nothing sensitive here"
        assertEquals(text, ToolOutputRedaction.mask(text, listOf("sk-secret123")))
    }

    @Test
    fun `an empty secret list changes nothing`() {
        assertEquals("anything", ToolOutputRedaction.mask("anything", emptyList()))
    }

    @Test
    fun `blank text stays blank`() {
        assertEquals("", ToolOutputRedaction.mask("", listOf("sk-secret123")))
    }

    @Test
    fun `values below the match threshold are not masked`() {
        // Same threshold as the shell path — masking a 4-char value would
        // mangle ordinary words ("true", "data", "http").
        val text = "true null abc"
        assertEquals(text, ToolOutputRedaction.mask(text, listOf("true", "null", "abc")))
    }

    @Test
    fun `every occurrence is masked not just the first`() {
        val out = ToolOutputRedaction.mask(
            "sk-secret123 and again sk-secret123",
            listOf("sk-secret123"),
        )
        assertFalse(out.contains("sk-secret123"))
    }

    @Test
    fun `the model variant appends the privacy reminder`() {
        val out = ToolOutputRedaction.maskForModel("value is sk-secret123", listOf("sk-secret123"))
        assertFalse(out.contains("sk-secret123"))
        assertTrue(
            "the model must be told the value was masked",
            out.contains("Privacy mode is ON"),
        )
    }

    @Test
    fun `the model variant stays silent when nothing was masked`() {
        val text = "nothing sensitive"
        assertEquals(text, ToolOutputRedaction.maskForModel(text, listOf("sk-secret123")))
    }

    @Test
    fun `the quiet variant never appends the reminder`() {
        // The quiet variant is used for durable diagnostics (trace, learning
        // hook). A system reminder is an instruction to the MODEL and would
        // be noise in a log file.
        val out = ToolOutputRedaction.mask("value is sk-secret123", listOf("sk-secret123"))
        assertFalse(out.contains("Privacy mode is ON"))
    }

    @Test
    fun `masking is idempotent`() {
        // The shell executor masks its own output AND the shared boundary
        // masks again. The second pass must not change anything, and must
        // not append a second reminder.
        val once = ToolOutputRedaction.maskForModel("x sk-secret123 y", listOf("sk-secret123"))
        val twice = ToolOutputRedaction.maskForModel(once, listOf("sk-secret123"))
        assertEquals(once, twice)
    }
}
