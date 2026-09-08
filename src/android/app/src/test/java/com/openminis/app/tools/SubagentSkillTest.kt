package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SubagentSkillTest {

    /**
     * Minimal skill representation for test purposes.
     * Implements [SkillInfo] so it can be passed to SubagentSkill methods.
     */
    private data class TestSkill(
        override val name: String = "test-skill",
        override val description: String = "A test skill",
        override val body: String = "",
    ) : SkillInfo

    private fun makeSkill(body: String) = TestSkill(body = body)

    private fun makeTool(name: String) = AgentToolDefinition(
        name = name,
        description = "tool $name",
        parameters = emptyMap(),
    )

    // ── parseSubagentConfig ──────────────────────────────────────────────

    @Test
    fun `regular skill without frontmatter is not a subagent`() {
        val skill = makeSkill("Just some instructions.\n\nDo things.")
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertFalse(config.isSubagent)
        assertEquals(12, config.maxTurns)
        assertEquals(4096, config.maxOutputTokens)
        assertNull(config.allowedTools)
    }

    @Test
    fun `skill with subagent true is a subagent`() {
        val skill = makeSkill(
            """
            ---
            name: Researcher
            description: Deep research
            subagent: true
            ---
            You are a researcher. Do research.
            """.trimIndent(),
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertTrue(config.isSubagent)
        assertEquals(12, config.maxTurns)
        assertNull(config.allowedTools)
    }

    @Test
    fun `subagent with custom budget overrides defaults`() {
        val skill = makeSkill(
            """
            ---
            subagent: true
            max_turns: 6
            max_output_tokens: 2048
            ---
            You are a focused agent.
            """.trimIndent(),
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertTrue(config.isSubagent)
        assertEquals(6, config.maxTurns)
        assertEquals(2048, config.maxOutputTokens)
    }

    @Test
    fun `subagent with inline allowlist parses tool names`() {
        val skill = makeSkill(
            """
            ---
            subagent: true
            allowed_tools: [file_read, file_write, memory_get]
            ---
            You are a researcher.
            """.trimIndent(),
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertTrue(config.isSubagent)
        assertEquals(setOf("file_read", "file_write", "memory_get"), config.allowedTools)
    }

    @Test
    fun `subagent with dash allowlist parses tool names`() {
        val skill = makeSkill(
            """
            ---
            subagent: true
            allowed_tools:
              - file_read
              - memory_get
            ---
            You are a researcher.
            """.trimIndent(),
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertEquals(setOf("file_read", "memory_get"), config.allowedTools)
    }

    @Test
    fun `subagent with empty allowlist means no tools allowed`() {
        val skill = makeSkill(
            """
            ---
            subagent: true
            allowed_tools: []
            ---
            You are a purist.
            """.trimIndent(),
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertEquals(emptySet<Any>(), config.allowedTools)
    }

    @Test
    fun `subagent with unknown fields ignores them gracefully`() {
        val skill = makeSkill(
            """
            ---
            subagent: true
            max_turns: not-a-number
            max_output_tokens: 999999
            color: blue
            ---
            You are a tolerant agent.
            """.trimIndent(),
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertTrue(config.isSubagent)
        // Invalid number falls back to default; out-of-range clamps
        assertEquals(12, config.maxTurns)
        assertEquals(128_000, config.maxOutputTokens)
    }

    // ── buildFilteredTools ───────────────────────────────────────────────

    @Test
    fun `forbidden tools are always excluded`() {
        val all = listOf(
            makeTool("file_read"),
            makeTool("shell_execute"),
            makeTool("spawn_agent"),
            makeTool("browser_use"),
            makeTool("memory_get"),
            makeTool("unknown_future_tool"),
        )
        val filtered = SubagentSkill.buildFilteredTools(all, null)
        val names = filtered.map { it.name }.toSet()
        // [T-subagent-parity] shell_execute / browser_use are allowed.
        // [T-agent-capability] spawn_agent + memory tools are forbidden
        // (capability isolation); unknown_future_tool is dropped by the
        // fail-closed capability catalog — new main-agent tools never
        // leak to sub-agents automatically.
        assertEquals(setOf("file_read", "shell_execute", "browser_use"), names)
    }

    @Test
    fun `allowlist restricts to listed tools`() {
        val all = listOf(
            makeTool("file_read"),
            makeTool("file_write"),
            makeTool("file_edit"),
            makeTool("memory_get"),
            makeTool("memory_write"),
        )
        val filtered = SubagentSkill.buildFilteredTools(all, setOf("file_read", "memory_get"))
        val names = filtered.map { it.name }.toSet()
        // [T-agent-capability] memory_get is FORBIDDEN for sub-agents — the
        // allowlist can only narrow the base capability set, never widen it.
        assertEquals(setOf("file_read"), names)
    }

    @Test
    fun `allowlist cannot re-enable forbidden tools`() {
        val all = listOf(
            makeTool("file_read"),
            makeTool("shell_execute"),
            makeTool("spawn_agent"),
        )
        // Even if the skill author lists them, FORBIDDEN wins. [T-subagent-parity]
        // shell_execute is no longer forbidden, so only spawn_agent is dropped.
        val filtered = SubagentSkill.buildFilteredTools(
            all, setOf("file_read", "shell_execute", "spawn_agent"),
        )
        assertEquals(listOf("file_read", "shell_execute"), filtered.map { it.name })
    }

    @Test
    fun `empty allowlist yields no tools`() {
        val all = listOf(makeTool("file_read"), makeTool("memory_get"))
        val filtered = SubagentSkill.buildFilteredTools(all, emptySet())
        assertTrue(filtered.isEmpty())
    }

    // ── buildSystemPrompt ────────────────────────────────────────────────

    @Test
    fun `system prompt strips frontmatter`() {
        val skill = makeSkill(
            """
            ---
            name: Researcher
            description: Deep research
            subagent: true
            ---
            You are a researcher. Always cite sources.
            """.trimIndent(),
        )
        val prompt = SubagentSkill.buildSystemPrompt(skill)
        assertTrue(prompt.contains("You are a researcher"))
        assertFalse(prompt.contains("subagent:"))
        assertFalse(prompt.contains("---"))
    }

    @Test
    fun `system prompt falls back to description for frontmatter-only skill`() {
        val skill = TestSkill(
            name = "Empty",
            description = "Fallback description",
            body = """
                ---
                name: Empty
                description: Fallback description
                subagent: true
                ---
                """.trimIndent(),
        )
        assertEquals("Fallback description", SubagentSkill.buildSystemPrompt(skill))
    }

    @Test
    fun `system prompt returns raw body when no frontmatter`() {
        val skill = makeSkill("Plain instructions without frontmatter.")
        assertEquals("Plain instructions without frontmatter.", SubagentSkill.buildSystemPrompt(skill))
    }
}
// ── [T-subagent-fm] preserved-frontmatter parsing ────────────────────────

/**
 * Skill with a separately-preserved frontmatter block (mirrors the
 * registry's Skill model post-fix: body carries NO frontmatter, the
 * raw YAML lands in [frontmatter]).
 */
private data class SplitSkill(
    override val name: String = "split",
    override val description: String = "d",
    override val body: String = "Body only, no fences.",
    override val frontmatter: String? = null,
) : SkillInfo

class SubagentFrontmatterPreservationTest {

    @Test
    fun `preserved frontmatter drives subagent detection`() {
        val skill = SplitSkill(
            frontmatter = "name: general-agent\nsubagent: true\nmax_turns: 24\nmax_output_tokens: 8192",
            body = "You are an autonomous sub-agent.",
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertTrue(config.isSubagent)
        assertEquals(24, config.maxTurns)
        assertEquals(8192, config.maxOutputTokens)
    }

    @Test
    fun `body without frontmatter and null preserved field is not subagent`() {
        val skill = SplitSkill(frontmatter = null, body = "Plain body.")
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertFalse(config.isSubagent)
        assertEquals(12, config.maxTurns)
    }

    @Test
    fun `preserved frontmatter wins over body-embedded frontmatter`() {
        val skill = SplitSkill(
            frontmatter = "subagent: false",
            body = "---\nsubagent: true\n---\nbody",
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertFalse(config.isSubagent)
    }

    @Test
    fun `allowed_tools list parses from preserved frontmatter`() {
        val skill = SplitSkill(
            frontmatter = "subagent: true\nallowed_tools: [file_read, file_write]",
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertTrue(config.isSubagent)
        assertEquals(setOf("file_read", "file_write"), config.allowedTools)
    }

    @Test
    fun `body-embedded frontmatter fallback still works`() {
        val skill = object : SkillInfo {
            override val name = "legacy"
            override val description = "d"
            override val body = "---\nsubagent: true\nmax_turns: 6\n---\ninstructions"
        }
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertTrue(config.isSubagent)
        assertEquals(6, config.maxTurns)
    }

    @Test
    fun `system prompt built from body ignores preserved frontmatter`() {
        val skill = SplitSkill(
            frontmatter = "subagent: true\nmax_turns: 3",
            body = "Report discipline: data first.",
        )
        val prompt = SubagentSkill.buildSystemPrompt(skill)
        assertEquals("Report discipline: data first.", prompt)
    }

    // ── [A2] parseReportStatus ───────────────────────────────────────────

    @Test
    fun `parseReportStatus detects partial in structured contract tail`() {
        val report = """
            Working on it, gathering sources.
            status: partial
            key_findings: found 2 of 3 versions (github releases api)
            gaps: third vendor unreachable
            artifacts: /var/minis/workspace/out.csv
        """.trimIndent()
        assertEquals("partial", SubagentSkill.parseReportStatus(report))
    }

    @Test
    fun `parseReportStatus detects failed with parenthetical`() {
        val report = "status: failed (no provider context)"
        assertEquals("failed", SubagentSkill.parseReportStatus(report))
    }

    @Test
    fun `parseReportStatus accepts list marker and fullwidth colon`() {
        assertEquals("partial", SubagentSkill.parseReportStatus("- status: 部分完成"))
        assertEquals("partial", SubagentSkill.parseReportStatus("状态：partial"))
    }

    @Test
    fun `parseReportStatus scans tail window so prose status does not win`() {
        val prose = "status: this run tracks progress status across all vendors"
        val tail = "\n\nstatus: done\nkey_findings: ok (anchor)\ngaps: none\nartifacts: none"
        assertNull(SubagentSkill.parseReportStatus(prose + tail))
    }

    @Test
    fun `parseReportStatus returns null for prose or done reports`() {
        assertNull(SubagentSkill.parseReportStatus("I could not find any releases."))
        assertNull(SubagentSkill.parseReportStatus(""))
        assertNull(SubagentSkill.parseReportStatus("status: done\nkey_findings: ok (a)"))
    }

    @Test
    fun `parseReportStatus picks the last status line in window`() {
        val report = "status: partial\nkey_findings: x (a)\ngaps: y\nartifacts: none\n\nstatus: done"
        assertNull(SubagentSkill.parseReportStatus(report))
    }

    // ── [A3] runtime preamble ────────────────────────────────────────────

    @Test
    fun `runtime preamble is off by default`() {
        val skill = makeSkill("Body instructions.")
        val prompt = SubagentSkill.buildSystemPrompt(skill)
        assertFalse(SubagentResult.hasRuntimePreamble(prompt))
        assertEquals("Body instructions.", prompt)
    }

    @Test
    fun `runtime preamble prepends marker context and task heading`() {
        val skill = makeSkill("Body instructions.")
        val ctx = SubagentSkill.buildRuntimeContext(LocalDateTime.of(2026, 9, 8, 10, 30))
        val prompt = SubagentSkill.buildSystemPrompt(skill, runtimeContext = ctx)
        assertTrue(SubagentResult.hasRuntimePreamble(prompt))
        assertTrue(prompt.contains("Current date/time: 2026-09-08 10:30"))
        assertTrue(prompt.contains("/var/minis/workspace/"))
        assertTrue(prompt.contains("# Task from the parent agent"))
        assertTrue(prompt.trimEnd().endsWith("Body instructions."))
    }

    @Test
    fun `runtime preamble wraps description fallback for blank body`() {
        val skill = TestSkill(description = "Fallback description", body = "")
        val ctx = SubagentSkill.buildRuntimeContext(LocalDateTime.of(2026, 9, 8, 0, 0))
        val prompt = SubagentSkill.buildSystemPrompt(skill, runtimeContext = ctx)
        assertTrue(SubagentResult.hasRuntimePreamble(prompt))
        assertTrue(prompt.contains("Fallback description"))
    }
}
