package com.openminis.app.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [T-subagent-approval] User gate for sub-agent dispatch.
 *
 * Spawning a sub-agent hands an autonomous tool loop (shell, files, browser,
 * up to 48 unattended turns) to a MODEL-CHOSEN task — the human sees none of
 * those turns. This runtime had no approval concept at all, so "trust" was
 * binary at the tool-toggle level. The gate asks once per spawn (per-skill
 * "always allow" collapses repeat asks); a refusal aborts the spawn BEFORE
 * any run row, pill, journal, or budget reservation exists — a denied spawn
 * leaves nothing to clean up.
 */
enum class SpawnDecision { ALLOW_ONCE, ALWAYS_ALLOW, DENY }

/** One pending ask. Everything the dialog needs — no Android types, pure data. */
data class SpawnApprovalRequest(
    val id: String,
    val skillId: String,
    val skillName: String,
    val taskPreview: String,
    /** Model the spawn resolved to (routing is visible BEFORE approval). */
    val modelLabel: String,
    /** True when the run will execute in the background (detach). */
    val detached: Boolean,
)

/**
 * [T-subagent-approval] Pure pending-approval state machine.
 *
 * The ChatViewModel hosts one instance; the UI renders [requests], the
 * runner parks on the deferred [submit] hands back. Free of Android so the
 * resolve / expire / teardown races are unit-testable:
 *
 *  - resolve is exactly-once (the second resolver delivers nothing) — a
 *    dialog tap, a per-skill shortcut, and VM teardown can all race the same
 *    request;
 *  - [remove] is the waiter-timeout exit: the runner stops caring, the ask
 *    disappears from the UI. The deferred is deliberately NOT completed —
 *    a late tap on a half-rendered dialog then resolves nothing, and callers
 *    that must not lose a decision re-check [isOutstanding] after remove;
 *  - [denyAllOutstanding] is the teardown path (chat cleared / VM gone):
 *    every parked spawn resolves DENY, so no parent turn freezes forever
 *    behind a dialog nobody can see.
 *
 * No internal timer: expiry is the waiter's `withTimeoutOrNull`, which keeps
 * "who decides the answer" single-sourced (user tap / teardown), and a timed
 * out ask cancels itself by removing its row.
 */
class SpawnApprovalQueue {

    private class Entry(val request: SpawnApprovalRequest) {
        val deferred = CompletableDeferred<SpawnDecision>()
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val counter = AtomicLong(0)

    private val _requests = MutableStateFlow<List<SpawnApprovalRequest>>(emptyList())
    /** Pending asks in arrival order — what the dialog renders. */
    val requests: StateFlow<List<SpawnApprovalRequest>> = _requests.asStateFlow()

    /** A registered ask: the row to render + the deferred to wait on. */
    data class SubmittedAsk(
        val request: SpawnApprovalRequest,
        val deferred: CompletableDeferred<SpawnDecision>,
    )

    /** Mint an id, register the ask, hand back the pair to wait on. */
    fun submit(
        skillId: String,
        skillName: String,
        task: String,
        modelLabel: String,
        detached: Boolean,
    ): SubmittedAsk {
        val request = SpawnApprovalRequest(
            id = "spawn-ask-${counter.incrementAndGet()}",
            skillId = skillId,
            skillName = skillName,
            taskPreview = task.trim().take(TASK_PREVIEW_CHARS),
            modelLabel = modelLabel,
            detached = detached,
        )
        val entry = Entry(request)
        entries[request.id] = entry
        _requests.update { it + request }
        return SubmittedAsk(request, entry.deferred)
    }

    /** True while [id] still waits for a decision. */
    fun isOutstanding(id: String): Boolean = entries.containsKey(id)

    /**
     * User (or policy) answered [id]. True when THIS call delivered the
     * decision — false means it was already resolved or removed.
     */
    fun resolve(id: String, decision: SpawnDecision): Boolean {
        val entry = entries[id] ?: return false
        if (!entry.deferred.complete(decision)) return false
        retire(id)
        return true
    }

    /** Waiter gave up (its timeout fired) — drop the ask from the UI. */
    fun remove(id: String) {
        val entry = entries[id] ?: return
        // A resolved entry retired itself already; this branch covers the
        // resolve-had-no-entry-yet edge and keeps retire idempotent.
        if (entry.deferred.isCompleted) {
            retire(id)
            return
        }
        entries.remove(id)
        _requests.update { list -> list.filterNot { it.id == id } }
    }

    /** Teardown: deny every outstanding ask. Returns how many were woken. */
    fun denyAllOutstanding(): Int =
        entries.keys.toList().count { resolve(it, SpawnDecision.DENY) }

    /** "Allow all these"/"deny all" shortcut over every outstanding ask for a skill. */
    fun resolveForSkill(skillId: String, decision: SpawnDecision): Int =
        entries.values.filter { it.request.skillId == skillId }
            .count { resolve(it.request.id, decision) }

    private fun retire(id: String) {
        entries.remove(id)
        _requests.update { list -> list.filterNot { it.id == id } }
    }

    companion object {
        /** Enough to recognise the task without turning the dialog into a novel. */
        const val TASK_PREVIEW_CHARS = 600
    }
}
