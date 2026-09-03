package com.openminis.app.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicLong

/**
 * [T-subagent-ui] Shared state for sub-agent (spawn_agent) runs.
 *
 * Two channels with different consumers:
 *
 *  1. [runs] — the full lifecycle log of every sub-agent run this session
 *     spawned. The in-chat prompt pill ("N sub-agent(s) running — tap to
 *     view") and the SubagentDetailScreen second-level page both render
 *     from this. A run stays visible after completion (greyed success /
 *     failed) so the user can review what each sub-agent did; the registry
 *     only prunes to [MAX_RETAINED_RUNS] to bound memory.
 *
 *  2. [hasActiveRuns] — a lightweight boolean DERIVED from [runs] via a
 *     [kotlinx.coroutines.flow.Flow.map] (see [derivedHasActiveRuns]) —
 *     single source of truth, no duplicated state to drift.
 *
 * Registry is per-ChatViewModel instance (one chat session) — created in
 * ChatViewModel's init, cleared with the chat. Not a global singleton:
 * sub-agent runs belong to the conversation that spawned them, and
 * navigating between chats must not flash another chat's runs.
 *
 * Thread-safety: [T-subagent-atomic-registry] every mutation goes through
 * [MutableStateFlow.update] — its compare-and-set loop re-reads the LATEST
 * value on every attempt, so two concurrent updates can no longer collapse
 * to whichever swap lands last (the old read-modify-write lost updates:
 * a concurrent finish(SUCCESS) could be overwritten by a racing
 * stepOutput, leaving a finished run spinning as RUNNING in the UI).
 * Now each transform applies to the freshest list and retried until it
 * lands atomically; updates from tool-dispatch coroutines, shell line
 * callbacks, and parallel runs are all lossless.
 */
class SubagentRunRegistry {

    /** How a sub-agent run terminated. */
    enum class RunStatus { RUNNING, SUCCESS, FAILED, CANCELLED, QUEUED }

    /** One entry in the sub-agent's live execution log (a tool call step). */
    data class Step(
        val id: String,
        val turn: Int,
        val toolName: String,
        val toolTitle: String,
        val status: ToolStepStatus = ToolStepStatus.RUNNING,
        /** Live-updated output tail (bounded by the emitter). */
        val output: String = "",
        val startMs: Long = System.currentTimeMillis(),
        val durationMs: Long = 0L,
    )

    /** Tool-step render status for the detail page (mirrors pill semantics). */
    enum class ToolStepStatus { RUNNING, SUCCESS, FAILED }

