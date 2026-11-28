package com.openminis.app.ui.chat

import com.openminis.app.ui.chat.ChatModels.AssistantBlock
import com.openminis.app.ui.chat.ChatModels.ToolBlockStatus

/**
 * [T-fold-consecutive-tools] 决定**一条助手消息内**哪一段连续工具调用折叠成
 * 一行。
 *
 * 移植自上游 OpenMinis 的 `Agent/MessageList/ToolFoldPlanner.swift`（同一
 * 标记 `T-fold-consecutive-tools`），规则一字不改，只换成 Kotlin：
 *
 *  - run 从第一个工具开始，往后吃：**已结束**的工具（计数）、thinking
 *    （跟着走、不计数——交替思考的模型本来就在 thinking/tool 之间来回，
 *    否则永远够不到阈值）、**空文本块**（还没收到内容的 text）；
 *  - [T-fold-tool-notes] 工具之间的一行备注（`note`）也折进去、不计数；
 *  - run 结束于：非空正文、info/delegate 这类"屏障"块、以及**未结束**的
 *    工具（streaming / running）。
 *  - 只有 ≥ [MINIMUM_TOOL_COUNT] 个工具、且 run 后面跟着**非空正文**时才折：
 *    工具还在跑、或消息以工具结尾，一律保持展开。
 *  - [T-fold-lead-thinking] run 第一个工具**之前**的 thinking（中间只能隔空
 *    文本）也折进来——那是导致这些调用的推理，留在外面会孤零零挂在上方。
 *
 * 纯函数、无 Android 依赖：规则可单测（`ToolFoldPlannerTest`）。
 */
/**
 * [T-fold-consecutive-tools] 折叠开关的进程内副本。
 *
 * `buildFlatChatItems` 是纯函数（没有 Context），而开关存在 SharedPreferences
 * 里。这里存一份副本，由 MinisApp 启动时和设置页改动时写入——列表重建是热
 * 路径，不该每次都去读盘。
 */
internal object ToolFoldSettings {
    @Volatile
    var enabled: Boolean = true
}

internal object ToolFoldPlanner {

    /** 折叠所需的最少工具数。上游 2（用户要求从 3 降下来的：一对调用就够挤了）。 */
    const val MINIMUM_TOOL_COUNT = 2

    /** 请求结束时要折前面那串工具，回复至少要有几行。 */
    const val SEPARATOR_MINIMUM_LINES = 2

    enum class Outcome { UNFINISHED, SUCCEEDED, FAILED, CANCELLED }

    sealed class Input {
        data class Tool(val outcome: Outcome) : Input()
        data object Thinking : Input()
        data class Text(val isEmpty: Boolean) : Input()

        /** [T-fold-tool-notes] 工具之间的一行备注，不是回复。 */
        data object Note : Input()

        /** 永远可见并终止 run：info 行、子智能体委派。 */
        data object Barrier : Input()
    }

    data class Group(
        /** 被折进去的块下标，从第一个工具到最后一个成员（工具或 note）。 */
        val range: IntRange,
        val toolCount: Int,
        val failedCount: Int,
        val cancelledCount: Int,
    )

