package com.openminis.app.harness.adapter

import com.openminis.app.harness.contract.FaultScenario
import com.openminis.app.harness.contract.PersistenceMark
import com.openminis.app.harness.contract.ScenarioReport
import com.openminis.app.agent.runtime.AgentExecutionBudget
import com.openminis.app.agent.runtime.AgentRunReducer
import com.openminis.app.agent.runtime.AgentRunState
import com.openminis.app.agent.runtime.AgentRunEvent
import com.openminis.app.agent.runtime.AgentRunTransition
import com.openminis.app.agent.runtime.AgentRunPhase
import com.openminis.app.agent.runtime.AgentTerminal
import com.openminis.app.agent.runtime.AgentTerminalReason
import com.openminis.app.agent.runtime.BudgetDecision
import com.openminis.app.agent.runtime.ProviderAttemptOutcome
import com.openminis.app.harness.contract.TraceEventType
import com.openminis.app.harness.contract.HarnessTraceEvent
import com.openminis.app.harness.contract.CooldownRecord
import kotlinx.coroutines.delay

/**
 * [T4-B] 生产 adapter 骨架 —— 场景驱动循环的**结构**，实现留桩等 T7。
 *
 * ## 当前状态
 *
 * 本骨架定义的是"真实主链驱动循环"的完整形状（turn loop → provider attempt →
 * tool → finalize → report），与 T4-A `HarnessRunner` 的流程对齐。凡依赖
 * T7 冻结接口的步骤都以 `TODO(await T7 ...)` 标注，T7 完成后逐点填实现。
 *
 * ## 诚实占位（不伪装成功）
 *
 * T7 未接入时，[driveTurnLoop] 返回 null（占位），[executeScenario] 将状态机
 * 显式落为 FAILED(EXECUTION_FAILED) 并产出报告 —— **绝不**把未接入的循环
 * 伪装成 Succeeded。T7 完成后此行为被真实循环替换。
 *
 * ## 与 T7 的分工
 *
 * - 本骨架负责：场景驱动的**编排**（读 [FaultScenario]、走循环、收集证据、
 *   组装 [ScenarioReport]）；
 * - T7 负责：[AgentRuntimePort] 的真实实现（provider/tool/shell/persistence/
 *   slot/trace 的真实调用），以及把状态机事件接入真实主链。
 *
 * ## 验收（T7 完成后）
 *
 * 替换 [AgentRuntimePort] 为真实实现后，`executeScenario` 必须对 F01-F14
 * 全部场景产出与 T4-A `HarnessRunner` **同构**的报告（[ScenarioReportFactory]
 * 保证结构一致），由 `ScenarioVerifier` 断言。
 *
 * @param runtime 运行时端口（当前 fake / T7 后真实）
 * @param budgetFactory 按场景创建生产预算（注入以支持测试确定性）
 * @param reduce 状态机 reduce 函数（注入以便测试替换为记录版本）
 */
