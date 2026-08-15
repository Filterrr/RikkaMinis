# T4-B 验收清单 — F01-F14 在真实主链上的断言条件

> 版本：v1.0（2026-08-15）
> 基线：main `314e5c3`（T4-A 已合并）
> 依赖：T7（Agent Run 主链接入，`ChatViewModel.kt`）完成后本清单才可执行。
> 本清单是 `failure-matrix.md`（T0）在 T4-B 视角的**可执行化**：每个场景列出
> 在真实主链上必须断言的具体值/条件。断言执行器 = `ScenarioVerifier` +
> 本清单的补充检查（生产侧特有）。

## 0. 执行方式（T7 完成后）

1. 用 `RealAgentAdapter` 的真实实现（`AgentRuntimePort` 接 T7 主链）替换
   `HarnessRunner` 运行 `FaultScenarios.all()` 的每个场景；
2. 对每个场景：
   - 跑 `ScenarioVerifier.verify(scenario, report)` —— 断言公共契约；
   - 跑本清单 §2 的**场景专属断言**（生产侧特有，verifier 未覆盖）；
3. 全部通过才算 F01-F14 在真实主链上验收通过（蓝图 T7 验收：T7 至少 F01-F08，
   T4-B 扩展到 F01-F14 并独立确认）。

## 1. 公共断言（每个场景，已由 ScenarioVerifier 覆盖）

| # | 断言 | verifier 映射 |
|---|---|---|
| C1 | 终态唯一且符合期望 | `terminal` vs `expect.terminal` |
| C2 | provider attempt 计数精确 | `providerAttempts` vs `expect.providerAttempts` |
| C3 | 工具执行计数精确 | `toolExecutions` vs `expect.toolExecutions` |
| C4 | 重复副作用 = 0 或可解释 | `duplicateSideEffects` vs `expect.duplicateSideEffects` |
| C5 | 预算快照与消耗一致 | `budgetSnapshot` + `expect.budget` |
| C6 | 终态后资源全部释放 | `leaseCount == 0`（`expect.leasesReleased`） |
| C7 | trace 终态事件恰好 1 次 | `traceTerminalEvents == 1` |
| C8 | 持久化标记符合终态语义 | `persistenceMark` vs `expect.persistence` |
| C9 | recoverable 语义正确 | `recoverable` vs `expect.recoverable` |
| C10 | 冷却/取消/spawn/compact 计数 | `cooldownCount` / `providerCancellations` / `toolCancellations` / `spawnRejected` / `compactCalls` |

## 2. 场景专属断言（生产侧特有）

> 状态标注：`Verified` = T4-A fake runner 已验证；`Observed` = 代码观察；
> `Assumed(await T7)` = 依赖 T7 冻结接口，可能调整。

### F01 — 第一个 provider 429，fallback 成功
- Verified：attempt=2、cooldownCount=1、终态 Succeeded、persistence=COMPLETED
- Assumed(await T7)：真实 429 时 `GroupRouter` 把成员标记 Cooling；冷却时长按
  `RATE_LIMIT_COOLDOWN_DEFAULT_MS=60_000`（若 T7 保留）
- 补充断言：trace 含 `RETRY_REQUESTED` + `FALLBACK_SELECTED` 事件；fallback 成员
  的 attempt 被预算计数

### F02 — 所有 provider 失败
- Verified：attempt=3、终态 Failed、persistence=NONE
- 补充断言：错误已暴露给用户（UI 错误状态或 inline error）；**无悬挂 retry**
  （run 结束后没有后台协程继续尝试）；budget snapshot 的 providerAttemptsUsed 不再增长

### F03 — stream reset → retry/fallback 次数有限
- Verified：attempt=2（reset + fallback 成功）、终态 Succeeded
- 补充断言：retry 总数 ≤ 预算上限；trace 含 `RETRY_REQUESTED`；不无限重试
  （跑完场景后等待一个稳定窗口，确认无新 attempt 事件）

### F04 — 首 chunk 后 provider 断流
- Verified：终态 Interrupted、persistence=PARTIAL、recoverable=true
- 补充断言：**已收 chunk 保留在 UI 状态**（不丢已显示内容）；持久化标记
  partial/interrupted，**不得标记 completed**（蓝图 §4.2 不变量 6）

