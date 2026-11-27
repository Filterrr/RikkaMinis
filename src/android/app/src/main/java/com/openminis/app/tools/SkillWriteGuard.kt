package com.openminis.app.tools

/**
 * [T-skill-write-guard] 挡住"agent 给自己开权限"这条路。
 *
 * 子 agent 的权限写在 SKILL.md 的 frontmatter 里（`allowed_tools` /
 * `disallowed_tools` / `subagent` / `mcp_servers` / `read_only` …），而 agent
 * 持有 `file_write` / `file_edit`，且 `/var/minis/skills/` 下的任意 SKILL.md 没有任何守卫。
 * 于是一条 `file_write` 把某个只读 skill 的 `allowed_tools` 改成 null，下次
 * spawn 就拿到了 shell —— 一条完整的权限提升链，不需要任何漏洞。
 *
 * 策略不是"禁止改 skill"（用户就该能让 agent 帮自己写技能），而是：
 * **权限相关的键，agent 不能改也不能新增**。它们只能由用户在设置页里改，
 * 或者由 skill 的原始作者随文件一起提供。
 *
 * 纯函数、无 Android 依赖，单测直接覆盖。
 */
object SkillWriteGuard {

    /**
     * frontmatter 里一旦被 agent 改写就等于放权的键。
     *
     * `model` / `thinking` / `plan_first` 也列进来：它们不直接给权限，但能把
     * 一个便宜的子 agent 悄悄指到昂贵的模型上，或关掉计划门——同样是"用数据
     * 改运行行为"，一起收口更简单。
     */
    private val GUARDED_KEYS = setOf(
        "subagent",
        "allowed_tools",
        "disallowed_tools",
        "mcp_servers",
        "read_only",
        "max_parallel",
        "model",
        "thinking",
        "plan_first",
        "report_last_turn",
        "max_turns",
    )

    /** 只对沙箱里的 skill 定义生效。 */
    fun isSkillFile(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (!p.contains("/skills/")) return false
        return p.endsWith("/SKILL.md") || p == "SKILL.md"
    }

    /**
     * 返回错误文案（调用方直接作为工具错误给模型），null = 放行。
     *
     * [oldContent] 为 null 表示新建文件——新建一个 skill 也不允许自带权限声明，
     * 否则 agent 可以先 `file_write` 造一个 `allowed_tools: null` 的 skill
     * 再 spawn 它。
     */
    fun check(path: String, oldContent: String?, newContent: String): String? {
        if (!isSkillFile(path)) return null
        val before = guardedKeys(oldContent)
        val after = guardedKeys(newContent)
        // 删除同样是一次放权：删掉 `disallowed_tools` 等于解开黑名单，删掉
        // 整个 frontmatter 等于退回默认。只查"新增/改写"会漏掉这一半。
        val offending = (
            after.filter { (k, v) -> before[k] != v }.keys + (before.keys - after.keys)
            ).sorted()
        if (offending.isEmpty()) return null
        return "Error: SKILL.md permission fields cannot be changed by the agent " +
            "(${offending.joinToString(", ")}). These keys decide what a sub-agent " +
            "may do and must be edited by the user in Settings → Skills. " +
            "Write the body/instructions only, or ask the user to change the permissions."
    }

    /** 提取 frontmatter 里被守卫的键 → 值（只取单行形式，够用且不误判）。 */
    fun guardedKeys(content: String?): Map<String, String> {
        if (content.isNullOrBlank()) return emptyMap()
        val fm = FRONTMATTER.find(content)?.groupValues?.get(1) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in fm.split("\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            if (key !in GUARDED_KEYS) continue
            out[key] = line.substring(idx + 1).trim()
        }
        return out
    }

    /**
     * [T-skill-permissions-snapshot] 把 frontmatter 拆成「非权限行」和
     * 「权限行」两段。
     *
     * 这是入库方案的基础：安装/用户编辑时把权限行快照进数据库，运行时用
     * `非权限行 + 库里的权限行` 拼回来解析。文件之后被谁改都无所谓——权限
     * 只认库里那份。
     */
    fun splitGuarded(frontmatter: String?): Pair<String, String> {
        if (frontmatter.isNullOrBlank()) return "" to ""
        val kept = StringBuilder()
        val guarded = StringBuilder()
        // 续行（缩进行 / "- " 列表项）跟随上一个键的归属。
        var currentIsGuarded = false
        for (line in frontmatter.split("\n")) {
            val trimmed = line.trimStart()
            val key = trimmed.substringBefore(':', "").trim()
            val isKeyLine = trimmed.contains(':') && key.isNotEmpty() && !trimmed.startsWith("- ")
            if (isKeyLine) {
                currentIsGuarded = key in GUARDED_KEYS
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("- ") &&
                !line.startsWith(" ") && !line.startsWith("\t")
            ) {
                // 不是续行、也不是键：归属重置（避免把无关段落算进权限）。
                currentIsGuarded = false
            }
            if (currentIsGuarded) guarded.append(line).append('\n') else kept.append(line).append('\n')
        }
        return kept.toString().trim() to guarded.toString().trim()
    }

    private val FRONTMATTER = Regex("\\A---\\r?\\n([\\s\\S]*?)\\r?\\n---")
}