    fun plan(blocks: List<Input>, enabled: Boolean): List<Group> {
        if (!enabled) return emptyList()
        val groups = mutableListOf<Group>()
        var start: Int? = null
        // 当前位置之前那一段"连续 thinking / 空文本"的第一个 thinking。
        var leadThinking: Int? = null
        var lastMember = 0
        var tools = 0
        var failed = 0
        var cancelled = 0
        // run 在它最新一个 note 处的样子（软结束点）。
        var softEnd: Group? = null

        fun reset() {
            start = null
            tools = 0
            failed = 0
            cancelled = 0
            softEnd = null
        }

        // run 被切断且后面没有正文：把 note 之前那部分折掉（如果够数）。
        fun closeAtSoftEnd() {
            softEnd?.let { if (it.toolCount >= MINIMUM_TOOL_COUNT) groups.add(it) }
        }

        for ((i, block) in blocks.withIndex()) {
            when (block) {
                is Input.Tool -> {
                    if (block.outcome == Outcome.UNFINISHED) {
                        closeAtSoftEnd()
                        reset()
                        leadThinking = null
                    } else {
                        if (start == null) start = leadThinking ?: i
                        leadThinking = null
                        lastMember = i
                        tools += 1
                        if (block.outcome == Outcome.FAILED) failed += 1
                        if (block.outcome == Outcome.CANCELLED) cancelled += 1
                    }
                }

                Input.Barrier -> {
                    closeAtSoftEnd()
                    reset()
                    leadThinking = null
                }

                Input.Note -> {
                    val s = start
                    if (s == null) {
                        // 第一个调用之前的备注不属于任何 run，保持可见；
                        // 它同时把前面的 thinking 和后面的调用隔开。
                        leadThinking = null
                    } else {
                        lastMember = i
                        softEnd = Group(s..i, tools, failed, cancelled)
                    }
                }

                Input.Thinking -> if (start == null && leadThinking == null) leadThinking = i

                is Input.Text -> {
                    if (!block.isEmpty) {
                        val s = start
                        if (s != null && tools >= MINIMUM_TOOL_COUNT) {
                            groups.add(Group(s..lastMember, tools, failed, cancelled))
                        }
                        reset()
                        leadThinking = null
                    }
                    // 空文本块：跟着走，不打断、也不计数。
                }
            }
        }
        // 走到末尾还没遇到正文：只有 note 之前那部分折，其余保持展开。
        closeAtSoftEnd()
        return groups
    }

    /**
     * [T-fold-tool-notes] 把工具之间那些单行备注标记成 [Input.Note]。
     *
     * 非空文本满足：**单行**，且它后面还有工具调用。消息里最后一个工具之后
     * 的一切都是回复（包括被拆分成长回复的多段文本），永远不是 note。
     */
    fun markingNotes(inputs: List<Input>, isSingleLine: (Int) -> Boolean): List<Input> {
        val lastTool = inputs.indexOfLast { it is Input.Tool }
        if (lastTool < 0) return inputs
        val out = inputs.toMutableList()
        for (i in 0 until lastTool) {
            if (out[i] == Input.Text(isEmpty = false) && isSingleLine(i)) {
                out[i] = Input.Note
            }
        }
        return out
    }

    /** 单行 = 第一个与最后一个可见字符之间没有换行（与屏幕上的折行无关）。 */
    fun isSingleLine(text: String): Boolean {
        val first = text.indexOfFirst { !it.isWhitespace() }
        val last = text.indexOfLast { !it.isWhitespace() }
        if (first < 0 || last < 0) return true
        return text.substring(first, last + 1).none { it == '\n' || it == '\r' }
    }

    /** 有可见字符的行数（按换行算，不看屏幕折行）。 */
    fun nonEmptyLineCount(text: String): Int =
        text.split('\n').count { line -> line.any { !it.isWhitespace() } }

    /** 这个请求的回复够不够长，够就折它前面那串工具。 */
    fun isFoldSeparator(requestText: String): Boolean {
        var lines = 0
        for (line in requestText.split('\n')) {
            if (line.any { !it.isWhitespace() }) {
                lines += 1
                if (lines >= SEPARATOR_MINIMUM_LINES) return true
            }
        }
        return false
    }

    /**
     * 任务还在跑的消息：只规划到"最后一次够长回复"的最后一个块为止。
     * 返回 0 表示还没有可折的部分。
     */
    fun foldHorizon(blockIds: List<String>, separators: Set<String>): Int {
        if (separators.isEmpty()) return 0
        val last = blockIds.indexOfLast { it in separators }
        return if (last < 0) 0 else last + 1
    }

    /**
     * [T-fold-browse-hold] 用户正在往上翻时先别折：折叠会让列表高度突变，
     * 把正在看的内容顶走。已经折过的、刚打开的会话不受影响。
     */
    fun holdsBack(
        browsing: Boolean,
        alreadyFolded: Boolean,
        inRunningMessage: Boolean,
        shownAsRows: () -> Boolean,
    ): Boolean {
        if (!browsing || alreadyFolded) return false
        return inRunningMessage || shownAsRows()
    }
}