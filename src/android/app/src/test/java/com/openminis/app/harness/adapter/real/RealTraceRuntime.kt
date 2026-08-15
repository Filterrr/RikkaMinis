package com.openminis.app.harness.adapter.real

import com.openminis.app.harness.adapter.HarnessTraceEvent
import com.openminis.app.harness.adapter.TraceBridge
import com.openminis.app.harness.contract.TraceEventType
import com.openminis.app.tools.AgentTraceRecorder
import com.openminis.app.agent.runtime.AgentRunPhase
import com.openminis.app.agent.runtime.AgentTerminal

/**
 * [T7-real-runtime] 真实 trace 运行时 —— 把 harness 的 [HarnessTraceEvent]
 * 桥接到生产 [AgentTraceRecorder]（T6，schema 2.0 JSONL）。
 *
 * ## 职责
 *
 * T4-A harness 以 [TraceEventType] 序列描述一次 run；生产侧 trace 以
 * `AgentTraceRecorder` 的 schema 2.0 事件（state_transition / budget_consume /
 * resource_acquire / trace_end 等）落盘。本类把两者映射：
 *
 * | HarnessTraceEvent | AgentTraceRecorder 调用 |
 * |---|---|
 * | `RUN_START` | `beginRun`（含 provider_count/tool_count） |
 * | `PROVIDER_ATTEMPT_STARTED` | `stateTransition`（→ CallingModel）+ `budgetConsume(provider_attempts)` |
 * | `TOOL_STARTED` | `stateTransition`（→ ExecutingTools） |
 * | `TOOL_FINISHED` | `stateTransition`（ExecutingTools 自环） |
 * | `COMPACT_STARTED` | `stateTransition`（→ Compacting） |
 * | `COMPACT_FINISHED` | `stateTransition`（→ CallingModel） |
 * | `RETRY_REQUESTED` | `stateTransition`（→ Retrying） |
 * | `FALLBACK_SELECTED` | `stateTransition`（→ FallingBack） |
 * | `USER_CANCELLED` / `DEADLINE_REACHED` / `PROCESS_INTERRUPTED` | `stateTransition`（→ Finalizing） |
 * | `RUN_FINALIZED` | `stateTransition`（→ 终态）+ `endRun` |
 * | `SPAWN_REJECTED` | `error` |
 *
 * 与 [TraceBridge] 的关系：骨架通过 `TraceBridge.emit` 输出（写失败不阻断）；
 * 本类作为征收端把同一事件流落成生产 schema。recorder 自带 terminal 去重
 * （`writeTerminalOnce`），多次 RUN_FINALIZED 不会产生重复 trace_end。
 *
 * ## 生产装配
 *
 * 生产环境把 [com.openminis.app.tools.AgentTraceRecorder] 以真实文件 sink
 * （`workspace/.traces/agent-<ts>.jsonl`）构造并传入；JVM 测试用内存 sink
 * （`MutableList<String>` 收集），验证schema 合法性与事件序列。
 */
