# RikkaMinis 运行时契约（基线冻结版）

> T0 交付物之一。编制基线：`9672e09e`（2026-08-15 00:18:09 +0800，origin/main）。
> 本文件只冻结现状 + 目标契约，**不修改任何生产行为**。
> 编制日期：2026-08-15。
>
> **⚠️ 历史文档（2026-08-30 标注）：本文件是 2026-08-15 的基线快照，其中的
> 行数/文件数/T 系列任务进度均已过期——这些 T 任务（T1-T9）已全部完成并合入
> main，契约内容也已落地到生产代码。行数与任务状态请看当前 main 的实际代码，
> 本文件仅保留当时的契约定义与验收口径，供追溯设计意图。**

---

## 1. 基线快照

| 项 | 值 |
|---|---|
| 仓库 | `logicflow-GYW/RikkaMinis` |
| 基线 commit | `9672e09e277d13904baac6106e2a903d6249bd47` |
| 基线 commit 消息 | `cleanup: remove orphaned fallback-comment + duplicate line` |
| 基线时间 | 2026-08-15 00:18:09 +0800 |
| Gradle 模块 | 单模块 `:app`（`settings.gradle.kts` 只 include `:app`） |
| 主源码 | 404 个 `.kt` 文件，145,261 行（`src/android/app/src/main`）——**过期，现为 448 文件 / ~159,859 行（main @ d49235c）** |
| 测试源码 | 69 个 `.kt` 文件，13,733 行（`src/android/app/src/test`）——**过期，现为 187 文件 / ~34,598 行** |
| 测试/主源码比 | ≈ 9.5%（行数口径）——**过期，现约 17.8%** |
| CI workflow | `build-apk.yml`（main push + workflow_dispatch）、`scan-gate.yml`（PR）、`sync-upstream.yml` |
| 构建目录 | `src/android/`（`./gradlew` 在 `src/android/` 下执行） |

## 2. 测试命令（当前唯一 ground truth）

来自 `.github/workflows/build-apk.yml`：

```text
# 单元测试（release 变体，JVM）
./gradlew testReleaseUnitTest --stacktrace

# 仪器测试（真机/模拟器，仅编译不运行）
./gradlew compileDebugAndroidTestKotlin --stacktrace

# 构建 APK
./gradlew assembleRelease --stacktrace
```

静态扫描门禁（build-apk.yml 的 Pre-build 阶段 + scan-gate.yml）：

```text
sh scripts/scan/scan.sh
# 内含：four_way_sync_check.py（四处同步）、i18n_check.py（孤儿键）、
#       enum_parse_safety_check.py（裸 valueOf）
```

已知测试限制（T0 记录，供后续任务参照）：

- ~~沙箱 PRoot 拦截 JVM 内存标记（PaX），沙箱内无法运行 gradle 单测~~ **已过时**：沙箱内可跑纯 Kotlin/JVM 单测（Java 17 + kotlinc 编译 + JUnitCore），见 `sandbox-jvm-testing` skill。Gradle 全量单测仍依赖 CI。
- 仪器测试（androidTest）需要真机，CI 只编译不运行。
- 测试环境 JVM 无 Android 框架，凡依赖 `android.content.*` / `android.os.*` 的代码无法直接单测，必须先抽纯逻辑层。

## 3. 热点文件唯一 Owner（T0 冻结，后续任务必须服从）

> ⚠️ 本节的 T 系列任务已全部闭环，Owner 与「阶段交接」约束已随施工结束失效；
> 保留此表仅用于追溯当时的分工设计，不再是当前约束。

| 文件 | 路径 | 行数 | 唯一 Owner | 阶段交接 |
|---|---|---|---|---|
| `ChatViewModel.kt` | `ui/chat/ChatViewModel.kt` | 11,262 | **T7** | T7 期间其他任务禁止修改；T7 之后归 T8/T10 只读 |
| `ExecutionCoordinator.kt` | `sandbox/ExecutionCoordinator.kt` | 828 | **T3 → T7** | T3 抽/扩纯策略函数；T7 阶段接入主链时交接 |
| `AgentTraceRecorder.kt` | `tools/AgentTraceRecorder.kt` | 257 | **T6** | T6 扩展 schema；T4-B/T7 只读使用 |
| `SessionConcurrencyManager.kt` | `service/SessionConcurrencyManager.kt` | 62 | **T1** | T1 重构后 T7 只读使用 |
| `build.gradle.kts` / workflow | `src/android/app/build.gradle.kts`、`.github/workflows/*` | — | **T9/T10** | 性能门禁与 CI 变更由 T9 独占 |

