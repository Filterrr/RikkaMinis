package com.openminis.app.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-todo-tool] Session-scoped task list the agent maintains via the
 * `todo_write` tool. Surfaced as a floating badge in the chat top bar —
 * tap to see the current plan.
 *
 * In-memory + per-session (like iOS keeps its tool UI state): cleared on
 * session switch, NOT persisted across process death. The authoritative
 * copy always lives in the conversation transcript (the tool call + the
 * model's narration), so a process restart simply means the list starts
 * empty until the agent re-writes it — same trade-off the agent-loop
 * session files make.
 *
 * Data model mirrors Claude Code's TodoWrite shape the models already
 * know: {content, status: pending|in_progress|completed}.
 */
object TodoStore {

    enum class Status { pending, in_progress, completed }

    data class TodoItem(
        val content: String,
        val status: Status,
    )

    data class TodoList(
        val sessionId: String?,
        val items: List<TodoItem>,
        val updatedAtMs: Long,
    ) {
        val pendingCount: Int get() = items.count { it.status != Status.completed }
        val totalCount: Int get() = items.size
        val hasActive: Boolean get() = items.any { it.status == Status.in_progress }
    }

    private val _todo = MutableStateFlow(TodoList(null, emptyList(), 0L))
    val todo: StateFlow<TodoList> = _todo.asStateFlow()

    /** Replace the whole list (TodoWrite semantics — full rewrite each call). */
    fun write(sessionId: String?, items: List<TodoItem>) {
        _todo.value = TodoList(sessionId, items, System.currentTimeMillis())
    }

    /** Drop the list on session switch — the new session starts clean. */
    fun clearForSession(sessionId: String?) {
        val current = _todo.value
        if (current.sessionId != sessionId) {
            _todo.value = TodoList(sessionId, emptyList(), 0L)
        }
    }

    fun clearAll() {
        _todo.value = TodoList(null, emptyList(), 0L)
    }
}
