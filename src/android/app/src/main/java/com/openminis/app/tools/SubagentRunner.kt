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
    }

    // ── Entry point ──────────────────────────────────────────────────────

    /**
     * Execute one spawn_agent tool call. Returns the model-facing result;
     * streams every step into [registry] for the pill + detail page.
     */
    suspend fun executeSpawnAgent(argsJson: String, blockId: String): ToolExecutionResult {
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
            SubagentSkill.RUN_UNTIL_LEGACY_FIRST_TURN -> SubagentSkill.RUN_UNTIL_TURN_COMPLETE
            else -> null
        }
        if (runUntil == null) {
            return ToolExecutionResult(
                "Error: spawn_agent.run_until must be 'done' or 'turn_complete' " +
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

        // [T-subagent-scheduler] Queue on the two-level scheduler: per-skill
        // cap (frontmatter max_parallel — NOW actually consumed) then the
        // chat-global cap. Fair FIFO; over-limit spawns WAIT instead of
        // failing with a concurrency error (the old acquireOrFalse behavior
        // turned max_parallel into "over limit → spawn fails").
        return scheduler.run(skill.id, config.maxParallel) {
            executeLoop(skill, config, skillName, query, title, runUntil, blockId)
        }
    }

    // ── Model loop ───────────────────────────────────────────────────────

    private suspend fun executeLoop(
        skill: SkillInfo,
        config: SubagentSkill.SubagentConfig,
        skillName: String,
        query: String,
        title: String,
        runUntil: String,
        blockId: String,
    ): ToolExecutionResult {
        val sessionId = deps.activeSessionId()

        // Fail-closed capability filter + explicit allowlist.
        val subagentTools = SubagentSkill.buildFilteredTools(deps.agentTools(), config.allowedTools)
        if (subagentTools.isEmpty()) {
            return ToolExecutionResult(
                "Error: Skill '$skillName' has no usable tools (all filtered out by forbidden/allowlist)",
                false, toolTitle = title,
            )
        }

        val systemPrompt = SubagentSkill.buildSystemPrompt(skill)
        val history = mutableListOf(LLMMessage(role = LLMMessage.Role.USER, content = query))
        val provider = deps.currentProvider() ?: return ToolExecutionResult(
            "Error: No active provider available", false, toolTitle = title,
        )

        // [T-subagent-ui] Register the run — from here on the user sees
        // the in-chat pill and can open the live detail page.
        val run = registry.register(
            blockId = blockId,
            skillId = skill.id,
            skillName = skill.name,
            query = query,
            title = title,
            maxTurns = config.maxTurns,
            sessionId = sessionId,
        )

        val resultSb = StringBuilder()
        val artifacts = mutableListOf<String>()
        var turns = 0
        var lastText = ""

        try {
            while (turns < config.maxTurns) {
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
                            registry.appendResultText(
                                run.id,
                                "\n\n[transient stream error ($errDesc) — retrying " +
                                    "$streamRetryAttempt/${STREAM_RETRY_DELAYS_SEC.size} in ${delaySec}s]\n",
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
    ): String? = SubagentRunJournal.write(
        run = if (run.sessionId.isEmpty()) run.copy(sessionId = sessionId) else run,
        terminal = terminal,
        resultText = resultText,
        turns = turns,
        error = error,
        artifacts = artifacts,
        context = context,
    )

    companion object {
        private const val TAG = "ChatViewModel"

        /**
         * [T-subagent-transient-retry] Sub-agent stream retry backoff
         * (seconds) — mirrors the main loop's AUTO_RETRY_DELAYS_SEC.
         */
        private val STREAM_RETRY_DELAYS_SEC = intArrayOf(1, 2, 4)
    }
}
