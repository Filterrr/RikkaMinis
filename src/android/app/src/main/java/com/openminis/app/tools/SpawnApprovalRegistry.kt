package com.openminis.app.tools

import java.util.concurrent.ConcurrentHashMap

/**
 * [T-notif-inline-decision] Process-wide index of the live per-chat
 * [SpawnApprovalQueue]s.
 *
 * ## Why a registry is needed
 *
 * The in-app dialog reaches its queue directly — the ChatViewModel owns both.
 * The notification's Allow/Deny buttons cannot: they arrive as a broadcast
 * carrying an ask id and nothing else, and the queues are per-chat ViewModel
 * state. Without an index, answering from the shade would have to either
 * guess a chat or foreground the app (defeating the point).
 *
 * So each VM registers its queue here on creation and drops it on `onCleared`,
 * which makes "who owns this ask id?" answerable from outside any chat. Ask
 * ids are process-unique (see `SpawnApprovalQueue.nextAskId`), so at most one
 * live queue can recognise an id — the lookup is unambiguous rather than
 * best-effort.
 *
 * Entries are keyed by session id so a chat that re-registers (config change,
 * navigation) replaces its own slot instead of leaking a stale queue.
 */
object SpawnApprovalRegistry {

    private val queuesBySession = ConcurrentHashMap<String, SpawnApprovalQueue>()

    /** Publish [queue] as the live queue for [sessionId]. */
    fun register(sessionId: String, queue: SpawnApprovalQueue) {
        if (sessionId.isBlank()) return
        queuesBySession[sessionId] = queue
    }

    /**
     * Drop [sessionId]'s queue — but only when it is still the registered one,
     * so a ViewModel being cleared cannot evict the queue a newer VM for the
     * same session just installed.
     */
    fun unregister(sessionId: String, queue: SpawnApprovalQueue) {
        if (sessionId.isBlank()) return
        queuesBySession.remove(sessionId, queue)
    }

    /** Live queues, newest registration first is NOT guaranteed — order is irrelevant. */
    fun liveQueues(): Collection<SpawnApprovalQueue> = queuesBySession.values

    /** The session that owns [askId], or null when no live queue has it. */
    fun sessionForAsk(askId: String): String? =
        queuesBySession.entries.firstOrNull { it.value.isOutstanding(askId) }?.key

    /**
     * Deliver [decision] to whichever live queue owns [askId]. Returns the
     * session id that accepted it, or null when nothing recognised the id
     * (ask timed out, chat torn down, or process restarted).
     */
    fun resolveById(askId: String, decision: SpawnDecision): String? {
        val session = sessionForAsk(askId) ?: return null
        val queue = queuesBySession[session] ?: return null
        return if (queue.resolve(askId, decision)) session else null
    }

    /** Test seam: drop every registration. */
    fun clear() {
        queuesBySession.clear()
    }
}
