package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-report-hygiene] Pins the gate that refuses to hand the parent
 * an empty husk as a report.
 *
 * The regression: a sub-agent executed five tool calls, gathered real output,
 * and closed with exactly `<parameter name="status">done</parameter>`. The
 * runtime passed that line through as the findings and still reported SUCCESS.
 */
class SubagentReportHygieneTest {

    // The exact observed string.
    private val husk = "<parameter name=\"status\">done</parameter>"

    // ─── 1. the husk is detected ───────────────────────────────────────────

    @Test
    fun `the observed husk is degenerate`() {
        assertTrue(SubagentReportHygiene.isDegenerate(husk))
    }

    @Test
    fun `other tag shapes are degenerate too`() {
        listOf(
            "<status>done</status>",
            "<parameter name=\"key_findings\">none</parameter>",
            "<invoke name=\"report\"></invoke>",
            "<function_calls></function_calls>",
            "<tool_use name=\"x\"/>",
            "ok <gaps>-</gaps>",
        ).forEach {
            assertTrue("expected degenerate: $it", SubagentReportHygiene.isDegenerate(it))
        }
    }

    @Test
    fun `a husk with surrounding whitespace is still degenerate`() {
        assertTrue(SubagentReportHygiene.isDegenerate("\n\n  $husk  \n\n"))
    }

    // ─── 2. real reports are NOT touched ───────────────────────────────────

    @Test
    fun `a normal structured report survives`() {
        val report = """
            status: done
            key_findings:
            - Ubuntu 24.04.4 LTS on aarch64 (uname -a)
            - disk 28% used (df -h /)
            gaps: none
            artifacts: none
        """.trimIndent()
        assertFalse(SubagentReportHygiene.isDegenerate(report))
    }

    @Test
    fun `a terse but real answer survives`() {
        // Brevity is not the failure mode — this must not be replaced.
        assertFalse(SubagentReportHygiene.isDegenerate("HTTP 200 from example.com"))
    }

    @Test
    fun `a short chinese report survives`() {
        assertFalse(SubagentReportHygiene.isDegenerate("外网连通正常，HTTP 200，磁盘使用率 28%"))
    }

    @Test
    fun `stripping tags from a real report keeps the substance`() {
        val withTags = "status: done\n<parameter name=\"note\">磁盘 28% 已用</parameter>\n完成"
        val judged = SubagentReportHygiene.judge(withTags)
        assertFalse(judged.degenerate)
        assertTrue(judged.stripped.contains("status: done"))
        assertTrue(judged.stripped.contains("磁盘 28% 已用"))
        assertFalse(judged.stripped.contains("<parameter"))
    }

    // ─── 3. reconstruction from tool evidence ──────────────────────────────

    @Test
    fun `digest is built when tool evidence exists`() {
        val digest = SubagentReportHygiene.assembleFromTools(
            listOf(
                SubagentReportHygiene.ToolDigest(
                    "shell_execute", "Check date and arch", true,
                    "aarch64\nUbuntu 24.04.4 LTS",
                ),
                SubagentReportHygiene.ToolDigest(
                    "shell_execute", "Test connectivity", false, "curl: timeout",
                ),
            ),
        )
        assertNotNull(digest)
        val d = digest!!
        assertTrue(d.contains("status: partial"))
        assertTrue(d.contains("runtime-assembled"))
        assertTrue(d.contains("[ok] 1. Check date and arch"))
        assertTrue(d.contains("[FAILED] 2. Test connectivity"))
        assertTrue(d.contains("aarch64"))
        assertTrue(d.contains("curl: timeout"))
        assertTrue(d.contains("gaps:"))
        assertTrue(d.contains("artifacts:"))
    }

    @Test
    fun `no evidence means no fabricated digest`() {
        assertNull(SubagentReportHygiene.assembleFromTools(emptyList()))
    }

    @Test
    fun `long tool output is clipped but keeps the tail`() {
        val long = (1..500).joinToString("\n") { "line $it" }
        val digest = SubagentReportHygiene.assembleFromTools(
            listOf(SubagentReportHygiene.ToolDigest("shell_execute", "big", true, long)),
            maxOutputCharsPerStep = 100,
        )!!
        assertTrue(digest.contains("[clipped]"))
        // The tail is what matters — the last line must survive.
        assertTrue(digest.contains("line 500"))
        assertFalse(digest.contains("line 1\n"))
    }

    @Test
    fun `digest never claims a model wrote it`() {
        val digest = SubagentReportHygiene.assembleFromTools(
            listOf(SubagentReportHygiene.ToolDigest("shell_execute", "t", true, "out")),
        )!!
        assertTrue(digest.contains("not interpretation"))
        assertTrue(digest.contains("no model-authored interpretation"))
    }

    @Test
    fun `empty run produces no work warning from the report gate`() {
        // Interaction with [fix/subagent-plan-execute]: a zero-tool run also
        // gets a "no tool calls were executed" warning from the runner. The
        // hygiene gate must not stack a second warning on top of it, so the
        // runner suppresses the no-work warning when the report was degenerate.
        // This pins the gate's half of that contract: a degenerate report from
        // an EMPTY run yields no digest, leaving the runner's own warning as
        // the single message.
        assertNull(SubagentReportHygiene.assembleFromTools(emptyList()))
        assertTrue(SubagentReportHygiene.isDegenerate("<parameter name=\"status\">done</parameter>"))
    }

    // ─── 4. prompt contract guard ──────────────────────────────────────────

    @Test
    fun `skill contract forbids xml reports`() {
        // The model merged the plan's XML convention with the plain-text
        // report contract. Both sub-agent skills must now say so explicitly.
        for (path in listOf(
            "assets/skills/general-agent/SKILL.md",
            "assets/skills/read-only-researcher/SKILL.md",
        )) {
            val text = java.io.File("src/main/$path")
            if (!text.isFile) continue
            val body = text.readText()
            assertTrue("$path must forbid XML report form", body.contains("PLAIN TEXT, not XML"))
            assertTrue("$path must show the tag example", body.contains("status: done"))
        }
    }
}
