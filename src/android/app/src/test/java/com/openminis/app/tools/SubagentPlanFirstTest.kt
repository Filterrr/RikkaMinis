package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-plan-first] Pins the plan-first flow pieces that run without
 * Android: frontmatter opt-out parsing, `<task-plan>` extraction (first block
 * wins, later replaces, open-tag-without-close untouched), and plan item
 * parsing (bullets, numbered lists, prose fallback).
 */
class SubagentPlanFirstTest {

    // ─── 1. frontmatter parsing ────────────────────────────────────────────

    @Test
    fun `plan_first defaults to true`() {
        val skill = object : SkillInfo {
            override val name = "s"
            override val description = "d"
            override val body = "body"
            override val frontmatter = "subagent: true\nmax_turns: 6"
        }
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertTrue(config.isSubagent)
        assertTrue(config.planFirst)
    }

    @Test
    fun `plan_first false opts out`() {
        val skill = object : SkillInfo {
            override val name = "s"
            override val description = "d"
            override val body = "body"
            override val frontmatter = "subagent: true\nplan_first: false"
        }
        assertFalse(SubagentSkill.parseSubagentConfig(skill).planFirst)
    }

    @Test
    fun `plan_first accepts yes and rejects garbage to true`() {
        fun config(fm: String) = SubagentSkill.parseSubagentConfig(object : SkillInfo {
            override val name = "s"
            override val description = "d"
            override val body = "body"
            override val frontmatter = fm
        })
        assertTrue(config("subagent: true\nplan_first: yes").planFirst)
        assertTrue(config("subagent: true\nplan_first: off-balance").planFirst) // garbage → default
        assertFalse(config("subagent: true\nplan_first: no").planFirst)
        assertFalse(config("subagent: true\nplan_first: off").planFirst)
    }

    // ─── 2. plan directive ─────────────────────────────────────────────────

    @Test
    fun `directive names the tag and forbids tools on plan turn`() {
        assertTrue(SubagentSkill.PLAN_DIRECTIVE.contains("<task-plan>"))
        assertTrue(SubagentSkill.PLAN_DIRECTIVE.contains("NO tool calls"))
    }

    // ─── 3. extraction ─────────────────────────────────────────────────────

    @Test
    fun `extract first plan and strip it from narration`() {
        val text = "I'll start.\n<task-plan>\n- Search X\n- Write report\n</task-plan>\nBeginning now."
        val (plan, cleaned) = SubagentSkill.extractTaskPlan(text)!!
        assertEquals("- Search X\n- Write report", plan)
        assertFalse(cleaned.contains("<task-plan>"))
        assertTrue(cleaned.contains("Beginning now."))
        assertTrue(cleaned.contains("I'll start."))
    }

    @Test
    fun `missing or unclosed block returns null untouched`() {
        assertNull(SubagentSkill.extractTaskPlan("no block here"))
        // Unclosed: stream cut mid-tag — text must be returned as-is.
        val cut = "thinking <task-plan>\n- one\n- two"
        val out = SubagentSkill.extractTaskPlan(cut)
        assertNull(out)
    }

    @Test
    fun `empty block returns null`() {
        assertNull(SubagentSkill.extractTaskPlan("<task-plan></task-plan>"))
        assertNull(SubagentSkill.extractTaskPlan("<task-plan>   </task-plan>"))
    }

    // ─── 4. item parsing ───────────────────────────────────────────────────

    @Test
    fun `parses dash star and numbered bullets`() {
        val items = SubagentSkill.parseTaskPlanItems(
            "- Search X\n* Compare A vs B\n3. Write report",
        )
        assertEquals(listOf("Search X", "Compare A vs B", "Write report"), items)
    }

    @Test
    fun `prose-only plan becomes single item`() {
        val items = SubagentSkill.parseTaskPlanItems("Just read the file and summarize.")
        assertEquals(1, items.size)
        assertTrue(items[0].contains("summarize"))
    }

    @Test
    fun `items capped at twelve`() {
        val many = (1..30).joinToString("\n") { "- step $it" }
        assertEquals(12, SubagentSkill.parseTaskPlanItems(many).size)
    }

    // ─── 5. registry round-trip ────────────────────────────────────────────

    @Test
    fun `registry stores and replaces plan`() {
        val registry = SubagentRunRegistry()
        val run = registry.register(
            blockId = "b1", skillId = "s", skillName = "S",
            query = "q", title = "t", maxTurns = 5,
        )
        registry.setTaskPlan(run.id, listOf("one", "two"))
        assertEquals(listOf("one", "two"), registry.runs.value.first { it.id == run.id }.taskPlan)
        // Re-plan replaces.
        registry.setTaskPlan(run.id, listOf("revised"))
        assertEquals(listOf("revised"), registry.runs.value.first { it.id == run.id }.taskPlan)
        // Empty is a no-op — never wipes a plan.
        registry.setTaskPlan(run.id, emptyList())
        assertEquals(listOf("revised"), registry.runs.value.first { it.id == run.id }.taskPlan)
    }

    @Test
    fun `replaceTurnNarration rewrites last text segment of turn`() {
        val registry = SubagentRunRegistry()
        val run = registry.register(
            blockId = "b2", skillId = "s", skillName = "S",
            query = "q", title = "t", maxTurns = 5,
        )
        registry.markExecuting(run.id)
        registry.turnStarted(run.id, 1)
        registry.appendSegmentText(run.id, "narration ")
        registry.appendSegmentText(run.id, "<task-plan>- a</task-plan>")
        registry.replaceTurnNarration(run.id, 1, "narration (plan captured)")
        val run2 = registry.runs.value.first { it.id == run.id }
        val seg = run2.segments.last() as SubagentRunRegistry.Segment.Text
        assertEquals("narration (plan captured)", seg.content)
    }
}