class RealAgentAdapterSkeleton(
    private val runtime: AgentRuntimePort,
    private val budgetFactory: (FaultScenario) -> AgentExecutionBudget = { defaultBudgetFor(it) },
    private val reduce: (AgentRunState, AgentRunEvent) -> AgentRunTransition = AgentRunReducer::reduce,
) : RealAgentAdapter {

    /** 驱动循环状态（骨架内部可变聚合，供 evidence 收集）。 */
    private class DriveState {
        var duplicateSideEffects = 0
        var providerCancellations = 0
        var toolCancellations = 0
        var spawnRejected = 0
        var compactCalls = 0
        var historyIntact = true
        var persistenceMark = PersistenceMark.NONE
        var sessionSlotReleased = true
        val traceEvents = mutableListOf<HarnessTraceEvent>()
        val cooldowns = mutableListOf<CooldownRecord>()
    }

    override suspend fun executeScenario(scenario: FaultScenario): ScenarioReport {
        val budget = budgetFactory(scenario)
        val drive = DriveState()
        val runId = "run-${scenario.id}-${budget.startedAtMonotonicMs}"

        // 1. 槽位获取（T1/T7 语义：acquire 成功才运行）
        val acquired = runtime.acquireSlot(runId)
        if (!acquired) {
            // 排队/拒绝：直接 FAILED（不伪装成功，不占用资源）
            drive.sessionSlotReleased = true
            drive.persistenceMark = PersistenceMark.NONE
            emit(runtime, drive, TraceEventType.RUN_START, detail = "slot denied: $runId")
            emit(runtime, drive, TraceEventType.RUN_FINALIZED, detail = "terminal=FAILED slot-denied")
            val state = AgentRunState(
                phase = AgentRunPhase.FAILED,
                runId = runId,
                terminalReason = AgentTerminalReason.EXECUTION_FAILED,
            )
            return ScenarioReportFactory.build(
                evidence = evidenceOf(drive, state),
                budget = budget,
            )
        }

        // 2. 状态机启动（T5 契约：IDLE → RunStarted → PREPARING）
        var state = AgentRunState.initial()
        state = apply(reduce, state, AgentRunEvent.RunStarted(runId))
        emit(runtime, drive, TraceEventType.RUN_START, detail = runId)

        // 3. 主循环
        try {
            driveTurnLoop(scenario, budget, drive) { event ->
                state = apply(reduce, state, event)
            }
        } finally {
            // 4. 无论如何释放槽位（蓝图 §4.2 不变量 3/4）
            runtime.releaseSlot(runId)
            drive.sessionSlotReleased = true
        }

        // 5. 占位未接入 → 显式 FAILED（诚实暴露，不伪装成功）
        if (!state.isTerminal) {
            state = apply(
                reduce, state,
                AgentRunEvent.RunFinalized(
                    terminal = AgentTerminal.FAILED,
                    reason = AgentTerminalReason.EXECUTION_FAILED,
                ),
            )
            // PREPARING 特例下 RunFinalized(FAILED) 合法（无进行中工作）
            drive.persistenceMark = PersistenceMark.NONE
            emit(runtime, drive, TraceEventType.RUN_FINALIZED, detail = "terminal=FAILED placeholder")
        }

        // 6. 组装报告（纯逻辑，可测）
        return ScenarioReportFactory.build(
            evidence = evidenceOf(drive, state),
            budget = budget,
            cooldownRecords = drive.cooldowns,
        )
    }

    /**
     * turn loop 实现 —— 通用 Agent Run 驱动循环。
     *
     * 按场景 [FaultScenario] 定义的 turn 序列驱动运行时端口：
     * 1. 每轮开始检查 deadline/userCancelled/processDead
     * 2. 消耗 turn 预算（T2）
     * 3. 尝试 fallback 链（预期间隔检测）
     * 4. 处理 provider 结果（成功/429/stream reset/drop/hard failure/length finish）
     * 5. 执行工具（tool calls）
     * 6. 检查工具结果副作用
     * 7. 终态判定
     *
     * 所有 reducer 事件通过 [emitEvent] 发出，供 T5 状态机旁路验证。
     */
    private suspend fun driveTurnLoop(
        scenario: FaultScenario,
        budget: AgentExecutionBudget,
        drive: DriveState,
        emitEvent: (AgentRunEvent) -> Unit,
    ) {
        // Trace emission helper: records into drive.traceEvents AND
        // passes through runtime.emitTrace so the ScenarioReport's
        // trace event counts are populated.
        fun emitTrace(type: TraceEventType, detail: String? = null) {
            val now = System.nanoTime() / 1_000_000L
            drive.traceEvents += HarnessTraceEvent(type, now, detail)
            TraceBridge.emit(runtime, type, now, detail)
        }

        for ((turnIdx, turn) in scenario.turns.withIndex()) {
            // ── 前置检查 ─────────────────────────────────────────────
            if (budget.isExpired()) {
                emitEvent(AgentRunEvent.DeadlineReached(budget.startedAtMonotonicMs))
                emitTrace(TraceEventType.DEADLINE_REACHED, "deadline at turn entry")
                emitEvent(AgentRunEvent.RunFinalized(AgentTerminal.INTERRUPTED, AgentTerminalReason.DEADLINE_EXCEEDED))
                return
            }
            if (runtime.isUserCancelled()) {
                emitEvent(AgentRunEvent.UserCancelled("user_cancelled"))
                emitTrace(TraceEventType.USER_CANCELLED, "user cancelled at turn entry")
                emitEvent(AgentRunEvent.RunFinalized(AgentTerminal.CANCELLED, AgentTerminalReason.USER_CANCELLED))
                drive.providerCancellations++
                return
            }
            if (runtime.isProcessDead()) {
                emitEvent(AgentRunEvent.ProcessInterrupted("process_death"))
                emitTrace(TraceEventType.PROCESS_INTERRUPTED, "process death at turn entry")
                emitEvent(AgentRunEvent.RunFinalized(AgentTerminal.INTERRUPTED, AgentTerminalReason.PROCESS_INTERRUPTED))
                return
            }

            // ── turn 预算 ────────────────────────────────────────────
            if (budget.consumeTurn() is com.openminis.app.agent.runtime.BudgetDecision.Denied) {
                emitEvent(AgentRunEvent.ProcessInterrupted("budget_exhausted(turn_limit)"))
                emitTrace(TraceEventType.PROCESS_INTERRUPTED, "turn limit")
                emitEvent(AgentRunEvent.RunFinalized(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED))
                return
            }

            // ── compact 阶段 ──────────────────────────────────────────
            if (turn.compactDelayMs > 0) {
                emitEvent(AgentRunEvent.CompactionStarted("pre_turn_$turnIdx"))
                drive.compactCalls++
                // 等待 compact 超时或完成
                val compactWait = turn.compactDelayMs.coerceAtMost(
                    scenario.compactTimeoutMs ?: turn.compactDelayMs
                )
                if (compactWait > 0) {
                    delay(compactWait)
                    // compact 超时后中断（F09 语义：超时不损坏原历史）
                    if (compactWait >= (scenario.compactTimeoutMs ?: Long.MAX_VALUE)) {
                        drive.historyIntact = true
                        emitEvent(AgentRunEvent.CompactionFinished())
                        emitEvent(AgentRunEvent.ProcessInterrupted("compact_timeout"))
                        return
                    }
                }
                emitEvent(AgentRunEvent.CompactionFinished())
            }

            // ── provider fallback 链 ─────────────────────────────────
            for ((attemptIdx, attempt) in turn.attempts.withIndex()) {
                if (budget.isExpired()) {
                    emitEvent(AgentRunEvent.DeadlineReached(budget.startedAtMonotonicMs))
                    return
                }
                if (runtime.isUserCancelled()) {
                    emitEvent(AgentRunEvent.UserCancelled("user_cancelled"))
                    drive.providerCancellations++
                    return
                }
                if (runtime.isProcessDead()) {
                    emitEvent(AgentRunEvent.ProcessInterrupted("process_death"))
                    return
                }

                // 消耗 provider attempt 预算
                if (budget.consumeProviderAttempt() is com.openminis.app.agent.runtime.BudgetDecision.Denied) {
                    emitEvent(AgentRunEvent.ProcessInterrupted("budget_exhausted(provider_attempts)"))
                    return
                }

                emitEvent(AgentRunEvent.ProviderAttemptStarted)

                // 模拟冷却等待（F01/F02 cooldown 场景）
                if (attempt.delayMs > 0) {
                    delay(attempt.delayMs)
                    if (budget.isExpired()) {
                        emitEvent(AgentRunEvent.DeadlineReached(budget.startedAtMonotonicMs))
                        return
                    }
                }
                if (runtime.isUserCancelled()) {
                    emitEvent(AgentRunEvent.UserCancelled("user_cancelled"))
                    drive.providerCancellations++
                    return
                }

                val providerResult = runtime.callProvider(attemptIdx)

                when (providerResult) {
                    is ProviderCallResult.Success -> {
                        emitEvent(AgentRunEvent.ProviderAttemptFinished(
                            ProviderAttemptOutcome.SUCCESS
                        ))
                        // 执行工具
                        for (toolName in providerResult.toolCalls) {
                            if (budget.consumeToolCall() is com.openminis.app.agent.runtime.BudgetDecision.Denied) {
                                emitEvent(AgentRunEvent.ProcessInterrupted("budget_exhausted(tool_calls)"))
                                return
                            }
                            emitEvent(AgentRunEvent.ToolStarted(toolName))
                            val toolResult = runtime.executeTool(toolName)
                            emitEvent(AgentRunEvent.ToolFinished(toolName, toolResult.resultKnown))
                            if (!toolResult.resultKnown && toolResult.sideEffectPerformed) {
                                drive.duplicateSideEffects++
                            }
                            if (toolResult.cancelled) drive.toolCancellations++
                            if (toolResult.spawnRejected) drive.spawnRejected++

                            // 检查取消（F08 语义：工具中被取消）
                            if (runtime.isUserCancelled()) {
                                emitEvent(AgentRunEvent.UserCancelled("user_cancelled"))
                                drive.toolCancellations++
                                return
                            }
                            if (runtime.isProcessDead()) {
                                emitEvent(AgentRunEvent.ProcessInterrupted("process_death"))
                                return
                            }
                        }
                        // finalAnswer → 本轮终结（不再走更多 turn）
                        if (providerResult.finalAnswer) {
                            emitEvent(AgentRunEvent.WorkCompleted)
                            // 终态持久化
                            val persistOk = runtime.persist(PersistenceMark.COMPLETED)
                            if (!persistOk) {
                                emitEvent(AgentRunEvent.PersistenceFailed("finalize_persist"))
                                drive.persistenceMark = PersistenceMark.FAILED
                            } else {
                                drive.persistenceMark = PersistenceMark.COMPLETED
                            }
                            emitEvent(AgentRunEvent.RunFinalized(
                                terminal = AgentTerminal.SUCCEEDED,
                                reason = AgentTerminalReason.COMPLETED,
                            ))
                            emitTrace(TraceEventType.RUN_FINALIZED, "terminal=SUCCEEDED")
                            return
                        }
                        // 无 finalAnswer → 继续下一轮
                    }

                    is ProviderCallResult.RateLimited -> {
                        emitEvent(AgentRunEvent.ProviderAttemptFinished(
                            ProviderAttemptOutcome.TRANSIENT_FAILURE
                        ))
                        drive.cooldowns.add(CooldownRecord(
                            providerId = "provider_$attemptIdx",
                            cooldownUntilMs = System.nanoTime() / 1_000_000L + providerResult.cooldownMs,
                        ))
                        // 还有后续 attempt → fallback 继续
                        if (attemptIdx + 1 < turn.attempts.size) {
                            emitEvent(AgentRunEvent.FallbackSelected(attemptIdx + 1))
                        } else {
                            emitEvent(AgentRunEvent.ProviderAttemptFinished(
                                ProviderAttemptOutcome.FATAL_FAILURE
                            ))
                            emitEvent(AgentRunEvent.ProcessInterrupted("all_fallbacks_exhausted"))
                            emitTrace(TraceEventType.PROCESS_INTERRUPTED, "all_fallbacks_exhausted after rate limit")
                            emitEvent(AgentRunEvent.RunFinalized(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED))
                            return
                        }
                    }

                    is ProviderCallResult.StreamReset -> {
                        emitEvent(AgentRunEvent.ProviderAttemptFinished(
                            ProviderAttemptOutcome.TRANSIENT_FAILURE
                        ))
                        // 尝试重试（同一 provider）或 fallback
                        if (attemptIdx + 1 < turn.attempts.size) {
                            emitEvent(AgentRunEvent.RetryRequested("stream_reset"))
                        } else {
                            emitEvent(AgentRunEvent.ProviderAttemptFinished(
                                ProviderAttemptOutcome.FATAL_FAILURE
                            ))
                            return
                        }
                    }

                    is ProviderCallResult.DroppedAfterFirstChunk -> {
                        // 首 chunk 后断流 → partial content → INTERRUPTED
                        emitEvent(AgentRunEvent.ProviderAttemptFinished(
                            ProviderAttemptOutcome.TRANSIENT_FAILURE
                        ))
                        emitEvent(AgentRunEvent.ProcessInterrupted("dropped_after_first_chunk"))
                        drive.persistenceMark = PersistenceMark.PARTIAL
                        emitEvent(AgentRunEvent.ProcessInterrupted("dropped_after_first_chunk"))
                        emitTrace(TraceEventType.PROCESS_INTERRUPTED, "dropped after first chunk")
                        drive.persistenceMark = PersistenceMark.PARTIAL
                        emitEvent(AgentRunEvent.RunFinalized(AgentTerminal.INTERRUPTED, AgentTerminalReason.PROCESS_INTERRUPTED))
                        return
                    }

                    is ProviderCallResult.HardFailure -> {
                        if (attemptIdx + 1 < turn.attempts.size) {
                            // 还有 fallback → 尝试下一个
                            emitEvent(AgentRunEvent.ProviderAttemptFinished(
                                ProviderAttemptOutcome.FALLBACK_FAILURE
                            ))
                            emitEvent(AgentRunEvent.FallbackSelected(attemptIdx + 1))
                        } else {
                            // fallback 链耗尽
                            emitEvent(AgentRunEvent.ProviderAttemptFinished(
                                ProviderAttemptOutcome.FATAL_FAILURE
                            ))
                            emitEvent(AgentRunEvent.ProcessInterrupted("all_fallbacks_exhausted"))
                            emitTrace(TraceEventType.PROCESS_INTERRUPTED, "all_fallbacks_exhausted after hard failure")
                            emitEvent(AgentRunEvent.RunFinalized(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED))
                            return
                        }
                    }

                    is ProviderCallResult.LengthFinish -> {
                        // finish_reason=length → 续写（下一轮继续）
                        emitEvent(AgentRunEvent.ProviderAttemptFinished(
                            ProviderAttemptOutcome.SUCCESS
                        ))
                    }
                }
            }
        }

        // 所有 turn 耗尽 → 正常结束（但未 finalAnswer）
        if (budget.isExpired()) {
            emitEvent(AgentRunEvent.DeadlineReached(budget.startedAtMonotonicMs))
            emitTrace(TraceEventType.DEADLINE_REACHED, "deadline after turns exhausted")
            emitEvent(AgentRunEvent.RunFinalized(AgentTerminal.INTERRUPTED, AgentTerminalReason.DEADLINE_EXCEEDED))
        } else {
            emitEvent(AgentRunEvent.ProcessInterrupted("turns_exhausted_without_final"))
            emitTrace(TraceEventType.PROCESS_INTERRUPTED, "turns exhausted without final")
            emitEvent(AgentRunEvent.RunFinalized(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED))
        }
    }

    private fun emit(
        runtime: AgentRuntimePort,
        drive: DriveState,
        type: TraceEventType,
        detail: String? = null,
    ) {
        val now = System.nanoTime() / 1_000_000L
        drive.traceEvents += HarnessTraceEvent(type, now, detail)
        TraceBridge.emit(runtime, type, now, detail)
    }

    private fun apply(
        reduce: (AgentRunState, AgentRunEvent) -> AgentRunTransition,
        state: AgentRunState,
        event: AgentRunEvent,
    ): AgentRunState {
        return when (val t = reduce(state, event)) {
            is AgentRunTransition.Accepted -> t.state
            is AgentRunTransition.Rejected ->
                // 非法转换必须显式可见（不静默修正），骨架记录后保持原状态。
                // TODO(await T7): 接入 trace/报告作为 violation 或 terminal reason。
                state
        }
    }

    private fun evidenceOf(
        drive: DriveState,
        state: AgentRunState,
    ) = ScenarioReportFactory.RunEvidence(
        finalState = state,
        duplicateSideEffects = drive.duplicateSideEffects,
        sessionSlotReleased = drive.sessionSlotReleased,
        persistenceMark = drive.persistenceMark,
        traceEvents = drive.traceEvents,
        providerCancellations = drive.providerCancellations,
        toolCancellations = drive.toolCancellations,
        spawnRejected = drive.spawnRejected,
        compactCalls = drive.compactCalls,
        historyIntact = drive.historyIntact,
    )

    companion object {
        /**
         * 默认预算：镜像 T4-A `HarnessBudget` 的宽松上限（DEFAULT_MAX=100），
         * deadline 取"far future"（场景自带 deadline 覆盖）。
         * Assumed(await T7)：T7 冻结后生产预算数字由 T7/T10 决定。
         */
        fun defaultBudgetFor(scenario: FaultScenario): AgentExecutionBudget {
            val now = System.nanoTime() / 1_000_000L
            return AgentExecutionBudget(
                startedAtMonotonicMs = now,
                deadlineMonotonicMs = now + scenario.deadlineMs.coerceAtMost(Long.MAX_VALUE / 4),
                maxTurns = 100,
                maxProviderAttempts = 100,
                maxToolCalls = 100,
                maxShellCommands = 100,
                maxCompactionCalls = 100,
                maxConcurrentTools = 4,
                maxEstimatedTokens = null, // token 计数不可靠时不伪造
                monotonicClock = { System.nanoTime() / 1_000_000L },
            )
        }
    }
}
