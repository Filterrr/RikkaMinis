# PR #66–#70 审计报告 · RikkaMinis (Filterrr/RikkaMinis)

审计日期：2026-10-21 · 审计范围：`main` @ `d078b651`（#66~#70 全部已合入）
方法：读 diff → 定位可疑逻辑 → 用 kotlinc 直编真实生产类做探针复现 → 负向确认归因

---

## 结论概览

| # | 严重度 | 归属 | 一句话 |
|---|--------|------|--------|
| B1 | **高** | #68 | hygiene 门判的是「全轮累积文本」而非「模型最后一条消息」→ 空壳报告照旧直接交给父 agent |
| B2 | **高** | #67 | 并行工具调用产生 N 条连续 USER 消息 → 新修复在并行场景下**伪造出比原来更多**的「用户打断」提示 |
| B3 | **中** | #69 | 「没干活」信号只活在 inline 结果的 header 里 → detached / join / 后台唤醒三条路径完全丢失 |
| B4 | **中** | #69/#70 | verdict 只存在于 `SubagentResult` → detached 路径（`JobOutcome` / registry `Run`）根本没有这个字段 |
| B5 | **中** | #66 | `executedToolCalls` 把被 loop-detector **拦截**的调用也计为「已执行」→ 0 工具的真跑被判 COMPLETED |
| B6 | 低 | #66/#69 | `turn_complete` 出口硬编码 `STOPPED_EARLY` 且文案硬编码 `1/$maxTurns`，与 `assess()` 的优先级规则矛盾 |
| B7 | 低 | #70 | `NO_OUTPUT` 的文案让父 agent 去读 journal，但**空输出是唯一不写 journal 的出口** |

---

## B1 · 空壳报告门被「全轮累积文本」稀释（PR #68）

**位置**：`SubagentRunner.kt:1679`（`finalText = resultSb.toString()`）→ `:1715`（`judge(finalText)`）

**机制**：`resultSb` 累积**每一轮**的旁白文本。#68 的退化判定是「含合成标签 **且** 剥掉标签后剩余实质字符 < 12」。只要任何一轮有过正常旁白，剩余字符就冲过阈值，门永不触发。

**实测**（`SubagentReportHygiene.judge` 真实调用）：

| 场景 | 剥标签后实质字符 | degenerate | 结果 |
|---|---|---|---|
| 只有空壳（现场案例） | 4 | ✅ true | 触发摘要（符合预期） |
| 计划轮旁白 + 空壳 | 41 | ❌ false | **原始空壳直接交给父 agent** |
| 一轮旁白 + 空壳 | 27 | ❌ false | **同上** |
| 旁白 + 工具轮 + 空壳 | 50 | ❌ false | **同上** |

父 agent 实际收到的 prompt：

```
Sub-agent 'general-agent' ran 2 turn(s).
[completed] 3 tool calls executed; the report below is the model's own account.

---
I'll check the current date and disk layout first.
<parameter name="status">done</parameter>
```

**加重因素**：#66 引入了「计划轮」，而计划轮**必然**带一段旁白（`I will gather the evidence...`）。也就是说 #66 让 #68 的门**更难触发**——两个 PR 互相削弱。#68 的现场案例是「单轮空壳」，恰好是唯一能触发的形状。

**次生问题**：`:1717` 的日志把 `finalText.length` 称作 "the model's final message" 长度——同一个 `finalText` 实际是全轮累积，误导排查。

**归因**：#68 之前无 `SubagentReportHygiene`（`git show cdbf8df0^` 计数 = 0）。**由 #68 引入**。

**建议**：门应判**最后一条 assistant 消息**，而不是累积文本；或对累积文本改用「标签行之外无实质」的判定。同时把 `plan_first` 的计划轮旁白排除在稀释源之外。

---

## B2 · 并行工具调用下「幻影打断」比修复前更多（PR #67）

**位置**：`SubagentRunner.kt:1545`（`applyBridges`）；子 agent 每个 tool call 单独 append 一条 USER 消息（`:1433`）

**机制**：子 agent 循环按 `for ((call, result) in toolCalls.zip(results))` **逐个** append USER 消息。一轮里 N 个并行工具 → N 条**连续** USER 消息。

#67 的新判据是「相邻两条 USER → 插一条 assistant bridge」。于是：
- N=1 → 0 条（修好了）
- N=2 → 1 条
- N=3 → **2 条**
- N=4 → **3 条**

旧代码（`history.last().role == USER`）在这几种情况下**恒为 1 条**。

**实测 A/B**（真实 `SubagentHistoryRepair.applyBridges`）：

