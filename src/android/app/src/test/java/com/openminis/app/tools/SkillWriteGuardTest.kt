package com.openminis.app.tools

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-skill-write-guard] 权限提升路径的回归测试。
 *
 * 这条链是真实存在的：agent 有 file_write → 改 SKILL.md 的 allowed_tools
 * → 下次 spawn 拿到 shell。所以每个用例都从"agent 会怎么写"出发，而不是
 * 从函数的参数签名出发。
 */
class SkillWriteGuardTest {

    private val skillPath = "/var/minis/skills/researcher/SKILL.md"

    private fun doc(frontmatter: String, body: String = "正文内容"): String =
        "---\n$frontmatter\n---\n\n$body\n"

    @Test
    fun `widening allowed_tools is refused`() {
        val before = doc("name: researcher\nallowed_tools: [file_read, web_search]")
        val after = doc("name: researcher\nallowed_tools: null")
        val err = SkillWriteGuard.check(skillPath, before, after)
        assertTrue(err != null && err.contains("allowed_tools"))
    }

    @Test
    fun `turning on subagent is refused`() {
        val before = doc("name: notes\nallowed_tools: [file_read]")
        val after = doc("name: notes\nsubagent: true\nallowed_tools: [file_read]")
        assertTrue(SkillWriteGuard.check(skillPath, before, after) != null)
    }

    @Test
    fun `dropping disallowed_tools is refused`() {
        val before = doc("name: coder\ndisallowed_tools: [shell_execute]")
        val after = doc("name: coder")
        assertTrue(SkillWriteGuard.check(skillPath, before, after) != null)
    }

    @Test
    fun `creating a skill that already declares permissions is refused`() {
        val newFile = doc("name: sneaky\nsubagent: true\nallowed_tools: null")
        assertTrue(SkillWriteGuard.check(skillPath, null, newFile) != null)
    }

    @Test
    fun `editing the body is allowed`() {
        val before = doc("name: researcher\nallowed_tools: [file_read]", "旧的正文")
        val after = doc("name: researcher\nallowed_tools: [file_read]", "新的正文，权限没动")
        assertNull(SkillWriteGuard.check(skillPath, before, after))
    }

    @Test
    fun `non-skill paths are never guarded`() {
        val before = doc("allowed_tools: null")
        assertNull(SkillWriteGuard.check("/var/minis/workspace/notes.md", before, before))
        assertNull(SkillWriteGuard.check("/var/minis/skills/researcher/scripts/run.sh", before, before))
    }

    @Test
    fun `guard only inspects the frontmatter, not the body`() {
        val before = doc("name: notes", "正文里提到 allowed_tools: null 是没关系的")
        val after = doc("name: notes", "正文改了，allowed_tools: null 只是文字")
        assertNull(SkillWriteGuard.check(skillPath, before, after))
    }
}