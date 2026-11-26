package com.openminis.app.tools

import com.openminis.app.data.model.ThinkingLevel

/**
 * [T-agent-profiles] 内置子 agent 定义。
 *
 * 之前"能不能当子 agent""能用哪些工具""用哪个模型"全部写在 SKILL.md 的
 * frontmatter 里。三个问题：
 *
 *  1. **它是一份数据，而数据是 agent 自己能写的** —— `file_write` 到
 *     `/var/minis/skills/**` 没有任何守卫，把 `allowed_tools` 改成 null
 *     就等于给自己/给子 agent 开 shell；
 *  2. **想跑一次临时任务就得先造一个文件** —— "读三个文件总结一下"也要
 *     先写 SKILL.md、声明 `subagent: true`、再 spawn；
 *  3. **配置跟着文件走，评审跟着人走** —— 代码里的常量能被 review，磁盘上
 *     的 YAML 不会。
 *
 * profile 把"运行定义"从"知识包"里拆出来：权限与预算进代码，领域知识仍可
 * 来自 skill。用户自己的 subagent skill 依旧有效，作为覆盖层。
 */
internal data class AgentProfile(
    /** 调用时用的名字，也是并发/审批键的一部分：`profile:<id>`。 */
    val id: String,
    val name: String,
    val description: String,
    /** 只放角色与工作方式；报告契约、预算提示由 runner 统一追加。 */
    val systemPrompt: String,
    /** 该 profile 允许的能力集合（求交的基准，永远不因调用参数而放大）。 */
    val capabilities: Set<AgentCapability>,
    val model: String? = null,
    val thinking: ThinkingLevel = ThinkingLevel.OFF,
    val maxParallel: Int = 1,
    val planFirst: Boolean = true,
    val defaultTurns: Int = 12,
) {
    /** 稳定标识：和 skill id 落在同一个命名空间里，注册表/并发键无需特判。 */
    val skillId: String get() = "profile:$id"
}

internal object AgentProfiles {

    val GENERAL = AgentProfile(
        id = "general",
        name = "General",
        description = "与主 agent 同等工具集的通用执行者。任务清楚但需要多轮探索时用它。",
        systemPrompt = """
            你是一个执行型子智能体：任务已经交到你手上，不要反问要不要做，直接做。
            先想清楚需要几步，然后把结果做出来——能引用文件路径就写路径，能给出命令就写命令。
            你的最终回复就是交付物，不要写"我准备开始"这类过程叙述。
        """.trimIndent(),
        capabilities = setOf(
            AgentCapability.FILE_READ,
            AgentCapability.FILE_WRITE,
            AgentCapability.SHELL,
            AgentCapability.BROWSER,
            AgentCapability.WEB_SEARCH,
            AgentCapability.SELF_INSPECT,
        ),
    )

    val RESEARCHER = AgentProfile(
        id = "researcher",
        name = "Researcher",
        description = "只读检索与交叉验证：查资料、比对多个来源、给可核查的引用锚点。",
        systemPrompt = """
            你是调研型子智能体。只读：不要写文件、不要改系统状态。
            至少用两个来源交叉验证同一结论；冲突时把冲突写出来，不要二选一硬下结论。
            每条结论后面给出你从哪看到的（URL/文件名/命令输出片段），让上层能复查。
        """.trimIndent(),
        capabilities = setOf(
            AgentCapability.FILE_READ,
            AgentCapability.BROWSER,
            AgentCapability.WEB_SEARCH,
            AgentCapability.SELF_INSPECT,
        ),
    )

    val CODER = AgentProfile(
        id = "coder",
        name = "Coder",
        description = "改代码：读文件、写补丁、跑命令验证。适合实现与重构。",
        systemPrompt = """
            你是编码型子智能体。先读再改：动手前至少确认相关文件当前内容。
            多文件改动优先用 apply_patch（原子、可回滚），单点改动用 file_edit。
            改完必须验证：至少跑一次能证明改对了的命令，把命令与输出摘要写进最终回复。
            遇到与任务无关的既有问题，记下来，不要顺手改。
        """.trimIndent(),
        capabilities = setOf(
            AgentCapability.FILE_READ,
            AgentCapability.FILE_WRITE,
            AgentCapability.SHELL,
            AgentCapability.SELF_INSPECT,
        ),
    )

