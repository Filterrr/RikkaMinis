package com.openminis.app.tools

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-subagent-scheduler] Two-level concurrency scheduler for sub-agent runs.
 *
 * Replaces the former [max_parallel]-ignoring [SubagentDispatchLimiter]:
 *
 *     App hard cap (MAX_PARALLEL_CAP = 4)
 *      └── per-skill cap (SKILL.md frontmatter `max_parallel`, 1..4)
 *
 * A spawn acquires its SKILL-level permit first, then a GLOBAL permit —
 * this order matters: a serial skill (`max_parallel: 1`) queues its own
 * overflow spawns on the skill semaphore WITHOUT holding global permits,
 * so other skills keep their share of the chat-wide budget.
 *
 * Both levels are fair-FIFO coroutine [Semaphore]s. Spawns beyond a limit
 * QUEUE (block) until a permit frees — they never fail with "concurrency
 * limit reached". Queueing is the intended `max_parallel` semantics:
 * parallelism is a ceiling, not a rejection threshold.
 *
 * Why no stale-lease self-heal: the former limiter kept one manual lease
 * timestamp for the whole holder set, which a 31-min reset could corrupt
 * (releasing permits still legitimately held by younger holders). Here
 * every permit is scoped by [withPermit] — structured concurrency
 * guarantees release on success, exception, AND cancellation. Process
 * death kills the whole VM including this scheduler, so a lease can never
 * outlive its holder in a way that matters; an in-memory limiter has no
 * post-mortem state to heal.
 *
 * Per-skill note: a skill's semaphore is created once per chat with the
 * FIRST spawn's limit (clamped to [SubagentSkill.MAX_PARALLEL_CAP]).
 * Limits come from static SKILL.md frontmatter, so they are stable for
 * the lifetime of a chat in practice.
 */
