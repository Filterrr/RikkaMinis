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

    /**
     * How a sub-agent run terminated. [TIMED_OUT] is a distinct terminal
     * status (not FAILED) because the recovery advice differs: a timed-out
     * run's partial report is usually still worth reading, and the parent may
     * succeed by re-spawning the SAME task with a larger budget.
     */
    enum class RunStatus { RUNNING, SUCCESS, FAILED, CANCELLED, QUEUED, TIMED_OUT }

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
     * [T-subagent-chat-stream] One entry of the run's INTERLEAVED transcript:
     * the sub-agent's own narration, its reasoning, and its tool calls, in the
     * order they actually happened. The detail page renders these as a chat
     * stream (text → tool pill → text → tool pill), the same reading order the
     * parent chat uses.
     *
     * Why a separate list instead of deriving it from [Run.steps] + text:
     * text deltas land in [Run.resultText] as ONE concatenated blob and steps
     * carry only tool calls, so the alternation — the thing that makes the log
     * readable — was unrecoverable. [turn] carries the model turn the item
     * belongs to, so a renderer can group items per turn without re-parsing
     * text positions.
     *
     * [Text]/[Thinking] are append-only accumulators (each delta extends the
     * last item of the same kind inside the same turn); [ToolCall] is created
     * on ToolCallComplete and shares its id with the matching [Step], so the
     * pill can render live status/output from [Run.steps].
     */
    sealed interface Segment {
        /** Model turn (1-based) this segment belongs to. */
        val turn: Int

        /** Narration / answer text streamed by the sub-agent. */
        data class Text(
            override val turn: Int,
            val content: String,
        ) : Segment

        /**
         * [T-subagent-thinking] Reasoning stream (`ThinkingDelta` /
         * `ReasoningContent`). Distinct from [Text] so the page can render it
         * as a collapsible "Deep thinking" row instead of mixing chain-of-
         * thought into the answer.
         */
        data class Thinking(
            override val turn: Int,
            val content: String,
        ) : Segment

        /** A tool call; [id] matches the [Step] with the same id. */
        data class ToolCall(
            override val turn: Int,
            val id: String,
            val toolName: String,
            val toolTitle: String,
        ) : Segment
    }

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
        /**
         * [T-subagent-queue-visibility] When the run was REGISTERED while
         * QUEUED — i.e. the moment it began waiting for a scheduler permit.
         * 0 for runs that never queued. Kept after execution starts so the
         * wait is measurable; see [queueWaitMs].
         */
        val queuedAtMs: Long = 0L,
        val endedAtMs: Long = 0L,
        /** Current turn (1-based) / configured max turns. */
        val turn: Int = 0,
        val maxTurns: Int = 0,
        /** Final or streaming text produced by the sub-agent. */
        val resultText: String = "",
        /**
         * [T-subagent-chat-stream] Interleaved transcript — narration,
         * reasoning and tool calls in arrival order. Empty for runs that
         * produced nothing yet (and for runs restored from a journal written
         * before this field existed). [steps] stays the source of truth for
         * per-tool status/output; this list carries the ORDER and the prose.
         */
        val segments: List<Segment> = emptyList(),
        /**
         * [T-subagent-thinking] Accumulated reasoning text of the CURRENT and
         * past turns, kept separate from [resultText] so chain-of-thought is
         * never delivered as the report. Bounded by [MAX_THINKING_TEXT_CHARS].
         */
        val thinkingText: String = "",
        /**
         * [T-subagent-thinking] True when this run was actually started with
         * reasoning enabled (the skill asked for it AND the model supports
         * it). Rendered as a meta chip so "why is there no thinking block?"
         * is answerable from the page instead of guesswork — a request that
         * was silently downgraded must not look like a bug.
         */
        val reasoningEnabled: Boolean = false,
        /** Error detail when [status] is FAILED/CANCELLED/TIMED_OUT. */
        val error: String? = null,
        /**
         * [T-subagent-token-accounting] Cumulative API-reported usage across
         * every turn of this run. Previously the parent had NO cost signal for
         * a sub-agent — the whole "delegate cheap work to a cheaper model"
         * rationale was unfalsifiable. -1 = never reported by the provider
         * (some endpoints omit usage), surfaced as "unknown" rather than 0.
         */
        val tokensIn: Int = -1,
        val tokensOut: Int = -1,
        /**
         * [T-subagent-model-routing] Which model actually ran this sub-agent —
         * the parent paid for it, so it must be visible without re-reading the
         * skill. Empty until the runner stamps it.
         */
        val modelLabel: String = "",
        /**
         * [T-subagent-report-hygiene] Run-level notices (retry lines, etc.) —
         * never part of the report text the parent consumes.
         */
        val notices: String = "",
        val steps: List<Step> = emptyList(),
        /** True when the user opened the detail page at least once. */
        val opened: Boolean = false,
    ) {
        val isActive: Boolean get() = status == RunStatus.RUNNING || status == RunStatus.QUEUED
        val isExecuting: Boolean get() = status == RunStatus.RUNNING
        val isQueued: Boolean get() = status == RunStatus.QUEUED

        /**
         * [T-subagent-queue-visibility] How long this run waited for a
         * scheduler permit before executing, in ms — null while still queued
         * (the wait is ongoing; the live elapsed tile covers that case) and
         * null for runs that never queued. Kept as a computed property so
         * every surface reads the same number.
         */
        val queueWaitMs: Long?
            get() {
                if (queuedAtMs <= 0L) return null
                if (status == RunStatus.QUEUED) return null
                return (startedAtMs - queuedAtMs).coerceAtLeast(0L)
            }
        val durationMs: Long
            get() = (if (endedAtMs > 0) endedAtMs else System.currentTimeMillis()) - startedAtMs

        /** Tool steps as a map — the chat-stream pills read status/output here. */
        val stepsById: Map<String, Step>
            get() = steps.associateBy { it.id }

        /** Ordered transcript, or an empty list for runs that have none yet. */
        val transcript: List<Segment> get() = segments
    }

    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    /** [T-subagent-spawn-dedupe] Guards the check-then-insert in [registerOrReuse]. */
    private val registerLock = Any()

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
            // [T-subagent-queue-visibility] Stamp the registration moment so
            // the wait for a permit stays measurable after execution starts.
            queuedAtMs = if (queued) System.currentTimeMillis() else 0L,
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
     * [T-subagent-spawn-dedupe] Outcome of an idempotent register attempt:
     * [run] is either a freshly created run ([reused] == false) or an
     * EXISTING active run with the same skill + query ([reused] == true).
     */
    data class RegisterOutcome(val run: Run, val reused: Boolean)

    /**
     * [T-subagent-spawn-dedupe] Look up an ACTIVE run for the same skill +
     * query WITHOUT creating anything.
     *
     * Extracted so the spawn path can check for a duplicate BEFORE asking the
     * user to approve it: previously the approval dialog ran first, so a model
     * that re-issued an identical task prompted the user again for work that
     * was already running — and with a fan-out, N duplicate asks arrived in a
     * row. A duplicate is not a new decision, so it must not consume a new
     * decision from the human.
     */
    fun findActiveDuplicate(skillId: String, query: String): Run? = synchronized(registerLock) {
        _runs.value.firstOrNull {
            it.isActive && it.skillId == skillId && it.query == query
        }
    }

    /**
     * [T-subagent-spawn-dedupe] Idempotent register used by spawn_agent.
     *
     * While a run with the SAME skillId AND query is still active
     * (QUEUED/RUNNING), a second identical spawn MUST NOT create a
     * duplicate run. The model occasionally re-issues the same task
     * cross-turn (the same-turn fingerprint dedupe in the dispatch loop
     * only catches byte-identical args, and only within one turn), and
     * same-batch spawns whose args differ only in tool_title / run_until
     * slip past it too — each spawn used to create real duplicate work
     * ("multiple identical sub-agents running at once"). Now the duplicate
     * resolves to the existing run so the caller can answer the spawn
     * with its run_id instead of executing again.
     *
     * Completed runs (SUCCESS/FAILED/CANCELLED) never match — re-running
     * a finished task is a legitimate request; ToolLoopDetector guards
     * pathological repetition. Check+insert run under [registerLock]:
     * same-batch spawns fan out on concurrent coroutines, so a plain
     * check-then-register could interleave and double-spawn.
     */
    fun registerOrReuse(
        blockId: String,
        skillId: String,
        skillName: String,
        query: String,
        title: String,
        maxTurns: Int,
        sessionId: String = "",
        groupId: String = "",
        queued: Boolean = false,
    ): RegisterOutcome = synchronized(registerLock) {
        val duplicate = _runs.value.firstOrNull {
            it.isActive && it.skillId == skillId && it.query == query
        }
        if (duplicate != null) return RegisterOutcome(duplicate, reused = true)
        RegisterOutcome(
            register(blockId, skillId, skillName, query, title, maxTurns, sessionId, groupId, queued),
            reused = false,
        )
    }

    /** [T-subagent-orchestration] The scheduler granted a permit — flip a
     * QUEUED run to RUNNING.
     *
     * [T-subagent-queue-visibility] The queue wait is preserved, not lost.
     * The old comment claimed "the queue wait is still visible via the
     * untouched startedAtMs", but this function OVERWROTE startedAtMs with
     * the execution start — so the pill's live timer reset to 0:00 the moment
     * a run finally started, erasing exactly the delay the user was staring
     * at ("why is nothing happening?"). [Run.queuedAtMs] is stamped at
     * registration and kept, so the UI can show "waited 2m10s" and the run's
     * total latency stays auditable.
     */
    fun markExecuting(runId: String) {
        updateRun(runId) { run ->
            if (run.status == RunStatus.QUEUED) {
                run.copy(
                    status = RunStatus.RUNNING,
                    startedAtMs = System.currentTimeMillis(),
                )
            } else {
                run
            }
        }
    }

    /**
     * [T-subagent-queue-visibility] Convenience passthrough to
     * [Run.queueWaitMs] for registry-level callers and tests.
     */
    fun queueWaitMs(run: Run): Long? = run.queueWaitMs

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
            run.copy(
                steps = steps,
                // [T-subagent-chat-stream] The pill is a transcript item too:
                // appended here (not in a separate call) so the ORDER of
                // narration vs. tool calls survives without the call sites
                // having to coordinate — stepStarted is already invoked from
                // the exact point in the stream where the call arrived.
                segments = appendToolCallSegment(run.segments, turn, stepId, toolName, toolTitle),
            )
        }
    }

    /**
     * [T-subagent-chat-stream] Append a tool call to the transcript, ignoring
     * a repeat of a step already present (the stream can re-emit a tool call
     * id on retry — the log must not grow a duplicate pill for it).
     */
    private fun appendToolCallSegment(
        current: List<Segment>,
        turn: Int,
        stepId: String,
        toolName: String,
        toolTitle: String,
    ): List<Segment> {
        if (current.any { it is Segment.ToolCall && it.id == stepId }) return current
        val item = Segment.ToolCall(turn, stepId, toolName, toolTitle)
        // Replacing the list (not mutating it) keeps Run a value snapshot.
        return appendBounded(current, item)
    }

    /**
     * [T-subagent-chat-stream] Append text to the transcript. Consecutive
     * deltas inside the same turn extend the trailing [Segment.Text] rather
     * than creating one segment per token — the renderer keys on segment
     * identity and a 200-segment turn would thrash the LazyColumn.
     */
    fun appendSegmentText(runId: String, delta: String) {
        if (delta.isEmpty()) return
        updateRun(runId) { run ->
            run.copy(segments = appendStreamed(run.segments, run.turn, delta, thinking = false))
        }
    }

    /**
     * [T-subagent-thinking] Append reasoning to the transcript AND to the
     * run's [Run.thinkingText] accumulator (the page's "deep thinking" row
     * reads the accumulator so it survives segment pruning).
     */
    fun appendSegmentThinking(runId: String, delta: String) {
        if (delta.isEmpty()) return
        updateRun(runId) { run ->
            run.copy(
                segments = appendStreamed(run.segments, run.turn, delta, thinking = true),
                thinkingText = (run.thinkingText + delta).takeLast(MAX_THINKING_TEXT_CHARS),
            )
        }
    }

    /**
     * Shared tail-append for streamed prose: extend the trailing segment of
     * the same turn + kind, else start a new one. Deltas interleaved with a
     * tool call land in a NEW segment, which is exactly how the alternation
     * ("text → pill → text") is reconstructed.
     */
    private fun appendStreamed(
        current: List<Segment>,
        turn: Int,
        delta: String,
        thinking: Boolean,
    ): List<Segment> {
        val last = current.lastOrNull()
        val sameKind = when {
            thinking -> last is Segment.Thinking && last.turn == turn
            else -> last is Segment.Text && last.turn == turn
        }
        if (sameKind) {
            // Exhaustive over the sealed interface so the accumulator type is
            // never nullable; the ToolCall arm is unreachable here (sameKind
            // only matches Text/Thinking) and simply declines to grow.
            val grown: Segment = when (val l = last) {
                is Segment.Text -> l.copy(content = l.content + delta)
                is Segment.Thinking -> l.copy(content = l.content + delta)
                is Segment.ToolCall -> return current
                null -> return current
            }
            return current.dropLast(1) + grown
        }
        val fresh: Segment = if (thinking) Segment.Thinking(turn, delta) else Segment.Text(turn, delta)
        return appendBounded(current, fresh)
    }

    /**
     * [T-subagent-chat-stream] Bound the transcript so a 100-turn run cannot
     * pin unbounded prose in a StateFlow. Oldest entries are dropped first —
     * the tail is where the run currently is, and the full text survives in
     * the journal for anything the user needs to re-read.
     */
    private fun appendBounded(current: List<Segment>, item: Segment): List<Segment> {
        val next = current + item
        return if (next.size > MAX_SEGMENTS) next.takeLast(MAX_SEGMENTS) else next
    }

    /** [T-subagent-thinking] Record whether reasoning actually ran (see Run). */
    fun setReasoningEnabled(runId: String, enabled: Boolean) {
        updateRun(runId) { if (it.reasoningEnabled == enabled) it else it.copy(reasoningEnabled = enabled) }
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

    /**
     * [T-subagent-report-hygiene] Run-level notices that are NOT the report:
     * transient-retry lines, truncation warnings. Kept OUT of [resultText]
     * because [finish] falls back to the streamed resultText when the caller
     * passes a blank terminal text (models that stop on a tool call never
     * emit a summary) — notices mixed into that stream were delivered to the
     * parent AS the sub-agent's findings.
     */
    fun appendNotice(runId: String, line: String) {
        if (line.isBlank()) return
        updateRun(runId) { run ->
            run.copy(notices = (run.notices + "\n" + line).trim().takeLast(MAX_NOTICE_CHARS))
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
    /**
     * [T-subagent-terminal-once] Terminal write that applies only while the
     * run is still active — the boolean-returning spelling of the loop's
     * terminal transitions. See [finishOnce] for the rationale; kept as its
     * own name because most call sites do not care whether they won.
     */
    fun finishIfActive(runId: String, status: RunStatus, resultText: String = "", error: String? = null) {
        finishOnce(runId, status, resultText, error)
    }

    /**
     * [T-subagent-terminal-once] Terminal write that REFUSES to overwrite an
     * already-terminal run. [finish] below is the unconditional variant used
     * by legacy call sites; this one is the honest model.
     *
     * Why this exists: the runner's natural-exit path calls [finish] with
     * SUCCESS, which replaced a CANCELLED status written earlier by the
     * cancel cascade — a run the user explicitly stopped would flip back to
     * "Completed" in the pill and detail page. A terminal state is a fact
     * about what happened first; it must not be rewritten by a path that was
     * only possible because the cancel did its job (the loop unwound, then
     * its success branch ran).
     *
     * Returns true when THIS call was the one that terminated the run, false
     * when a terminal state already existed (the caller then knows its
     * outcome was discarded).
     */
    fun finishOnce(runId: String, status: RunStatus, resultText: String = "", error: String? = null): Boolean {
        var applied = false
        updateRun(runId) { run ->
            if (!run.isActive) {
                run
            } else {
                applied = true
                run.copy(
                    status = status,
                    endedAtMs = System.currentTimeMillis(),
                    resultText = resultText.ifBlank { run.resultText },
                    error = error,
                )
            }
        }
        return applied
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

    /**
     * [T-subagent-token-accounting] Add one turn's API-reported usage to the
     * run's running totals. Lives on the atomic [updateRun] path — the same
     * compare-and-set the shell line callbacks use — so two providers
     * reporting usage for the same run can't interleave and lose an add.
     *
     * Providers that never emit a Usage chunk leave the totals at -1
     * ("unknown"); we only promote them to real numbers once we have seen at
     * least one report, so 0 is never mistaken for "reported nothing".
     */
    fun addUsage(runId: String, inputTokens: Int, outputTokens: Int) {
        if (inputTokens <= 0 && outputTokens <= 0) return
        updateRun(runId) { run ->
            val inBase = if (run.tokensIn < 0) 0 else run.tokensIn
            val outBase = if (run.tokensOut < 0) 0 else run.tokensOut
            run.copy(
                tokensIn = inBase + inputTokens,
                tokensOut = outBase + outputTokens,
            )
        }
    }

    /**
     * [T-subagent-model-routing] Stamp the model a run actually executed on,
     * once the runner has resolved (or inherited) it.
     */
    fun setModelLabel(runId: String, label: String) {
        if (label.isBlank()) return
        updateRun(runId) { if (it.modelLabel == label) it else it.copy(modelLabel = label) }
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
        private const val MAX_NOTICE_CHARS = 2_000
        /**
         * [T-subagent-chat-stream] Transcript bounds. Segments are what the
         * detail page renders, so an unbounded list would pin a long run's
         * whole prose in a StateFlow that recomposes the page on every delta.
         * The tail is kept: the journal holds the complete text.
         */
        const val MAX_SEGMENTS = 240
        /**
         * [T-subagent-thinking] Reasoning is the most token-hungry stream a
         * sub-agent produces (it can dwarf the answer). Capped harder than
         * the report: it is context, not output.
         */
        const val MAX_THINKING_TEXT_CHARS = 12_000
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
