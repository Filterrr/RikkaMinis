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

    /** Diagnostics: spawns currently executing inside [run]'s block. */
    private val activeGlobal = AtomicInteger(0)

    /**
     * Run [block] as one sub-agent execution, bounded by the skill-level
     * cap ([skillLimit], clamped to 1..[SubagentSkill.MAX_PARALLEL_CAP])
     * and then the chat-global cap. Fair-FIFO at both levels — extra
     * spawns wait their turn instead of failing.
     */
    suspend fun <T> run(skillId: String, skillLimit: Int, block: suspend () -> T): T =
        skillSemaphore(skillId, skillLimit).withPermit {
            global.withPermit {
                activeGlobal.incrementAndGet()
                try {
                    block()
                } finally {
                    activeGlobal.decrementAndGet()
                }
            }
        }

    /** Get-or-create the per-skill semaphore (atomic — no duplicate permits). */
    private fun skillSemaphore(skillId: String, skillLimit: Int): Semaphore =
        perSkill.computeIfAbsent(skillId) {
            Semaphore(skillLimit.coerceIn(1, SubagentSkill.MAX_PARALLEL_CAP))
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
