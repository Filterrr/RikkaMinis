package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.EnvVarRedactor
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.offload.ModelExecutionStreamException
import com.openminis.app.sandbox.offload.ModelStreamErrorException
import com.openminis.app.sandbox.offload.ModelWorkerDiedException
import com.openminis.app.sandbox.offload.ProviderExecutionGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * [T-subagent-runner] Sub-agent runtime extracted from ChatViewModel —
 * owns the whole spawn_agent lifecycle: arg validation, skill lookup,
 * config parsing, scheduler queueing, registry streaming, the model loop
 * (with transient-retry parity to the main loop), sub-tool dispatch
 * (shell / browser / file), artifact tracking, journaling, and terminal
 * reporting.
 *
 * ChatViewModel keeps only a thin [Deps] adapter (provider/session access
 * and the browser executor, which belongs to the conversation layer) plus
 * the spawn_agent dispatch line. This file is VM-free and unit-testable
 * beyond the registry/skill layers.
 *
 * Concurrency: [executeSpawnAgent] queues on [SubagentScheduler] —
 * skill-level cap first (`max_parallel` frontmatter, 1..4), then the
 * chat-global cap. Over-limit spawns WAIT; they are never rejected.
 */
class SubagentRunner(
    private val context: Context,
    private val scheduler: SubagentScheduler,
    private val registry: SubagentRunRegistry,
    private val orchestration: SubagentOrchestration.Registries,
    private val deps: Deps,
) {
    /**
     * Conversation-layer dependencies. Deliberately narrow: no ChatViewModel
     * or SkillRepository reference leaks into the sub-agent runtime — skill
     * access goes through [findSkill]/[isSkillEnabledForSession] so this
     * class depends only on the [SkillInfo] abstraction.
     */
    interface Deps {
        fun findSkill(skillName: String): SkillInfo?
        fun isSkillEnabledForSession(skillId: String): Boolean
        fun agentTools(): List<AgentToolDefinition>
        fun currentProvider(): com.openminis.app.provider.LLMProvider?
        fun activeSessionId(): String
        suspend fun executeBrowserUse(argsJson: String): ToolExecutionResult
        fun maybeReloadSkillsForPath(argsJson: String)
        fun unwrapFlowException(e: Throwable): Throwable

        /**
         * [T-subagent-orchestration] Scope for DETACHED sub-agent runs
         * (run_until="detach"). Must outlive the parent stream job (a user
         * stream-cancel must NOT kill background runs) but die with the chat
         * VM. Recommended: CoroutineScope(SupervisorJob(viewModelScope job)
         * + Dispatchers.IO) — supervisor semantics isolate sibling failures,
         * VM clear tears everything down.
         */
        fun orchestrationScope(): kotlinx.coroutines.CoroutineScope

        /**
         * [T-subagent-model-routing] Every model a `spawn_agent(model = …)`
         * may name, as (instanceId, entryId, modelId, displayName). Default
         * empty = routing disabled (tests / a VM that cannot see the catalog)
         * → an explicit `model` argument fails loudly instead of guessing.
         */
        fun modelCatalog(): List<SubagentModelResolver.Candidate> = emptyList()

        /**
         * [T-subagent-model-routing] Build a provider for a catalog candidate
         * resolved from `spawn_agent(model = …)`. Return null when the model
         * cannot be instantiated (missing credential, disabled instance).
         * Default null = routing unavailable → an explicit model fails loudly.
         */
        fun providerForCandidate(candidate: SubagentModelResolver.Candidate): com.openminis.app.provider.LLMProvider? = null

        /** [T-subagent-model-routing] Human label of a provider (for the run record). */
        fun providerLabel(provider: com.openminis.app.provider.LLMProvider): String = provider.model.displayName

        /**
         * [T-subagent-wake-parent] Deliver a DETACHED run's terminal outcome
         * to the parent conversation as a synthetic turn, so the parent's
         * model naturally synthesises a reply without the user having to ask
         * "did that finish?" — the behaviour ExTV shipped and this runtime
         * lacked.
         *
         * Implementations MUST NOT preempt a live parent turn: a sub-agent
         * wake-up is informational, so queue it (the app's prompt queue) when
         * the parent is streaming rather than cancelling its in-flight work.
         *
         * Default no-op keeps every existing Deps adapter source-compatible.
         */
        suspend fun wakeParentWithResult(runId: String, prompt: String) {}
    }

    // ── Entry point ──────────────────────────────────────────────────────

    /**
     * Execute one spawn_agent tool call. Returns the model-facing result;
     * streams every step into [registry] for the pill + detail page.
     *
     * [groupId] links the spawn to its dispatch batch ([T-subagent-orchestration]
     * SubagentGroup) — the parent loop mints one group per spawn batch so
     * join/wait/cancel can address the whole batch by id.
     */
    suspend fun executeSpawnAgent(
        argsJson: String,
        blockId: String,
        groupId: String = "",
    ): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val skillName = args.optString("skill_name", "").trim()
        val query = args.optString("query", "").trim()
        val title = args.optString("tool_title", "Sub-agent").ifBlank { "Sub-agent" }
        val rawRunUntil = args.optString("run_until", SubagentSkill.RUN_UNTIL_DONE)
            .ifBlank { SubagentSkill.RUN_UNTIL_DONE }

        if (skillName.isBlank()) {
            return ToolExecutionResult("Error: spawn_agent requires 'skill_name'", false, toolTitle = title)
        }
        if (query.isBlank()) {
            return ToolExecutionResult("Error: spawn_agent requires 'query'", false, toolTitle = title)
        }
        // [T-subagent-first-turn] 'first_turn' → 'turn_complete' alias.
        val runUntil = when (rawRunUntil) {
            SubagentSkill.RUN_UNTIL_DONE -> SubagentSkill.RUN_UNTIL_DONE
            SubagentSkill.RUN_UNTIL_TURN_COMPLETE -> SubagentSkill.RUN_UNTIL_TURN_COMPLETE
            SubagentSkill.RUN_UNTIL_DETACH -> SubagentSkill.RUN_UNTIL_DETACH
            SubagentSkill.RUN_UNTIL_LEGACY_FIRST_TURN -> SubagentSkill.RUN_UNTIL_TURN_COMPLETE
            else -> null
        }
        if (runUntil == null) {
            return ToolExecutionResult(
                "Error: spawn_agent.run_until must be 'done', 'turn_complete', or 'detach' " +
                    "(legacy 'first_turn' is accepted as an alias)",
                false, toolTitle = title,
            )
        }

        val skill = deps.findSkill(skillName) ?: return ToolExecutionResult(
            "Error: Skill '$skillName' not found. Make sure it is installed and the name is correct.",
            false, toolTitle = title,
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        if (!config.isSubagent) {
            return ToolExecutionResult(
                "Error: Skill '$skillName' is not a sub-agent skill. " +
                    "Add `subagent: true` to its SKILL.md frontmatter to enable sub-agent mode.",
                false, toolTitle = title,
            )
        }

        if (!deps.isSkillEnabledForSession(skill.id)) {
            return ToolExecutionResult(
                "Error: Skill '$skillName' is disabled for this session",
                false, toolTitle = title,
            )
        }

        // Fail-closed capability filter + explicit allowlist (validated
        // BEFORE registration so a misconfigured skill never creates a run).
        val subagentTools = SubagentSkill.buildFilteredTools(deps.agentTools(), config.allowedTools)
        if (subagentTools.isEmpty()) {
            return ToolExecutionResult(
                "Error: Skill '$skillName' has no usable tools (all filtered out by forbidden/allowlist)",
                false, toolTitle = title,
            )
        }
        val parentProvider = deps.currentProvider() ?: return ToolExecutionResult(
            "Error: No active provider available", false, toolTitle = title,
        )
        val sessionId = deps.activeSessionId()

        // [T-subagent-model-routing] Resolve an explicit `model` BEFORE the
        // run is registered — a bad model name must fail the SPAWN, not create
        // a run that dies in the background. Fail-loud by design: silently
        // inheriting the parent's (expensive) model would turn a typo into a
        // billing surprise, which is the opposite of why this knob exists.
        val modelArg = args.optString("model", "").trim()
        val provider = if (modelArg.isEmpty()) parentProvider else when (
            val resolved = SubagentModelResolver.resolve(
                modelArg, deps.modelCatalog(),
            )
        ) {
            is SubagentModelResolver.Result.Resolved ->
                deps.providerForCandidate(resolved.candidate)
                    ?: return ToolExecutionResult(
                        "Error: model '${resolved.candidate.describe()}' resolved but could not be " +
                            "instantiated (disabled provider or missing credential). Spawn aborted; " +
                            "no sub-agent run was created.",
                        false, toolTitle = title,
                    )
            is SubagentModelResolver.Result.Failed ->
                return ToolExecutionResult(
                    "Error: ${resolved.message} Spawn aborted; no sub-agent run was created.",
                    false, toolTitle = title,
                )
            // `model` was non-blank, so Inherit cannot happen here — treat as failure.
            SubagentModelResolver.Result.Inherit -> return ToolExecutionResult(
                "Error: model catalog is unavailable, so an explicit model cannot be honoured. " +
                    "Omit the 'model' argument to inherit the parent's model.",
                false, toolTitle = title,
            )
        }

        // [T-subagent-run-timeout] Clamp per the tool contract. The default is
        // deliberate: a detached run has no human watching it, so an
        // unbounded loop is a cost and permit leak, not a feature.
        //
        // [T-subagent-queue-budget] The budget is an ABSOLUTE deadline from
        // SPAWN time, not a stopwatch started when the permit lands. Two
        // reasons, both observed in the wild (dogfood run subagent-1/2,
        // 2026-09-14): a research run spent its whole 600s budget while the
        // model loop was only half done, and with a per-execution stopwatch a
        // queued spawn would silently extend that — "timeout_seconds: 600"
        // meaning "600s plus unbounded waiting" is a lie the tool description
        // must not tell. Inline spawns additionally cap how long they will
        // SIT in the scheduler queue (see tryAcquire below): the parent turn
        // is parked on this call returning, so queue time is user-visible
        // freeze, not background progress.
        val timeoutSeconds = args.optInt("timeout_seconds", SubagentSkill.DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, SubagentSkill.MAX_TIMEOUT_SECONDS)
        val deadlineNanos = System.nanoTime() + timeoutSeconds * 1_000_000_000L

        // [T-subagent-orchestration] Register BEFORE the scheduler hands out
        // a permit, flagged QUEUED — over-limit spawns become visible as
        // "queued · waiting for slot" in the pill row instead of appearing
        // only when execution starts.
        // [T-subagent-spawn-dedupe] Idempotent register: an identical task
        // (same skill + query) still QUEUED/RUNNING resolves to the EXISTING
        // run instead of double-spawning real duplicate work.
        val outcome = registry.registerOrReuse(
            blockId = blockId,
            skillId = skill.id,
            skillName = skill.name,
            query = query,
            title = title,
            maxTurns = config.maxTurns,
            sessionId = sessionId,
            groupId = groupId,
            queued = true,
        )
        if (outcome.reused) {
            return deduplicatedSpawnResult(outcome.run.id, skillName)
        }
        val run = outcome.run
        val job = SubagentOrchestration.SubagentJob(
            runId = run.id,
            skillId = skill.id,
            skillName = skill.name,
            detached = runUntil == SubagentSkill.RUN_UNTIL_DETACH,
        )
        orchestration.putJob(job)

        if (runUntil == SubagentSkill.RUN_UNTIL_DETACH) {
            // [T-subagent-orchestration] Fire-and-forget: launch on the
            // orchestration scope (outlives stream cancel, dies with the VM)
            // and return the handle immediately. The parent collects via
            // join_subagents / wait_any or discards via cancel_subagents.
            val detachedJob = deps.orchestrationScope().launch {
                try {
                    scheduler.run(skill.id, config.maxParallel, deadlineNanos) {
                        registry.markExecuting(run.id)
                        executeLoop(
                            run = run, skill = skill, config = config, skillName = skillName,
                            query = query, title = title, runUntil = SubagentSkill.RUN_UNTIL_DONE,
                            sessionId = sessionId, provider = provider, subagentTools = subagentTools,
                            timeoutSeconds = timeoutSeconds,
                            deadlineNanos = deadlineNanos,
                        )
                    }
                } catch (e: CancellationException) {
                    // cancel_subagents completed the deferred first, then
                    // cancelled this coroutine (or the VM is being torn down).
                    // executeLoop reconciles the registry when it was running;
                    // reconcile here for the never-started case.
                    registry.finishIfActive(
                        run.id, SubagentRunRegistry.RunStatus.CANCELLED,
                        error = e.message ?: "detached run cancelled",
                    )
                    journal(run, "CANCELLED", "", 0, e.message ?: "cancelled", emptyList(), sessionId)
                } catch (e: Exception) {
                    AppLogger.warning(TAG, "[Subagent] detached '$skillName' crashed: ${e.message}")
                    registry.finishIfActive(
                        run.id, SubagentRunRegistry.RunStatus.FAILED,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                } finally {
                    // Complete the job from the registry's terminal snapshot if
                    // nobody else did (cancel path completes the deferred BEFORE
                    // cancelling the coroutine, so this is usually a no-op), and
                    // wake the parent conversation when the result was pushed
                    // rather than pulled. [T-subagent-delivery]
                    maybeWakeParent(job)
                }
            }
            job.coroutineJob = detachedJob
            return ToolExecutionResult(
                output = buildString {
                    append("Sub-agent '$skillName' spawned detached.")
                    append("\nrun_id: ${run.id}")
                    if (groupId.isNotEmpty()) append("\ngroup_id: $groupId")
                    append("\n\nIt is now running in the background — this call returned WITHOUT waiting. ")
                    append("Collect the result later with join_subagents (run_ids or group_id), ")
                    append("or race siblings with wait_any. Cancel with cancel_subagents if it becomes unnecessary. ")
                    append("Meanwhile continue other work — do not idle.")
                },
                success = true,
                toolTitle = "Sub-agent: $skillName",
            )
        }

        // [T-subagent-scheduler] Inline spawn: queue on the two-level
        // scheduler (per-skill cap then chat-global cap), flip QUEUED→
        // RUNNING when the permit lands, and complete the job deferred in
        // every exit path so a later join_subagents(run_ids=[…]) can pick
        // up even an inline result.
        //
        // [T-subagent-queue-budget] A QueueTimeout means the spawn's own
        // budget was eaten by WAITING for a slot — the run never started, so
        // there are no partial findings to report. Surface it as an ordinary
        // failed tool result (actionable: retry later, spawn detached, or
        // raise timeout_seconds), NOT as an exception crashing the parent
        // turn, and reconcile the registry so the pill does not spin forever.
        return try {
            scheduler.run(
                skill.id, config.maxParallel,
                deadlineNanos = deadlineNanos,
                maxQueueWaitMs = SubagentSkill.INLINE_QUEUE_WAIT_MS,
            ) {
            registry.markExecuting(run.id)
            try {
                val result = executeLoop(
                    run = run, skill = skill, config = config, skillName = skillName,
                    query = query, title = title, runUntil = runUntil,
                    sessionId = sessionId, provider = provider, subagentTools = subagentTools,
                    timeoutSeconds = timeoutSeconds,
                    deadlineNanos = deadlineNanos,
                )
                job.deferred.complete(
                    SubagentOrchestration.JobOutcome(
                        runId = run.id,
                        success = result.success,
                        report = result.output,
                        skillName = skill.name,
                        journalPath = run.id.let { rid ->
                            registry.runs.value.firstOrNull { it.id == rid }?.journalPath
                        },
                        error = if (result.success) null else result.output.take(300),
                    ),
                )
                result
            } catch (e: CancellationException) {
                job.deferred.complete(
                    SubagentOrchestration.JobOutcome(
                        runId = run.id, success = false, report = "",
                        skillName = skill.name, cancelled = true,
                        error = e.message ?: "cancelled",
                    ),
                )
                throw e
            } catch (e: Exception) {
                job.deferred.complete(
                    SubagentOrchestration.JobOutcome(
                        runId = run.id, success = false, report = "",
                        skillName = skill.name,
                        error = e.message ?: e.javaClass.simpleName,
                    ),
                )
                throw e
            }
            }
        } catch (e: SubagentScheduler.QueueTimeoutException) {
            registry.finishIfActive(
                run.id, SubagentRunRegistry.RunStatus.TIMED_OUT,
                error = e.message,
            )
            job.deferred.complete(
                SubagentOrchestration.JobOutcome(
                    runId = run.id, success = false, report = "",
                    skillName = skill.name, error = e.message,
                ),
            )
            ToolExecutionResult(
                "Error: sub-agent '$skillName' never started — ${e.message} " +
                    "(${scheduler.activeGlobalCount()}/${SubagentSkill.MAX_PARALLEL_CAP} chat slots busy). " +
                    "The run consumed NO tokens. Retry later, spawn with run_until='detach' to avoid " +
                    "parking this turn on a queue, or raise timeout_seconds if the queue is legitimately " +
                    "long-running.",
                false, toolTitle = "Sub-agent: $skillName",
            )
        }
    }

    /**
     * [T-subagent-spawn-dedupe] Model-facing answer for a duplicate spawn:
     * the identical task already has an active run — hand back ITS handle
     * so the model joins / waits / cancels that run instead of executing
     * the same work twice. Works for both inline and detached spawns: the
     * job deferred completes on every exit path, so join_subagents can
     * collect an inline run's result later too.
     */
    private fun deduplicatedSpawnResult(existingRunId: String, skillName: String): ToolExecutionResult {
        val snapshot = registry.runs.value.firstOrNull { it.id == existingRunId }
        val statusDesc = if (snapshot?.isQueued == true) "queued (waiting for a scheduler slot)" else "running"
        return ToolExecutionResult(
            output = buildString {
                append("Deduplicated: an identical sub-agent task is already $statusDesc — ")
                append("no new run was created.")
                append("\nrun_id: $existingRunId")
                snapshot?.groupId?.takeIf { it.isNotEmpty() }?.let { append("\ngroup_id: $it") }
                append("\n\nCollect its result with join_subagents (pass the run_id above), ")
                append("race it with wait_any, or cancel it with cancel_subagents. ")
                append("Do NOT spawn this same task again while that run is active.")
            },
            success = true,
            toolTitle = "Sub-agent: $skillName",
        )
    }

    /**
     * [T-subagent-orchestration] Complete a job's deferred from the run's
     * terminal registry snapshot. No-op (returns null) when the deferred
     * already completed (cancel cascade finished it first).
     *
     * The return value is the [T-subagent-delivery] signal: a non-null result
     * means THIS path is the run's terminal completer, so it owns the parent
     * wake-up. A null return means someone else already delivered.
     */
    private fun completeJobFromRegistry(job: SubagentOrchestration.SubagentJob): SubagentOrchestration.JobOutcome? {
        if (job.deferred.isCompleted) return null
        val snapshot = registry.runs.value.firstOrNull { it.id == job.runId }
        val outcome = SubagentOrchestration.JobOutcome(
            runId = job.runId,
            success = snapshot?.status == SubagentRunRegistry.RunStatus.SUCCESS,
            report = snapshot?.resultText ?: "",
            skillName = job.skillName,
            journalPath = snapshot?.journalPath,
            error = snapshot?.error,
            cancelled = snapshot?.status == SubagentRunRegistry.RunStatus.CANCELLED,
        )
        return if (job.deferred.complete(outcome)) outcome else null
    }

    /**
     * [T-subagent-delivery] Push a detached run's outcome to the parent only
     * when no parent call is parked on it. Two guards, in this order:
     *  1. [completeJobFromRegistry] returns null when the deferred was already
     *     completed elsewhere (a pull path, or the cancel cascade) — the party
     *     that completed it already has the outcome, so we would be second.
     *  2. [SubagentOrchestration.SubagentJob.hasAwaiters] covers the race
     *     where the run completes WHILE a join/wait is blocked: that join is
     *     about to return this very outcome, so a wake-up turn is duplication.
     *
     * [completeJobFromRegistry] runs FIRST on purpose — completing the deferred
     * is what releases a blocked join, and checking awaiters before that would
     * see a stale positive forever.
     *
     * Accepted residual race: a join that resolves targets and has not yet
     * entered its awaiting() bracket has no awaiter visible here, so both
     * paths could deliver. The window contains no suspension points and the
     * failure mode is one duplicate informational turn, not lost data.
     */
    private suspend fun maybeWakeParent(job: SubagentOrchestration.SubagentJob) {
        val outcome = completeJobFromRegistry(job) ?: return
        if (!job.detached) return
        if (job.hasAwaiters()) {
            AppLogger.info(TAG, "[Subagent] '${job.skillName}' result pulled by parent join — wake-up skipped")
            return
        }
        val snapshot = registry.runs.value.firstOrNull { it.id == job.runId }
        runCatching {
            deps.wakeParentWithResult(job.runId, buildWakePrompt(outcome, snapshot))
        }.onFailure {
            AppLogger.warning(TAG, "[Subagent] failed to wake parent with '${job.skillName}' result: ${it.message}")
        }
    }

    // ── Model loop ───────────────────────────────────────────────────────

    /**
     * The sub-agent's model loop. The run is ALREADY registered (and possibly
     * still QUEUED when this is invoked through the scheduler wrapper) —
     * registration moved to [executeSpawnAgent] so queued spawns are visible
     * in the UI before a scheduler permit lands.
     */
    private suspend fun executeLoop(
        run: SubagentRunRegistry.Run,
        skill: SkillInfo,
        config: SubagentSkill.SubagentConfig,
        skillName: String,
        query: String,
        title: String,
        runUntil: String,
        sessionId: String,
        provider: com.openminis.app.provider.LLMProvider,
        subagentTools: List<AgentToolDefinition>,
        timeoutSeconds: Int,
        deadlineNanos: Long,
    ): ToolExecutionResult {
        // [T-subagent-runtime-preamble] Inject a short runtime preamble
        // (current date/time + durable workspace root) ahead of the skill
        // body. Sub-agents run without any shell-derived context and used
        // to burn their first turn discovering what day it is.
        val systemPrompt = SubagentSkill.buildSystemPrompt(
            skill,
            runtimeContext = SubagentSkill.buildRuntimeContext(),
        )
        // [T-subagent-model-routing] Record what actually ran. A parent that
        // asked for a cheap model must be able to verify it got one.
        registry.setModelLabel(run.id, deps.providerLabel(provider))
        val history = mutableListOf(LLMMessage(role = LLMMessage.Role.USER, content = query))

        // [T-subagent-run-timeout] The absolute [deadlineNanos] arrives from
        // the spawn site — measured from SPAWN time and inclusive of queue
        // wait. Enforced at TURN boundaries only: a single hung shell_execute
        // is already capped at 900s by executeSubagentShell, and an interrupt
        // mid-stream would corrupt the history. The guard this adds is the
        // missing one — an unbounded number of turns, which nothing capped
        // before.

        val resultSb = StringBuilder()
        val artifacts = mutableListOf<String>()
        var turns = 0
        var lastText = ""
        // [T-subagent-context-budget] Last API-reported context size, 0 until a
        // Usage chunk says otherwise — trusted over the local estimate.
        var lastContextTokens = 0

        try {
            while (turns < config.maxTurns) {
                // [T-subagent-run-timeout] Checked at the turn boundary, i.e.
                // after the previous turn's tool results are already in
                // `history` and journaled. Stopping here — never mid-stream —
                // means the partial report is always coherent: whatever the
                // run learned so far survives into the TIMED_OUT result below.
                if (System.nanoTime() >= deadlineNanos) {
                    val partial = resultSb.toString().trim()
                    AppLogger.warning(TAG, "[Subagent] '$skillName' exceeded ${timeoutSeconds}s run budget at turn $turns")
                    registry.finish(
                        run.id, SubagentRunRegistry.RunStatus.TIMED_OUT,
                        resultText = partial,
                        error = "exceeded the $timeoutSeconds-second run budget after $turns turn(s)",
                    )
                    val timedOutResult = SubagentResult(
                        status = SubagentResult.Status.FAILED,
                        report = partial,
                        turns = turns,
                        maxTurns = config.maxTurns,
                        skillId = skill.id,
                        skillName = skill.name,
                        journalPath = journal(
                            run, "TIMED_OUT", partial, turns,
                            "timed out after ${timeoutSeconds}s", artifacts, sessionId,
                        ),
                        artifacts = artifacts.toList(),
                        error = "TIMED_OUT after $turns turn(s): the run exceeded its $timeoutSeconds-second " +
                            "budget. Its partial findings are above — re-spawn with a larger " +
                            "timeout_seconds only if the work is genuinely unfinished.",
                    )
                    return ToolExecutionResult(
                        timedOutResult.toPromptText(), false, toolTitle = "Sub-agent: ${skill.name}",
                    )
                }
                turns++
                registry.turnStarted(run.id, turns)
                val instance = provider.instanceContext ?: let {
                    registry.finish(
                        run.id, SubagentRunRegistry.RunStatus.FAILED,
                        error = "No provider instance context",
                    )
                    return ToolExecutionResult(
                        "Error: No provider instance context for sub-agent remote execution",
                        false, toolTitle = title,
                    )
                }
                val textSb = StringBuilder()
                val toolCalls = mutableListOf<SubagentToolCall>()

                // Sub-agent runs through :modelservice via the gateway.
                // [T-subagent-transient-retry] Mirror the MAIN agent loop's
                // transient-error policy (1s/2s/4s on the same provider):
                // SSL jitter, stream resets, and 0-chunk worker deaths are
                // transient — only the CURRENT turn's stream is retried;
                // executed tool results are already in history.
                var streamRetryAttempt = 0
                while (true) {
                    textSb.setLength(0)
                    toolCalls.clear()
                    try {
                        ProviderExecutionGateway.stream(
                            context = context,
                            instance = instance,
                            model = provider.model,
                            messages = history.toList(),
                            systemPrompt = systemPrompt,
                            maxTokens = config.maxOutputTokens,
                            temperature = null,
                            tools = subagentTools,
                            thinkingLevel = ThinkingLevel.OFF,
                        ).collect { chunk ->
                            when (chunk) {
                                is LLMStreamChunk.Text -> {
                                    textSb.append(chunk.text)
                                    registry.appendResultText(run.id, chunk.text)
                                }
                                is LLMStreamChunk.ToolCallComplete -> {
                                    toolCalls.add(SubagentToolCall(chunk.id, chunk.name, chunk.args))
                                    registry.stepStarted(
                                        run.id, chunk.id, turns, chunk.name,
                                        try {
                                            chunk.args.optString("tool_title", chunk.name)
                                        } catch (_: Exception) { chunk.name },
                                    )
                                }
                                // [T-subagent-token-accounting] The sub-agent
                                // loop used to DROP this chunk — a delegated
                                // run's cost was invisible, which made the
                                // "cheaper model for cheap work" rationale
                                // unfalsifiable.
                                is LLMStreamChunk.Usage -> {
                                    registry.addUsage(
                                        run.id,
                                        chunk.usage.inputTokens,
                                        chunk.usage.outputTokens,
                                    )
                                    if (chunk.usage.latestContextTokens > 0) {
                                        lastContextTokens = chunk.usage.latestContextTokens
                                    }
                                }
                                else -> {}
                            }
                        }
                        break  // stream finished cleanly — exit the retry loop
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val actual = deps.unwrapFlowException(e)
                        val is5xx = actual is com.openminis.app.data.model.LLMError.ProviderError &&
                            actual.detail.contains(Regex("\\b[5][0-9]{2}\\b"))
                        val workerDiedZeroChunk =
                            ((actual is ModelWorkerDiedException) ||
                                (actual is ModelStreamErrorException)) &&
                            (actual as? ModelExecutionStreamException)?.hadChunks == false
                        val isTransient = actual is com.openminis.app.data.model.LLMError.NetworkError ||
                            actual is com.openminis.app.data.model.LLMError.TransientError ||
                            is5xx ||
                            workerDiedZeroChunk
                        if (isTransient && streamRetryAttempt < STREAM_RETRY_DELAYS_SEC.size) {
                            val delaySec = STREAM_RETRY_DELAYS_SEC[streamRetryAttempt]
                            streamRetryAttempt += 1
                            val errDesc = actual.message ?: actual.javaClass.simpleName
                            AppLogger.warning(
                                TAG,
                                "[Subagent] transient stream error on ${provider.model.displayName}, " +
                                    "turn $turns retry $streamRetryAttempt/" +
                                    "${STREAM_RETRY_DELAYS_SEC.size} in ${delaySec}s: $errDesc",
                            )
                            // [T-subagent-report-hygiene] Notices go to the
                            // pill/detail stream, NOT into resultText: finish()
                            // falls back to the streamed text when the model
                            // emits no closing summary, and these lines were
                            // being delivered to the parent AS the report
                            // (dogfood subagent-1/2, 2026-09-14 — the wake-up
                            // "report" was nothing but retry chatter).
                            registry.appendNotice(
                                run.id,
                                "[transient stream error ($errDesc) — retrying " +
                                    "$streamRetryAttempt/${STREAM_RETRY_DELAYS_SEC.size} in ${delaySec}s]",
                            )
                            kotlinx.coroutines.delay(delaySec * 1000L)
                            continue
                        }
                        throw e  // fatal for this run — outer handler journals + reports
                    }
                }

                val text = textSb.toString()
                lastText = text
                if (text.isNotBlank()) {
                    if (resultSb.isNotEmpty()) resultSb.append('\n')
                    resultSb.append(text)
                }

                if (toolCalls.isEmpty()) {
                    // Model finished naturally — no more tool calls
                    break
                }

                // Append assistant turn with tool uses to history
                history.add(LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = text,
                    contentParts = toolCalls.map { call ->
                        AgentContentPart.ToolUse(id = call.id, name = call.name, input = call.args)
                    },
                ))

                // [T-tool-batch-executor] Same batching semantics as the
                // main loop (shared ToolConcurrencyPolicy): consecutive
                // parallel-safe reads fan out, the rest stay serial. Before
                // this, sub-agent tool calls were strictly sequential.
                val calls = toolCalls.map { ToolBatchExecutor.Call(it.id, it.name, it.args.toString()) }
                val results = ToolBatchExecutor.executeBatched(calls) { call ->
                    executeSubagentTool(call.name, call.argsJson, call.id, run.id, artifacts)
                }
                for ((call, result) in toolCalls.zip(results)) {
                    val resultContent = if (result.success) result.output else "Error: ${result.output}"
                    registry.stepFinished(
                        run.id, call.id, result.success,
                        output = resultContent.lines().takeLast(
                            SubagentRunRegistry.MAX_STEP_OUTPUT_LINES,
                        ).joinToString("\n"),
                    )
                    history.add(LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "Result of ${call.name} (${call.id}):\n$resultContent",
                        contentParts = listOf(AgentContentPart.ToolResult(
                            id = call.id, name = call.name,
                            content = resultContent, isError = !result.success,
                        )),
                    ))
                }

                // [T-subagent-checkpoint] Persist a mid-run progress anchor
                // after every completed turn. Process death never executes
                // the terminal journal paths below (they live in return /
                // exception handlers), so this per-turn write is the only
                // recovery signal a killed run leaves behind. Best-effort —
                // failures are swallowed inside the checkpoint writer.
                SubagentRunCheckpoint.write(run, turns, text, context)

                // [T-subagent-context-budget] The sub-agent's `history` only
                // ever grew before this — `general-agent` ships max_turns: 48
                // and a research run's tool outputs are routinely tens of KB,
                // so long runs died on a provider context error instead of
                // finishing. Trim at the turn boundary (after this turn's
                // tool_results are in, before the next request) with the same
                // turn-granular invariants the main loop uses: whole rounds
                // only, task message and the newest rounds always kept.
                runCatching {
                    val window = provider.model.contextWindowTokens
                    val trimmed = SubagentHistoryBudget.trim(
                        history = history,
                        contextWindowTokens = window,
                        apiContextTokens = lastContextTokens,
                    )
                    if (trimmed.didTrim) {
                        history.clear()
                        history.addAll(trimmed.messages)
                        AppLogger.info(
                            TAG,
                            "[Subagent] '$skillName' context trim: dropped ${trimmed.droppedMessages} " +
                                "messages (~${trimmed.estimatedTokens}t est) to fit ${window}t window",
                        )
                    }
                }

                if (runUntil == SubagentSkill.RUN_UNTIL_TURN_COMPLETE) {
                    // [T-subagent-first-turn] Caller asked for a first-turn
                    // readout only — stop after turn 1's tools EXECUTED and
                    // their results are recorded (naming now matches the
                    // actual semantics).
                    val partial = resultSb.toString().trim()
                    registry.finish(
                        run.id, SubagentRunRegistry.RunStatus.SUCCESS,
                        resultText = partial,
                        error = "Stopped early (run_until=$runUntil)",
                    )
                    val result = SubagentResult(
                        status = SubagentResult.Status.SUCCESS,
                        report = partial,
                        declaredStatus = SubagentSkill.parseReportStatus(partial),
                        turns = turns,
                        maxTurns = config.maxTurns,
                        skillId = skill.id,
                        skillName = skill.name,
                        journalPath = journal(
                            run, SubagentResult.journalTerminal(SubagentResult.Status.SUCCESS, stoppedEarly = true),
                            partial, turns, error = "stopped early (run_until=$runUntil)", artifacts, sessionId,
                        ),
                        artifacts = artifacts.toList(),
                    )
                    return ToolExecutionResult(
                        result.toPromptText(stoppedEarly = true), true, toolTitle = "Sub-agent: ${skill.name}",
                    )
                }
            }
        } catch (e: CancellationException) {
            // [T-subagent-durability] NEVER swallow cancellation. Journal
            // the partial report, reconcile the registry to CANCELLED (the
            // pill must not spin forever), then rethrow so the framework's
            // cancel cleanup can attach the recovery pointer.
            val partial = resultSb.toString()
            registry.finish(
                run.id, SubagentRunRegistry.RunStatus.CANCELLED,
                resultText = partial,
                error = e.message ?: "cancelled",
            )
            journal(run, "CANCELLED", partial, turns, e.message ?: "cancelled by user", artifacts, sessionId)
            AppLogger.warning(TAG, "[Subagent] '$skillName' cancelled after $turns turn(s); partial report journaled")
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            AppLogger.warning(TAG, "[Subagent] '$skillName' error after $turns turn(s): $msg")
            registry.finish(
                run.id, SubagentRunRegistry.RunStatus.FAILED,
                resultText = resultSb.toString(), error = msg,
            )
            val partial = resultSb.toString()
            val result = SubagentResult(
                status = SubagentResult.Status.FAILED,
                report = partial,
                turns = turns,
                maxTurns = config.maxTurns,
                skillId = skill.id,
                skillName = skill.name,
                journalPath = journal(run, "FAILED", partial, turns, msg, artifacts, sessionId),
                artifacts = artifacts.toList(),
                error = msg,
            )
            return ToolExecutionResult(result.toPromptText(), false, toolTitle = "Sub-agent: ${skill.name}")
        }

        if (turns >= config.maxTurns && lastText.isNotBlank()) {
            resultSb.append("\n\n[Sub-agent reached max turns (${config.maxTurns})]")
        }

        val finalText = resultSb.toString().trim()
        if (finalText.isBlank()) {
            registry.finish(run.id, SubagentRunRegistry.RunStatus.SUCCESS, resultText = "")
            return ToolExecutionResult(
                "Sub-agent '$skillName' completed in $turns turn(s) with no output.",
                true, toolTitle = "Sub-agent: ${skill.name}",
            )
        }

        registry.finish(run.id, SubagentRunRegistry.RunStatus.SUCCESS, resultText = finalText)
        val result = SubagentResult(
            status = SubagentResult.Status.SUCCESS,
            report = finalText,
            declaredStatus = SubagentSkill.parseReportStatus(finalText),
            turns = turns,
            maxTurns = config.maxTurns,
            skillId = skill.id,
            skillName = skill.name,
            journalPath = journal(
                run, SubagentResult.journalTerminal(SubagentResult.Status.SUCCESS, stoppedEarly = false),
                finalText, turns, error = null, artifacts, sessionId,
            ),
            artifacts = artifacts.toList(),
        )
        return ToolExecutionResult(result.toPromptText(), true, toolTitle = "Sub-agent: ${skill.name}")
    }


    // ── Sub-agent tool dispatch ──────────────────────────────────────────

    /**
     * Execute a tool inside a sub-agent's loop. [T-subagent-capability]
     * Runtime enforcement layer: the schema filter already excluded
     * forbidden tools, but this refuse-with-explanation path guarantees a
     * stale allowlist or future regression can never EXECUTE them
     * (defense in depth — capability checks must fail closed twice).
     */
    suspend fun executeSubagentTool(
        name: String,
        argsJson: String,
        callId: String,
        runId: String,
        artifacts: MutableList<String>? = null,
    ): ToolExecutionResult {
        if (name in SubagentSkill.FORBIDDEN_TOOLS) {
            return forbiddenToolResult(name)
        }
        val capability = AgentCapabilities.capabilityOf(name)
        if (capability == null || capability !in AgentCapabilities.SUBAGENT_BASE) {
            return forbiddenToolResult(name)
        }
        return when (name) {
            "shell_execute" -> executeSubagentShell(argsJson, callId, runId)
            "browser_use" -> deps.executeBrowserUse(argsJson)
            FileWriteTool.NAME, FileEditTool.NAME -> {
                val result = executeFileTool(name, argsJson)
                if (result.success) {
                    extractPath(argsJson)?.let { artifacts?.add(it) }
                    deps.maybeReloadSkillsForPath(argsJson)
                }
                result
            }
            else -> executeFileTool(name, argsJson)
        }
    }

    private fun forbiddenToolResult(name: String): ToolExecutionResult = ToolExecutionResult(
        "Error: Unknown or forbidden tool: $name. This tool is not part of your " +
            "capability set — do NOT retry it and do NOT improvise around it. " +
            "Sub-agents cannot spawn further agents or touch the parent agent's " +
            "memory. Finish now: report what you have accomplished so far and " +
            "state this tool gap as a caveat.",
        false,
    )

    private fun extractPath(argsJson: String): String? = try {
        val args = JSONObject(argsJson)
        args.optString("path", "").ifBlank { args.optString("file_path", "").ifBlank { null } }
    } catch (_: Exception) {
        null
    }

    /**
     * [T-subagent-parity] shell_execute inside a sub-agent: built on
     * [ExecutionCoordinator] with per-line streaming into the registry's
     * current step. Mirrors the main agent's shell path (timeout clamping,
     * exit-code suffix, timeout flag, env-var redaction) minus the
     * parent-loop UI plumbing.
     */
    private suspend fun executeSubagentShell(argsJson: String, stepId: String, runId: String): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val command = args.optString("command", "")
        val timeoutSec = args.optInt("timeout", 900).coerceIn(1, 900)
        val toolTitle = args.optString("tool_title", "shell_execute")
        if (command.isBlank()) {
            return ToolExecutionResult("Error: 'command' is required", false, toolTitle = toolTitle)
        }
        val result = ExecutionCoordinator.execute(
            sessionId = deps.activeSessionId(),
            command = command,
            timeout = timeoutSec * 1000L,
            lineCallback = { rawLine ->
                if (runId.isEmpty()) return@execute
                val trimmedLine = rawLine.trimEnd()
                if (trimmedLine.isEmpty()) return@execute
                registry.stepOutput(runId, stepId, trimmedLine)
            },
        )
        val output = if (result.output.isBlank()) "(no output)" else result.output
        val exitInfo = if (result.exitCode != 0) " (exit code ${result.exitCode})" else ""
        // Exit code 124 = the wrapper timeout fired (mirrors main path).
        val timedOut = result.exitCode == 124
        val finalOutput = "$output$exitInfo"
        val (redactedOut, _) = EnvVarRedactor.redactIfEnabled(finalOutput)
        if (runId.isNotEmpty()) {
            registry.stepFinished(
                runId, stepId, result.exitCode == 0,
                output = redactedOut.lines().takeLast(
                    SubagentRunRegistry.MAX_STEP_OUTPUT_LINES,
                ).joinToString("\n"),
            )
        }
        return ToolExecutionResult(
            output = redactedOut,
            success = result.exitCode == 0,
            toolTitle = toolTitle,
            timedOut = timedOut,
        )
    }

    /**
     * [T-subagent-parity] file tools inside a sub-agent — same executors as
     * the main agent (paths resolve through the session's sandbox
     * identically). Memory tools never reach here: the capability filter
     * drops them from the schema and [executeSubagentTool] refuses them at
     * runtime ([T-subagent-capability] memory isolation).
     */
    private fun executeFileTool(name: String, argsJson: String): ToolExecutionResult = when (name) {
        FileReadTool.NAME -> FileReadTool.execute(argsJson, deps.activeSessionId(), context)
        ReadImageTool.NAME -> ReadImageTool.execute(argsJson, deps.activeSessionId(), context)
        FileWriteTool.NAME -> FileWriteTool.execute(argsJson, deps.activeSessionId(), context)
        FileEditTool.NAME -> FileEditTool.execute(argsJson, deps.activeSessionId(), context)
        else -> forbiddenToolResult(name)
    }

    // ── Journaling ───────────────────────────────────────────────────────

    /**
     * [T-subagent-durability] Persist the terminal report via
     * [SubagentRunJournal]. Failures are swallowed — journaling must never
     * break the run itself.
     */
    private fun journal(
        run: SubagentRunRegistry.Run,
        terminal: String,
        resultText: String,
        turns: Int,
        error: String?,
        artifacts: List<String>,
        sessionId: String,
    ): String? {
        // [T-subagent-journal-steps] Re-read the LIVE snapshot: the `run`
        // handle here is the object captured at registration, whose `steps`
        // were empty then and never mutate afterwards (Run is immutable and
        // the registry swaps whole objects). Without this the journal's
        // "## Steps" section was ALWAYS empty — every dogfooded report so far
        // lost its audit trail for exactly this reason. Terminal transitions
        // happen before journal() is called, so the registry already holds
        // the full step list plus usage/model labels.
        val live = registry.runs.value.firstOrNull { it.id == run.id } ?: run
        // Same re-read discipline for the CALLER-SUPPLIED fields: the
        // detached cancel handler journals with ("", 0) — the coroutine was
        // cut off before it could assemble a return value — but the registry
        // HAS the streamed text and the turn count. Falling back here means a
        // cancelled run's journal still carries everything it produced,
        // instead of "(no text output produced)" over real findings.
        val effectiveText = resultText.ifBlank { live.resultText }
        val effectiveTurns = if (turns > 0) turns else live.turn
        val path = SubagentRunJournal.write(
            run = if (live.sessionId.isEmpty()) live.copy(sessionId = sessionId) else live,
            terminal = terminal,
            resultText = effectiveText,
            turns = effectiveTurns,
            error = error,
            artifacts = artifacts,
            context = context,
        )
        // [T-subagent-checkpoint] Terminal state reached — the journal is
        // now the durable record, so the mid-run checkpoint has served its
        // purpose. Sweep it so a live run never leaves a stale .ckpt that
        // recovery tooling would misread as an interrupted run.
        SubagentRunCheckpoint.sweep(run.id, context, sessionId.ifEmpty { run.sessionId })
        // [T-subagent-orchestration] Surface the anchor in the registry so
        // the detail page + join results can point at it.
        if (!path.isNullOrEmpty()) registry.setJournalPath(run.id, path)
        return path
    }

    // ── Orchestration tools (parent-only) ────────────────────────────────

    /**
     * [T-subagent-orchestration] join_subagents / wait_any / cancel_subagents
     * executor. Sub-agents never reach this (FORBIDDEN_TOOLS + capability
     * catalog keep the tools out of their schema AND refuse them at runtime).
     */
    suspend fun executeOrchestrationTool(name: String, argsJson: String): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val toolTitle = args.optString("tool_title", name).ifBlank { name }
        val runIds = SubagentOrchestrationTools.parseRunIds(
            args.optString("run_ids", "").ifBlank { null },
        )
        val groupId = args.optString("group_id", "").trim().ifEmpty { null }

        return when (name) {
            SubagentOrchestrationTools.JOIN_NAME -> {
                val timeoutMs = (args.optLong("timeout_sec", 0L)
                    .takeIf { it > 0 } ?: SubagentOrchestration.DEFAULT_JOIN_TIMEOUT_MS) * 1000L
                val target = SubagentOrchestration.resolveJoinTargets(orchestration, runIds, groupId)
                    ?: return ToolExecutionResult(
                        "No matching sub-agent runs found" +
                            (runIds.takeIf { it.isNotEmpty() }?.let { " for run_ids=$it" } ?: "")
                            .let { s -> s + (groupId?.let { " / group_id=$it" } ?: "") } +
                            ". Detached spawns (run_until='detach') return run_id / group_id — " +
                            "pass those. Nothing to join yet? spawn_agent first.",
                        false, toolTitle = toolTitle,
                    )
                val (group, jobs) = target
                // [T-subagent-delivery] Bracket the await so a run finishing
                // inside the window is delivered by THIS pull path only.
                val outcomes = SubagentOrchestration.awaiting(jobs) {
                    SubagentOrchestration.joinAll(jobs, timeoutMs)
                }
                buildJoinPrompt(group, jobs, outcomes, timedOut = outcomes.any { !it.success && it.error?.contains("join timed out") == true })
            }
            SubagentOrchestrationTools.WAIT_ANY_NAME -> {
                val timeoutMs = (args.optLong("timeout_sec", 0L)
                    .takeIf { it > 0 } ?: SubagentOrchestration.DEFAULT_WAIT_ANY_TIMEOUT_MS) * 1000L
                val successOnly = args.optBoolean("success_only", true)
                val target = SubagentOrchestration.resolveJoinTargets(orchestration, runIds, groupId)
                    ?: return ToolExecutionResult(
                        "No matching sub-agent runs found for wait_any" +
                            (groupId?.let { " / group_id=$it" } ?: "") +
                            ". Detached spawns return run_id / group_id — pass those.",
                        false, toolTitle = toolTitle,
                    )
                val (group, jobs) = target
                // [T-subagent-delivery] Bracket the race with the WHOLE batch,
                // losers included. Tempting to mark only the eventual winner,
                // but the race blocks until SOMEONE completes — meanwhile a
                // loser finishing would push a wake-up into a parent that is
                // synchronously parked here. Foregoing a loser's push is
                // strictly safer, and the result below enumerates the
                // still-running ids so the parent can join them explicitly.
                // The counter releases on return, so a loser that finishes
                // AFTER the race is still pushed.
                val winner = SubagentOrchestration.awaiting(jobs) {
                    SubagentOrchestration.waitAny(jobs, timeoutMs, successOnly)
                }
                if (winner == null) {
                    ToolExecutionResult(
                        "wait_any timed out after ${timeoutMs / 1000}s — no member of group ${group.id} " +
                            "completed" + (if (successOnly) " successfully" else "") + " yet. " +
                            "The runs keep going; call wait_any or join_subagents again later.",
                        false, toolTitle = toolTitle,
                    )
                } else {
                    val losers = jobs.filter { it.runId != winner.runId }
                    ToolExecutionResult(
                        buildString {
                            append("wait_any: first completion in group ${group.id} → run ")
                            append(winner.runId)
                            append(" (").append(winner.skillName).append(")")
                            append(if (winner.cancelled) " CANCELLED" else if (winner.success) " SUCCESS" else " FAILED")
                            append("\n\n---\n").append(winner.report.ifBlank { "(no report text)" })
                            winner.journalPath?.let { append("\n\nJournal: $it") }
                            append("\n\n---\nStill running (NOT waited on): ")
                            append(losers.joinToString(", ") { it.runId }.ifEmpty { "(none)" })
                            append(". Reap losers with cancel_subagents when you no longer need them.")
                        },
                        success = !winner.cancelled && (winner.success || !successOnly),
                        toolTitle = toolTitle,
                    )
                }
            }
            SubagentOrchestrationTools.CANCEL_NAME -> {
                val reason = args.optString("reason", "cancelled by parent agent").ifBlank { "cancelled by parent agent" }
                val jobs = when {
                    runIds.isNotEmpty() -> orchestration.getJobs(runIds)
                    !groupId.isNullOrBlank() -> {
                        val group = orchestration.getGroup(groupId)
                            ?: return ToolExecutionResult(
                                "No group '$groupId' found.", false, toolTitle = toolTitle,
                            )
                        orchestration.getJobs(group.runIds)
                    }
                    else -> orchestration.liveDetachedJobs()
                }
                if (jobs.isEmpty()) {
                    return ToolExecutionResult(
                        "No matching sub-agent runs to cancel" +
                            (groupId?.let { " for group_id=$it" } ?: "") + ".",
                        false, toolTitle = toolTitle,
                    )
                }
                val wakeRunIds = SubagentOrchestration.cancelCascade(jobs, reason)
                var killed = 0
                for (job in jobs) {
                    registry.finishIfActive(
                        job.runId, SubagentRunRegistry.RunStatus.CANCELLED,
                        error = reason,
                    )
                    job.coroutineJob?.let { cj ->
                        if (cj.isActive) {
                            cj.cancel(kotlinx.coroutines.CancellationException(reason))
                            killed++
                        }
                    }
                }
                ToolExecutionResult(
                    buildString {
                        append("Cancelled ${jobs.size} sub-agent run(s) — $killed background coroutine(s) torn down, ")
                        append("registry reconciled, ${wakeRunIds.size} blocked join/wait woke with 'cancelled'. ")
                        append("Cancelled runs: ").append(jobs.joinToString(", ") { it.runId })
                    },
                    true, toolTitle = toolTitle,
                )
            }
            else -> ToolExecutionResult("Error: unknown orchestration tool '$name'", false, toolTitle = toolTitle)
        }
    }

    /** Model-facing rendering of a join's outcomes. */
    private fun buildJoinPrompt(
        group: SubagentOrchestration.SubagentGroup,
        jobs: List<SubagentOrchestration.SubagentJob>,
        outcomes: List<SubagentOrchestration.JobOutcome>,
        timedOut: Boolean,
    ): ToolExecutionResult {
        val sb = StringBuilder()
        sb.append(SubagentOrchestration.joinSummary(group, jobs, outcomes))
        sb.append("\n")
        for ((job, outcome) in jobs.zip(outcomes)) {
            sb.append("\n\n=== run ").append(job.runId)
                .append(" (").append(outcome.skillName).append(") — ")
                .append(
                    when {
                        outcome.cancelled -> "CANCELLED"
                        outcome.success -> "SUCCESS"
                        else -> "FAILED"
                    },
                )
                .append(" ===")
            if (outcome.report.isNotBlank()) sb.append("\n").append(outcome.report)
            else sb.append("\n(no report text)")
            outcome.error?.let { sb.append("\nError: ").append(it) }
            outcome.journalPath?.let { sb.append("\nJournal: ").append(it) }
        }
        if (timedOut) {
            sb.append("\n\nOne or more runs timed out of this join but keep running in the background — ")
            sb.append("call join_subagents again for just those run_ids to collect them later.")
        }
        return ToolExecutionResult(
            sb.toString(),
            success = outcomes.isNotEmpty() && outcomes.all { it.success || it.cancelled },
            toolTitle = "join_subagents",
        )
    }

    companion object {
        private const val TAG = "ChatViewModel"

        /**
         * [T-subagent-transient-retry] Sub-agent stream retry backoff
         * (seconds) — mirrors the main loop's AUTO_RETRY_DELAYS_SEC.
         */
        private val STREAM_RETRY_DELAYS_SEC = intArrayOf(1, 2, 4)
    }
}

