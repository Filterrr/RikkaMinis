package com.openminis.app.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-todo-tool] Per-session task list the agent maintains via the
 * `todo_write` tool. Surfaced as a floating badge in the chat top bar —
 * tap to see the current plan.
 *
 * ## Storage model (fix: task list leaking across sessions)
 *
 * Lists are stored PER SESSION, keyed by the session id that wrote them.
 * The published [todo] flow always carries the list of the FOREGROUND
 * session (as tracked by `ChatScreen`'s lifecycle hook via
 * [setActiveSession]), so switching conversations shows each conversation's
 * own plan instead of whichever session wrote last.
 *
 * The bug this replaces: a single process-wide list meant (a) session B's
 * screen rendered session A's in-flight plan (last-writer-wins), and (b)
 * the clear-on-switch path wiped A's list when the user merely peeked at B
 * — returning to A showed an empty or foreign list. Both were the same
 * root cause: one mutable slot for N concurrent sessions (every
 * ChatViewModel stays alive in [com.openminis.app.ui.chat.ChatViewModelStore]
 * by design, and their agent loops can write at any time).
 *
 * In-memory, NOT persisted across process death — same trade-off as before:
 * the authoritative copy always lives in the conversation transcript (the
 * tool call + the model's narration), so a process restart simply means the
 * list starts empty until the agent re-writes it.
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

    private val EMPTY = TodoList(null, emptyList(), 0L)

    /** One list per session id. Bounded by [MAX_TRACKED_SESSIONS]. */
    private val lists = LinkedHashMap<String, TodoList>()

    /** The session whose list [todo] publishes. Tracked by the UI lifecycle. */
    @Volatile
    private var activeSessionId: String? = null

    private val _todo = MutableStateFlow(EMPTY)
    val todo: StateFlow<TodoList> = _todo.asStateFlow()

    /**
     * Cap on tracked sessions. Every chat the user opens adds at most one
     * entry (~a handful of short strings); the LRU bound keeps a very long
     * app session from accumulating them unboundedly. Comfortably above any
     * realistic number of concurrently-live ChatViewModels.
     */
    private const val MAX_TRACKED_SESSIONS = 32

    /**
     * Publish the given session's list as the visible one. Called by
     * ChatScreen's lifecycle hook (enter/dispose), mirroring
     * ChatViewModelStore.setActiveSession.
     */
    @Synchronized
    fun setActiveSession(sessionId: String?) {
        activeSessionId = sessionId
        _todo.value = sessionId?.let { lists[it] } ?: EMPTY
    }

    /**
     * Withdraw the active-session pointer on screen dispose — but only when
     * [sessionId] is STILL the published one. A fast A→B→dispose(B) race
     * (B's screen disposes after A's mount published A) must not retract
     * A's publication, or the badge shows an empty list while A's screen is
     * actually foregrounded.
     */
    @Synchronized
    fun retractActiveSession(sessionId: String?) {
        if (activeSessionId == sessionId) {
            activeSessionId = null
            _todo.value = EMPTY
        }
    }

    /** Replace the whole list for [sessionId] (TodoWrite semantics — full rewrite each call). */
    @Synchronized
    fun write(sessionId: String?, items: List<TodoItem>) {
        if (sessionId == null) {
            // No session context (headless/edge callers): keep the old
            // single-slot semantics — publish directly, store nothing.
            _todo.value = TodoList(null, items, System.currentTimeMillis())
            return
        }
        lists[sessionId] = TodoList(sessionId, items, System.currentTimeMillis())
        // LRU refresh: reinserting moves the key to the end of the LinkedHashMap.
        if (lists.size > MAX_TRACKED_SESSIONS) {
            val eldest = lists.keys.first()
            if (eldest != activeSessionId) lists.remove(eldest)
        }
        if (activeSessionId == sessionId) {
            _todo.value = lists[sessionId] ?: EMPTY
        }
    }

    fun clearAll() {
        synchronized(this) {
            lists.clear()
        }
        _todo.value = EMPTY
    }

    /**
     * Drop one session's list (session deleted). If the deleted session is
     * the currently-published one, the visible list resets to empty — the
     * badge lives inside that (now closed) screen anyway.
     */
    @Synchronized
    fun dropSession(sessionId: String?) {
        if (sessionId == null) return
        lists.remove(sessionId)
        if (activeSessionId == sessionId) {
            activeSessionId = null
            _todo.value = EMPTY
        }
    }

    /** Debug/diagnostic read of one session's list (not the UI path). */
    @Synchronized
    fun listFor(sessionId: String?): TodoList =
        sessionId?.let { lists[it] } ?: EMPTY
}
