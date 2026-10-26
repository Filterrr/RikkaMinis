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
import com.openminis.app.sandbox.PRootKernel
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
     * [feat/subagent-roster-routing] The roster as a trailing note, for the
     * errors where the skill EXISTS but cannot be spawned (not a sub-agent
     * skill, disabled for this session). The parent's instruction says not
     * to retry the SAME spawn — so the note has to offer a DIFFERENT one.
     *
     * Empty when no roster is known (tests, or a chat with no sub-agent
     * installed): the message then says nothing extra, exactly as before.
     */
    private fun rosterSuffix(): String {
        val roster = SubagentRosterHint.build(deps.subagentRoster())
        return if (roster.isEmpty()) "" else "\n\n$roster"
    }

    /**
     * Conversation-layer dependencies. Deliberately narrow: no ChatViewModel
     * or SkillRepository reference leaks into the sub-agent runtime — skill
     * access goes through [findSkill]/[isSkillEnabledForSession] so this
     * class depends only on the [SkillInfo] abstraction.
     */
    interface Deps {
        fun findSkill(skillName: String): SkillInfo?
        fun isSkillEnabledForSession(skillId: String): Boolean

        /**
         * [feat/subagent-roster-routing] The sub-agents that are spawnable
         * RIGHT NOW — installed, enabled for this session, and marked
         * `subagent: true`.
         *
         * Used for two things:
         *  - the roster the parent sees (discoverability — the parent must
         *    not have to guess a `skill_name`);
         *  - the error message when the parent names one that does not exist:
         *    it lists the valid names so the model can CORRECT THE NAME IN
         *    THE SAME RESPONSE. Without it the model's own rule ("do not
         *    retry the same spawn") turned one bad name into a lost
         *    delegation.
         *
         * Default empty = no roster known (tests / a VM without the
         * repository) → the old behaviour, no listing.
         */
        fun subagentRoster(): List<SubagentRosterHint.Entry> = emptyList()

        /**
         * [feat/subagent-roster-routing] The CONFIGURED roster — the
         * user's own sub-agent definitions (name, description, the
         * instructions that make that agent what it is).
         *
         * These entries name a SPECIALITY, not a runtime: they say WHEN to
         * delegate somewhere and WHAT that agent must do. The runtime
         * comes from a skill. Default empty = no configured roster (the
         * common case for a fresh install).
         */
        fun configuredSubagents(): List<SubagentDelegation.ConfiguredAgent> = emptyList()

        fun agentTools(): List<AgentToolDefinition>
        fun currentProvider(): com.openminis.app.provider.LLMProvider?
        fun activeSessionId(): String
        suspend fun executeBrowserUse(argsJson: String): ToolExecutionResult

        /**
         * [T-subagent-websearch] One-shot web search for a sub-agent.
         *
         * The tool was absent from the capability catalog, so sub-agents never
         * even saw its schema — the research workflows this fork advertises
         * spent their turns hand-rolling navigate → get_readable instead. The
         * default fails loudly: a Deps adapter that cannot provide search must
         * say so in the tool result rather than pretend the tool works.
         */
        suspend fun executeWebSearch(argsJson: String): ToolExecutionResult =
            ToolExecutionResult(
                "Error: web_search is unavailable in this sub-agent host. " +
                    "Use browser_use (navigate + get_readable) instead, or report the gap.",
                false,
            )

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

        // ── [T-subagent-model-spread] Group-aware routing for spawn batches ──
        //
        // When several spawns run in ONE batch while the chat is bound to a
        // model group (or a spawn explicitly names one), the children rotate
        // over the group's members — 1 号子 agent → a、2 号 → b — instead of
        // every child inheriting the parent's single active member. The
        // per-call member choice is precomputed by the conversation layer
        // (which knows the batch structure + emission order) and read back
        // here by blockId, so concurrent spawn coroutines never race for a
        // slot. Defaults (null/empty) disable spreading entirely — every
        // existing Deps adapter is source- and behaviour-compatible.

        /**
         * Batch member assignments for inherited-model spawns, keyed by the
         * spawn's tool_use block id. Built by the parent loop per dispatch
         * batch (see ChatViewModel.planSpawnBatchSpread) — only batches with
         * >1 spawn get entries; a lone spawn never appears here and keeps the
         * plain parent provider.
         */
        fun groupSpreadAssignments(): Map<String, SubagentGroupSpread.Assignment> = emptyMap()

        /**
         * [T-subagent-model-spread] Diagnostic callback for the spread audit
         * line ("spawn_agent model resolution"), invoked once per spawn with
         * what actually happened. A no-op default keeps tests silent.
         */
        fun recordSpreadAssignment(blockId: String, detail: String) {}

        /** Resolve a `model` token that matched no model to a configured group: (groupId, name), or null. */
        fun findModelGroup(query: String): Pair<String, String>? = null

        /** Live routable members of [groupId] (enabled instance, non-hidden), declaration order. */
        fun modelGroupMembers(groupId: String): List<SubagentModelResolver.Candidate> = emptyList()

        /** Names of configured groups — appended to fail-loud error text (caller bounds the list). */
        fun modelGroupNames(): List<String> = emptyList()

        /**
         * [T-subagent-budget-inheritance] The parent loop's execution budget
         * as a child-facing guard, or null when no run budget is active (tests,
         * or a spawn outside an agent loop). Returning null makes the
         * inheritance layer a complete no-op.
         */
        fun subagentBudgetGuard(): SubagentBudgetGuard? = null

        /**
         * [T-subagent-approval] True when "always allow" is granted for this
         * skill — the fast path that skips the dialog entirely. Default false:
         * an implementation without persistence still ASKS (safe direction).
         */
        fun isSkillAlwaysAllowed(skillId: String): Boolean = false

        /**
         * [T-subagent-approval] Record an "always allow" grant for [skillId].
         * Only called after the user explicitly chose that option.
         */
        fun setSkillAlwaysAllowed(skillId: String) {}

        /**
         * [T-subagent-approval] Park the spawn until the user answers. Returns
         * DENY when nobody answered in time — a gate left unanswered must not
         * let autonomous work through. The default (no gate wired) allows,
         * keeping every existing Deps adapter's behaviour unchanged.
         */
        suspend fun awaitSpawnApproval(
            skillId: String,
            skillName: String,
            task: String,
            modelLabel: String,
            detached: Boolean,
        ): SpawnDecision = SpawnDecision.ALLOW_ONCE

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

        // [feat/subagent-roster-routing] Resolve the requested name against
        // BOTH routes:
        //  1. a SKILL — the runtime (tool set, budget, instructions);
        //  2. a CONFIGURED roster entry — the speciality the user defined.
        //
        // A configured entry is a NAME + DESCRIPTION, not a runtime, so it
        // needs a skill to run on. The pairing rule is deliberate:
        //  - same name as a sub-agent skill → that skill, with the
        //    configured DESCRIPTION as the routing text (the user wrote it
        //    to say WHEN to delegate there);
        //  - otherwise → the DEFAULT sub-agent skill (general-agent), with
        //    the configured INSTRUCTIONS appended as the mission.
        //
        // Before this, `SubAgentRoster.resolve` had ZERO call sites and
        // the `subagent_task` tool was never registered: the configured
        // roster was a complete data model with no runtime behind it.
        val delegation = SubagentDelegation.resolve(
            requested = skillName,
            skillRoster = deps.subagentRoster(),
            configured = deps.configuredSubagents(),
            skillExists = { n -> deps.findSkill(n) != null },
        )
        val skill = delegation?.let { deps.findSkill(it.skillName) }
            ?: deps.findSkill(skillName)
            ?: return ToolExecutionResult(
                // Name the valid alternatives. The parent's standing
                // instruction is "do not retry the same spawn", so a bare
                // "not found" makes it give up on the delegation entirely;
                // listing the roster lets it pick a real one in the SAME
                // turn.
                SubagentRosterHint.unknownAgentMessage(skillName, deps.subagentRoster()),
                false, toolTitle = title,
            )
        val config = SubagentSkill.parseSubagentConfig(skill)
        if (!config.isSubagent) {
            return ToolExecutionResult(
                "Error: Skill '$skillName' is not a sub-agent skill. " +
                    "Add `subagent: true` to its SKILL.md frontmatter to enable sub-agent mode." +
                    rosterSuffix(),
                false, toolTitle = title,
            )
        }

        if (!deps.isSkillEnabledForSession(skill.id)) {
            return ToolExecutionResult(
                "Error: Skill '$skillName' is disabled for this session" + rosterSuffix(),
                false, toolTitle = title,
            )
        }

        // [feat/subagent-roster-routing] The mission for this run: the
        // configured roster entry's instructions when the parent named a
        // configured speciality, empty for an ordinary skill spawn.
        val extraInstructions = delegation?.extraInstructions.orEmpty()

        // Fail-closed capability filter + explicit allowlist (validated
        // BEFORE registration so a misconfigured skill never creates a run).
        val subagentTools = SubagentSkill.buildFilteredTools(
            deps.agentTools(),
            config.allowedTools,
            // [feat/subagent-roster-routing] The blacklist applies to the
            // schema too — the model should not even SEE a tool the skill
            // denies. (The runtime gate enforces it regardless.)
            config.disallowedTools,
        )
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
        //
        // [T-subagent-model-spread] The argument may ALSO name a model group:
        // 'group:<name|id>' explicitly, or a bare name/id that matched no
        // model (cross-tier precedence — a value that matches any model stays
        // a model; see SubagentGroupSpread.resolveSpawnModelPlan).
        //
        // The runner itself NEVER chooses a member by mutable rule: for a
        // BATCHED spawn the conversation layer (ChatViewModel, which knows the
        // batch structure and emission order) precomputes one assignment per
        // call and the runner picks up its own by blockId — see
        // [Deps.groupSpreadAssignments]. This keeps children deterministic
        // under async fan-out. A lone call with no precomputed assignment
        // (manual dispatch, tests) uses the group's first usable member.
        val modelArg = args.optString("model", "").trim()
        val precomputed = deps.groupSpreadAssignments()[blockId]
        // [T-subagent-model-spread] Non-empty only when this spawn actually
        // landed on a group member via the batch plan — drives the approval
        // dialog text, the run's notice line, and the spawn result so the
        // parent can VERIFY the spread instead of trusting it.
        var spreadLabel = ""
        // Precedence: the BATCH PLAN wins when present. The conversation layer
        // only builds plans for calls it could resolve to a group it spreads
        // (inherited-model calls on a group-bound chat, and multi-spawn
        // batches whose `model` names a group) — so a plan is exactly the
        // deterministic member pick for THIS call, decided in emission order
        // before the async fan-out. Everything else falls through to the
        // explicit-model plan (single model = a pin; group = first member for
        // a lone spawn; unknown = fail loudly) and finally to plain inherit.
        val plan = if (modelArg.isEmpty()) null else SubagentGroupSpread.resolveSpawnModelPlan(
            rawModel = modelArg,
            modelCatalog = deps.modelCatalog(),
            groupLookup = { q -> deps.findModelGroup(q) },
            groupMembers = { gid -> deps.modelGroupMembers(gid) },
            knownGroupNames = { deps.modelGroupNames() },
        )
        val provider = when {
            precomputed != null -> buildSpreadProvider(blockId, precomputed)?.let { pick ->
                spreadLabel = "group:${precomputed.groupName} member " +
                    "${pick.index + 1}/${precomputed.members.size}" +
                    (if (pick.walkedForward > 0) {
                        " (assigned slot ${precomputed.memberIndex + 1} fell through)"
                    } else "")
                pick.provider
            } ?: parentProvider
            plan is SubagentGroupSpread.ModelPlan.SingleModel -> {
                val p = deps.providerForCandidate(plan.candidate)
                if (p == null) {
                    deps.recordSpreadAssignment(blockId, "single:${plan.candidate.describe()}:unbuildable")
                    return ToolExecutionResult(
                        "Error: model '${plan.candidate.describe()}' resolved but could not be " +
                            "instantiated (disabled provider or missing credential). Spawn aborted; " +
                            "no sub-agent run was created.",
                        false, toolTitle = title,
                    )
                }
                deps.recordSpreadAssignment(blockId, "single:${plan.candidate.describe()}:ok")
                p
            }
            plan is SubagentGroupSpread.ModelPlan.Mapped -> {
                // Explicit `group:` / bare-name pick WITHOUT a batch plan
                // (a lone spawn, or a group too small to rotate): "use this
                // group" for a single child means its first usable member.
                // A multi-spawn batch gets a plan instead — see the comment
                // above — so rotation applies there.
                val members = deps.modelGroupMembers(plan.groupId)
                val pick = members.firstOrNull()
                val p = pick?.let { deps.providerForCandidate(it) }
                if (p == null || pick == null) {
                    deps.recordSpreadAssignment(blockId, "group:${plan.groupName}:unusable")
                    return ToolExecutionResult(
                        "Error: model group '${plan.groupName}' has no member that could be " +
                            "instantiated (disabled provider or missing credential). Spawn " +
                            "aborted; no sub-agent run was created.",
                        false, toolTitle = title,
                    )
                }
                spreadLabel = "group:${plan.groupName} member 1/${members.size}"
                deps.recordSpreadAssignment(blockId, "group:${plan.groupName}→${pick.describe()}:1/${members.size}")
                p
            }
            plan is SubagentGroupSpread.ModelPlan.GroupUnusable -> {
                deps.recordSpreadAssignment(blockId, "group:unusable")
                return ToolExecutionResult("Error: ${plan.message}", false, toolTitle = title)
            }
            plan is SubagentGroupSpread.ModelPlan.Failed -> {
                deps.recordSpreadAssignment(blockId, "failed")
                return ToolExecutionResult(
                    "Error: ${plan.message} Spawn aborted; no sub-agent run was created.",
                    false, toolTitle = title,
                )
            }
            // `model` was non-blank and neither a model nor a group path
            // matched, so Inherit cannot happen here — treat as failure.
            plan is SubagentGroupSpread.ModelPlan.Inherit -> return ToolExecutionResult(
                "Error: an explicit model (or model group) could not be honoured in this " +
                    "host. Omit the 'model' argument to inherit the parent's model.",
                false, toolTitle = title,
            )
            // Plain inherit (no `model`, no batch plan) — pre-spread behaviour,
            // unchanged.
            else -> parentProvider
        }

        // [T-subagent-output-from-model] The per-turn output ceiling for every
        // request this run makes comes from the MODEL it will actually use:
        // 管理提供商 / Manage Providers → per-model "Max Output Tokens", folded
        // into provider.model by ModelEntry and read through
        // LLMProvider.effectiveMaxOutputTokens. It is the same single knob the
        // main loop's dynamicMaxTokens() sizes its turns from; the skill
        // frontmatter's `max_output_tokens` key was removed so the two can
        // never drift. (A spawn `model` argument routes to a different model —
        // the ceiling follows THAT model, not the parent's.) The per-turn
        // request additionally clamps into the remaining context window, see
        // [resolveSubagentTurnMaxTokens].
        val modelMaxOutputTokens = provider.effectiveMaxOutputTokens(provider.model)
        // Worst case a child can spend: every turn asking up to the (globally
        // capped) ceiling. Reserved against the parent's pool for the run's
        // lifetime, settled with the real usage at the end.
        val worstCaseChildTokens =
            config.maxTurns.toLong() * subagentOutputCeiling(modelMaxOutputTokens).toLong()

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
        // [fix/subagent-queue-budget] The run's wall-clock budget is NOT
        // consumed by the time it spends waiting for a concurrency slot. The
        // old code computed one absolute deadline at spawn time and handed it
        // to the scheduler, which used it for BOTH the queue wait and the
        // run — so a task that waited ten minutes for a slot started with
        // only five minutes of its fifteen-minute budget left, and then died
        // of "timeout" after doing almost nothing. The wait is a property of
        // the QUEUE; the run budget is a property of the TASK.
        //
        // `deadlineNanos` below is therefore only the QUEUE deadline: how
        // long this spawn may sit waiting for a slot. The run's own clock
        // starts when the run actually starts (see
        // [SubagentBudgetClock.startRun]).
        val deadlineNanos = System.nanoTime() + timeoutSeconds * 1_000_000_000L

        // [T-subagent-budget-inheritance] Pure check before any run exists
        // (F13): a parent on an expired shared deadline or with zero token
        // headroom must not spawn at all. Refusing BEFORE registration leaves
        // nothing behind — no run row, no pill, no journal. The reservation
        // itself is taken later, inside the loop's try, so a spawn that dies
        // before execution (queue timeout, cancel-before-start) cannot leak
        // one.
        deps.subagentBudgetGuard()?.spawnBlockReason()?.let { reason ->
            return ToolExecutionResult(
                "Error: sub-agent '$skillName' was not spawned — $reason " +
                    "Continue the work inline in this turn, or ask the user to " +
                    "compact / start a new chat.",
                false, toolTitle = "Sub-agent: $skillName",
            )
        }

        // [T-subagent-approval] Human gate, AFTER skill validation / model
        // routing / budget pre-check and BEFORE run registration: a denied
        // spawn must leave nothing behind (no run row, no pill, no journal,
        // no budget reservation). The user sees WHICH model will run and
        // whether it goes to the background before answering — the two
        // things that make a delegated autonomous loop risky. Always-allowed
        // skills skip the dialog entirely.
        //
        // [T-subagent-spawn-dedupe] A DUPLICATE of an in-flight task is
        // resolved BEFORE the gate: the model re-issuing the same task is not
        // a new decision, so it must not cost the user a new prompt. The
        // authoritative check-then-register still happens below (registerOrReuse
        // is atomic); this early probe only decides whether the human is
        // bothered at all.
        val earlyDuplicate = registry.findActiveDuplicate(skill.id, query)
        if (earlyDuplicate != null) {
            SubagentOrchestration.attachReusedRun(orchestration, earlyDuplicate.id, groupId)
            return deduplicatedSpawnResult(earlyDuplicate.id, skillName, batchGroupId = groupId)
        }
        if (!deps.isSkillAlwaysAllowed(skill.id)) {
            when (deps.awaitSpawnApproval(
                skillId = skill.id,
                skillName = skill.name,
                task = query,
                modelLabel = deps.providerLabel(provider) +
                    (if (spreadLabel.isNotBlank()) " [$spreadLabel]" else ""),
                detached = runUntil == SubagentSkill.RUN_UNTIL_DETACH,
            )) {
                SpawnDecision.DENY -> return ToolExecutionResult(
                    "Error: the user declined this sub-agent spawn. Do NOT retry " +
                        "the same spawn — the answer will not change by asking again. " +
                        "Do the work inline, or ask the user what they would prefer.",
                    false, toolTitle = "Sub-agent: $skillName",
                )
                SpawnDecision.ALWAYS_ALLOW -> deps.setSkillAlwaysAllowed(skill.id)
                SpawnDecision.ALLOW_ONCE -> Unit
            }
        }

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
            // [T-subagent-orchestration-fix] The deduped run still belongs to
            // THIS spawn batch's group: a join by group_id (or by "most
            // recent batch") must return this run's outcome too, or the model
            // collects fewer reports than tasks it dispatched. Membership
            // only — the existing job handle owns the result delivery.
            SubagentOrchestration.attachReusedRun(orchestration, outcome.run.id, groupId)
            return deduplicatedSpawnResult(outcome.run.id, skillName, batchGroupId = groupId)
        }
        val run = outcome.run
        val job = SubagentOrchestration.SubagentJob(
            runId = run.id,
            skillId = skill.id,
            skillName = skill.name,
            detached = runUntil == SubagentSkill.RUN_UNTIL_DETACH,
        )
        // [T-subagent-orchestration-fix] THE missing wiring: attach to the
        // spawn batch's group AND register the job handle. Before this the
        // group registry was empty in production, so every group-addressed
        // join/wait/cancel (and the "most recent batch" default) failed with
        // "No matching sub-agent runs found" while the spawn tool result kept
        // advertising a group_id that could never resolve.
        SubagentOrchestration.registerSpawn(orchestration, job, groupId)

        if (runUntil == SubagentSkill.RUN_UNTIL_DETACH) {
            // [T-subagent-orchestration] Fire-and-forget: launch on the
            // orchestration scope (outlives stream cancel, dies with the VM)
            // and return the handle immediately. The parent collects via
            // join_subagents / wait_any or discards via cancel_subagents.
            val detachedJob = deps.orchestrationScope().launch {
                try {
                    scheduler.run(skill.id, config.maxParallel, deadlineNanos) {
                        registry.markExecuting(run.id)
                        executeLoopGuarded(run, worstCaseChildTokens) {
                            executeLoop(
                                run = run, skill = skill, config = config, skillName = skillName,
                                query = query, title = title, runUntil = SubagentSkill.RUN_UNTIL_DONE,
                                sessionId = sessionId, provider = provider, subagentTools = subagentTools,
                                timeoutSeconds = timeoutSeconds,
                                // [fix/subagent-queue-budget] The run's own
                                // clock starts HERE — when the run actually
                                // begins — not when the spawn was requested.
                                // The time spent waiting for a concurrency
                                // slot is the queue's cost, not the task's.
                                deadlineNanos = SubagentBudgetClock.startRun(timeoutSeconds).runDeadlineNanos,
                                modelMaxOutputTokens = modelMaxOutputTokens,
                                spreadLabel = spreadLabel,
                                extraInstructions = extraInstructions,
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    // cancel_subagents completed the deferred first, then
                    // cancelled this coroutine (or the VM is being torn down).
                    // executeLoop reconciles the registry when it was running;
                    // reconcile here for the never-started case.
                    registry.finishIfActive(
                        run.id, SubagentRunRegistry.RunStatus.CANCELLED,
                        error = e.message ?: "detached run cancelled",
                        // [fix/subagent-verdict-and-report-gate] A cancelled
                        // run delivered nothing — say so on the detached
                        // paths instead of letting the snapshot read as a
                        // plain success.
                        outcome = SubagentRunAssessment.Outcome.FAILED,
                        reportSource = SubagentRunAssessment.ReportSource.NONE,
                        // [fix/subagent-verdict-and-report-gate] The loop's
                        // counter is not in scope here (it lives inside the
                        // run loop); the detached path reads its state from
                        // the registry snapshot, so read it from there too.
                        executedToolCalls = registry.runs.value
                            .firstOrNull { it.id == run.id }?.executedToolCalls ?: 0,
                    )
                    journal(run, "CANCELLED", "", 0, e.message ?: "cancelled", emptyList(), sessionId)
                } catch (e: Exception) {
                    AppLogger.warning(TAG, "[Subagent] detached '$skillName' crashed: ${e.message}")
                    // [fix/subagent-verdict-consistency-v3] The cancel branch
                    // above journals its terminal record; this branch did not,
                    // so a crashed detached run left NO durable trace — the
                    // registry said FAILED while `.subagent/<runId>.md` did not
                    // exist and the `.ckpt` was never swept. The failure
                    // reason was unrecoverable after the fact.
                    val crashError = e.message ?: e.javaClass.simpleName
                    registry.finishIfActive(
                        run.id, SubagentRunRegistry.RunStatus.FAILED,
                        error = crashError,
                        // [fix/subagent-verdict-and-report-gate] Same for a
                        // crashed detached run.
                        outcome = SubagentRunAssessment.Outcome.FAILED,
                        reportSource = SubagentRunAssessment.ReportSource.NONE,
                        // [fix/subagent-verdict-and-report-gate] Same scope
                        // reason as the cancel path above.
                        executedToolCalls = registry.runs.value
                            .firstOrNull { it.id == run.id }?.executedToolCalls ?: 0,
                    )
                    // Journal the crash so the durable record matches the
                    // registry's FAILED state.
                    journal(run, "FAILED", "", 0, crashError, emptyList(), sessionId)
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
                    if (spreadLabel.isNotBlank()) append("\nmodel: $spreadLabel")
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
                    val result = executeLoopGuarded(run, worstCaseChildTokens) {
                        executeLoop(
                            run = run, skill = skill, config = config, skillName = skillName,
                            query = query, title = title, runUntil = runUntil,
                            sessionId = sessionId, provider = provider, subagentTools = subagentTools,
                            timeoutSeconds = timeoutSeconds,
                            // [fix/subagent-queue-budget] The run's own
                            // clock starts HERE — when the run actually
                            // begins — not when the spawn was requested.
                            // The time spent waiting for a concurrency
                            // slot is the queue's cost, not the task's.
                            deadlineNanos = SubagentBudgetClock.startRun(timeoutSeconds).runDeadlineNanos,
                            modelMaxOutputTokens = modelMaxOutputTokens,
                            spreadLabel = spreadLabel,
                            extraInstructions = extraInstructions,
                        )
                    }
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
                            // [fix/subagent-verdict-and-report-gate] Carry
                            // the verdict on the inline path too — the same
                            // JobOutcome type serves both paths, and a
                            // detached run that was joined later must not
                            // lose the verdict on the way through. The
                            // verdict is not on ToolExecutionResult; the
                            // runner wrote it into the registry snapshot on
                            // its terminal write, so read it from there.
                            outcome = registry.runs.value.firstOrNull { it.id == run.id }?.runOutcome
                                ?: SubagentRunAssessment.Outcome.COMPLETED,
                            reportSource = registry.runs.value.firstOrNull { it.id == run.id }?.reportSource
                                ?: SubagentRunAssessment.ReportSource.MODEL,
                            executedToolCalls = registry.runs.value
                                .firstOrNull { it.id == run.id }?.executedToolCalls ?: 0,
                        ),
                    )
                    result
                } catch (e: CancellationException) {
                    job.deferred.complete(
                        SubagentOrchestration.JobOutcome(
                            runId = run.id, success = false, report = "",
                            skillName = skill.name, cancelled = true,
                            error = e.message ?: "cancelled",
                            // [fix/subagent-verdict-consistency-v3] A cancelled
                            // run delivered no work. Leaving the default
                            // (COMPLETED + MODEL) would tell a later join
                            // that the model wrote a report.
                            outcome = SubagentRunAssessment.Outcome.FAILED,
                            reportSource = SubagentRunAssessment.ReportSource.NONE,
                        ),
                    )
                    throw e
                } catch (e: Exception) {
                    job.deferred.complete(
                        SubagentOrchestration.JobOutcome(
                            runId = run.id, success = false, report = "",
                            skillName = skill.name,
                            error = e.message ?: e.javaClass.simpleName,
                            // [fix/subagent-verdict-consistency-v3] The
                            // default JobOutcome verdict is COMPLETED/MODEL
                            // — "the model wrote a report". This branch is
                            // the opposite: the loop threw, nothing was
                            // delivered. Carry the truth so a later join or
                            // wait_any does not report the crash as a
                            // successful run.
                            outcome = SubagentRunAssessment.Outcome.FAILED,
                            reportSource = SubagentRunAssessment.ReportSource.NONE,
                        ),
                    )
                    throw e
                }
            }
        } catch (e: SubagentScheduler.QueueTimeoutException) {
            registry.finishIfActive(
                run.id, SubagentRunRegistry.RunStatus.TIMED_OUT,
                error = e.message,
                // [fix/subagent-verdict-and-report-gate] The run never
                // started — it consumed no tokens and did no work.
                outcome = SubagentRunAssessment.Outcome.TIMED_OUT,
                reportSource = SubagentRunAssessment.ReportSource.NONE,
                executedToolCalls = 0,
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
     * [T-subagent-model-spread] Result of building a batch assignment: the
     * provider plus WHICH member it actually landed on (the assigned one, or
     * a walk-forward survivor).
     */
    private data class SpreadPick(
        val provider: com.openminis.app.provider.LLMProvider,
        val candidate: SubagentModelResolver.Candidate,
        val index: Int,
        val walkedForward: Int,
    )

    /**
     * [T-subagent-model-spread] Build the provider for a precomputed batch
     * assignment: try the assigned member, walk FORWARD through the rest of
     * the snapshot (a key removed between plan time and dispatch must not fail
     * the whole batch), and return null when no member builds — the caller
     * then falls back to the parent provider, preserving pre-spread
     * behaviour as the floor.
     */
    private fun buildSpreadProvider(
        blockId: String,
        assignment: SubagentGroupSpread.Assignment,
    ): SpreadPick? {
        val n = assignment.members.size
        if (n == 0) return null
        for (step in 0 until n) {
            val idx = (assignment.memberIndex + step) % n
            val candidate = assignment.members[idx]
            val built = deps.providerForCandidate(candidate)
            if (built != null) {
                AppLogger.info(
                    TAG,
                    "[Subagent] spread '${assignment.groupName}': run on " +
                        "${candidate.describe()} (member ${idx + 1}/$n" +
                        (if (step > 0) ", walked forward $step from assigned slot)" else ")"),
                )
                deps.recordSpreadAssignment(
                    blockId,
                    "spread:${assignment.groupName}→${candidate.describe()}:${idx + 1}/$n",
                )
                return SpreadPick(
                    provider = built,
                    candidate = candidate,
                    index = idx,
                    walkedForward = step,
                )
            }
        }
        AppLogger.warning(
            TAG,
            "[Subagent] spread '${assignment.groupName}': no member could be instantiated — " +
                "falling back to the parent provider",
        )
        deps.recordSpreadAssignment(blockId, "spread:${assignment.groupName}:fallback-parent")
        return null
    }

    /**
     * [T-subagent-spawn-dedupe] Model-facing answer for a duplicate spawn:
     * the identical task already has an active run — hand back ITS handle
     * so the model joins / waits / cancels that run instead of executing
     * the same work twice. Works for both inline and detached spawns: the
     * job deferred completes on every exit path, so join_subagents can
     * collect an inline run's result later too.
     */
    private fun deduplicatedSpawnResult(
        existingRunId: String,
        skillName: String,
        batchGroupId: String = "",
    ): ToolExecutionResult {
        val snapshot = registry.runs.value.firstOrNull { it.id == existingRunId }
        val statusDesc = if (snapshot?.isQueued == true) "queued (waiting for a scheduler slot)" else "running"
        return ToolExecutionResult(
            output = buildString {
                append("Deduplicated: an identical sub-agent task is already $statusDesc — ")
                append("no new run was created.")
                append("\nrun_id: $existingRunId")
                snapshot?.groupId?.takeIf { it.isNotEmpty() }?.let { append("\ngroup_id: $it") }
                // [T-subagent-orchestration-fix] batchGroupId is the batch THIS
                // duplicate call arrived with. The reused run was just attached
                // to it, but the run row still carries its ORIGINAL group — so
                // naming only one would send a group-addressed join at the
                // wrong batch or lose the original. Both memberships are true;
                // both are stated.
                if (batchGroupId.isNotEmpty() && batchGroupId != snapshot?.groupId) {
                    append("\nbatch_group_id: $batchGroupId — this call's batch; the run above is a member of it too, ")
                    append("so joining either group returns this run")
                }
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
            // [fix/subagent-verdict-and-report-gate] Read the verdict back
            // from the registry snapshot. Without this the detached path
            // could only see `status == SUCCESS`, so every detached run
            // reached the parent as an ordinary success — UNSTARTED,
            // DEGRADED and NO_OUTPUT were all flattened into "succeeded".
            //
            // [fix/subagent-verdict-consistency-v3] A MISSING snapshot is a
            // third case, distinct from both: the registry row was pruned
            // before the terminal write could be read back. The old
            // fallback said COMPLETED + MODEL while `success` was false —
            // "failed, but the model wrote a report". Unknown is the honest
            // answer, and UNKNOWN tells the parent not to trust any text
            // that arrives with it.
            outcome = snapshot?.runOutcome ?: SubagentRunAssessment.Outcome.UNKNOWN,
            reportSource = snapshot?.reportSource
                ?: SubagentRunAssessment.ReportSource.NONE,
            executedToolCalls = snapshot?.executedToolCalls ?: 0,
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
     * [T-subagent-budget-inheritance] Wrap [executeLoop] with the parent
     * budget's reserve/settle pair. A wrapper rather than per-return-path
     * calls: the loop has five exits (success, max-turns, TIMED_OUT, cancel,
     * fatal) and every one of them must settle — a leaked reservation would
     * permanently shrink the parent's pool, the exact accounting bug this
     * feature exists to prevent.
     *
     * Actual spend is read from the registry's atomic usage totals at settle
     * time, NOT from a local accumulator — the same -1-means-unknown rule
     * applies, so an unreported run nets out to a bare release.
     *
     * [T-subagent-output-from-model] The worst-case estimate is passed in by
     * the spawn site (maxTurns × the run model's effective output ceiling),
     * NOT read off the skill config — the output dimension now lives on the
     * model (管理提供商 → Max Output Tokens), and the spawn site is the only
     * place that has resolved which model the run will use.
     */
    private suspend fun executeLoopGuarded(
        run: SubagentRunRegistry.Run,
        worstCaseChildTokens: Long,
        block: suspend () -> ToolExecutionResult,
    ): ToolExecutionResult {
        val guard = deps.subagentBudgetGuard()
        val reserved = guard != null
        if (reserved) {
            guard!!.reserve(worstCaseChildTokens)
        }
        try {
            return block()
        } finally {
            if (reserved) {
                val snapshot = registry.runs.value.firstOrNull { it.id == run.id }
                val actual = snapshot?.let {
                    it.tokensIn.coerceAtLeast(0).toLong() + it.tokensOut.coerceAtLeast(0).toLong()
                } ?: 0L
                runCatching { guard!!.settle(actual) }
            }
        }
    }

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
        /**
         * [T-subagent-output-from-model] Per-turn output ceiling resolved at
         * the spawn site from the run's ACTUAL model (管理提供商 → Max Output
         * Tokens → effectiveMaxOutputTokens). Each request further clamps it
         * into the remaining context window, see [resolveSubagentTurnMaxTokens].
         */
        modelMaxOutputTokens: Int,
        /**
         * [feat/subagent-roster-routing] The CONFIGURED roster entry's
         * instructions for this delegation. Empty for an ordinary skill
         * spawn. Appended to the skill body in the system prompt, so the
         * mission the user configured reaches the model that does the work.
         */
        extraInstructions: String = "",
        /**
         * [T-subagent-model-spread] "group:<name> member k/n" when this run
         * landed on a group member via the batch plan; empty for ordinary
         * spawns. Folded into the run's model label so the detail page and
         * every downstream surface show the same story.
         */
        spreadLabel: String = "",
    ): ToolExecutionResult {
        // [T-subagent-runtime-preamble] Inject a short runtime preamble
        // (current date/time + durable artifacts root) ahead of the skill
        // body. Sub-agents run without any shell-derived context and used
        // to burn their first turn discovering what day it is.
        //
        // [T-subagent-dir-isolation] The run gets its OWN artifacts
        // directory (/var/minis/workspace/subagents/<runId>/), created
        // eagerly so even a one-shot `file_write report.md` relative fallback
        // cannot scatter files into the shared root. Concurrent runs writing
        // same-named deliverables (`report.md`, `data.csv`) were the exact
        // collision this closes: with N parallel research agents, last
        // writer won and the parent could not attribute a file to a run.
        val artifactsDir = SubagentSkill.artifactsDirFor(run.id)
        runCatching {
            val dirFile = PRootKernel.resolveSessionHostPath(sessionId, artifactsDir, context)
            if (dirFile != null) dirFile.mkdirs()
        }
        val systemPrompt = SubagentSkill.buildSystemPrompt(
            skill,
            runtimeContext = SubagentSkill.buildRuntimeContext(
                runId = run.id,
                // [fix/subagent-budget-visibility] The skill body tells the
                // model to stay within its turn budget, but the budget lives
                // in the frontmatter that buildSystemPrompt strips — so the
                // model was told to respect a limit it was never given.
                maxTurns = config.maxTurns,
                timeoutSeconds = timeoutSeconds,
            ),
            // [feat/subagent-roster-routing] The CONFIGURED roster entry's
            // instructions — the speciality the user wrote for this agent.
            // Appended to the skill body, so the runtime contract (how to
            // work) and the mission (what to do) both survive.
            extraInstructions = extraInstructions,
        )
        // [fix/subagent-plan-first] When the skill keeps plan_first on (the
        // default), the FIRST turn is a planning turn: the model must lay out
        // its intended flow in a <task-plan> block before touching any tool.
        // The plan is extracted into the registry and rendered as a checklist
        // on the detail page — the user sees "what will happen" before it
        // happens. A skill can opt out with `plan_first: false`.
        val planFirst = config.planFirst
        val systemPromptWithPlan = if (planFirst) {
            "$systemPrompt\n\n${SubagentSkill.PLAN_DIRECTIVE}"
        } else systemPrompt
        // [T-subagent-model-routing] Record what actually ran. A parent that
        // asked for a cheap model must be able to verify it got one.
        // [T-subagent-model-spread] A spread run additionally says WHICH group
        // member it took — the "did 1 号 really get a, 2 号 b?" check.
        registry.setModelLabel(
            run.id,
            deps.providerLabel(provider) +
                (if (spreadLabel.isNotBlank()) " [$spreadLabel]" else ""),
        )
        val history = mutableListOf(LLMMessage(role = LLMMessage.Role.USER, content = query))

        // [T-subagent-run-timeout] The absolute [deadlineNanos] arrives from
        // the spawn site — but since [fix/subagent-queue-budget] it is
        // measured from the moment the RUN STARTED, not from the spawn
        // request, so time spent queueing for a concurrency slot does not
        // eat the run's budget. Enforced at TURN boundaries only: a single
        // hung shell_execute is already capped at 900s by
        // executeSubagentShell, and an interrupt mid-stream would corrupt the
        // history. The guard this adds is the missing one — an unbounded
        // number of turns, which nothing capped before.

        val resultSb = StringBuilder()
        val artifacts = mutableListOf<String>()
        var turns = 0
        var lastText = ""
        // [fix/subagent-plan-execute] True once a tool-less PLAN turn was
        // answered with an execution nudge. Bounds the nudge to one per run.
        var planNudgeIssued = false
        // [fix/subagent-plan-execute] Count of tool calls that actually
        // EXECUTED (not merely proposed). Terminal reporting uses it to
        // refuse to call a plan-only run a success: before this, a run that
        // never touched a tool reached the parent as "completed" with a plan
        // as its findings, and the parent had no machine-visible signal that
        // nothing had been done.
        var executedToolCalls = 0
        // [T-subagent-thinking] Resolve the reasoning level ONCE, before the
        // loop, and record the outcome on the run. The value that reaches the
        // provider may legitimately differ from the skill's request (the
        // model may not support reasoning), and a silent downgrade is
        // indistinguishable from a bug — the meta chip on the detail page
        // makes the effective choice visible instead.
        val reasoningLevel = resolveSubagentThinkingLevel(
            requested = config.thinkingLevel,
            modelSupportsReasoning = provider.model.supportsReasoning,
        )
        registry.setReasoningEnabled(run.id, reasoningLevel.isEnabled)
        if (config.thinkingLevel.isEnabled && !reasoningLevel.isEnabled) {
            registry.appendNotice(
                run.id,
                "[thinking: requested ${config.thinkingLevel.displayName} but " +
                    "${provider.model.displayName} does not support reasoning — ran with it off]",
            )
        }
        // [T-subagent-loop-detector] A per-run loop detector with the SAME
        // semantics the main loop uses (10 warnings / 20 blocks, poll-aware).
        //
        // Why a sub-agent needs one: its only stop conditions are maxTurns and
        // the wall clock, so a tool that keeps failing the same way (a command
        // that always errors, a URL that never loads, a path that never
        // exists) burns the ENTIRE run budget — every turn costs a model call
        // and produces nothing — and the parent receives a TIMED_OUT report
        // with no findings. The main loop is protected from exactly this by
        // ToolLoopDetector; delegating a task must not mean losing that
        // protection. Scope is per run (the detector is not thread-safe by
        // design and a sub-agent loop is single-threaded), so no session state
        // leaks in from the parent.
        val loopDetector = com.openminis.app.agent.ToolLoopDetector()
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
                    // [feat/subagent-roster-routing] P1-4 — a TIMED OUT run
                    // must deliver its evidence too. Same defect class as
                    // the crash path: the accumulated text is often empty for
                    // a tool-driven run, so the parent received "timed out,
                    // no report" and the journal held the only copy.
                    val timeoutSteps = registry.runs.value.firstOrNull { it.id == run.id }?.steps.orEmpty()
                    val recovered = SubagentPartialRecovery.recover(
                        accumulatedText = resultSb.toString(),
                        steps = timeoutSteps.map { step ->
                            SubagentPartialRecovery.Step(
                                toolName = step.toolName,
                                toolTitle = step.toolTitle,
                                success = step.status == SubagentRunRegistry.ToolStepStatus.SUCCESS,
                                output = step.output,
                            )
                        },
                        stopReason = "TIMED OUT after ${timeoutSeconds}s",
                    )
                    val partial = recovered.report
                    AppLogger.warning(TAG, "[Subagent] '$skillName' exceeded ${timeoutSeconds}s run budget at turn $turns")
                    registry.finishIfActive(
                        run.id, SubagentRunRegistry.RunStatus.TIMED_OUT,
                        resultText = partial,
                        error = "exceeded the $timeoutSeconds-second run budget after $turns turn(s)",
                        // [fix/subagent-verdict-and-report-gate] Persist the
                        // verdict so the detached paths report TIMED_OUT
                        // rather than a bare success.
                        outcome = SubagentRunAssessment.Outcome.TIMED_OUT,
                        reportSource = recovered.source,
                        executedToolCalls = executedToolCalls,
                    )
                    val reconciledPartial = reconcileArtifactPaths(partial, artifacts, run.id)
                    val timedOutResult = SubagentResult(
                        status = SubagentResult.Status.FAILED,
                        report = reconciledPartial,
                        outcome = SubagentRunAssessment.Outcome.TIMED_OUT,
                        reportSource = recovered.source,
                        executedToolCalls = executedToolCalls,
                        turns = turns,
                        maxTurns = config.maxTurns,
                        skillId = skill.id,
                        skillName = skill.name,
                        journalPath = journal(
                            run, "TIMED_OUT", reconciledPartial, turns,
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
                    registry.finishIfActive(
                        run.id, SubagentRunRegistry.RunStatus.FAILED,
                        error = "No provider instance context",
                        // [fix/subagent-verdict-and-report-gate]
                        outcome = SubagentRunAssessment.Outcome.FAILED,
                        reportSource = SubagentRunAssessment.ReportSource.NONE,
                        executedToolCalls = executedToolCalls,
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
                    // [T-subagent-chat-stream] Snapshot the transcript position
                    // BEFORE this attempt writes anything. A transient failure
                    // mid-stream (SSL reset, worker death) leaves the deltas
                    // the attempt already emitted in the registry — without a
                    // rollback the retry would append them AGAIN, duplicating
                    // every narration / reasoning row of the turn.
                    val attemptSnap = registry.turnSnapshot(run.id)
                    val attemptToolIds = mutableListOf<String>()
                    try {
                        ProviderExecutionGateway.stream(
                            context = context,
                            instance = instance,
                            model = provider.model,
                            messages = history.toList(),
                            systemPrompt = systemPromptWithPlan,
                            // [T-subagent-output-from-model] Per-turn output
                            // ceiling = the run model's configured Max Output
                            // Tokens, clamped into the remaining context window
                            // (same sizing the main loop's dynamicMaxTokens
                            // applies). Recomputed per attempt so a retry after
                            // a Usage chunk uses the latest context figure.
                            maxTokens = resolveSubagentTurnMaxTokens(
                                modelMaxOutputTokens = modelMaxOutputTokens,
                                contextWindowTokens = provider.model.contextWindowTokens,
                                lastContextTokens = lastContextTokens,
                            ),
                            temperature = null,
                            tools = subagentTools,
                            thinkingLevel = reasoningLevel,
                        ).collect { chunk ->
                            when (chunk) {
                                is LLMStreamChunk.Text -> {
                                    textSb.append(chunk.text)
                                    registry.appendResultText(run.id, chunk.text)
                                    // [T-subagent-chat-stream] Same delta also
                                    // extends the transcript, so narration and
                                    // tool pills keep their true order.
                                    registry.appendSegmentText(run.id, chunk.text)
                                }
                                // [T-subagent-thinking] Reasoning used to be
                                // dropped on the floor here (it shares the
                                // `else -> {}` branch below). It is captured
                                // separately from the answer — chain-of-
                                // thought must never leak into the report the
                                // parent consumes.
                                is LLMStreamChunk.ThinkingDelta -> {
                                    registry.appendSegmentThinking(run.id, chunk.text)
                                }
                                is LLMStreamChunk.ReasoningContent -> {
                                    // Accumulated blob echoed at stream end.
                                    // OpenAI-family providers ALSO stream the
                                    // same reasoning as ThinkingDelta events,
                                    // so the blob is a duplicate of what the
                                    // deltas already delivered — dedupe=true
                                    // makes it a no-op in that case while
                                    // still accepting new text from providers
                                    // that ONLY send the blob.
                                    registry.appendSegmentThinking(
                                        run.id,
                                        chunk.content,
                                        dedupe = true,
                                    )
                                }
                                is LLMStreamChunk.ToolCallComplete -> {
                                    toolCalls.add(SubagentToolCall(chunk.id, chunk.name, chunk.args))
                                    attemptToolIds.add(chunk.id)
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
                                    // [T-subagent-output-from-model] Mirrors the
                                    // main loop's rule: trust the API-reported
                                    // context size; when a provider omits
                                    // latestContextTokens, recover it from fresh
                                    // input + cache tokens (inputTokens is
                                    // fresh-only — the cached portion is already
                                    // subtracted). Without this a cache-hit
                                    // provider leaves lastContextTokens at 0 and
                                    // every turn asks for the FULL output ceiling
                                    // even when the window is nearly full.
                                    val ctx = subagentContextTokensFromUsage(chunk.usage)
                                    if (ctx > 0) {
                                        lastContextTokens = ctx
                                    }
                                }
                                else -> {}
                            }
                        }
                        break  // stream finished cleanly — exit the retry loop
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val actual = deps.unwrapFlowException(e)
                        // [T-provider-verbatim-errors] Structured status, not a
                        // regex over the displayed text — see the matching
                        // comment in ChatViewModel's runAgentLoop catch block.
                        val is5xx = (actual as? com.openminis.app.data.model.LLMError)?.isServerError == true
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
                            // "report" was nothing but retry chatter). The
                            // notice is written AFTER the segment rollback
                            // below so the retry cannot wipe it — notices and
                            // segments are separate fields, but sequencing the
                            // rollback first keeps the intent obvious.
                            registry.appendNotice(
                                run.id,
                                "[transient stream error ($errDesc) — retrying " +
                                    "$streamRetryAttempt/${STREAM_RETRY_DELAYS_SEC.size} in ${delaySec}s]",
                            )
                            // [T-subagent-chat-stream] Roll back this attempt's
                            // partial transcript + tool rows before the retry,
                            // so the retried stream does not double them. The
                            // notice ABOVE is written after the rollback would
                            // wipe it — notices live outside segments, so the
                            // order here is: rollback segments, then the
                            // notice (already appended before this line) is
                            // untouched because resetTurn only touches
                            // segments/steps.
                            registry.resetTurn(run.id, attemptSnap, attemptToolIds)
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

                // [fix/subagent-plan-first] Scan the turn's narration for a
                // <task-plan> block. The FIRST emission sets the plan; a
                // later block REPLACES it (the directive tells the model to
                // re-emit when its flow changes materially — the display
                // follows the newest intent). The raw block is stripped from
                // the streamed transcript segments so the plan renders as a
                // dedicated checklist, not a stray XML chunk in the prose —
                // the narration text already consumed by the registry is
                // rewritten via the same segment-replace path the retry
                // rollback uses.
                var planEmittedThisTurn = false
                if (planFirst && text.contains("<${SubagentSkill.PLAN_TAG}>")) {
                    val extracted = SubagentSkill.extractTaskPlan(text)
                    if (extracted != null) {
                        val (planText, cleanedText) = extracted
                        planEmittedThisTurn = true
                        registry.setTaskPlan(run.id, SubagentSkill.parseTaskPlanItems(planText))
                        if (cleanedText != text.trim()) {
                            // The report accumulator ALSO carries the block —
                            // a wake-up/join report that quotes the plan is
                            // noise for the parent (the plan is display
                            // state). Keep both mirrors consistent.
                            registry.replaceTurnNarration(run.id, turns, cleanedText)
                            registry.replaceResultText(run.id, cleanedText)
                            // [fix/subagent-plan-execute] Keep the REPORT
                            // accumulator in sync too. `resultSb` was appended
                            // before extraction, so a plan-only run handed the
                            // parent a literal "<task-plan>…</task-plan>"
                            // block as its findings. Rewriting the tail (not
                            // appending) preserves earlier turns' text.
                            if (resultSb.isNotEmpty()) {
                                val tailStart = resultSb.length - text.length
                                if (tailStart >= 0) {
                                    resultSb.setLength(tailStart)
                                    if (cleanedText.isNotBlank()) resultSb.append(cleanedText)
                                }
                            }
                        }
                    }
                }

                if (toolCalls.isEmpty()) {
                    // [fix/subagent-plan-execute] A tool-less turn is NOT
                    // automatically "done". plan_first tells the model to stop
                    // after outlining, so the FIRST tool-less turn of a
                    // plan-first run is the plan turn — breaking here ended
                    // the run before a single tool ran (the model obeyed the
                    // directive and the parent got a plan as its only
                    // deliverable). The gate keeps that turn non-terminal and
                    // injects a directive nudge; it fires at most once per
                    // run, so a model that keeps talking still terminates.
                    val planCaptured = registry.runs.value
                        .firstOrNull { it.id == run.id }?.taskPlan.orEmpty().isNotEmpty()
                    val decision = SubagentPlanGate.decide(
                        planFirst = planFirst,
                        planCaptured = planCaptured,
                        emittedPlanThisTurn = planEmittedThisTurn,
                        nudgeIssued = planNudgeIssued,
                        maxTurnsReached = turns >= config.maxTurns,
                    )
                    if (decision == SubagentPlanGate.Decision.NUDGE_TO_EXECUTE) {
                        planNudgeIssued = true
                        val nudge = SubagentPlanGate.nudgeText(planEmittedThisTurn)
                        AppLogger.info(
                            TAG,
                            "[Subagent] '$skillName' plan-only turn $turns — nudging to execute " +
                                "(planEmitted=$planEmittedThisTurn)",
                        )
                        registry.appendNotice(
                            run.id,
                            if (planEmittedThisTurn) {
                                "[plan] recorded — execution nudge issued (turn $turns produced no tool call)"
                            } else {
                                "[plan] none emitted — execution nudge issued (turn $turns produced no tool call)"
                            },
                        )
                        // Balanced history: the assistant's tool-less turn must
                        // be followed by a user turn, otherwise the wire
                        // alternation breaks on the next request.
                        history.add(
                            LLMMessage(
                                role = LLMMessage.Role.ASSISTANT,
                                content = text,
                            ),
                        )
                        history.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = nudge,
                            ),
                        )
                        continue
                    }
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
                //
                // [T-subagent-loop-detector] Each call is pre-checked against
                // the run's detector exactly as the main loop does: CRITICAL
                // short-circuits execution and returns a synthesized error
                // result (keeping the tool_use/tool_result pairing balanced),
                // so a stuck loop stops COSTING instead of merely being
                // warned about. WARNING outcomes are appended to the result so
                // the model sees them on its next turn.
                val calls = toolCalls.map { ToolBatchExecutor.Call(it.id, it.name, it.args.toString()) }
                // [fix/subagent-verdict-and-report-gate] Calls the loop
                // detector short-circuited at CRITICAL level. The detector
                // returns a synthesized error result WITHOUT running the tool,
                // so these calls are NOT "executed" — counting them as such
                // made a run that never touched the filesystem report
                // COMPLETED. Measured: 3 calls all blocked → executedToolCalls
                // was 3 → outcome COMPLETED (deliverable), while the truth is
                // UNSTARTED (nothing ran). The counter's own comment claimed
                // it counted "actually EXECUTED" calls — the implementation
                // did not match the comment.
                val blockedCallIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
                val results = ToolBatchExecutor.executeBatched(calls) { call ->
                    val paramsMap = try {
                        val o = call.argsJson.let { org.json.JSONObject(it) }
                        buildMap<String, Any?> {
                            val keys = o.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                put(k, if (o.get(k) == org.json.JSONObject.NULL) null else o.get(k))
                            }
                        }
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    val precheck = loopDetector.check(call.name, paramsMap)
                    if (precheck.level == com.openminis.app.agent.Level.CRITICAL) {
                        val blocked = precheck.message ?: "[LOOP BLOCKED] tool execution blocked"
                        AppLogger.warning(
                            TAG,
                            "[Subagent] '$skillName' tool BLOCKED by loop detector " +
                                "name=${call.name} reason=$blocked",
                        )
                        registry.appendNotice(run.id, "[loop blocked: ${call.name}]")
                        // [fix/subagent-verdict-and-report-gate] Record the
                        // short-circuit so the executed-call count can exclude
                        // it — the tool below never ran.
                        blockedCallIds.add(call.id)
                        ToolExecutionResult(blocked, false, toolTitle = call.name)
                    } else {
                        // [fix/subagent-tool-preflight] Validate the call
                        // BEFORE dispatching it, exactly like the main agent
                        // does. The sub-agent runner had no preflight: a
                        // malformed call was dispatched and only failed
                        // inside the tool, so the model spent a turn (of a
                        // scarce budget) learning that `path` was missing.
                        // The error comes back as the tool result, so the
                        // model can correct itself on the NEXT turn — the
                        // same feedback loop the main agent has.
                        val preflightError = preflightErrorFor(call, subagentTools)
                        if (preflightError != null) {
                            AppLogger.warning(
                                TAG,
                                "[Subagent] '$skillName' tool REJECTED by preflight " +
                                    "name=${call.name} reason=$preflightError",
                            )
                            registry.appendNotice(run.id, "[preflight rejected: ${call.name}]")
                            // The tool did NOT run — exclude it from the
                            // executed-call count, same as a loop-detector
                            // short-circuit.
                            blockedCallIds.add(call.id)
                            ToolExecutionResult(preflightError, false, toolTitle = call.name)
                        } else {
                            executeSubagentTool(
                                name = call.name,
                                argsJson = call.argsJson,
                                callId = call.id,
                                runId = run.id,
                                artifacts = artifacts,
                                // [fix/subagent-allowed-tools-enforcement] The
                                // skill's allowlist travels with the run, so the
                                // runtime gate sees the same list the schema
                                // filter used.
                                allowedTools = config.allowedTools,
                                // [feat/subagent-roster-routing] The blacklist
                                // travels too, so the runtime gate can deny a
                                // tool the schema filter already hid.
                                disallowedTools = config.disallowedTools,
                            )
                        }
                    }
                }
                // [fix/subagent-verdict-and-report-gate] Count only the calls
                // that ACTUALLY executed. A CRITICAL short-circuit never ran
                // its tool, so it must not push the run out of UNSTARTED.
                executedToolCalls = SubagentExecutedCallCount.add(
                    current = executedToolCalls,
                    proposedCallIds = toolCalls.map { it.id },
                    blockedCallIds = blockedCallIds,
                )
                // [fix/subagent-parallel-tool-results] Collect the turn's
                // results; they are emitted as ONE user message after the
                // loop (see the comment at the append site below).
                val resultParts = mutableListOf<AgentContentPart.ToolResult>()
                for ((call, result) in toolCalls.zip(results)) {
                    val resultContent = if (result.success) result.output else "Error: ${result.output}"
                    registry.stepFinished(
                        run.id, call.id, result.success,
                        // [fix/subagent-step-output-parity] The PREVIEW keeps
                        // the head for every tool except shell_execute, which
                        // keeps the tail — mirroring the main loop's T263
                        // fix. A uniform takeLast() dropped the first line,
                        // which is where file_read's
                        // "[path | N bytes | M lines | showing A-B of M]"
                        // banner lives, so a recovered step could not say
                        // WHICH file was read or how much of it was shown.
                        output = SubagentStepOutputTrim.trim(call.name, resultContent),
                    )
                    // [T-subagent-loop-detector] Record post-execution and fold
                    // a WARNING into the text the model receives, mirroring the
                    // main loop's postRecord handling.
                    val paramsForRecord = try {
                        val o = org.json.JSONObject(call.args.toString())
                        buildMap<String, Any?> {
                            val keys = o.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                put(k, if (o.get(k) == org.json.JSONObject.NULL) null else o.get(k))
                            }
                        }
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    val postRecord = loopDetector.record(
                        toolName = call.name,
                        params = paramsForRecord,
                        result = if (result.success) result.output else null,
                        errorMessage = if (result.success) null else result.output,
                        toolCallId = call.id,
                    )
                    val contentForModel = if (
                        postRecord.level == com.openminis.app.agent.Level.WARNING &&
                        postRecord.message != null
                    ) {
                        "$resultContent\n\n${postRecord.message}"
                    } else {
                        resultContent
                    }
                    // [T-subagent-vision] Forward the tool's IMAGE payload.
                    //
                    // Before this, a sub-agent's read_image / browser screenshot
                    // arrived as text only: the model got the metadata banner
                    // ("[path | WxH | N bytes]") and no pixels, while the main
                    // loop passed imageData/imageMimeType/imageLinuxPath through
                    // to the same provider. A sub-agent asked to inspect a chart
                    // or read a screenshot therefore either guessed from the
                    // filename or silently produced confident descriptions of
                    // an image it never saw — worse than failing, because the
                    // fabricated specifics look like findings.
                    //
                    // The worker protocol already carries these fields on
                    // ToolResult parts (ModelExecutionDispatcher →
                    // ModelExecutionService → provider), and each provider
                    // re-checks the model's inputModalities, so a text-only
                    // model keeps working exactly as before. When the bytes are
                    // elided by an image budget, imageLinuxPath keeps the image
                    // re-fetchable — the same contract the main loop relies on.
                    //
                    // [fix/subagent-parallel-tool-results] The result is NOT
                    // appended here. A turn with N parallel calls used to
                    // append N consecutive USER messages, and BOTH the
                    // provider-side `sanitizeToolPairing` and the runner's own
                    // Pass 1 repair then treated every result but the first as
                    // an orphan: the sanitizer stripped it from the wire, and
                    // Pass 1 replaced it with a fake "Tool execution was
                    // interrupted" placeholder. Measured on the real
                    // production classes: 3 parallel reads → the model saw
                    // 1 real output + 2 fake interruptions. The results are
                    // collected below and emitted as ONE user message — the
                    // shape the main loop uses and the sanitizer expects.
                    resultParts += AgentContentPart.ToolResult(
                        id = call.id,
                        name = call.name,
                        content = contentForModel,
                        isError = !result.success,
                        imageData = result.imageData,
                        imageMimeType = result.imageMimeType,
                        imageLinuxPath = result.imageLinuxPath,
                    )
                }
                // [fix/subagent-parallel-tool-results] One USER message for the
                // whole turn's results. The wire format is identical for every
                // provider family (OpenAI emits one `role:"tool"` per part,
                // Anthropic one tool_result block per part, Gemini one
                // functionResponse per part) — only the LOCAL message grouping
                // changes, and the grouping is what the pairing sanitizer and
                // the Pass 1 repair read.
                SubagentToolResultBatch.buildToolResultMessage(resultParts)
                    ?.let { history.add(it) }

                // [T-subagent-checkpoint] Persist a mid-run progress anchor
                // after every completed turn. Process death never executes
                // the terminal journal paths below (they live in return /
                // exception handlers), so this per-turn write is the only
                // recovery signal a killed run leaves behind. Best-effort —
                // failures are swallowed inside the checkpoint writer.
                SubagentRunCheckpoint.write(run, turns, text, context)

                // [fix/subagent-11148-id-mismatch] Repair tool_use/tool_result
                // pairing on the sub-agent's OWN history — same repair pass
                // the main loop runs via sanitizeAgentHistory(). The
                // sub-agent loop grew its history with zero repair passes:
                // any interrupted/retried turn that left a dangling tool_use
                // or an orphan tool_result re-shipped on EVERY subsequent
                // request, and gateways that id-map tool results (Volcano
                // Ark) answered with 11148 "tool calls and tool results do
                // not match" — fatal for the rest of the run. Repairing at
                // the turn boundary keeps the run alive instead of burning
                // the remaining turns on a poisoned history.
                //
                // Written as a local mirror instead of importing
                // sanitizeAgentHistoryMessagesImpl so the runner stays
                // UI-package-free (main-source cross-package `internal` is
                // fine, but keeping SubagentRunner's import set Android/UI
                // -clean keeps it unit-testable in isolation).
                val subagentBridgeText =
                    "(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)"
                runCatching {
                    // Pass 1 — dangling tool_use: inject a placeholder result.
                    var idx = 0
                    while (idx < history.size) {
                        val msg = history[idx]
                        if (msg.role != LLMMessage.Role.ASSISTANT) { idx++; continue }
                        val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                        if (toolUses.isEmpty()) { idx++; continue }
                        val useIds = toolUses.map { it.id }.toSet()
                        val next = history.getOrNull(idx + 1)
                        val nextResultIds = next?.contentParts
                            ?.filterIsInstance<AgentContentPart.ToolResult>()
                            ?.map { it.id }?.toSet() ?: emptySet()
                        val missing = useIds - nextResultIds
                        if (missing.isEmpty()) { idx++; continue }
                        val placeholders = toolUses.filter { it.id in missing }.map { use ->
                            AgentContentPart.ToolResult(
                                id = use.id, name = use.name,
                                content = "Tool execution was interrupted; the tool may or may not have completed. Do not blindly re-issue the same tool call — first check the conversation and any prior results.",
                                isError = true,
                            )
                        }
                        AppLogger.warning(
                            TAG,
                            "[Subagent] '$skillName' injected ${placeholders.size} placeholder tool_result(s) after turn $turns history[$idx]",
                        )
                        if (next != null && next.role == LLMMessage.Role.USER &&
                            next.contentParts.any { it is AgentContentPart.ToolResult }
                        ) {
                            history[idx + 1] = next.copy(contentParts = next.contentParts + placeholders)
                        } else {
                            history.add(
                                idx + 1,
                                LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = placeholders),
                            )
                        }
                        idx++
                    }
                    // Pass 2 — orphan tool_result: drop results with no call.
                    val allUseIds = history.flatMap { it.contentParts }
                        .filterIsInstance<AgentContentPart.ToolUse>().map { it.id }.toSet()
                    val it2 = history.listIterator()
                    while (it2.hasNext()) {
                        val msg = it2.next()
                        if (msg.role != LLMMessage.Role.USER) continue
                        val cleaned = msg.contentParts.filter { part ->
                            part !is AgentContentPart.ToolResult || part.id in allUseIds
                        }
                        if (cleaned.isEmpty() && msg.content.isBlank()) it2.remove()
                        else if (cleaned.size < msg.contentParts.size) it2.set(msg.copy(contentParts = cleaned))
                    }
                    // Pass 3 — consecutive user roles (e.g. a trim boundary
                    // landed between a result and the next queued turn): an
                    // assistant bridge keeps the wire alternation valid.
                    //
                    // [fix/subagent-phantom-bridge] The guard here used to be
                    // `history.last()?.role == USER`, which is ALWAYS true at
                    // this point — a tool result is itself a USER-role message,
                    // so every tool-executing turn appended a fabricated
                    // "you were interrupted by a new user message" assistant
                    // turn. The predicate was ported from the main loop's
                    // ensureRoleAlternationBeforeUserAppend, where it is only
                    // evaluated immediately before a REAL user prompt is
                    // appended; copied here it lost that precondition and
                    // inverted its meaning. Observed: sub-agents burning turns
                    // reasoning about a non-existent new user message.
                    //
                    // The real violation is two consecutive USER messages —
                    // never produced by the assistant(tool_use) →
                    // user(tool_result) cycle. Repair every such pair, not
                    // just the tail (a trim can leave one mid-history), and
                    // stay idempotent so the pass cannot accumulate bridges.
                    val bridgesAdded = SubagentHistoryRepair.applyBridges(history, subagentBridgeText)
                    if (bridgesAdded > 0) {
                        AppLogger.info(
                            TAG,
                            "[Subagent] '$skillName' inserted $bridgesAdded assistant bridge(s) " +
                                "to restore role alternation after turn $turns",
                        )
                    }
                }

                // [T-subagent-context-budget] The sub-agent's `history` only
                // ever grew before this — `general-agent` ships max_turns: 48
                // and a research run's tool outputs are routinely tens of KB,
                // so long runs died on a provider context error instead of
                // finishing. Trim at the turn boundary (after this turn's
                // tool_results are in, before the next request) with the same
                // turn-granular invariants the main loop uses: whole rounds
                // only, task message and the newest rounds always kept.
                //
                // [T-subagent-vision] Image bytes get their own pass here,
                // unconditionally (not only when the context is tight):
                // tool_result images now reach the model, and a long run's
                // decoded bitmaps would otherwise sit in the heap until the
                // run ends. Stale rounds keep a re-fetchable path instead.
                runCatching {
                    val (slimmed, elidedImages) = SubagentHistoryBudget.elideStaleImages(history)
                    if (elidedImages > 0) {
                        history.clear()
                        history.addAll(slimmed)
                        AppLogger.info(
                            TAG,
                            "[Subagent] '$skillName' elided $elidedImages stale tool-result " +
                                "image(s) from older rounds to bound heap",
                        )
                    }
                }
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
                    registry.finishIfActive(
                        run.id, SubagentRunRegistry.RunStatus.SUCCESS,
                        resultText = partial,
                        error = "Stopped early (run_until=$runUntil)",
                        // [fix/subagent-verdict-and-report-gate] A first-turn
                        // readout is not a finished task — the detached
                        // paths must say so.
                        outcome = SubagentRunAssessment.Outcome.STOPPED_EARLY,
                        reportSource = if (partial.isBlank()) {
                            SubagentRunAssessment.ReportSource.NONE
                        } else SubagentRunAssessment.ReportSource.PARTIAL,
                        executedToolCalls = executedToolCalls,
                    )
                    val reconciledPartial = reconcileArtifactPaths(partial, artifacts, run.id)
                    val result = SubagentResult(
                        status = SubagentResult.Status.SUCCESS,
                        report = reconciledPartial,
                        declaredStatus = SubagentSkill.parseReportStatus(partial),
                        outcome = SubagentRunAssessment.Outcome.STOPPED_EARLY,
                        reportSource = if (partial.isBlank()) {
                            SubagentRunAssessment.ReportSource.NONE
                        } else SubagentRunAssessment.ReportSource.PARTIAL,
                        executedToolCalls = executedToolCalls,
                        turns = turns,
                        maxTurns = config.maxTurns,
                        skillId = skill.id,
                        skillName = skill.name,
                        journalPath = journal(
                            run, SubagentResult.journalTerminal(SubagentResult.Status.SUCCESS, stoppedEarly = true),
                            reconciledPartial, turns, error = "stopped early (run_until=$runUntil)", artifacts, sessionId,
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
            //
            // [feat/subagent-roster-routing] P1-4 — a CANCELLED run also
            // keeps its evidence. The user cancelled because the run was
            // taking too long; the work it DID do is exactly the part worth
            // keeping.
            val cancelSteps = registry.runs.value.firstOrNull { it.id == run.id }?.steps.orEmpty()
            val recovered = SubagentPartialRecovery.recover(
                accumulatedText = resultSb.toString(),
                steps = cancelSteps.map { step ->
                    SubagentPartialRecovery.Step(
                        toolName = step.toolName,
                        toolTitle = step.toolTitle,
                        success = step.status == SubagentRunRegistry.ToolStepStatus.SUCCESS,
                        output = step.output,
                    )
                },
                stopReason = "was cancelled",
            )
            val partial = recovered.report
            registry.finishIfActive(
                run.id, SubagentRunRegistry.RunStatus.CANCELLED,
                resultText = partial,
                error = e.message ?: "cancelled",
                // [fix/subagent-verdict-and-report-gate]
                outcome = SubagentRunAssessment.Outcome.FAILED,
                reportSource = recovered.source,
                executedToolCalls = executedToolCalls,
            )
            journal(
                run, "CANCELLED", reconcileArtifactPaths(partial, artifacts, run.id),
                turns, e.message ?: "cancelled by user", artifacts, sessionId,
            )
            AppLogger.warning(TAG, "[Subagent] '$skillName' cancelled after $turns turn(s); partial report journaled")
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            AppLogger.warning(TAG, "[Subagent] '$skillName' error after $turns turn(s): $msg")
            // [feat/subagent-roster-routing] P1-4 — a CRASHED run must still
            // deliver its evidence.
            //
            // The bug this closes: the normal path rebuilds a digest from the
            // tool calls when the model's closing message carries no
            // findings (SubagentReportDecision), but the EXCEPTION path did
            // not. A run that executed 16 turns of real tool calls and then
            // hit a stream error delivered "FAILED" with an EMPTY report to
            // the parent — 16 turns of work silently reduced to nothing,
            // while the journal (which the parent had to go and read by
            // hand) held the only copy.
            //
            // Measured on a live run: 3 audit sub-agents, 0 reports
            // delivered, 100% of the evidence stranded in the journals.
            val crashSteps = registry.runs.value.firstOrNull { it.id == run.id }?.steps.orEmpty()
            val recovered = SubagentPartialRecovery.recover(
                accumulatedText = resultSb.toString(),
                steps = crashSteps.map { step ->
                    SubagentPartialRecovery.Step(
                        toolName = step.toolName,
                        toolTitle = step.toolTitle,
                        success = step.status == SubagentRunRegistry.ToolStepStatus.SUCCESS,
                        output = step.output,
                    )
                },
                stopReason = "CRASHED ($msg)",
            )
            // The guard in SubagentArtifactPathWiringTest pins the name
            // `partial` for the variable that carries a partial report out
            // of every terminal exit — keep it: the guard is what stops a
            // new exit from skipping the artifact reconciliation.
            val partial = recovered.report
            val reportSource = recovered.source
            registry.finishIfActive(
                run.id, SubagentRunRegistry.RunStatus.FAILED,
                outcome = SubagentRunAssessment.Outcome.FAILED,
                reportSource = reportSource,
                executedToolCalls = executedToolCalls,
                resultText = partial, error = msg,
            )
            val reconciledPartial = reconcileArtifactPaths(partial, artifacts, run.id)
            val result = SubagentResult(
                status = SubagentResult.Status.FAILED,
                report = reconciledPartial,
                outcome = SubagentRunAssessment.Outcome.FAILED,
                reportSource = reportSource,
                executedToolCalls = executedToolCalls,
                turns = turns,
                maxTurns = config.maxTurns,
                skillId = skill.id,
                skillName = skill.name,
                journalPath = journal(run, "FAILED", reconciledPartial, turns, msg, artifacts, sessionId),
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
            registry.finishIfActive(
                run.id, SubagentRunRegistry.RunStatus.SUCCESS,
                resultText = "",
                // [fix/subagent-verdict-and-report-gate] A run that
                // produced no text delivered nothing.
                outcome = SubagentRunAssessment.Outcome.NO_OUTPUT,
                reportSource = SubagentRunAssessment.ReportSource.NONE,
                executedToolCalls = executedToolCalls,
            )
            // [fix/subagent-run-assessment] This exit used to return a bare
            // string with no verdict, so a run that produced nothing looked
            // like a normal completion carrying "(no output)". Route it
            // through the same assessment so the parent sees why.
            val blankOutcome = SubagentRunAssessment.assess(
                loopFailed = false,
                timedOut = false,
                stoppedEarly = false,
                executedToolCalls = executedToolCalls,
                reportSource = SubagentRunAssessment.ReportSource.NONE,
                declaredStatus = null,
            )
            return ToolExecutionResult(
                "Sub-agent '$skillName' ran $turns turn(s)." +
                    "\n" + SubagentRunAssessment.headline(
                        blankOutcome, executedToolCalls, SubagentRunAssessment.ReportSource.NONE,
                    ) +
                    "\n\n(no text output produced)",
                true, toolTitle = "Sub-agent: ${skill.name}",
            )
        }

        // [fix/subagent-report-hygiene] A non-blank final message is not
        // automatically a REPORT. Observed: a run executed five tool calls,
        // gathered real data, and closed with the single line
        // `<parameter name="status">done</parameter>` — the model merged the
        // plan directive's XML-block convention with the plain-text report
        // contract. The parent received that line as the findings and the run
        // still reported SUCCESS.
        //
        // Two-step handling: when the run DID work, rebuild a digest from the
        // tool evidence so the parent gets the facts that were gathered;
        // when it did not, say so instead of passing the husk through.
        // [fix/subagent-verdict-and-report-gate] The gate judges the model's
        // FINAL MESSAGE (`lastText`), not `finalText` — the accumulation of
        // every turn's narration. Judging the accumulation meant any earlier
        // prose pushed the husk past the substance threshold, so the gate
        // never fired and the parent received the bare tag as the findings.
        //
        // Reproduced with a real sub-agent run (2026-10-22): the closing
        // message was `<status>done</status>` plus a paragraph of English
        // narration. The closing message alone strips down to 4 meaningful
        // characters (a husk); the accumulation reached 69 (not a husk). The
        // gate therefore did not fire and the journal recorded no
        // "runtime-assembled report" — the parent got the raw husk.
        //
        // This matters because PR #66 made the plan turn non-terminal, and a
        // plan turn ALWAYS carries prose: the two fixes cancelled each other
        // out for exactly the runs they were written for.
        val executedSteps = registry.runs.value.firstOrNull { it.id == run.id }?.steps.orEmpty()
        // [fix/subagent-verdict-and-report-gate] The whole report decision
        // lives in SubagentReportDecision — which text is judged, what is
        // delivered, and what the verdict is. Keeping the choice of INPUT
        // here in the runner is exactly what made the defect untestable:
        // the pure function was never wrong, the call site passed the wrong
        // text to it. See SubagentReportDecision's doc for the measurements.
        val decision = SubagentReportDecision.decide(
            SubagentReportDecision.Input(
                finalMessage = lastText,
                accumulatedText = finalText,
                executedToolCalls = executedToolCalls,
                hasToolEvidence = executedSteps.isNotEmpty(),
                toolSteps = executedSteps.map { step ->
                    SubagentReportHygiene.ToolDigest(
                        toolName = step.toolName,
                        toolTitle = step.toolTitle,
                        success = step.status == SubagentRunRegistry.ToolStepStatus.SUCCESS,
                        output = step.output,
                    )
                },
            ),
        )
        val finalMessage = lastText.trim()
        // [fix/subagent-verdict-and-report-gate] `decision` is the single
        // source of truth: it already decided what is delivered, where it
        // came from, and what the verdict is. The runner only adds the
        // logging and the registry notice around it.
        val digest = if (decision.reportSource == SubagentRunAssessment.ReportSource.RUNTIME_DIGEST) {
            decision.reportText
        } else null
        val reportText = when {
            digest != null -> {
                AppLogger.warning(
                    TAG,
                    "[Subagent] '$skillName' final message was a husk (${finalMessage.length} chars, " +
                        "${SubagentReportHygiene.stripSyntheticTags(finalMessage).length} after tag strip) " +
                        "— rebuilt from " +
                        "${executedSteps.size} tool step(s)",
                )
                registry.appendNotice(
                    run.id,
                    "[report] the model's final message carried no findings — " +
                        "runtime assembled a digest from the tool output instead",
                )
                digest
            }
            decision.degenerate -> {
                // No tool evidence to fall back on: the husk IS all there is.
                // Still refuse to hand the parent a bare tag as a "report".
                registry.appendNotice(
                    run.id,
                    "[report] the model's final message carried no findings and no tool " +
                        "output was recorded",
                )
                decision.reportText
            }
            else -> decision.reportText
        }

        // [fix/subagent-plan-execute] A run that executed ZERO tool calls did
        // not do any work, whatever its prose says. Before this the parent
        // received "Sub-agent completed in 1 turn(s)" plus a plan and had no
        // way to tell that nothing was read, run, or written — the report was
        // indistinguishable from a real one at the header level. Say it out
        // loud so a parent (or the user) cannot mistake it for a deliverable.
        //
        // [fix/subagent-report-hygiene] Suppressed when the hygiene branch
        // already said it: a degenerate report from a zero-tool run carries
        // the "no usable findings / no tool output" warning, and stacking a
        // second warning on top of it is noise, not emphasis. A digest also
        // implies executed tool calls, so the two conditions cannot both be
        // true in a way that leaves the parent uninformed.
        // [fix/subagent-report-hygiene] Suppressed when the report gate already
        // spoke: a degenerate report carries its own "no usable findings"
        // warning, and stacking a second warning on top is noise.
        //
        // [fix/subagent-run-assessment] The warning MOVED to the headline:
        // `headline()` now says "[unstarted] 0 tool calls executed - nothing
        // was read, run or written" for exactly this case. Keeping the old
        // paragraph as well repeated the same fact twice in one message AND
        // injected an English warning into the middle of the report body,
        // where a model quoting the report would treat it as a finding.
        // One statement, in the header, is enough.
        // [fix/subagent-artifact-path-truth] The model's closing message
        // describes its own deliverables in prose, and prose drifts. The
        // runtime knows the truth — the canonical paths of the successful
        // writes — so reconcile BEFORE the text is delivered, journaled and
        // written into the registry snapshot. All three consumers then see
        // the same paths, and a parent that follows them finds the files.
        val deliveredText = reconcileArtifactPaths(reportText, artifacts, run.id)
        // [fix/subagent-run-assessment] One verdict for the whole run, derived
        // from facts already in hand: how many tools actually ran and where the
        // delivered text came from. The parent triages from this instead of
        // re-deriving "did it actually do anything?" from the header.
        val reportSource = decision.reportSource
        // [fix/subagent-verdict-and-report-gate] The declared status is a
        // property of the model's FINAL MESSAGE, so it is read from there.
        // Reading it out of `deliveredText` (the accumulated narration) let a
        // `status: partial` the model wrote in an EARLIER turn be taken as
        // this turn's self-assessment — the contract pins `status:` to the
        // report tail, but the tail of the accumulation is not the model's
        // closing message.
        val declared = decision.declaredStatus
        val outcome = decision.outcome
        registry.finishIfActive(
            run.id, SubagentRunRegistry.RunStatus.SUCCESS,
            resultText = deliveredText,
            // [fix/subagent-verdict-and-report-gate] Persist the verdict so
            // the detached paths can read it back — they build their result
            // from this snapshot.
            outcome = outcome,
            reportSource = reportSource,
            executedToolCalls = executedToolCalls,
        )
        val result = SubagentResult(
            status = SubagentResult.Status.SUCCESS,
            report = deliveredText,
            declaredStatus = declared,
            outcome = outcome,
            reportSource = reportSource,
            executedToolCalls = executedToolCalls,
            turns = turns,
            maxTurns = config.maxTurns,
            skillId = skill.id,
            skillName = skill.name,
            journalPath = journal(
                run, SubagentResult.journalTerminal(SubagentResult.Status.SUCCESS, stoppedEarly = false),
                deliveredText, turns, error = null, artifacts, sessionId,
            ),
            artifacts = artifacts.toList(),
        )
        // [fix/subagent-run-assessment] The loop's `success` flag is the
        // ToolExecutionResult contract the agent loop keys off: an UNSTARTED
        // run is not a failure (nothing errored) but the parent must not treat
        // it as a normal result either, so it stays `true` and the outcome
        // line carries the signal.
        return ToolExecutionResult(result.toPromptText(), true, toolTitle = "Sub-agent: ${skill.name}")
    }


    // ── Sub-agent tool dispatch ──────────────────────────────────────────

    /**
     * [fix/subagent-tool-preflight] Validate one tool call against the tool
     * schemas the model was shown, returning null when the call is
     * well-formed or a human-readable reason when it should be rejected.
     *
     * The rule is the SAME one the main agent applies
     * ([ToolCallPreflight]) — one implementation, two callers, so the two
     * loops cannot drift apart.
     *
     * [tools] is the SUB-AGENT's filtered schema, not the main agent's: the
     * sub-agent only ever sees its own capabilities, and a tool it was not
     * offered must not be validated against a schema it never received.
     */
    private fun preflightErrorFor(
        call: ToolBatchExecutor.Call,
        tools: List<AgentToolDefinition>,
    ): String? {
        val args = try {
            org.json.JSONObject(call.argsJson)
        } catch (_: Exception) {
            // Malformed JSON — the tool itself would fail on this. Report it
            // as a preflight failure so the model gets a clear correction
            // instead of a stack-shaped tool error.
            return "Tool '${call.name}' was called with malformed arguments that are not valid JSON."
        }
        return ToolCallPreflight.validate(call.name, args, tools)
    }

    /**
     * Execute a tool inside a sub-agent's loop. [T-subagent-capability]
     * Runtime enforcement layer: the schema filter already excluded
     * forbidden tools, but this refuse-with-explanation path guarantees a
     * stale allowlist or future regression can never EXECUTE them
     * (defense in depth — capability checks must fail closed twice).
     *
     * [fix/subagent-allowed-tools-enforcement] The skill's `allowed_tools`
     * allowlist is enforced HERE too, not only in the schema filter. The
     * schema filter governs what the model is TOLD it may call; this
     * governs what actually HAPPENS. Measured on the real runtime
     * (2026-10-22): a `read-only-researcher` run (allowlist = file_read /
     * read_image / browser_use) still wrote files with `file_write` and
     * created files with `shell_execute` — both landed on disk. The schema
     * looked safe; the runtime was not.
     *
     * @param allowedTools the skill's allowlist, or null when unrestricted.
     */
    suspend fun executeSubagentTool(
        name: String,
        argsJson: String,
        callId: String,
        runId: String,
        artifacts: MutableList<String>? = null,
        allowedTools: Set<String>? = null,
        // [feat/subagent-roster-routing] The skill's `disallowed_tools`
        // blacklist. Applied AFTER the allowlist and it WINS — see
        // SubagentAllowedTools.isAllowed for why the restrictive reading is
        // the safe one.
        disallowedTools: Set<String> = emptySet(),
    ): ToolExecutionResult {
        if (name in SubagentSkill.FORBIDDEN_TOOLS) {
            return forbiddenToolResult(name)
        }
        val capability = AgentCapabilities.capabilityOf(name)
        if (capability == null || capability !in AgentCapabilities.SUBAGENT_BASE) {
            return forbiddenToolResult(name)
        }
        // [fix/subagent-allowed-tools-enforcement] The allowlist gate. A
        // null allowlist means the skill declared none — unrestricted, as
        // before. A non-null allowlist means EXACTLY those tools, and
        // everything else is refused even if the capability catalog grants
        // it and the schema filter should already have hidden it.
        if (!SubagentAllowedTools.isAllowed(name, allowedTools, disallowedTools)) {
            AppLogger.warning(
                TAG,
                "[Subagent] '$name' REFUSED — not permitted by this skill's " +
                    if (name in disallowedTools) "disallowed_tools" else "allowed_tools",
            )
            return ToolExecutionResult(
                SubagentAllowedTools.refusalText(name, allowedTools, disallowedTools),
                false,
                toolTitle = name,
            )
        }
        return when (name) {
            "shell_execute" -> executeSubagentShell(argsJson, callId, runId)
            "browser_use" -> deps.executeBrowserUse(argsJson)
            // [T-subagent-websearch] Same executor the main loop uses
            // (configured engine + render wait + readable extraction), so a
            // sub-agent's search behaves identically to the parent's.
            "web_search" -> deps.executeWebSearch(argsJson)
            FileWriteTool.NAME, FileEditTool.NAME -> {
                val result = executeFileTool(name, argsJson)
                if (result.success) {
                    // [fix/subagent-artifact-path-truth] Store the CANONICAL
                    // path, not the raw `path` argument. A relative path (or
                    // one with `./` / `a//b` segments) is not a resolvable
                    // anchor — the parent would have to guess the base. The
                    // run's artifacts directory is the only sensible base for
                    // a relative path, and it is fixed for the whole run.
                    extractPath(argsJson)?.let { raw ->
                        val canonical = SubagentArtifactTruth.canonicalize(
                            raw,
                            SubagentSkill.artifactsDirFor(runId),
                        )
                        if (canonical.isNotEmpty()) artifacts?.add(canonical)
                    }
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
     * [fix/subagent-artifact-path-truth] Reconcile a report's self-declared
     * artifact paths against the runtime's write log before the parent sees
     * it.
     *
     * The model writes prose about its own deliverables, and prose drifts:
     * a misspelled directory, a relative path, a level dropped. The runtime
     * knows the truth (the canonical paths of the successful writes). This
     * rewrites the model's paths to the real ones and flags any path the
     * runtime never recorded, instead of handing the parent two contradictory
     * sets of paths with no indication of which to trust.
     *
     * Deliberately applied on EVERY exit path (success, timeout, early stop,
     * cancellation, exception): a report that drifted is just as useless to a
     * parent reading a partial result as to one reading a complete one.
     */
    private fun reconcileArtifactPaths(
        report: String,
        artifacts: List<String>,
        runId: String,
    ): String {
        if (report.isBlank() || artifacts.isEmpty()) return report
        val reconciliation = SubagentArtifactTruth.reconcile(
            report = report,
            recordedPaths = artifacts,
            artifactsDir = SubagentSkill.artifactsDirFor(runId),
        )
        return reconciliation.report
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
                // [fix/subagent-step-output-parity] Same rule as every other
                // tool — this one is shell_execute, so it keeps the TAIL.
                output = SubagentStepOutputTrim.trim("shell_execute", redactedOut),
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
                    // [fix/subagent-verdict-consistency-v3] The winner line
                    // and the success flag both read the binary `success`
                    // field, which flattens every non-COMPLETED verdict
                    // into "FAILED" — the parent could not tell an
                    // UNSTARTED run from a crashed one, and (worse) a
                    // success_only race would treat a DEGRADED digest as a
                    // usable winner. Use the verdict.
                    val winnerLabel = if (winner.cancelled) {
                        "CANCELLED"
                    } else {
                        SubagentRunAssessment.summaryStatus(
                            runtimeStatus = null,
                            verdict = winner.effectiveOutcome,
                            hasReport = winner.report.isNotBlank(),
                        )
                    }
                    val winnerDelivered = winner.delivered
                    ToolExecutionResult(
                        buildString {
                            append("wait_any: first completion in group ${group.id} → run ")
                            append(winner.runId)
                            append(" (").append(winner.skillName).append(")")
                            append(" ").append(winnerLabel)
                            append("\n\n---\n").append(boundWaitAnyReport(winner))
                            winner.journalPath?.let { append("\n\nJournal: $it") }
                            append("\n\n---\nStill running (NOT waited on): ")
                            append(losers.joinToString(", ") { it.runId }.ifEmpty { "(none)" })
                            append(". Reap losers with cancel_subagents when you no longer need them.")
                        },
                        // success_only=true promises "the first one that
                        // SUCCEEDS". A DEGRADED digest, a PARTIAL report or
                        // an UNSTARTED run is not a success — the race must
                        // keep going for a run that actually delivered.
                        success = !winner.cancelled && (winnerDelivered || !successOnly),
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
                // [T-subagent-cancel-honesty] Split the request into what this
                // tool can actually affect (detached runs hold their own
                // coroutine) and what it cannot (inline runs live inside the
                // parent turn). See the result text below for the user-facing
                // contract.
                val detachedJobs = jobs.filter { it.detached }
                val inlineJobs = jobs.filter { !it.detached }
                val wakeRunIds = SubagentOrchestration.cancelCascade(detachedJobs, reason)
                var killed = 0
                for (job in detachedJobs) {
                    registry.finishIfActive(
                        job.runId, SubagentRunRegistry.RunStatus.CANCELLED,
                        error = reason,
                        // [fix/subagent-verdict-and-report-gate]
                        outcome = SubagentRunAssessment.Outcome.FAILED,
                        reportSource = SubagentRunAssessment.ReportSource.NONE,
                    )
                    job.coroutineJob?.let { cj ->
                        if (cj.isActive) {
                            cj.cancel(kotlinx.coroutines.CancellationException(reason))
                            killed++
                        }
                    }
                }
                // [T-subagent-cancel-honesty] INLINE runs are NOT cancellable
                // from here, and saying otherwise is a lie with teeth: the
                // inline loop executes INSIDE the parent turn's dispatch
                // stack, so the only thing that can stop it is a cancellation
                // of that turn (the user's Stop) — there is no child coroutine
                // to tear down (SubagentJob.coroutineJob stays null for
                // non-detached spawns). The previous code reported every
                // matched run as "cancelled" regardless, which also pushed a
                // false CANCELLED outcome into a deferred that the still-
                // running loop would later contradict with its real result.
                // Reported truthfully instead, with the two routes that DO
                // work (let it finish and join it, or stop the parent turn).
                val inlineLive = inlineJobs.filter { !it.deferred.isCompleted }
                ToolExecutionResult(
                    buildString {
                        append("Cancelled ${detachedJobs.size} detached sub-agent run(s) — ")
                        append("$killed background coroutine(s) torn down, ")
                        append("registry reconciled, ${wakeRunIds.size} blocked join/wait woke with 'cancelled'. ")
                        if (detachedJobs.isNotEmpty()) {
                            append("\nCancelled runs: ").append(detachedJobs.joinToString(", ") { it.runId })
                        }
                        if (inlineLive.isNotEmpty()) {
                            append("\n\nNOT cancelled (inline runs cannot be stopped out-of-band): ")
                            append(inlineLive.joinToString(", ") { it.runId })
                            append(". An inline sub-agent runs inside the parent turn that spawned it, ")
                            append("so the only thing that ends it early is stopping that turn. ")
                            append("Otherwise it finishes on its own and you can collect it with join_subagents — ")
                            append("the run is unaffected by this call.")
                        }
                        if (inlineJobs.isNotEmpty()) {
                            val alreadyDone = inlineJobs.filter { it.deferred.isCompleted }
                            if (alreadyDone.isNotEmpty()) {
                                append("\nAlready finished (nothing to cancel): ")
                                append(alreadyDone.joinToString(", ") { it.runId })
                                append(" — join_subagents still returns their reports.")
                            }
                        }
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
        // [T-subagent-report-budget] Bound each report for the same reason the
        // detached wake-up path is bounded (WAKE_REPORT_MAX_CHARS): these
        // texts RIDE IN THE PARENT TURN. A single join of four detached
        // research runs could legally carry 4 × 24k chars of registry
        // resultText (inline results are unbounded outright), spending most
        // of the parent's window on the first tool result of the turn —
        // exactly what the 4k wake-up cap exists to prevent, defeated by the
        // sibling code path. The journal holds the full text (and is named on
        // truncation), so nothing is lost — only the automatic spend is.
        for ((job, outcome) in jobs.zip(outcomes)) {
            sb.append("\n\n=== run ").append(job.runId)
                .append(" (").append(outcome.skillName).append(") — ")
                .append(
                    when {
                        outcome.cancelled -> "CANCELLED"
                        // [fix/subagent-verdict-consistency-v3] The old
                        // three-way split (cancelled / success / failed)
                        // collapsed every verdict the runtime knows about
                        // into "FAILED" — an UNSTARTED run, a DEGRADED
                        // runtime digest and a model-declared PARTIAL all
                        // reached the parent as the same word. The parent
                        // then had to re-read the body to learn whether the
                        // run did anything. Use the verdict label.
                        else -> SubagentRunAssessment.summaryStatus(
                            runtimeStatus = null,
                            verdict = outcome.effectiveOutcome,
                            hasReport = outcome.report.isNotBlank(),
                        )
                    },
                )
                .append(" ===")
            if (outcome.report.isNotBlank()) {
                sb.append("\n").append(
                    boundReportForParent(
                        report = outcome.report,
                        journalPath = outcome.journalPath,
                        maxChars = JOIN_REPORT_MAX_CHARS,
                    ),
                )
            } else {
                sb.append("\n(no report text)")
            }
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