```
 n | OLD(pre-#67) | NEW(post-#67) | delta
 1 |      1      |      0      | 修好
 2 |      1      |      1      | 持平
 3 |      1      |      2      | 更差 +1
 4 |      1      |      3      | 更差 +2
 5 |      1      |      4      | 更差 +3
```

N=3 时父 provider 实际收到的 history：

```
[0] USER   parts=[]
[1] ASSISTANT parts=[USE(call_0), USE(call_1), USE(call_2)]
[2] USER   parts=[RESULT(call_0)]
[3] ASSISTANT parts=[TEXT(bridge)]   <-- 伪造的「用户打断」
[4] USER   parts=[RESULT(call_1)]
[5] ASSISTANT parts=[TEXT(bridge)]   <-- 伪造的「用户打断」
[6] USER   parts=[RESULT(call_2)]
```

**为什么高频**：`ToolConcurrencyPolicy` 的并行安全工具包含 `file_read` / `read_image` / `memory_get` / browser 读动作。一条消息里发 3 个 `file_read` 是**常规操作**，不是边角情况。

**对照主循环**：主循环把当轮**所有**工具结果塞进**一条** USER 消息（`ChatViewModel.kt:10186`，`contentParts = resultParts`），从根上避免了连续 USER。子 agent 没跟上这个约定。

**归因**：#67 之前是 `history.lastOrNull()?.role == USER`（已确认 `git show 91e92f57^`）。**由 #67 引入**（在并行场景下是回归）。

**建议**：修法应是「并行工具结果合并成一条 USER 消息」（对齐主循环），或 bridge 判据改为「USER 消息**不带** ToolResult 且与前一条也非 ToolResult」。

---

## B3 · 「没干活」信号在 detached / join / 后台唤醒三条路径全丢（PR #69）

**位置**：`SubagentRunner.kt:1676`（max-turns note）、`:1778`（`deliveredText = reportText`）

**机制**：#66 把「0 次工具调用」警告写进 `resultText`（正文里）。#69 认为 header 已经说了，把正文警告**删掉**了：

```
-        val noWorkWarning = if (executedToolCalls == 0 && !verdict.degenerate) {
-            "\n\n⚠️ [no tool calls were executed in this run — ...]"
-        val deliveredText = reportText + noWorkWarning
+        val deliveredText = reportText
```

新的信号只存在于 `SubagentResult.toPromptText()` 的 **header 行**——而 header 只有 **inline**（`run_until=done`）路径会渲染。

而 detached / join / 后台唤醒读的是 **registry 的 `resultText`**：
- `completeJobFromRegistry()` → `report = snapshot?.resultText`
- `buildWakePrompt()` → `outcome.report`
- join 渲染 → `boundReportForParent(report = outcome.report, ...)`

**实测**：

```
PR#66 registry.resultText 携带警告: true
PR#69 registry.resultText 携带警告: false
```

一个 0 工具的计划轮 detached run，在后台唤醒时父 agent 看到的就是：`[Sub-agent 'X' finished in the background — status SUCCESS]` + 一份计划，**没有任何「这没干活」的提示**。

**归因**：#69 删除了 `noWorkWarning`。**由 #69 引入**（相对 #66 是信息退化）。

**建议**：警告必须留在 `resultText`（或同时进 registry），不能只活在 header。

---

## B4 · verdict 没有覆盖 detached 路径（PR #69/#70）

**位置**：`SubagentOrchestration.JobOutcome`（`SubagentOrchestration.kt:77`）、`SubagentRunRegistry.Run`

**实测**：

```
JobOutcome fields: runId, success, report, skillName, journalPath, error, cancelled
has outcome field?        false
has executedToolCalls field? false
```

`SubagentResult` 新增的 `outcome` / `reportSource` / `executedToolCalls` 三个字段**只在内联路径**存在。detached 路径构造的是 `JobOutcome`（`SubagentRunner.kt:773`），从 registry 快照取字段——而 registry `Run` 里也**没有** verdict 字段（`grep` 无命中）。

结果：detached run 无论是否 0 工具、是否降级，父 agent 看到的永远是 `success=true` + 报告正文。`SubagentRunAssessment.isDeliverable()` 在生产代码里**一次都没被调用**（只在测试里用）。

**建议**：verdict 落进 registry `Run`（或 `JobOutcome`），让 join / wake 复用同一套判定。

---

## B5 · 被拦截的工具被计入「已执行」（PR #66）

**位置**：`SubagentRunner.kt:1375`

**机制**：`executedToolCalls += toolCalls.size` 统计的是**提议**的调用数，不是**真正执行**的。而同一段代码里，loop-detector 判 CRITICAL 时**根本不执行**该工具：