class RealTraceRuntime(
    private val recorder: AgentTraceRecorder,
    private val runId: String,
    private val sessionId: String,
) {
    /** 收集到的原始 JSONL 行（测试断言用；生产可忽略）。 */
    val lines: MutableList<String> = mutableListOf()

    private val phaseSchema: Map<TraceEventType, String?> = mapOf(
        TraceEventType.RUN_START to null,
        TraceEventType.PROVIDER_ATTEMPT_STARTED to AgentRunPhase.CALLING_MODEL.name,
        TraceEventType.TOOL_STARTED to AgentRunPhase.EXECUTING_TOOLS.name,
        TraceEventType.TOOL_FINISHED to AgentRunPhase.EXECUTING_TOOLS.name,
        TraceEventType.COMPACT_STARTED to AgentRunPhase.COMPACTING.name,
        TraceEventType.COMPACT_FINISHED to AgentRunPhase.CALLING_MODEL.name,
        TraceEventType.RETRY_REQUESTED to AgentRunPhase.RETRYING.name,
        TraceEventType.FALLBACK_SELECTED to AgentRunPhase.FALLING_BACK.name,
        TraceEventType.USER_CANCELLED to AgentRunPhase.FINALIZING.name,
        TraceEventType.DEADLINE_REACHED to AgentRunPhase.FINALIZING.name,
        TraceEventType.PROCESS_INTERRUPTED to AgentRunPhase.FINALIZING.name,
        TraceEventType.PERSISTENCE_FAILED to AgentRunPhase.FINALIZING.name,
        TraceEventType.RUN_FINALIZED to null, // 终态由 detail 解析
        TraceEventType.SPAWN_REJECTED to null,
    )

    /**
     * 处理一条事件。写失败吞异常（蓝图 §T6 兼容要求），不阻断主执行。
     */
    fun record(event: HarnessTraceEvent) {
        runCatching { recordInternal(event) }
    }

    private fun recordInternal(event: HarnessTraceEvent) {
        when (event.type) {
            TraceEventType.RUN_START -> {
                recorder.beginRun(
                    runId = runId,
                    sessionId = sessionId,
                    provider = "harness",
                    prompt = event.detail ?: "scenario run",
                    providerCount = 0,
                    toolCount = 0,
                )
            }
            TraceEventType.PROVIDER_ATTEMPT_STARTED -> {
                recorder.stateTransition(
                    from = "Idle".takeIf { _first } ?: phaseSchema[TraceEventType.PROVIDER_ATTEMPT_STARTED]!!,
                    to = AgentRunPhase.CALLING_MODEL.name,
                    reason = "ProviderAttemptStarted",
                )
                _first = false
                recorder.budgetConsume(
                    dimension = AgentTraceRecorder.DIMENSION_PROVIDER_ATTEMPTS,
                    consumed = 1,
                    remaining = 0,
                    total = 0,
                )
            }
            TraceEventType.TOOL_STARTED -> {
                recorder.toolCall(
                    turn = 0,
                    toolId = "tool-${event.atMs}",
                    name = event.detail ?: "unknown",
                    argsJson = "{}",
                )
            }
            TraceEventType.TOOL_FINISHED -> {
                recorder.toolResult(
                    turn = 0,
                    toolId = "tool-${event.atMs}",
                    name = event.detail ?: "unknown",
                    success = true,
                    output = "{}",
                    durationMs = 0,
                )
            }
            TraceEventType.COMPACT_STARTED -> {
                recorder.stateTransition(
                    from = AgentRunPhase.CALLING_MODEL.name,
                    to = AgentRunPhase.COMPACTING.name,
                    reason = event.detail,
                )
            }
            TraceEventType.COMPACT_FINISHED -> {
                recorder.stateTransition(
                    from = AgentRunPhase.COMPACTING.name,
                    to = AgentRunPhase.CALLING_MODEL.name,
                    reason = event.detail,
                )
            }
            TraceEventType.RETRY_REQUESTED -> {
                recorder.stateTransition(
                    from = AgentRunPhase.CALLING_MODEL.name,
                    to = AgentRunPhase.RETRYING.name,
                    reason = event.detail,
                )
                recorder.retryDecision(
                    operationType = "provider_call",
                    operationName = event.detail,
                    safetyLevel = AgentTraceRecorder.SAFETY_READ_ONLY,
                    outcome = AgentTraceRecorder.OUTCOME_SAFE_TO_RETRY,
                    reason = event.detail,
                    attempt = null,
                    maxAttempts = null,
                    willRetry = true,
                )
            }
            TraceEventType.FALLBACK_SELECTED -> {
                recorder.stateTransition(
                    from = AgentRunPhase.CALLING_MODEL.name,
                    to = AgentRunPhase.FALLING_BACK.name,
                    reason = event.detail,
                )
            }
            TraceEventType.USER_CANCELLED -> {
                recorder.stateTransition(
                    from = AgentRunPhase.CALLING_MODEL.name,
                    to = AgentRunPhase.FINALIZING.name,
                    reason = "UserCancelled: ${event.detail}",
                )
            }
            TraceEventType.DEADLINE_REACHED -> {
                recorder.stateTransition(
                    from = AgentRunPhase.CALLING_MODEL.name,
                    to = AgentRunPhase.FINALIZING.name,
                    reason = "DeadlineReached",
                )
            }
            TraceEventType.PROCESS_INTERRUPTED -> {
                recorder.stateTransition(
                    from = AgentRunPhase.CALLING_MODEL.name,
                    to = AgentRunPhase.FINALIZING.name,
                    reason = event.detail ?: "ProcessInterrupted",
                )
            }
            TraceEventType.PERSISTENCE_FAILED -> {
                recorder.persistenceResult(
                    target = "message_db",
                    success = false,
                    errorType = "write_failure",
                    durationMs = 0,
                )
            }
            TraceEventType.RUN_FINALIZED -> {
                val terminal = when {
                    event.detail?.contains("SUCCEEDED") == true -> AgentTerminal.SUCCEEDED
                    event.detail?.contains("CANCELLED") == true -> AgentTerminal.CANCELLED
                    event.detail?.contains("INTERRUPTED") == true -> AgentTerminal.INTERRUPTED
                    else -> AgentTerminal.FAILED
                }
                recorder.stateTransition(
                    from = AgentRunPhase.FINALIZING.name,
                    to = terminal.name,
                    reason = event.detail,
                )
                recorder.endRun(
                    terminalState = terminal.name,
                    terminalReason = null,
                    durationMs = if (_startMs > 0) event.atMs - _startMs else 0,
                )
            }
            TraceEventType.SPAWN_REJECTED -> {
                recorder.error(turn = null, phase = AgentRunPhase.EXECUTING_TOOLS.name, message = "spawn rejected")
            }
        }
    }

    private var _first = true
    private var _startMs = 0L

    /** run 开始时刻（单调），用于 duration 计算。 */
    fun markStart(atMs: Long) {
        _startMs = atMs
    }

    companion object {
        /** 生产 sink：创建写文件（或转发）的 recorder。sink 由调用方提供。 */
        fun createWithSink(
            runId: String,
            sessionId: String,
            sink: (String) -> Unit,
        ): RealTraceRuntime {
            val recorder = AgentTraceRecorder(appendLine = sink)
            return RealTraceRuntime(recorder, runId, sessionId)
        }
    }
}