class SubagentScheduler(
    private val globalLimit: Int = SubagentSkill.MAX_PARALLEL_CAP,
) {
    init {
        require(globalLimit >= 1) { "globalLimit must be >= 1" }
    }

    private val global = Semaphore(globalLimit)
    private val perSkill = ConcurrentHashMap<String, Semaphore>()

    /**
     * [feat/subagent-roster-routing] P2-4 — the limit each per-skill
     * semaphore was built with. kotlinx's Semaphore does not expose its
     * own capacity, so the scheduler keeps it alongside.
     */
    private val perSkillLimit = ConcurrentHashMap<String, Int>()

    /** Diagnostics: spawns currently executing inside [run]'s block. */
    private val activeGlobal = AtomicInteger(0)

    /**
     * Run [block] as one sub-agent execution, bounded by the skill-level
     * cap ([skillLimit], clamped to 1..[SubagentSkill.MAX_PARALLEL_CAP])
     * and then the chat-global cap. Fair-FIFO at both levels — extra
     * spawns wait their turn instead of failing.
     *
     * [deadlineNanos] (when > 0) makes the queue wait BOUNDED by the spawn's
     * own wall-clock budget: if the budget is already spent while still
     * queued, the spawn fails fast with [QueueTimeoutException] instead of
     * executing a run whose deadline expired before it started. The wait
     * itself remains FIFO; the deadline only refuses to START late work.
     */
    suspend fun <T> run(
        skillId: String,
        skillLimit: Int,
        deadlineNanos: Long = 0L,
        maxQueueWaitMs: Long = 0L,
        block: suspend () -> T,
    ): T {
        if (deadlineNanos <= 0L && maxQueueWaitMs <= 0L) {
            // Ungated (legacy) path: wait FIFO forever, as before.
            return skillSemaphore(skillId, skillLimit).withPermit {
                global.withPermit { counted(block) }
            }
        }
        // Bounded path. The wait bound is the MIN of the spawn's remaining
        // budget and an explicit queue cap:
        //  - deadlineNanos alone (detached): "do not start work whose budget
        //    already expired" — a background run may still wait for its turn.
        //  - maxQueueWaitMs (inline): the parent TURN is parked on this call,
        //    so its queue wait is user-visible freeze and must stay short no
        //    matter how large the run budget is.
        val remainingMs = if (deadlineNanos > 0L) {
            ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
        } else Long.MAX_VALUE
        // An EXPIRED deadline refuses the spawn even when a slot is free —
        // tryAcquire(0) would happily grab an available permit and run work
        // whose budget died before it started. (Self-caught while writing
        // SubagentSchedulerQueueTest; the "expired deadline" case is exactly
        // this path.)
        if (deadlineNanos > 0L && remainingMs == 0L) {
            throw QueueTimeoutException(
                "this spawn's timeout_seconds budget was already exhausted " +
                    "before a scheduler slot could start it",
            )
        }
        val waitMs = if (maxQueueWaitMs > 0L) minOf(remainingMs, maxQueueWaitMs) else remainingMs
        if (!tryAcquire(skillId, skillLimit, waitMs)) {
            throw QueueTimeoutException(
                if (maxQueueWaitMs > 0L) {
                    "no scheduler slot became free within ${maxQueueWaitMs}ms of queue wait"
                } else {
                    "no scheduler slot became available within this spawn's " +
                        "timeout_seconds budget (queue wait consumed it)"
                },
            )
        }
        try {
            return counted(block)
        } finally {
            release(skillId, skillLimit)
        }
    }

    private suspend fun <T> counted(block: suspend () -> T): T {
        activeGlobal.incrementAndGet()
        try {
            return block()
        } finally {
            activeGlobal.decrementAndGet()
        }
    }

    /**
     * Thrown by [run] / [tryAcquire] when the spawn's own timeout budget was
     * consumed by QUEUE WAITING — the run never started. The runner maps this
     * to a model-facing error that names the queue as the cause, distinct
     * from a mid-run TIMED_OUT (which carries partial findings).
     */
    class QueueTimeoutException(override val message: String? = null) : Exception(
        message ?: "no scheduler slot within the spawn's timeout_seconds budget",
    )

    /**
     * Bounded acquisition used by the runner's spawn paths: wait for BOTH
     * permits (skill first, then global — same order as [run]) but give up
     * after [waitBudgetMs] and roll back any partially-held permit.
     *
     * Why this exists: a fair FIFO semaphore queue is correct for detached
     * spawns and terrible for INLINE ones — the parent turn is parked on
     * scheduler.run() returning, and the queue head is whatever the previous
     * spawn's remaining budget is. Before this, `timeout_seconds` only started
     * counting AFTER the permit landed, so "600s" could mean "600s of work
     * plus unbounded waiting" — a frozen chat for tens of minutes with no
     * signal to the model or the user.
     *
     * Returns true when both permits are held (caller MUST [release] in a
     * finally); false when the budget ran out (nothing is held). A caller
     * passing waitBudgetMs <= 0 gets the immediate-try behaviour: no slot, no
     * wait.
     *
     * Rollback note: giving up on the GLOBAL permit releases the skill permit
     * again, so a spawn that timed out waiting for a global slot cannot
     * strand a skill-level slot either.
     *
     * [fix/subagent-verdict-consistency-v3] The budget is a TOTAL, not a
     * per-level allowance. The two acquisitions used to receive the same
     * [waitBudgetMs] each, so a spawn that waited the full budget for the
     * skill permit then waited the full budget again for the global one —
     * twice the deadline the caller was promised. The budget is now
     * measured against a single deadline that both levels share.
     */
    suspend fun tryAcquire(skillId: String, skillLimit: Int, waitBudgetMs: Long): Boolean {
        val skillSemaphore = skillSemaphore(skillId, skillLimit)
        if (waitBudgetMs <= 0) {
            // Immediate-try semantics: no waiting at either level.
            if (!skillSemaphore.tryAcquire()) return false
            if (!global.tryAcquire()) {
                skillSemaphore.release()
                return false
            }
            return true
        }
        val deadlineNanos = System.nanoTime() + waitBudgetMs * 1_000_000L
        if (!acquireWithin(skillSemaphore, deadlineNanos)) return false
        val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
        if (!acquireWithin(global, deadlineNanos, remainingMs)) {
            skillSemaphore.release()
            return false
        }
        return true
    }

    /** Release a pair acquired by [tryAcquire]. Mirrors its acquisition order. */
    fun release(skillId: String, skillLimit: Int) {
        global.release()
        skillSemaphore(skillId, skillLimit).release()
    }

    /**
     * Acquire one permit, waiting at most until [deadlineNanos].
     *
     * [remainingMs] is the slice of the shared budget still available; the
     * deadline is authoritative so two consecutive acquisitions cannot each
     * consume the full budget.
     */
    private suspend fun acquireWithin(
        semaphore: Semaphore,
        deadlineNanos: Long,
        remainingMs: Long = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L),
    ): Boolean =
        if (remainingMs <= 0) {
            semaphore.tryAcquire()
        } else {
            kotlinx.coroutines.withTimeoutOrNull(remainingMs) {
                semaphore.acquire()
                true
            } ?: false
        }

    /**
     * Get-or-create the per-skill semaphore (atomic — no duplicate permits).
     *
     * [feat/subagent-roster-routing] P2-4 — the limit is re-checked on every
     * acquire, not frozen at the first spawn.
     *
     * The bug: the semaphore was created ONCE per skill with whatever limit
     * the FIRST spawn of that skill carried, and every later spawn reused
     * it. So editing `max_parallel` in the SKILL.md — or simply having the
     * first spawn come from a different code path that passed a different
     * value — silently had no effect for the rest of the session. The
     * frontmatter was parsed, clamped, and then thrown away.
     *
     * The fix: when the requested limit differs from the one the existing
     * semaphore was built with, replace it. The replacement is safe
     * because the OLD semaphore's permits are only meaningful to the runs
     * currently holding them; a run in flight keeps its permit on the old
     * object, and the new semaphore starts from the new limit. To avoid
     * handing out MORE permits than the new limit while old holders are
     * still running, the new semaphore starts with the old permits already
     * taken — the conservative direction: the cap can tighten immediately
     * but loosens only as the old holders finish.
     */
    private fun skillSemaphore(skillId: String, skillLimit: Int): Semaphore {
        val limit = skillLimit.coerceIn(1, SubagentSkill.MAX_PARALLEL_CAP)
        val existing = perSkill[skillId]
        if (existing != null && perSkillLimit[skillId] == limit) return existing
        val replacement = Semaphore(limit)
        if (existing != null) {
            // Carry the in-flight holders over: take one permit for each
            // run still holding one on the old semaphore, so the new cap
            // is not exceeded by the overlap.
            val inFlight = perSkillLimit[skillId]?.minus(existing.availablePermits) ?: 0
            // tryAcquire, not acquire: this is not a suspend context, and
            // the permit is available by construction (the replacement was
            // created with `limit` permits and we take at most `limit`).
            repeat(inFlight.coerceIn(0, limit)) { replacement.tryAcquire() }
        }
        perSkill[skillId] = replacement
        perSkillLimit[skillId] = limit
        return replacement
    }

    // ── Diagnostics (tests / debug overlays) ─────────────────────────────

    /** Currently executing spawns across ALL skills in this chat. */
    fun activeGlobalCount(): Int = activeGlobal.get()

    /** Available global permits — 0 means the chat is at its hard cap. */
    fun availableGlobalPermits(): Int = global.availablePermits

    /** Available permits for a skill's semaphore (0 = that skill is serializing). */
    fun availableSkillPermits(skillId: String): Int =
        perSkill[skillId]?.availablePermits ?: -1
}