```kotlin
val precheck = loopDetector.check(call.name, paramsMap)
if (precheck.level == CRITICAL) {
    ... ToolExecutionResult(blocked, false, ...)   // 从未执行
} else {
    executeSubagentTool(...)                        // 真正执行
}
}
executedToolCalls += toolCalls.size     // ← 拦截的也算进去了
```

**实测**：

```
提议工具调用 : 20
executedToolCalls : 20   (toolCalls.size)
真正执行     : 0
verdict with 20 'executed' : COMPLETED
verdict with 0  'executed' : UNSTARTED
```

变量自己的注释写着 "Count of tool calls that actually EXECUTED (not merely proposed)"——注释与实现不符。

**影响**：#66 的整个立论是「0 次工具 = 什么都没干」。一个被 loop-detector 反复拦截的 run 会报「COMPLETED，20 次工具调用」，而实际一次都没跑。这是 #69 的 UNSTARTED 判定也被一起绕过。

**归因**：`executedToolCalls` 由 #66 引入。**由 #66 引入**。

**建议**：改用 `results.count { it.wasExecuted }` 之类；或给 `ToolExecutionResult` 加 `executed` 标记。

---

## B6 · `turn_complete` 出口硬编码 verdict 与轮数文案（PR #66/#69）

**位置**：`SubagentRunner.kt:1614`

**问题 1**：该出口直接写 `outcome = Outcome.STOPPED_EARLY`，**不调用 `assess()`**。所以 #70 新增的 `DECLARED_FAILED` 优先级规则在这条路径上不生效——模型自述 `status: failed` 的首轮读出仍报 `[stopped early]`。

**实测**：

```
actual turns = 2, maxTurns = 40, model declared status: failed
父 agent 看到: Sub-agent 'general-agent' first-turn readout (1/40 turns):
               [stopped early] 4 tool calls executed in the first turn, as requested.
assess() 本应给出: DECLARED_FAILED
```

**问题 2**：文案硬编码 `1/$maxTurns`，但 #66 的 nudge 让**第 1 轮变成非终态**——`run_until=turn_complete` 的 run 完全可能在第 2 轮才被读出。文案会说「1/40 turns」而实际是 2 轮。

**归因**：硬编码 outcome 由 #69 引入；`1/$maxTurns` 文案早于本批 PR，但 #66 让它变错。

---

## B7 · `NO_OUTPUT` 文案指向不存在的 journal（PR #70）

**位置**：`SubagentRunAssessment.kt:165`；`SubagentRunner.kt:1681-1701`

**实测**：

```
[no output] 3 tool calls executed, but the run delivered no text at all.
The work may or may not have happened; re-spawn, or read the journal for the raw tool output.
```

但**空输出出口是唯一不写 journal 的出口**（`journal()` 的 5 个调用点：TIMED_OUT / turn_complete / CANCELLED / FAILED / 正常收尾——全都在别处）。父 agent 被指引去读一个不存在的文件。

**归因**：文案由 #70 引入。

**建议**：要么该出口补写 journal，要么文案改成「read the run's tool output in the UI」。

---

## 验证记录

- **上游测试**：#66~#70 的 4 个测试类（48 例）用 kotlinc 直编 + JUnitCore 跑，**47 通过，1 失败**——失败的是 `ProductionSources.read` 找不到源码根目录，**沙箱路径问题，非真实缺陷**。
- **静态门禁**：`scripts/scan/scan.sh` → **5 passed, 0 failed**。
- **探针**：6 个探针对象，全部用**仓库真实生产类**编译运行（`SubagentPlanGate` / `SubagentHistoryRepair` / `SubagentReportHygiene` / `SubagentRunAssessment` / `SubagentResult` / `SubagentSkill` / `SubagentOrchestration`）。
- **归因确认**：逐条用 `git show <pr>^` 确认缺陷代码在对应 PR 之前不存在。

### 未构成缺陷（已排除）

- PR #66 的 `SubagentPlanGate` 优先级逻辑本身正确（7 种组合逐条验证）。
- PR #67 的 `applyBridges` 幂等性正确（重复调用不累积）。
- PR #68 的「长度阈值会误杀简短真实报告」已被 #68 自己用双条件判定修掉（实测「HTTP 200 from example.com」不再被判退化）。
- PR #70 的 `isModelAuthored` 门控正确（digest 的 `status: partial` 不再被当成模型自述）。
- PR #67 的 Pass 1/2 在并行工具场景下**不丢**真实输出（3/3 保留）——问题只在 Pass 3 的 bridge 数量。