### F05 — 工具执行失败 → tool_result 回传 → loop 收敛
- Verified：attempt=2（工具轮 + 最终答案轮）、toolExecutions=1、终态 Succeeded
- 补充断言：工具失败结果回传 LLM（下轮上下文包含 tool_result）；loop 收敛
  （turn 数 ≤ 预算，无死循环）；tool execution count=1（无隐式重跑）

### F06 — 工具副作用已发生，返回前 shell 死亡
- Verified：终态 Interrupted、duplicateSideEffects=0、recoverable=true
- 补充断言：**不重复执行**（真实 `ExecutionCoordinator` 不透明重跑 UNKNOWN/
  NON_IDEMPOTENT_WRITE 命令）；返回 OutcomeUnknown 语义（T3 `RetryOutcome`）；
  状态检查优先于重跑（若 shell 死亡后需恢复，先查副作用是否已发生）

### F07 — 用户在 provider 调用中取消
- Verified：终态 Cancelled、providerCancellations=1、persistence=PARTIAL
- 补充断言：provider job 已取消（真实 provider 调用被 cancel）；session 槽位释放
  （`SessionConcurrencyManager` lease 归还）；**无新 provider attempt 开始**

### F08 — 用户在工具调用中取消
- Verified：终态 Cancelled、toolCancellations=1
- 补充断言：tool slot / shell / session 全部释放（`AgentExecutionBudget`
  `releaseToolSlot` + session lease）；无新 tool job 创建

### F09 — compact 超时
- Verified：终态 Interrupted、compactCalls=1、historyIntact=true、recoverable=true
- 补充断言：**原始历史不损坏**（compact 未完成时原始消息不变，只产生新
  marker/summary，蓝图 T5 不变量）；compact 计数入预算（`consumeCompaction`）

### F10 — 五会话并发，第六个排队
- Verified（fake 层）：active≤5、FIFO、取消队中 waiter 不复活
- Assumed(await T7)：真实场景由 `SessionConcurrencyManager`（T1）覆盖；
  T4-B 在真实主链验证：6 个并发 run → active≤5、第 6 个排队并在空位后获得槽位
  或被明确取消；排队不 deadlock（有取消路径）

### F11 — deadline 到达
- Verified：终态 Interrupted、providerCancellations=1、budget expired=true
- 补充断言：deadline 后**不再发新 provider/tool 请求**（`AgentExecutionBudget`
  `isExpired` 门禁生效）；trace 含 `DEADLINE_REACHED`；budget snapshot isExpired=true

### F12 — persistence 写入失败
- Verified：终态 Failed、persistence=FAILED（不伪装 Succeeded）
- 补充断言：必需持久化失败时 `RunFinalized(SUCCEEDED)` 被 reducer 拒绝
  （`SUCCEED_WITH_PERSISTENCE_FAILURE`）；trace 保留（trace 写失败是另一路径，
  此处指 DB/文件写入）；错误可见

### F13 — 子 agent 试图递归 spawn
- Verified：终态 Succeeded、spawnRejected=1
- 补充断言：递归 spawn 被拒绝且消耗可解释（拒绝原因进 trace/budget，
  `tryReserveChildBudget` 或深度检查）；不产生无限递归

### F14 — process death / restart 模拟
- Verified：终态 Interrupted、duplicateSideEffects=0、recoverable=true
- Assumed(await T7)：真实进程死亡由 T8（Interrupted/OutcomeUnknown 恢复语义）
  覆盖；T4-B 验证：重启后 open run 被标为 interrupted 或可安全恢复；不默认
  成功；不重复未知副作用

## 3. 验收通过标准（T7 完成后一次性确认）

1. `FaultMatrixScenarioTest` 等价测试以真实 adapter 跑 F01-F14，全部通过；
2. §2 每个 Assumed 项在 T7 冻结后复核，标注 Verified 或记录偏差；
3. 正常成功路径（无故障）的 provider/tool 调用数与 T4-A fake runner 一致；
4. 任意终态都释放 session/tool/shell 资源（§1 C6）；
5. trace 终态事件只出现一次（§1 C7）。

## 4. 当前状态

- 执行器代码：`harness/adapter/`（接口 + 骨架 + 纯逻辑映射 + 结构测试）✅
- 场景定义：`harness/scenarios/FaultScenarios.kt`（T4-A）✅
- 断言引擎：`harness/runner/ScenarioVerifier.kt`（T4-A）✅
- **阻塞**：T7（`ChatViewModel.kt` 主链接入）—— `AgentRuntimePort` 真实实现
  未冻结，§2 的 Assumed 项不可验证。