    val REVIEWER = AgentProfile(
        id = "reviewer",
        name = "Reviewer",
        description = "对抗式复核：找反例、证据缺口、被偷换的前提。不改任何东西。",
        systemPrompt = """
            你是复核型子智能体，任务是**证伪**而不是附和。
            逐条检查给定结论：它的前提成立吗？有没有反例？数字算对了吗？引用是真的吗？
            只报你能用证据支撑的问题；不确定的标成"未验证"，不要混进"已确认的问题"里。
        """.trimIndent(),
        capabilities = setOf(
            AgentCapability.FILE_READ,
            AgentCapability.SELF_INSPECT,
        ),
    )

    val OPS = AgentProfile(
        id = "ops",
        name = "Ops",
        description = "环境与运维：装包、跑脚本、看日志、排障。会执行命令，但只读文件。",
        systemPrompt = """
            你是运维型子智能体。优先用命令拿到事实（版本、进程、端口、日志），不要凭印象。
            破坏性命令先说明它会造成什么，再执行；不可逆的不要做，把方案交给上层决定。
            最终回复写清楚：做了什么、看到了什么、结论是什么。
        """.trimIndent(),
        capabilities = setOf(
            AgentCapability.FILE_READ,
            AgentCapability.SHELL,
            AgentCapability.SELF_INSPECT,
        ),
    )

    val ALL: List<AgentProfile> = listOf(GENERAL, RESEARCHER, CODER, REVIEWER, OPS)

    fun find(idOrName: String): AgentProfile? {
        val key = idOrName.trim()
        if (key.isEmpty()) return null
        return ALL.firstOrNull { it.id.equals(key, ignoreCase = true) || it.name.equals(key, ignoreCase = true) }
    }

    /** 默认 profile：`agent` 缺省时用它，不再要求先造 SKILL.md。 */
    fun default(): AgentProfile = GENERAL

    /**
     * [T-agent-profiles] `list_agents` 的定义。
     *
     * 取代把 roster 拼成字符串塞进 spawn_agent 描述的做法：那是一个快照，
     * 装了新 skill 不会更新，删了也还留着。让模型每次问一次，拿到的就是
     * 当下的真实集合。
     */
    fun definition(): com.openminis.app.data.model.AgentToolDefinition =
        com.openminis.app.data.model.AgentToolDefinition(
            name = "list_agents",
            description = "List the agents you can delegate to: the built-in profiles " +
                "(general / researcher / coder / reviewer / ops — no SKILL.md needed) plus " +
                "every installed sub-agent skill, with the capabilities each one holds. " +
                "Call this when you are unsure which agent fits, instead of guessing a name.",
            parameters = mapOf(
                "tool_title" to com.openminis.app.data.model.AgentToolParam(
                    "string",
                    "A concise summary (e.g. 'List available agents').",
                ),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
        )

    /** 把 profile 包装成 SkillInfo，runner 下游（并发键、registry、审批）无需特判。 */
    fun toSkillInfo(profile: AgentProfile): SkillInfo = ProfileSkillInfo(
        id = profile.skillId,
        name = profile.name,
        description = profile.description,
        body = profile.systemPrompt,
    )

    fun toConfig(profile: AgentProfile): SubagentSkill.SubagentConfig = SubagentSkill.SubagentConfig(
        isSubagent = true,
        maxTurns = profile.defaultTurns,
        allowedTools = AgentCapabilities.toolsFor(profile.capabilities),
        maxParallel = profile.maxParallel,
        thinkingLevel = profile.thinking,
        planFirst = profile.planFirst,
        model = profile.model,
        reportLastTurn = true,
        readOnly = AgentCapability.FILE_WRITE !in profile.capabilities,
    )

    private data class ProfileSkillInfo(
        override val id: String,
        override val name: String,
        override val description: String,
        override val body: String,
        override val frontmatter: String? = null,
    ) : SkillInfo
}