    /**
     * A sub-agent run. Immutable snapshot — updates replace the whole object
     * via [updateRun] so Compose sees a new reference (cheap diffing).
     */
    data class Run(
        val id: String,
        /** Parent spawn_agent tool_use block id — links the pill to the run. */
        val blockId: String,
        /** Skill id the sub-agent runs (e.g. "deep-research"). */
        val skillId: String,
        /** Display name (skill.name). */
        val skillName: String,
        /** The task/query handed to the sub-agent. */
        val query: String,
        /** Short "what is this sub-agent doing" — spawn tool_title. */
        val title: String,
        /**
         * Session sandbox id the run executes in — journaling resolves the
         * per-session workspace host path through it. Empty for tests.
         */
        val sessionId: String = "",
        /**
         * [T-subagent-orchestration] Spawn batch group id when the run was
         * created as part of a spawn_agent batch (single spawns get one too
         * — a group of one). Empty for legacy/unknown runs.
         */
        val groupId: String = "",
        /**
         * [T-subagent-durability] Durable journal path surfaced once known
         * (terminal write or recovery pointer). The detail page shows it as
         * a recovery anchor.
         */
        val journalPath: String? = null,
        val status: RunStatus = RunStatus.RUNNING,
        val startedAtMs: Long = System.currentTimeMillis(),
        val endedAtMs: Long = 0L,
        /** Current turn (1-based) / configured max turns. */
        val turn: Int = 0,
        val maxTurns: Int = 0,
        /** Final or streaming text produced by the sub-agent. */
        val resultText: String = "",
        /** Error detail when [status] is FAILED/CANCELLED. */
        val error: String? = null,
        val steps: List<Step> = emptyList(),
        /** True when the user opened the detail page at least once. */
        val opened: Boolean = false,
    ) {
        val isActive: Boolean get() = status == RunStatus.RUNNING || status == RunStatus.QUEUED
        val isExecuting: Boolean get() = status == RunStatus.RUNNING
        val isQueued: Boolean get() = status == RunStatus.QUEUED
        val durationMs: Long
            get() = (if (endedAtMs > 0) endedAtMs else System.currentTimeMillis()) - startedAtMs
    }

    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    /**
     * [T-subagent-atomic-registry] Derived from [runs] — no duplicated
     * `_hasActiveRuns` state. Collectors outside a coroutine scope can use
     * the eager snapshot below instead.
     */
    fun derivedHasActiveRuns(scope: CoroutineScope) = runs.map { list ->
        list.any(Run::isActive)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Monotonic ids — unique within the VM lifetime, stable across updates. */
    private val idCounter = AtomicLong(0)
    fun nextId(prefix: String): String = "$prefix-${idCounter.incrementAndGet()}"

    /**
     * Synchronous snapshot of "any run active" for imperative callers
     * (UI click handlers, tests). Kept in lockstep with the flow updates
     * by the same single-writer mutation path.
     */
    val hasActiveRunsSnapshot: Boolean
        get() = _runs.value.any { it.isActive }

    // [T-subagent-serial] spawn_agent is classified SERIAL_BARRIER in the
    // dispatch loop (non-parallel-safe), so at most one run is RUNNING at a
    // time — but the boolean is derived, not asserted, so a policy change
    // keeps the UI correct.

    /**
     * [T-subagent-atomic-registry] Atomic compare-and-set mutation on the
     * runs list. The transform receives the FRESHEST list and is retried
     * until its swap lands — interleaved updates compose instead of
     * overwriting each other.
     */
    private inline fun mutateRuns(transform: (List<Run>) -> List<Run>) {
        _runs.update { current -> transform(current) }
    }

    fun register(
        blockId: String,
        skillId: String,
        skillName: String,
        query: String,
        title: String,
        maxTurns: Int,
        sessionId: String = "",
        groupId: String = "",
        queued: Boolean = false,
    ): Run {
        val run = Run(
            id = nextId("subagent"),
            blockId = blockId,
            skillId = skillId,
            skillName = skillName,
            query = query,
            title = title,
            sessionId = sessionId,
            groupId = groupId,
            // [T-subagent-orchestration] QUEUED: the spawn is registered
            // BEFORE the scheduler hands out a permit, so over-limit spawns
            // are visible as "queued · waiting for slot" instead of
            // appearing only when they start executing.
            status = if (queued) RunStatus.QUEUED else RunStatus.RUNNING,
            maxTurns = maxTurns,
        )
        // Newest first — the pill shows the freshest run; detail page lists
        // history the same way.
        mutateRuns { current ->
            listOf(run) + current.let { list ->
                if (list.size >= MAX_RETAINED_RUNS) list.take(MAX_RETAINED_RUNS - 1) else list
            }
        }
        return run
    }

    /**
     * [T-subagent-orchestration] The scheduler granted a permit — flip a
     * QUEUED run to RUNNING and stamp the actual execution start time (the
     * queue wait is still visible via the untouched [Run.startedAtMs].
     */
    fun markExecuting(runId: String) {
        updateRun(runId) { run ->
            if (run.status == RunStatus.QUEUED) {
                run.copy(status = RunStatus.RUNNING, startedAtMs = System.currentTimeMillis())
            } else {
                run
            }
        }
    }

    /** [T-subagent-orchestration] Attach the spawn-batch group id after creation. */
    fun setGroupId(runId: String, groupId: String) {
        if (groupId.isEmpty()) return
        updateRun(runId) { if (it.groupId == groupId) it else it.copy(groupId = groupId) }
    }

    /** [T-subagent-durability] Surface the journal path as soon as it is known. */
    fun setJournalPath(runId: String, path: String?) {
        if (path.isNullOrEmpty()) return
        updateRun(runId) { if (it.journalPath == path) it else it.copy(journalPath = path) }
    }

    /**
     * [T-subagent-orchestration] Group progress snapshot for UI + prompts:
     * (total, finished, active) over the CURRENT registry contents for the
     * group's run ids. Members pruned by [MAX_RETAINED_RUNS] are dropped
     * from the count.
     */
    fun groupProgress(groupId: String): Triple<Int, Int, Int>? {
        if (groupId.isEmpty()) return null
        val members = _runs.value.filter { it.groupId == groupId }
        if (members.isEmpty()) return null
        val finished = members.count { !it.isActive }
        val active = members.count { it.isActive }
        return Triple(members.size, finished, active)
    }

    fun updateRun(runId: String, transform: (Run) -> Run) {
        mutateRuns { current ->
            val idx = current.indexOfFirst { it.id == runId }
            if (idx < 0) return@mutateRuns current
            current.toMutableList().apply { this[idx] = transform(current[idx]) }
        }
    }

    /** Turn started — bump the turn counter and clear stale step state. */
    fun turnStarted(runId: String, turn: Int) {
        updateRun(runId) { it.copy(turn = turn) }
    }

    /** Tool call started inside the sub-agent loop. */
    fun stepStarted(runId: String, stepId: String, turn: Int, toolName: String, toolTitle: String) {
        updateRun(runId) { run ->
            val steps = run.steps.toMutableList()
            val idx = steps.indexOfFirst { it.id == stepId }
            val step = Step(
                id = stepId, turn = turn, toolName = toolName, toolTitle = toolTitle,
            )
            if (idx >= 0) steps[idx] = step else steps.add(step)
            run.copy(steps = steps)
        }
    }

    /**
     * Live output tail for a step. Called from shell line callbacks —
     * [stepOutput] keeps only the last [MAX_STEP_OUTPUT_LINES] lines.
     */
    fun stepOutput(runId: String, stepId: String, outputTail: String) {
        updateRun(runId) { run ->
            val steps = run.steps.toMutableList()
            val idx = steps.indexOfFirst { it.id == stepId }
            if (idx < 0) return@updateRun run
            steps[idx] = steps[idx].copy(output = outputTail)
            run.copy(steps = steps)
        }
    }

    fun stepFinished(runId: String, stepId: String, success: Boolean, output: String = "") {
        updateRun(runId) { run ->
            val steps = run.steps.toMutableList()
            val idx = steps.indexOfFirst { it.id == stepId }
            if (idx < 0) return@updateRun run
            val start = steps[idx]
            steps[idx] = start.copy(
                status = if (success) ToolStepStatus.SUCCESS else ToolStepStatus.FAILED,
                output = output.ifBlank { start.output },
                durationMs = System.currentTimeMillis() - start.startMs,
            )
            run.copy(steps = steps)
        }
    }

    /** Streaming text delta from the sub-agent's current model turn. */
    fun appendResultText(runId: String, delta: String) {
        if (delta.isEmpty()) return
        updateRun(runId) { run ->
            run.copy(resultText = (run.resultText + delta).takeLast(MAX_RESULT_TEXT_CHARS))
        }
    }

    fun markOpened(runId: String) {
        updateRun(runId) { if (it.opened) it else it.copy(opened = true) }
    }

    /**
     * [T-subagent-orchestration] Terminal flip that only applies to runs
     * still active (RUNNING/QUEUED). cancel_subagents races the runner's
     * own finally-path: if the runner already finished the run, this is a
     * no-op instead of overwriting SUCCESS with CANCELLED.
     */
    fun finishIfActive(runId: String, status: RunStatus, resultText: String = "", error: String? = null) {
        updateRun(runId) { run ->
            if (!run.isActive) run
            else run.copy(
                status = status,
                endedAtMs = System.currentTimeMillis(),
                resultText = resultText.ifBlank { run.resultText },
                error = error,
            )
        }
    }

    fun finish(runId: String, status: RunStatus, resultText: String = "", error: String? = null) {
        updateRun(runId) { run ->
            run.copy(
                status = status,
                endedAtMs = System.currentTimeMillis(),
                resultText = resultText.ifBlank { run.resultText },
                error = error,
            )
        }
    }

    /** Wipe all state (clearChat / session switch). */
    fun clear() {
        mutateRuns { emptyList() }
    }

    companion object {
        /** Bounded history so a long session can't grow the list forever. */
        const val MAX_RETAINED_RUNS = 20
        const val MAX_STEP_OUTPUT_LINES = 60
        const val MAX_RESULT_TEXT_CHARS = 24_000
    }
}

/**
 * [T-subagent-parallel] Two-level scheduler front-end + legacy helpers.
 *
 * The production concurrency scheduler is [SubagentScheduler] (coroutine
 * Semaphore based, skill-frontmatter aware, queueing). The former manual
 * [SubagentDispatchLimiter] is retired: its shared single-lease self-heal
 * could release permits still legitimately held by younger holders after
 * the 31-min stale reset, and its acquireOrFalse() production path turned
 * max_parallel into "over limit → spawn fails" instead of queueing.
 */