约束：

- `ChatViewModel.kt` 同一时间只允许一个任务负责人修改（冲突矩阵见蓝图第 7 节）。
- 新增纯逻辑文件（如 `agent/runtime/AgentExecutionBudget.kt`、`AgentRunState.kt`）不属于任何现存文件，按蓝图第 6 节各任务自建。
- `docs/stability/` 目录由 T0 建立，T6/T9 可在各自章节追加观测记录，不得改写其他任务的已冻结结论。

## 4. Agent Run 终态契约（目标，4.1-4.2 节）

一次用户请求最终只能归入以下四种终态之一：

```text
Succeeded   回答、工具结果和必要的持久化全部完成
Failed      执行失败，错误已暴露，临时状态已清理
Cancelled   用户主动取消，后台请求、工具和资源都已停止或进入明确的取消状态
Interrupted 进程死亡、系统回收、达到总 deadline 或执行结果未知，不能伪装成成功
```

`Interrupted` ≠ `Failed`。它表示"系统无法证明执行没有发生副作用"或"结果没有完整落库"。

每个终态必须满足的不变量（对任意 Agent Run `R`）：

```text
1. R 有且只有一个终态。
2. R 的所有 provider attempt、tool call、compact call 都已经结束、取消或明确标记 unknown。
3. R 不再持有会话并发槽位。
4. R 不再持有 shell、WebView、临时文件写入或其它可计数资源。
5. R 的 streaming / awaiting / retrying 标志不会继续驱动后台工作。
6. 若存在部分输出，持久化状态必须标记为 partial/interrupted，而不能标记 completed。
7. 自动恢复不会重复执行无法证明幂等的副作用操作。
8. trace 中存在 start 和 terminal event，且 terminal event 只出现一次。
```

## 5. 全局资源预算契约（目标，4.3 节）

第一版字段（蓝图冻结，T2 实现，数字由 T2/T9 另行确定，T0 不擅自选择）：

```kotlin
data class AgentExecutionBudget(
    val startedAtMonotonicMs: Long,
    val deadlineMonotonicMs: Long,
    val maxTurns: Int,
    val maxProviderAttempts: Int,
    val maxToolCalls: Int,
    val maxShellCommands: Int,
    val maxCompactionCalls: Int,
    val maxConcurrentTools: Int,
    val maxEstimatedTokens: Long?,
)
```

原则：

- retry、fallback、compact、subagent 都消耗同一份父预算；
- 预算消耗必须是可追踪的事件，而不是散落的计数器；
- 预算不能回退，除非明确是预留后取消的资源；
- 已知 token usage 才能消耗 token 预算，未知值必须记录 unknown，不能伪造精确计数；
- 第一阶段允许预算只做 advisory/trace，不改变生产行为；
- 在故障 Harness 和真实基线通过之前，不启用激进硬阈值。

### 现状盘点（Observed，供 T2 参照）

当前生产代码中存在的"近似预算"（均非贯穿整轮的总账）：

- `ChatViewModel.kt`：`MAX_AGENT_TURNS = 200`（单轮 agent loop 的 turn 上限）、`COMPACT_KEEP_RECENT_USER_TURNS = 3`、context 相关阈值（NEEDS_COMPACT 等，见 `ContextCompactor.kt`）。
- `ExecutionCoordinator.kt`：`NATIVE_HEAP_HIGH_WATER_MARK_MB = 256L`、`APP_NATIVE_HEAP_HIGH_WATER_MARK_MB = 120L`、全局并发 shell 上限（Semaphore）、shell 生成分阶段预算（`internalDegradationPhase`：NORMAL/MILD/MODERATE/SEVERE/CRITICAL/LOCKED）。
- `SessionConcurrencyManager.kt`：`MAX_CONCURRENT = 5`。
- provider 层：429 冷却（`GroupRouter.kt` RATE_LIMIT_COOLDOWN_DEFAULT_MS=60_000）、fallback 链、重试次数。

这些是局部护栏，**不是**蓝图要求的"贯穿整轮 Agent Run 的共享预算"。T2 建立总账后，T7 以 adapter 接入，禁止在本阶段混入。

