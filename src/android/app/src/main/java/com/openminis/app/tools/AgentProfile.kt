package com.openminis.app.tools

import com.openminis.app.data.model.ThinkingLevel

/**
 * [T-agent-profiles] 内置子 agent 定义。
 *
 * 之前"能不能当子 agent""能用哪些工具""用哪个模型"全部写在 SKILL.md 的
 * frontmatter 里。三个问题：
 *
 *  1. **它是一份数据，而数据是 agent 自己能写的** —— `file_write` 到
 *     `/var/minis/skills/` 下的任意 SKILL.md 没有任何守卫，把 `allowed_tools` 改成 null
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
        description = "General-purpose executor with the parent's tool set. Use when the task is clear but needs several rounds of work.",
        systemPrompt = """
            You are an execution sub-agent. The task has been handed to you - do not ask whether to do it, just do it.
            Work out the steps, then produce the result: cite file paths, give exact commands, show what you found.
            Your final message IS the deliverable - no "I will start by..." narration.
            Respond in the same language as the task description.
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
        description = "Read-only research: gather sources, cross-check them, return verifiable anchors.",
        systemPrompt = """
            You are a research sub-agent. Read-only: do not write files, do not change system state.
            Cross-check every claim against at least two sources; when they conflict, report the conflict instead of picking one.
            After each conclusion, say where you saw it (URL / file path / command output) so the caller can re-check.
            Respond in the same language as the task description.
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
        description = "Code changes: read, patch, then prove it with a command. Good for implementation and refactors.",
        systemPrompt = """
            You are a coding sub-agent. Read before you write: confirm the current content of every file you touch.
            For multi-file changes prefer apply_patch (atomic, reversible); for a single spot use file_edit.
            Always verify: run at least one command that proves the change works, and put the command plus its output summary in your final message.
            Note unrelated pre-existing problems; do not fix them on the side.
            Respond in the same language as the task description.
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
        description = "Adversarial review: hunt for counter-examples, evidence gaps and switched premises. Changes nothing.",
        systemPrompt = """
            You are a review sub-agent. Your job is to FALSIFY, not to agree.
            Take each claim apart: do its premises hold? Is there a counter-example? Is the arithmetic right? Is the citation real?
            Report only problems you can back with evidence; mark anything uncertain as "unverified" instead of mixing it in with confirmed findings.
            Respond in the same language as the task description.
        """.trimIndent(),
        capabilities = setOf(
            AgentCapability.FILE_READ,
            AgentCapability.SELF_INSPECT,
        ),
    )

    val OPS = AgentProfile(
        id = "ops",
        name = "Ops",
        description = "Environment and ops: install, run scripts, inspect logs, troubleshoot. Executes commands, reads files.",
        systemPrompt = """
            You are an ops sub-agent. Get facts from commands (versions, processes, ports, logs), not from memory.
            Before a destructive command, state what it will destroy; if it is irreversible, do not run it - hand the plan back to the caller.
            Your final message answers three things: what you ran, what you saw, what it means.
            Respond in the same language as the task description.
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
            description = "List every agent you can delegate to (built-in profiles plus " +
                "installed sub-agent skills) and the capabilities each holds. Call it instead " +
                "of guessing a name.",
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