/** [T-subagent-delivery] Cap on how much report text rides into the parent turn. */
private const val WAKE_REPORT_MAX_CHARS = 4000

/**
 * [T-subagent-delivery] Render the parent-facing turn for a detached run's
 * terminal outcome.
 *
 * Framed as an EVENT, not a user request, so the parent model folds it into
 * its plan instead of answering it conversationally. The report is bounded — a
 * detached run that produced a 50 KB dump must not spend the parent's whole
 * window on it; the journal holds the full text and is named so the parent can
 * read selectively.
 *
 * Top-level + pure (no runner state) so the wording contract is unit-testable.
 */
internal fun buildWakePrompt(
    outcome: SubagentOrchestration.JobOutcome,
    snapshot: SubagentRunRegistry.Run?,
    maxReportChars: Int = WAKE_REPORT_MAX_CHARS,
): String = buildString {
    val status = snapshot?.status?.name ?: if (outcome.success) "SUCCESS" else "FAILED"
    appendLine("[Sub-agent '${outcome.skillName}' finished in the background — status $status]")
    appendLine("run_id: ${outcome.runId}")
    snapshot?.let { run ->
        if (run.modelLabel.isNotBlank()) appendLine("model: ${run.modelLabel}")
        if (run.tokensIn >= 0 || run.tokensOut >= 0) {
            appendLine("tokens: in=${run.tokensIn.coerceAtLeast(0)} out=${run.tokensOut.coerceAtLeast(0)}")
        }
        appendLine("turns: ${run.turn}/${run.maxTurns}")
    }
    if (!outcome.error.isNullOrBlank()) appendLine("error: ${outcome.error}")
    if (outcome.report.isNotBlank()) {
        appendLine()
        if (outcome.report.length > maxReportChars) {
            append(outcome.report.take(maxReportChars))
            appendLine(" …[truncated]")
        } else {
            appendLine(outcome.report)
        }
    }
    outcome.journalPath?.let {
        appendLine()
        append("Full report: $it (file_read it for anything truncated above).")
    }
}.trim()
