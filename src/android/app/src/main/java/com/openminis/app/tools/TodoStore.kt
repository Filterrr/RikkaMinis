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
 * ## Draft→real id canonicalization (fix: badge never shows on re-entered
 * chats)
 *
 * A fresh chat's ChatViewModel constructor sessionId is the DRAFT key
 * (`__new__<uuid>`). The todo executor writes under that constructor value
 * — it cannot read the VM's [realSessionId], which lives in the VM while
 * this store is process-wide. Two mismatches followed:
 *
 *  1. After the first send, [com.openminis.app.ui.chat.ChatViewModelStore.rename]
 *     maps the draft key to the real id, but the ChatScreen still composed
 *     with the draft route param for the rest of that turn; a list written
 *     as `__new__…` never matched a foreground published later with the
 *     real id.
 *  2. When the user left and re-entered the conversation, the route (and
 *     the mount publication) carried the REAL id — the stored list sat
 *     orphaned under the draft key, so the badge stayed hidden forever.
 *
 * Fix: ids are canonicalized through a draft→real alias map (mirrored from
 * ChatViewModelStore via [renameSession], called by ensureSession at draft
 * persist time) at EVERY boundary — [setActiveSession], [write], and the
 * [activeSessionId] resolution — so the visible list always follows the
 * conversation the user is looking at, whichever spelling of the id a
 * caller uses.
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

    /**
     * Draft→real id mirror of ChatViewModelStore.aliases. Registered by
     * [renameSession] when ensureSession persists a draft; consulted by
     * every id boundary below. Guarded by `this` (all accessors are
     * @Synchronized).
     */
    private val draftAliases = HashMap<String, String>()

    /**
     * The session whose list [todo] publishes, EXACTLY as the UI passed it
     * (may be a draft key). Resolve through [activeSessionKey] — never
     * compare or store lists against this raw value.
     */
    @Volatile
    private var activeSessionIdRaw: String? = null

    /** Canonical (alias-resolved) id of the foreground session. */
    private val activeSessionKey: String?
        get() = activeSessionIdRaw?.let { draftAliases[it] ?: it }

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
     * ChatViewModelStore.setActiveSession. Accepts either spelling of the
     * id (draft key or real id) — canonicalized before use.
     */
    @Synchronized
    fun setActiveSession(sessionId: String?) {
        activeSessionIdRaw = sessionId
        _todo.value = activeSessionKey?.let { lists[it] } ?: EMPTY
    }

    /**
     * Withdraw the active-session pointer on screen dispose — but only when
     * [sessionId] is STILL the published one (compared canonically, so a
     * screen holding the stale draft param retracts correctly after the
     * draft was persisted). A fast A→B→dispose(B) race (B's screen disposes
     * after A's mount published A) must not retract A's publication, or the
     * badge shows an empty list while A's screen is actually foregrounded.
     */
    @Synchronized
    fun retractActiveSession(sessionId: String?) {
        if (sessionId == null) return
        val canonical = draftAliases[sessionId] ?: sessionId
        if (activeSessionIdRaw != null && activeSessionKey == canonical) {
            activeSessionIdRaw = null
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
        // [fix-todo-badge-never-shows] Canonicalize the caller's id (the VM
        // constructor value — still the draft key for a freshly persisted
        // chat) through the alias map, so the list lands under the same key
        // the foreground screen publishes with.
        val canonicalId = draftAliases[sessionId] ?: sessionId
        lists[canonicalId] = TodoList(canonicalId, items, System.currentTimeMillis())
        // LRU refresh: reinserting moves the key to the end of the LinkedHashMap.
        if (lists.size > MAX_TRACKED_SESSIONS) {
            val eldest = lists.keys.first()
            if (eldest != activeSessionKey) lists.remove(eldest)
        }
        if (activeSessionKey == canonicalId) {
            _todo.value = lists[canonicalId] ?: EMPTY
        }
    }

    /**
     * [fix-todo-badge-never-shows] Register the draft→real mapping when
     * ensureSession persists a draft chat. Moves any list written under the
     * draft key onto the real id and keeps the alias so late writes still
     * carrying the (stale) constructor key land in the right slot.
     */
    @Synchronized
    fun renameSession(fromSessionId: String?, toSessionId: String) {
        if (fromSessionId == null || fromSessionId == toSessionId) return
        lists.remove(fromSessionId)?.let { list ->
            if (!lists.containsKey(toSessionId)) {
                lists[toSessionId] = list.copy(sessionId = toSessionId)
            }
        }
        draftAliases[fromSessionId] = toSessionId
        // No manual flow re-point needed: activeSessionKey resolves through
        // draftAliases, so the very next write() with either spelling
        // matches the foreground and publishes.
    }

    fun clearAll() {
        synchronized(this) {
            lists.clear()
            draftAliases.clear()
            activeSessionIdRaw = null
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
        // Compute activeness BEFORE purging aliases — the canonical
        // resolution below loses the draft→real link otherwise.
        val wasActive = activeSessionIdRaw == sessionId || activeSessionKey == sessionId
        lists.remove(sessionId)
        // [fix-todo-badge-never-shows] Purge draft aliases pointing at (or
        // keyed by) the deleted id, mirroring ChatViewModelStore.release().
        draftAliases.entries.removeAll {
            it.key == sessionId || it.value == sessionId
        }
        if (wasActive) {
            activeSessionIdRaw = null
            _todo.value = EMPTY
        }
    }

    /** Debug/diagnostic read of one session's list (not the UI path). */
    @Synchronized
    fun listFor(sessionId: String?): TodoList =
        sessionId?.let { lists[it] } ?: EMPTY
}
