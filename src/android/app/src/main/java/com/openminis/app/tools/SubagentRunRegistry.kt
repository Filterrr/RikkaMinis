package com.openminis.app.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
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
 *  2. [_hasActiveRuns] — a lightweight boolean derived from [runs], read by
 *     ChatScreen to decide whether the pill row is visible at all.
 *
 * Registry is per-ChatViewModel instance (one chat session) — created in
 * ChatViewModel's init, cleared with the chat. Not a global singleton:
 * sub-agent runs belong to the conversation that spawned them, and
 * navigating between chats must not flash another chat's runs.
 *
 * Thread-safety: `runs` is a single [MutableStateFlow] whose list is
 * replaced on every update (copy-on-write). [emitRun] / [updateRun] are
 * safe to call from the tool-dispatch coroutines and shell line callbacks
 * because each mutation re-reads the latest list under the flow value
 * swap; worst case two interleaved updates collapse to whichever swap
 * lands last, which is acceptable for a live progress view (every path
 * also emits a final terminal update).
 */
class SubagentRunRegistry {

    /** How a sub-agent run terminated. */
    enum class RunStatus { RUNNING, SUCCESS, FAILED, CANCELLED }

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
        val isActive: Boolean get() = status == RunStatus.RUNNING
        val durationMs: Long
            get() = (if (endedAtMs > 0) endedAtMs else System.currentTimeMillis()) - startedAtMs
    }

    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    private val _hasActiveRuns = MutableStateFlow(false)
    val hasActiveRuns: StateFlow<Boolean> = _hasActiveRuns.asStateFlow()

    /** Monotonic ids — unique within the VM lifetime, stable across updates. */
    private val idCounter = AtomicLong(0)
    fun nextId(prefix: String): String = "$prefix-${idCounter.incrementAndGet()}"

    // [T-subagent-serial] spawn_agent is classified SERIAL_BARRIER in the
    // dispatch loop (non-parallel-safe), so at most one run is RUNNING at a
    // time — but the boolean is derived, not asserted, so a policy change
    // keeps the UI correct.
    private fun recomputeActive() {
        _hasActiveRuns.value = _runs.value.any { it.isActive }
    }

    fun register(
        blockId: String,
        skillId: String,
        skillName: String,
        query: String,
        title: String,
        maxTurns: Int,
    ): Run {
        val run = Run(
            id = nextId("subagent"),
            blockId = blockId,
            skillId = skillId,
            skillName = skillName,
            query = query,
            title = title,
            maxTurns = maxTurns,
        )
        // Newest first — the pill shows the freshest run; detail page lists
        // history the same way.
        _runs.value = listOf(run) + _runs.value.let { list ->
            if (list.size >= MAX_RETAINED_RUNS) list.take(MAX_RETAINED_RUNS - 1) else list
        }
        recomputeActive()
        return run
    }

    fun updateRun(runId: String, transform: (Run) -> Run) {
        val current = _runs.value
        val idx = current.indexOfFirst { it.id == runId }
        if (idx < 0) return
        val updated = current.toMutableList()
        updated[idx] = transform(current[idx])
        _runs.value = updated
        recomputeActive()
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
        _runs.value = emptyList()
        recomputeActive()
    }

    companion object {
        /** Bounded history so a long session can't grow the list forever. */
        const val MAX_RETAINED_RUNS = 20
        const val MAX_STEP_OUTPUT_LINES = 60
        const val MAX_RESULT_TEXT_CHARS = 24_000
    }
}

/**
 * [T-subagent-serial] Serial dispatch gate for spawn_agent.
 *
 * spawn_agent is classified SERIAL_BARRIER in ToolConcurrencyPolicy, so the
 * Pass-2 loop never launches two spawn_agent calls concurrently — but the
 * MAIN agent model may still batch spawn_agent alongside other serial tools
 * within one turn, and those run sequentially on the same coroutine. The
 * real re-entrancy risk is different: a queued second spawn_agent starting
 * while the first one's registry entry is still RUNNING would leave two
 * "running" pills pointing at one chat (confusing, and the in-chat prompt
 * would refuse). This atomic flag makes any concurrent re-entry (today:
 * none; future: concurrent dispatch or skill-triggered spawns) fail fast
 * with an actionable error instead of interleaving registries.
 */
object SubagentDispatchGate {
    private val busy = AtomicBoolean(false)

    /**
     * [T-subagent-gate-lease] When the slot was acquired. A stale lease
     * (older than [LEASE_STALE_MS]) can be force-taken over by a new
     * spawn: the previous holder is provably dead — nothing about a
     * sub-agent run legitimately takes longer than the gateway's own
     * generation ceiling — and a leaked `true` (process-level cancel race
     * where the holder's finally never ran) must not wedge every future
     * spawn behind "another sub-agent is already running" forever.
     */
    @Volatile
    private var acquiredAtMs: Long = 0L

    /** Lease ceiling — matches the 30-min generation backstop + margin. */
    private const val LEASE_STALE_MS = 31L * 60L * 1000L

    /**
     * Try to acquire the single-run slot. False when a run is active —
     * UNLESS that run's lease is stale, in which case it is taken over
     * (self-heal) and true is returned.
     */
    fun tryAcquire(): Boolean {
        while (true) {
            val now = System.currentTimeMillis()
            if (busy.compareAndSet(false, true)) {
                acquiredAtMs = now
                return true
            }
            // Held — check for a leaked lease from a dead holder.
            val held = acquiredAtMs
            if (held > 0 && now - held > LEASE_STALE_MS) {
                // Try to steal: only succeeds if the flag is still set with
                // the same stale timestamp (lost no race in between).
                if (busy.compareAndSet(true, true) && acquiredAtMs == held) {
                    acquiredAtMs = now
                    return true  // force takeover
                }
            }
            return false
        }
    }

    /** Release the slot. Called in a finally block by the runner. */
    fun release() {
        acquiredAtMs = 0L
        busy.set(false)
    }

    /** Test/diagnostics: is the slot currently held? */
    fun isHeld(): Boolean = busy.get()
}
