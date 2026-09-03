package com.openminis.app.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * [T-subagent-orchestration] Parent-side orchestration layer for sub-agent
 * runs — groups, join, wait-any, cancel, and the detach lifecycle.
 *
 * Design (maps 1:1 to the ChatGPT review's recommendations):
 *
 *  - [SubagentGroup] — one spawn batch ("spawn_agent × N" from a single
 *    model turn) becomes ONE group, so the UI can show "Group progress:
 *    2/4" and offer group cancel instead of a pile of orphan pills.
 *  - [SubagentJobRegistry] — every spawn gets a [SubagentJob] carrying a
 *    [CompletableDeferred] terminal outcome. Detached spawns (run_until:
 *    "detach") register a job whose [SubagentJob.deferred] completes when
 *    the background runner finishes; join/wait_any await these deferreds.
 *    Deferred completion is decoupled from run-status updates: for
 *    inline (non-detached) spawns the runner completes the deferred in
 *    its finally block; for detached spawns the background wrapper does.
 *  - [SubagentOrchestration] — pure decision logic over the registries:
 *    fan-out plan for a spawn batch, join with per-run timeout, wait-any
 *    racing, and cascade-cancel closure. All thread-safe (ConcurrentHashMap
 *    + per-entry synchronization) because parent dispatch, background
 *    runners, and the UI layer all touch it.
 *
 * Both registries are per-ChatViewModel (per chat) like SubagentRunRegistry.
 */
object SubagentOrchestration {

    /** Default join timeout: 30 min — longer than any sane sub-agent budget. */
    const val DEFAULT_JOIN_TIMEOUT_MS = 30L * 60 * 1000

    /** Default wait_any timeout: 10 min. */
    const val DEFAULT_WAIT_ANY_TIMEOUT_MS = 10L * 60 * 1000

    // ── Group model ──────────────────────────────────────────────────────

    /**
     * One spawn batch. Created when the parent loop dispatches a
     * SPAWN_PARALLEL batch (or a single spawn — a group of one), and
     * joined by `join_subagents` / `wait_any` by id.
     */
    data class SubagentGroup(
        val id: String,
        /** The parent agent-loop turn's dispatch context (for debugging). */
        val parentBlockId: String,
        /** Member run ids in spawn order (stable for result zipping). */
        val runIds: List<String>,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    // ── Job model ────────────────────────────────────────────────────────

    /** Terminal outcome carried by a job's deferred — mirrors SubagentResult. */
    data class JobOutcome(
        val runId: String,
        val success: Boolean,
        /** Final report text (partial on failure/cancel). */
        val report: String,
        /** Skill display name, for rendering. */
        val skillName: String,
        /** Durable journal path when the report was persisted. */
        val journalPath: String? = null,
        val error: String? = null,
        /** True when the run terminated because of an explicit cancel. */
        val cancelled: Boolean = false,
    )

    /**
     * One spawn's lifecycle handle. [deferred] completes exactly once when
     * the run reaches a terminal state — join/wait_any await it. Runs that
     * die with the parent scope (process death, VM clear) never complete
     * their deferred; join detects that via the awaiting job's own
     * cancellation and surfaces a failed outcome.
     *
     * [coroutineJob] is set by the runner for DETACHED spawns so
     * cancel_subagents can tear the background coroutine down (parent →
     * child cascade); it stays null for inline spawns, whose lifecycle is
     * the parent loop's own dispatch.
     */
    class SubagentJob(
        val runId: String,
        val skillId: String,
        val skillName: String,
        val detached: Boolean,
        val deferred: CompletableDeferred<JobOutcome> = CompletableDeferred(),
        @Volatile var coroutineJob: kotlinx.coroutines.Job? = null,
    )

    // ── Registries ───────────────────────────────────────────────────────

    /**
     * Group + job registries. NOT an object singleton — one instance per
     * chat VM, cleared with it, so ids stay unique per chat and history
     * never leaks across conversations.
     */
    class Registries {
        private val groups = ConcurrentHashMap<String, SubagentGroup>()
        private val groupOrder = mutableListOf<String>()
        private val jobs = ConcurrentHashMap<String, SubagentJob>()
        private val jobOrder = mutableListOf<String>()
        private val lock = Any()

        private val groupCounter = AtomicLong(0)

        fun nextGroupId(): String = "sgroup-${groupCounter.incrementAndGet()}"

        /**
         * Attach [runId] to group [groupId], creating the record lazily on
         * first attach (the parent loop mints the group id per spawn batch
         * BEFORE any runner has registered — membership fills in as each
         * spawn's runner comes up). Idempotent, thread-safe.
         */
        fun attachRunToGroup(groupId: String, runId: String) {
            if (groupId.isEmpty() || runId.isEmpty()) return
            synchronized(lock) {
                val existing = groups[groupId]
                if (existing == null) {
                    groups[groupId] = SubagentGroup(
                        id = groupId,
                        parentBlockId = "",
                        runIds = listOf(runId),
                    )
                    groupOrder.add(groupId)
                    if (groupOrder.size > MAX_RETAINED_GROUPS) {
                        val evicted = groupOrder.removeAt(0)
                        groups.remove(evicted)
                    }
                } else if (runId !in existing.runIds) {
                    groups[groupId] = existing.copy(runIds = existing.runIds + runId)
                }
            }
        }

        fun getGroup(groupId: String): SubagentGroup? = groups[groupId]

        /** Newest-first group snapshot (for UI + "latest group" resolution). */
        fun groupsSnapshot(): List<SubagentGroup> = synchronized(lock) {
            groupOrder.reversed().mapNotNull { groups[it] }
        }

        /**
         * Register a job. Bounded history: when over budget, evict the
         * OLDEST already-completed job first; only if none has completed
         * (pathological — many live detached spawns) fall back to dropping
         * the oldest entry outright. Never evicts the job being added.
         */
        fun putJob(job: SubagentJob) {
            if (jobs.putIfAbsent(job.runId, job) != null) return
            synchronized(lock) {
                jobOrder.add(job.runId)
                if (jobOrder.size > MAX_RETAINED_JOBS) {
                    val evictable = jobOrder.firstOrNull { it != job.runId && jobs[it]?.deferred?.isCompleted == true }
                        ?: jobOrder.firstOrNull { it != job.runId }
                    if (evictable != null) {
                        jobOrder.remove(evictable)
                        jobs.remove(evictable)
                    }
                }
            }
        }

        fun getJob(runId: String): SubagentJob? = jobs[runId]

        /** Jobs for [runIds] that exist; preserves input order. */
        fun getJobs(runIds: List<String>): List<SubagentJob> =
            runIds.mapNotNull { jobs[it] }

        /** All live (unfinished) jobs — cancel cascade + shutdown sweep. */
        fun liveJobs(): List<SubagentJob> = jobs.values.filter { !it.deferred.isCompleted }

        /** Jobs that were spawned detached (background lifecycle). */
        fun liveDetachedJobs(): List<SubagentJob> =
            jobs.values.filter { it.detached && !it.deferred.isCompleted }

        fun clear() {
            groups.clear()
            jobs.clear()
            synchronized(lock) {
                groupOrder.clear()
                jobOrder.clear()
            }
        }

        // [T-subagent-orchestration] History bounds — plain vals (const is
        // not allowed inside a nested class of an object without companion).
        val MAX_RETAINED_GROUPS: Int = 20
        val MAX_RETAINED_JOBS: Int = 60
    }

    // ── Resolution (pure-ish, over the registries) ───────────────────────

    /**
     * Resolve a join target from tool args: explicit run_ids → those;
     * group_id → the group's members; neither → the newest group. Returns
     * null with a reason string when nothing joinable exists.
     */
    fun resolveJoinTargets(
        registries: Registries,
        runIds: List<String>,
        groupId: String?,
    ): Pair<SubagentGroup, List<SubagentJob>>? {
        if (runIds.isNotEmpty()) {
            val jobs = registries.getJobs(runIds)
            if (jobs.isEmpty()) return null
            val group = SubagentGroup(
                id = "ad-hoc",
                parentBlockId = "join-by-run-ids",
                runIds = jobs.map { it.runId },
            )
            return group to jobs
        }
        val group = if (!groupId.isNullOrBlank()) {
            registries.getGroup(groupId) ?: return null
        } else {
            registries.groupsSnapshot().firstOrNull() ?: return null
        }
        val jobs = registries.getJobs(group.runIds)
        if (jobs.isEmpty()) return null
        return group to jobs
    }

    /**
     * Classify a join target list up front so the tool can return a
     * helpful prompt even before awaiting: nothing to wait for if every
     * job already completed.
     */
    fun joinSummary(group: SubagentGroup, jobs: List<SubagentJob>, outcomes: List<JobOutcome>): String {
        val ok = outcomes.count { it.success && !it.cancelled }
        val failed = outcomes.count { !it.success && !it.cancelled }
        val cancelled = outcomes.count { it.cancelled }
        return "Group ${group.id} — ${ok} succeeded, ${failed} failed, ${cancelled} cancelled " +
            "(${jobs.size} total)"
    }

    // ── Awaiting ─────────────────────────────────────────────────────────

    /**
     * Join: await every job in [jobs] (bounded by [timeoutMs] TOTAL, not
     * per job). Already-completed jobs resolve instantly. On timeout the
     * unfinished jobs surface as failed outcomes with a timeout error —
     * the caller keeps its turn and can re-join later.
     *
     * Cancellation-safe: if the CALLING scope is cancelled (user stop),
     * awaitAll propagates CancellationException; the deferreds themselves
     * belong to the runner scopes and stay pending — a later join can
     * still pick up results if the background runs survive.
     */
    suspend fun joinAll(
        jobs: List<SubagentJob>,
        timeoutMs: Long,
    ): List<JobOutcome> {
        if (jobs.isEmpty()) return emptyList()
        val deadline = System.currentTimeMillis() + timeoutMs
        val outcomes = arrayOfNulls<JobOutcome>(jobs.size)
        kotlinx.coroutines.coroutineScope {
            jobs.forEachIndexed { idx, job ->
                launch {
                    outcomes[idx] = try {
                        withTimeoutRemaining(deadline) { job.deferred.await() }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        timeoutOutcome(job, timeoutMs)
                    } catch (e: TimeoutException) {
                        timeoutOutcome(job, timeoutMs)
                    }
                }
            }
        }
        return outcomes.map { it ?: timeoutOutcome(job = jobs[0], timeoutMs = timeoutMs) }
    }

    /**
     * Wait-any: race [jobs], resolve with the FIRST job that completes,
     * cancel the race scope (the other runs keep going in their own
     * scopes — we only cancel the awaiting). Optional [successOnly]: keep
     * waiting for the first SUCCESSFUL completion, skipping failures
     * (bounded by [timeoutMs] total). Returns null on timeout.
     */
    suspend fun waitAny(
        jobs: List<SubagentJob>,
        timeoutMs: Long,
        successOnly: Boolean,
    ): JobOutcome? {
        if (jobs.isEmpty()) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val completed = kotlinx.coroutines.withTimeoutOrNull(remaining) {
                kotlinx.coroutines.selects.select<SubagentOrchestration.JobOutcome> {
                    jobs.forEach { job ->
                        job.deferred.onAwait { outcome -> outcome }
                    }
                }
            } ?: return null  // timeout — nobody completed in the window
            // [T-subagent-orchestration] successOnly: the caller wants the
            // first SUCCESSFUL completion — a failed or cancelled outcome is
            // dropped from the race (even with a partial report; join is the
            // tool for collecting partials).
            if (successOnly && (completed.cancelled || !completed.success)) {
                val remainingJobs = jobs.filter { it.runId != completed.runId }
                if (remainingJobs.isEmpty()) return null
                return waitAny(remainingJobs, deadline - System.currentTimeMillis(), successOnly)
            }
            return completed
        }
    }

    private fun timeoutOutcome(job: SubagentJob, timeoutMs: Long): JobOutcome = JobOutcome(
        runId = job.runId,
        success = false,
        report = "",
        skillName = job.skillName,
        error = "join timed out after ${timeoutMs}ms (run still in background)",
    )

    private suspend fun <T> withTimeoutRemaining(
        deadline: Long,
        block: suspend () -> T,
    ): T {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) throw TimeoutException("join deadline passed")
        return kotlinx.coroutines.withTimeout(remaining) { block() }
    }

    /**
     * Cancel cascade: complete the job deferreds of [jobs] with cancelled
     * outcomes (so a blocked join wakes up) and return the run ids the
     * caller must signal in the run registry. Actual coroutine teardown is
     * done by the runner's cooperative cancellation points — the job
     * deferred completing is the signal layer, not the executor.
     */
    fun cancelCascade(jobs: List<SubagentJob>, reason: String): List<String> {
        val cancelled = mutableListOf<String>()
        for (job in jobs) {
            if (job.deferred.complete(
                    JobOutcome(
                        runId = job.runId,
                        success = false,
                        report = "",
                        skillName = job.skillName,
                        error = reason,
                        cancelled = true,
                    ),
                )
            ) {
                cancelled.add(job.runId)
            }
        }
        return cancelled
    }
}