## 6. 自动重试副作用等级契约（目标，4.4 节）

| 等级 | 含义 | 透明自动重试 |
|---|---|---|
| `READ_ONLY` | 只读、无外部副作用 | 可以，在预算内 |
| `IDEMPOTENT_WRITE` | 重复执行结果可证明相同 | 只有有幂等键或执行后校验时可以 |
| `NON_IDEMPOTENT_WRITE` | 重复执行可能重复副作用 | 不可以透明重试 |
| `UNKNOWN` | 系统无法证明安全性 | 默认不可以 |

`shell_execute` 默认必须是 `UNKNOWN`，除非调用方显式声明并提供验证方式。命令超时或 shell 在返回结果前死亡时，正确结果不是"重跑"，而是：

```text
OutcomeUnknown → 检查状态 → 决定恢复/报告
```

副作用等级只能由受信任的 Kotlin 调用点、内部工具注册表或系统内建 adapter 指定。**不能接受 LLM 在工具参数里自报安全等级后直接信任**。若未来允许 shell 调用方提供安全提示，该提示只能降低自动化权限，不能把 `UNKNOWN` 提升为更安全等级。

### 现状盘点（Observed，供 T3 参照）

- `ExecutionCoordinator.kt:691` 已有纯函数 `internalShouldRetryCommand(exitCode, shellAlive, attempt, maxRetries=2)`：仅当 shell 死亡（`exitCode == -1 || 124 || !shellAlive`）且未达上限时重试；正常非零退出或 shell 存活不重试。这是现有 shell 自愈的重试决策点，**没有副作用等级概念**——所有命令一视同仁按 shell 死亡重试。
- 无 `RetrySafety` / `RetryOutcome` 模型；无工具级副作用分类注册表。
- `CommandResult` 无 `outcomeKnown` / `retrySafety` 字段（T3 若改返回模型需向后兼容或加 adapter，见蓝图 T3 章节）。
- 已有测试：`ExecutionCoordinatorRetryTest.kt`（T3 必须保留并扩展）。

## 7. 资源与并发现状盘点（Observed，供 T1/T7 参照）

`SessionConcurrencyManager.kt`（62 行，T1 目标）已知风险（蓝图 + 本次代码确认）：

- `acquireSlot()` 的容量检查（`_runningSessions.value.size < MAX_CONCURRENT`）与写入（`_runningSessions.value = ... + sessionId`）不在同一同步区间——并发到达时理论上可同时越过容量检查。
- 内部用 `Set<String>` 按 `sessionId` 去重：同一 sessionId 的多个逻辑运行会被 Set 合并，静默掩盖。
- `waitQueue` 用 LinkedList 手动管理 waiter；`releaseSlot` 用 `@Synchronized` 但 `acquireSlot` 的检查-入队路径不在同一锁下。
- 无 runId 概念；`isSuspended()` 存在但调用面待 T1 核查。

`ExecutionCoordinator.kt` 的并发模型（本次代码确认）：

- 每会话一个 `PersistentShell` + 每会话 Mutex（同会话命令串行，跨会话并行）；
- 全局 shell 创建锁防重复创建；全局 Semaphore 限制并发命令数；
- shell 死亡在下次命令时检测并重建（同 bind mounts）。

## 8. Trace 现状盘点（Observed，供 T6 参照）

`AgentTraceRecorder.kt`（257 行，纯 JVM，注入 `appendLine` + `clock`）：

- 事件类型：`trace_start / turn_start / tool_call / tool_result / turn_end / trace_end / error`。
- 载荷截断：prompt ≤300 字符、tool args ≤500 字符（防 trace 膨胀）。
- 宿主（ChatViewModel）负责把行写入会话工作区 `workspace/.traces/agent-<ts>.jsonl`。
- **没有**：budget consume/refuse 事件、state transition 事件、resource acquire/release 事件、terminal reason、schema version。
- 已有测试：`AgentTraceRecorderTest.kt`。

## 9. 本契约的验收标准

- 文档与当前源码入口一致（本文件所有路径、行数、常量均为 `9672e09e` 实测）；
- 能从本文档定位每个施工任务的代码 owner（第 3 节）；
- 已记录当前已知测试限制（第 2 节）；
- T0 未修改任何生产代码（git diff 只含 `docs/stability/`）。
