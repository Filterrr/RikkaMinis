# RikkaMinis 性能基线（T9 门禁数据目录）

> 本目录存储**真机采集**的性能基线数据（JSONL），供 CI 的 T9 report-only
> 门禁（`PerfBaselineGateTest`）聚合、展示、对比。

## 目录约定

- `*.jsonl` — 基线数据文件（真机采集，`filesDir/perf-baseline/` 导出后
  随 PR/commit 提交到本目录）；
- `baseline.snapshot.json` — **Phase 2（enforced）快照**：`{ "指标名": p95 }`
  映射，用于 P95 退化 > 15% 即红的门禁对比。Phase 1（report-only）不需要；
- `perf-baseline-report.md` — 本地生成的聚合报告（可提交留档）。

## 采样协议（真机，protocol 见 `docs/stability/performance-baseline.md` §4）

设备：Redmi Note 12 Turbo (marble)，Android 15 + HyperOS 3.0。
场景集（`SyntheticWorkload.Scenario`，6 组共约 48 次 run）：

| 场景 | 次数 | 关键指标 |
|---|---|---|
| COLD_START | 5 | to_idle_ms / config_load_ms |
| SIMPLE_QA | 20 | first_turn_ttfb_ms / flatten / frozen |
| TOOL_CHAIN | 10 | tool 耗时 / shell_rss |
| MULTI_SESSION | 5 | queue_wait / peak heap / active count |
| COMPACT_TRIGGER | 5 | compact_duration / memory after |
| MEMORY_PRESSURE | 3 | peak RSS / thread count / OOM proximity |

采集步骤（app 端）：
1. 应用内置 `PerfBaselineCollector` 会在运行期把数据写入
   `filesDir/perf-baseline/`（每 run 一个 JSONL 段，5000 行轮转）；
2. 采集完一轮后，把 `perf-baseline/` 下的 JSONL 文件
   **按日期合并命名**（如 `2026-08-15-simple-qa.jsonl`）提交到本目录；
3. CI 下次构建时 `PerfBaselineGateTest` 自动聚合并生成报告
   （`build/reports/perf-gate/perf-baseline-report.md`，artifact 可见）。

## Phase 2 开启条件（报告-only → enforced）

同时满足才开闸（避免无基线/无故障 Harness 通过前启用激进硬预算，蓝图 T9 原文）：
1. 本目录已有 ≥1 份真机基线 JSONL；
2. 已生成并提交 `baseline.snapshot.json`（从基线聚合出的 p95 快照）；
3. CI 环境变量 `PERF_GATE_ENFORCE=true`。

未满足时门禁保持 report-only：每轮构建聚合 + 出报告 + 放行。

## 当前状态

- @2026-08-15：**尚无真机基线数据**——门禁处于 report-only（合法 PASS）。
  待用户按上述协议在真机采集 48 次 run 后提交数据。