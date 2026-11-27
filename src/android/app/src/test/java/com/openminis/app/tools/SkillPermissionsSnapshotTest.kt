package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-skill-permissions-snapshot] 权限入库的契约测试。
 *
 * 守的是一条真实攻击链：agent 有 shell → 改 `/var/minis/skills/x/SKILL.md`
 * 的 `allowed_tools` → 下次 spawn 拿到 shell。工具层的守卫只能拦"某些写法"，
 * 所以真正生效的权限必须是**用户确认过的那份快照**，文件里的那一段永久失去
 * 效力。
 */
class SkillPermissionsSnapshotTest {

    private fun skill(frontmatter: String, body: String = "body"): SkillInfo =
        object : SkillInfo {
            override val name = "researcher"
            override val id = "researcher"
            override val description = "d"
            override val body: String = body
            override val frontmatter: String? = frontmatter
        }

    @Test
    fun `without a snapshot the file is trusted`() {
        val cfg = SubagentSkill.parseSubagentConfig(
            skill("subagent: true\nallowed_tools: [file_read, shell_execute]"),
        )
        assertTrue(cfg.isSubagent)
        assertEquals(setOf("file_read", "shell_execute"), cfg.allowedTools)
    }

    @Test
    fun `a tampered file cannot widen what the snapshot allowed`() {
        val onDisk = skill("subagent: true\nallowed_tools: null")
        val snapshot = "subagent: true\nallowed_tools: [file_read]"
        val cfg = SubagentSkill.parseSubagentConfig(onDisk, permissionsSnapshot = snapshot)
        assertTrue(cfg.isSubagent)
        assertEquals("the file's null must not override the confirmed list",
            setOf("file_read"), cfg.allowedTools)
    }

    @Test
    fun `a tampered file cannot turn an ordinary skill into a sub-agent`() {
        val onDisk = skill("subagent: true\nallowed_tools: null\nname: notes")
        val cfg = SubagentSkill.parseSubagentConfig(onDisk, permissionsSnapshot = "")
        assertFalse("empty snapshot means 'confirmed: no permissions'", cfg.isSubagent)
    }

    @Test
    fun `non-permission keys still come from the file`() {
        val onDisk = skill("name: researcher\nallowed_tools: null\ndescription: edited by the user")
        val cfg = SubagentSkill.parseSubagentConfig(
            onDisk,
            permissionsSnapshot = "allowed_tools: [file_read, web_search]",
        )
        // 权限来自库；其余键（name/description 之类）仍以文件为准。
        assertEquals(setOf("file_read", "web_search"), cfg.allowedTools)
    }

    @Test
    fun `split separates permission lines and keeps the rest`() {
        val (kept, guarded) = SkillWriteGuard.splitGuarded(
            "name: researcher\ndescription: does research\nsubagent: true\nallowed_tools:\n  - file_read\n  - web_search\nnote: hi",
        )
        assertTrue(kept.contains("name: researcher"))
        assertTrue(kept.contains("note: hi"))
        assertFalse(kept.contains("allowed_tools"))
        assertTrue(guarded.contains("subagent: true"))
        assertTrue(guarded.contains("file_read"), "list continuation lines belong to the key")
        assertTrue(guarded.contains("web_search"))
        assertFalse(guarded.contains("description"))
    }

    @Test
    fun `split on a plain skill yields an empty guarded half`() {
        val (kept, guarded) = SkillWriteGuard.splitGuarded("name: notes\ndescription: plain")
        assertEquals("", guarded)
        assertTrue(kept.contains("name: notes"))
    }
}