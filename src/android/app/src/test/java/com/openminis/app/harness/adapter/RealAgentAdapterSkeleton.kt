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
import com.openminis.app.harness.contract.TraceEventType
import com.openminis.app.harness.contract.HarnessTraceEvent
import com.openminis.app.harness.contract.CooldownRecord

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
     * turn loop 骨架。T4-A `HarnessRunner` 的流程镜像：
     * turn → compact → provider fallback 链 → tool 执行 → 终态判定。
     *
     * TODO(await T7): 各步骤的真实行为（provider 调用、tool 执行、shell、
     * persist、deadline/cancel/processDeath 探测）由 T7 的 [AgentRuntimePort]
     * 实现填充。当前骨架按场景脚本意图注释每个步骤的期望行为。
     */
    private suspend fun driveTurnLoop(
        scenario: FaultScenario,
        budget: AgentExecutionBudget,
        drive: DriveState,
        emitEvent: (AgentRunEvent) -> Unit,
    ) {
        // TODO(await T7): 完整驱动循环 —— 当前为结构性占位（不执行任何真实调用）。
        // T7 完成后本方法实现：
        //   for (turn in scenario.turns) {
        //     budget.consumeTurn()                        // T2 预算
        //     if (turn.compactDelayMs > 0) performCompact() // F09
        //     for (attempt in turn.attempts) {            // fallback 链
        //       budget.consumeProviderAttempt()           // T2 预算
        //       when (runtime.callProvider(idx)) { ... }  // F01/F02/F03/F04
        //     }
        //     for (toolName in attempt.toolCalls) {       // 工具
        //       budget.consumeToolCall()                  // T2 预算
        //       runtime.executeTool(toolName)             // F05/F06/F08/F13
        //     }
        //     // deadline/cancel/processDeath 探测（F07/F11/F14）
        //   }
        //   // finalize：persist(mark) → RunFinalized(terminal)
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
