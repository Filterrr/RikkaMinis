# RikkaMinis 故障矩阵（F01-F14）

> T0 交付物之二。基线：`9672e09e`（origin/main，2026-08-15）。
> 本文件是 T4-A（fakes + 场景协议 + 独立 runner）和 T4-B（生产 adapter）的共用场景表。
> 每个场景必须断言右侧列出的全部项目，**禁止只验证错误字符串**。

---

## 0. 使用约定

- 每个场景 = 一组 fake 输入 + 一组必须断言。
- Fake 组件由 T4-A 提供：`FakeClock / FakeProvider / FakeToolExecutor / FakeShell / FakePersistence / FakeTraceSink / FakeSessionSlots`。
- 每个场景断言清单（公共部分，所有场景都要）：
  - terminal state（终态）；
  - provider attempts（attempt 次数）；
  - tool execution count（工具执行次数）；
  - duplicate side effect count（重复副作用计数，必须为 0 或可解释）；
  - budget snapshot（预算快照）；
  - resource lease count（资源租约数，终态后应为 0 或明确释放）；
  - trace terminal event count（必须恰好 1）；
  - persistence 状态（落库内容标记）；
  - 是否仍允许恢复（recoverable: true/false）。

禁止：

- 连接真实网络；
- 依赖真实 Android 生命周期；
- 用随机 sleep 制造竞态；
- 把 fake 测试写成只验证错误字符串。

## 1. 场景表

| ID | 故障 | 必须验证（除公共断言外） |
|---|---|---|
| **F01** | 第一个 provider 返回 429，fallback 成功 | attempt 预算计数正确；冷却状态记录（Cooling/cooldown）；终态 Succeeded；fallback 事件进入 trace |
| **F02** | 所有 provider 都失败（fallback 链耗尽） | 终态 Failed；无悬挂 retry（无后台继续）；attempt 数 = 预算上限且不再增加；错误已暴露给用户 |
| **F03** | stream reset（HTTP/2 流重置，如 CANCEL） | retry/fallback 次数有限（≤预算）；不无限重试；终态明确（Succeeded via fallback 或 Failed） |
| **F04** | 首个 chunk 之后 provider 断流 | 部分输出被标记 partial/interrupted（**不得标记 completed**）；终态 Interrupted 或 Failed（语义明确）；已收 chunk 保留在 UI 状态 |
| **F05** | 工具执行失败 | tool_result 回传 LLM；loop 收敛（不无限循环）；tool execution count = 1（无隐式重跑）；终态明确 |
| **F06** | 工具副作用已发生，返回前 shell 死亡 | **不重复执行**（duplicate side effect count = 0）；返回 OutcomeUnknown；终态 Interrupted 或按恢复策略处理；状态检查优先于重跑 |
| **F07** | 用户在 provider 调用中取消 | 终态 Cancelled；provider job 已取消；session 槽位释放；无新 provider attempt 开始 |
| **F08** | 用户在工具调用中取消 | 终态 Cancelled；tool slot / shell / session 全部释放；无新 tool job 创建 |
| **F09** | compact 超时 | 原始历史不损坏（不被覆盖）；Run 终态明确（Failed 或 Interrupted，不伪装 Succeeded）；compact 计数入预算 |
| **F10** | 五个会话并发，第六个排队 | active ≤ MAX_CONCURRENT(5)；FIFO 顺序；取消队中 waiter 不复活；第六个最终获得槽位或明确取消 |
| **F11** | deadline 到达（总预算 deadline） | 不再发新 provider/tool 请求；终态 Interrupted（deadline reason）；budget snapshot 显示已到期；trace 记录 deadline 事件 |
| **F12** | persistence 写入失败 | 不伪装成功（终态 Failed/Interrupted，不 Succeeded）；trace 保留（trace 写失败是另一条路径，此处指 DB/文件写入）；错误可见 |
| **F13** | 子 agent 试图递归 spawn | 被拒绝；消耗可解释（有明确的拒绝原因进 trace/budget）；不产生无限递归 |
| **F14** | process death / restart 模拟 | 重启后 open run 被标为 interrupted 或可安全恢复；不默认成功；不重复未知副作用 |

## 2. 场景协议（T4-A 交付格式）

每个场景以可执行协议形式交付，形如：

```text
scenario F01:
  provider:
    - attempt1: 429 (with Retry-After absent)
    - attempt2: success, stream ok
  tool: none
  shell: none
  user_cancel_at: null
  deadline: far future
  expect:
    terminal: Succeeded
    provider_attempts: 2
    tool_executions: 0
    duplicate_side_effects: 0
    budget: provider_attempts_consumed=2
    leases_released: true
    trace_terminal_events: 1
    persistence: completed
    recoverable: false
```

T4-A 交付：`src/android/app/src/test/.../harness/` 下的 fake 组件 + 场景定义 + 独立 runner（纯 JVM，可重复运行）。
T4-B 交付：把同一批场景挂接真实 Agent Run adapter（T7 提供），不改场景协议本身。

## 3. 与各任务的关系

- F01/F02/F03/F05 → 主要验证 provider retry/fallback + budget（T2）+ 终态（T5）。
- F04/F12/F14 → partial/interrupted 语义（T5/T8）+ persistence。
- F06 → 副作用重试策略（T3）——"副作用已发生但返回丢失"是 T3 的必须测试。
- F07/F08/F10 → 会话并发与取消（T1）。
- F09 → compact（现有 ContextCompactor + T2 budget）。
- F11 → deadline（T2/T7-C）。
- F13 → subagent 预算继承（T2/T7）。

## 4. 现状参照（Observed，基线时刻）

- 现有测试中与 F 系列最接近的：`ExecutionCoordinatorRetryTest.kt`（shell 死亡重试决策）、`GroupRouterTest.kt`（429 → Cooling 冷却）、`AnthropicProviderTest` / `OpenAIProviderTest` / `GeminiProviderTest` / `ThinkTagExtractionTest`（provider 层分块/断流行为）。
- **不存在**整轮执行的故障注入 Harness；无 FakeProvider/FakeToolExecutor 基础设施（T4-A 全新建立）。
- 已确认的基线行为：`internalShouldRetryCommand` 只按 shell 死亡重试（无副作用等级）；429 冷却 60s 默认（无 Retry-After header 时）；fallback 链成员重试行为在 `b8dec8c3` 恢复为"所有成员有限重试"。

## 5. 验收

- F01-F14 均能稳定重复运行（无随机时序依赖）；
- 失败时能指出违反的是：终态 / 预算 / 资源 / 持久化 / 副作用语义（五类之一）。
