package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] The frontmatter parser.
 *
 * These cases are the ones the old hand-rolled `startsWith("key:")` matcher
 * got wrong. Each of them was a real way for a skill author's intent to be
 * silently lost.
 */
class SubagentFrontmatterTest {

    // ── the bugs the old parser had ───────────────────────────────────────

    /**
     * A value containing a colon. The old parser cut at the FIRST colon, so
 * everything after the second one was silently dropped — a description like
     * "use this: for that" lost half its text.
     */
    @Test
    fun `a value may contain a colon`() {
        val fm = SubagentFrontmatter.parse("description: use this: for that")
        assertEquals("use this: for that", fm.string("description"))
    }

    /**
     * A quoted value containing a comma. The old parser split on EVERY
     * comma, so `["a, b"]` became two broken entries.
     */
    @Test
    fun `a quoted list value may contain a comma`() {
        val fm = SubagentFrontmatter.parse("""allowed_tools: ["a, b", "c"]""")
        assertEquals(listOf("a, b", "c"), fm.list("allowed_tools"))
    }

    @Test
    fun `a hash inside a word is not a comment`() {
        val fm = SubagentFrontmatter.parse("description: abc#def")
        assertEquals("abc#def", fm.string("description"))
    }

    @Test
    fun `a hash after a space starts a comment`() {
        val fm = SubagentFrontmatter.parse("description: abc # a comment")
        assertEquals("abc", fm.string("description"))
    }

    @Test
    fun `a key that merely contains another key is not that key`() {
        val fm = SubagentFrontmatter.parse("not_subagent: true\nsubagent: false")
        assertFalse(fm.boolean("subagent") ?: true)
        assertEquals("true", fm.string("not_subagent"))
    }

    // ── the shapes a frontmatter actually uses ────────────────────────────

    @Test
    fun `a plain scalar`() {
        val fm = SubagentFrontmatter.parse("max_turns: 40")
        assertEquals(40, fm.int("max_turns"))
    }

    @Test
    fun `an inline list`() {
        val fm = SubagentFrontmatter.parse("allowed_tools: [file_read, browser_use]")
        assertEquals(listOf("file_read", "browser_use"), fm.list("allowed_tools"))
    }

    @Test
    fun `a block list`() {
        val fm = SubagentFrontmatter.parse(
            """
            allowed_tools:
              - file_read
              - browser_use
            """.trimIndent(),
        )
        assertEquals(listOf("file_read", "browser_use"), fm.list("allowed_tools"))
    }

    @Test
    fun `a scalar reads as a one element list`() {
        // An author should not have to care whether they wrote
        // `allowed_tools: read` or `allowed_tools: [read]`.
        val fm = SubagentFrontmatter.parse("allowed_tools: file_read")
        assertEquals(listOf("file_read"), fm.list("allowed_tools"))
    }

    @Test
    fun `booleans accept the usual spellings`() {
        assertEquals(true, SubagentFrontmatter.parse("subagent: true").boolean("subagent"))
        assertEquals(true, SubagentFrontmatter.parse("subagent: yes").boolean("subagent"))
        assertEquals(true, SubagentFrontmatter.parse("subagent: on").boolean("subagent"))
        assertEquals(false, SubagentFrontmatter.parse("subagent: false").boolean("subagent"))
        assertEquals(false, SubagentFrontmatter.parse("subagent: no").boolean("subagent"))
        assertEquals(false, SubagentFrontmatter.parse("subagent: off").boolean("subagent"))
        assertEquals(true, SubagentFrontmatter.parse("""subagent: "true"""").boolean("subagent"))
    }

    @Test
    fun `a quoted scalar loses its quotes`() {
        val fm = SubagentFrontmatter.parse("""description: "a description"""")
        assertEquals("a description", fm.string("description"))
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val fm = SubagentFrontmatter.parse(
            """
            # a comment

            subagent: true

            # another
            max_turns: 12
            """.trimIndent(),
        )
        assertEquals(true, fm.boolean("subagent"))
        assertEquals(12, fm.int("max_turns"))
    }

    @Test
    fun `an absent key is null, not a guess`() {
        val fm = SubagentFrontmatter.parse("subagent: true")
        assertNull(fm.string("max_turns"))
        assertNull(fm.boolean("plan_first"))
        assertNull(fm.list("allowed_tools"))
    }

    @Test
    fun `a blank frontmatter parses to nothing`() {
        val fm = SubagentFrontmatter.parse("")
        assertTrue(fm.keys().isEmpty())
        val blank = SubagentFrontmatter.parse(null)
        assertTrue(blank.keys().isEmpty())
    }

    // ── extraction from a raw SKILL.md ────────────────────────────────────

    @Test
    fun `extract finds the frontmatter block`() {
        val raw = """
            ---
            subagent: true
            ---
            # Body

            Text with a --- rule inside.
        """.trimIndent()
        val fm = SubagentFrontmatter.parse(SubagentFrontmatter.extract(raw))
        assertEquals(true, fm.boolean("subagent"))
    }

    @Test
    fun `extract returns null when there is no frontmatter`() {
        assertNull(SubagentFrontmatter.extract("# Just a body"))
        assertNull(SubagentFrontmatter.extract("---\nunterminated: true"))
        assertNull(SubagentFrontmatter.extract(""))
    }

    // ── name validation ───────────────────────────────────────────────────

    @Test
    fun `a valid name passes`() {
        assertNull(SubagentFrontmatter.validateName("general-agent"))
        assertNull(SubagentFrontmatter.validateName("security-reviewer2"))
        assertNull(SubagentFrontmatter.validateName("a"))
    }

    @Test
    fun `a name must start with a letter`() {
        assertTrue(SubagentFrontmatter.validateName("2fast")!!.contains("start with a letter"))
        assertTrue(SubagentFrontmatter.validateName("-dash")!!.contains("start with a letter"))
    }

    @Test
    fun `a name may not contain spaces or punctuation`() {
        assertTrue(SubagentFrontmatter.validateName("two words")!!.contains("only letters"))
        assertTrue(SubagentFrontmatter.validateName("under_score")!!.contains("only letters"))
    }

    @Test
    fun `a name has a length limit`() {
        val long = "a".repeat(SubagentFrontmatter.MAX_NAME_LENGTH + 1)
        assertTrue(SubagentFrontmatter.validateName(long)!!.contains("limit"))
    }

    @Test
    fun `an empty name is rejected`() {
        assertTrue(SubagentFrontmatter.validateName("  ")!!.contains("empty"))
    }
}
