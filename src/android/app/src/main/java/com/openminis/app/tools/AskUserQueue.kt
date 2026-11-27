package com.openminis.app.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [T-ask-user] 一次挂起式提问。纯数据，无 Android 类型 —— 对话框、通知、
 * 单元测试读的都是同一份。
 *
 * 为什么不是"让模型结束这一轮去问"：结束一轮 = 上下文、工具状态、预算
 * 全部重来，用户回答之后模型还得重新读一遍历史才开始干活。挂起式提问让
 * 模型在**同一个 run 里**等，答案直接成为下一步的输入。
 */
data class AskUserRequest(
    val id: String,
    val question: String,
    val options: List<String>,
    /** 允许自由输入（没有问题选项时强制为 true）。 */
    val allowFreeText: Boolean,
)

/** 用户的回答：点了第几项（-1 表示没点）＋ 自由输入文本。 */
data class AskUserAnswer(
    val selectedIndex: Int,
    val text: String?,
)

/**
 * [T-ask-user] 待答问题队列，形状照抄 [SpawnApprovalQueue]（那套 resolve /
 * remove / teardown 的竞态语义已经在生产里跑过一遍了）：
 *
 *  - resolve 恰好一次：第二次调用返回 false，不重复投递；
 *  - remove 是等待方超时的出口：调用方不再关心，问题从 UI 消失，deferred
 *    故意不 complete —— 迟到的点击什么也不会解决；
 *  - cancelAllOutstanding 是 teardown 路径（清空会话 / VM 销毁）：每个挂起的
 *    问题都 complete(null)，没有一轮会永远冻在一个没人看得见的对话框后面。
 */
class AskUserQueue {

    private class Entry(val request: AskUserRequest) {
        val deferred = CompletableDeferred<AskUserAnswer?>()
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    private val _requests = MutableStateFlow<List<AskUserRequest>>(emptyList())

    /** 待答问题，按到达顺序 —— 对话框渲染的就是这个。 */
    val requests: StateFlow<List<AskUserRequest>> = _requests.asStateFlow()

    /** 注册一个问题，返回待渲染的行 + 要等的 deferred。 */
    fun submit(
        question: String,
        options: List<String>,
        allowFreeText: Boolean,
    ): Pair<AskUserRequest, CompletableDeferred<AskUserAnswer?>> {
        val cleaned = options
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_OPTIONS)
        val request = AskUserRequest(
            id = nextId(),
            question = question.trim().take(MAX_QUESTION_CHARS),
            options = cleaned,
            // 没有选项就只能自由输入，否则这个对话框没有任何可点的东西。
            allowFreeText = allowFreeText || cleaned.isEmpty(),
        )
        val entry = Entry(request)
        entries[request.id] = entry
        _requests.update { it + request }
        return request to entry.deferred
    }

    /** 用户回答了 [id]。true 表示这一次调用真的投递了答案。 */
    fun resolve(id: String, answer: AskUserAnswer): Boolean {
        val entry = entries[id] ?: return false
        if (!entry.deferred.complete(answer)) return false
        retire(id)
        return true
    }

    /** 等待方超时放弃 —— 把问题从 UI 摘掉，但不投递任何答案。 */
    fun remove(id: String) {
        val entry = entries[id] ?: return
        if (entry.deferred.isCompleted) {
            retire(id)
            return
        }
        entries.remove(id)
        _requests.update { list -> list.filterNot { it.id == id } }
    }

    /** Teardown：所有挂起问题完成为 null（没人回答）。 */
    fun cancelAllOutstanding(): Int =
        entries.keys.toList().count { id ->
            val entry = entries[id]
            if (entry != null && entry.deferred.complete(null)) {
                retire(id)
                true
            } else {
                false
            }
        }

    private fun retire(id: String) {
        entries.remove(id)
        _requests.update { list -> list.filterNot { it.id == id } }
    }

    companion object {
        /** 超过这个数就不再是"选一个"，而是一份菜单——让模型重新组织问题。 */
        const val MAX_OPTIONS = 6
        const val MAX_QUESTION_CHARS = 2000

        private val counter = AtomicLong(0)

        fun nextId(): String =
            "ask-" + System.currentTimeMillis().toString(36) + "-" + counter.incrementAndGet().toString(36)
    }
}