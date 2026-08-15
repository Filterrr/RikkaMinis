package com.openminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T9 report-only perf gate — executed by every CI build inside
 * "Run unit tests (full suite)" (`testReleaseUnitTest`).
 *
 * ## 门禁语义（蓝图 T9：Phase 1 report-only → Phase 2 enforced）
 *
 * 1. **定位基线目录**：`-Dperf.baseline.dir=<path>` 优先；否则从
 *    gradle 测试工作目录（app 模块）向上走到仓库根，找
 *    `docs/stability/perf-baseline/`。
 * 2. **无基线数据**（目录/文件缺失，真机尚未采集）→ **PASS（report-only）**，
 *    输出提示，不阻断合并。这是 Phase 1 的合法状态。
 * 3. **有基线 JSONL** → `PerfBaselineReport.aggregate()` 聚合，生成
 *    Markdown 报告 + 一行式摘要写入 `build/reports/perf-gate/`，
 *    断言聚合成功、报告非空 —— 门禁执行器本身每轮构建都被验证。
 * 4. **enforced 模式**（仅当环境变量 `PERF_GATE_ENFORCE=true` 且基线目录
 *    存在 `baseline.snapshot.json`，即真机基线已采集并提交后）→ 对比
 *    当前聚合 p95 vs 快照 p95，任一指标退化 > [thresholdPct]% 即失败。
 *    Phase 2 未启用前此分支不触发。
 *
 * 明确边界：本门禁**不采集数据**（真机采样协议见
 * `docs/stability/perf-baseline/README.md`），只负责"有数据可查、有回归能拦"。
 */
class PerfBaselineGateTest {

    private companion object {
        const val THRESHOLD_PCT = 15.0
        const val SNAPSHOT_FILE = "baseline.snapshot.json"
        // CI 写入路径（gradle test 工作目录 = app 模块目录）
        const val REPORT_OUT = "build/reports/perf-gate"
    }

    @Test
    fun `perf gate report-only pipeline runs in CI`() {
        val baselineDir = resolveBaselineDir()
        val reportOut = File(REPORT_OUT)
        reportOut.mkdirs()

        val jsonlFiles: List<File> = baselineDir
            .listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (jsonlFiles.isEmpty()) {
            // 真机基线未采集：合法 report-only PASS
            writeSummary(reportOut, """
                T9 perf gate: REPORT-ONLY PASS (no baseline data yet)
                baseline dir: ${baselineDir.path}
                Protocol: docs/stability/perf-baseline/README.md
            """.trimIndent())
            return
        }

        // 有数据 → 聚合 + 生成报告（执行器本身被验证）
        val report = PerfBaselineReport.aggregate(
            files = jsonlFiles,
            title = "CI Report-Only Gate — ${jsonlFiles.size} baseline file(s)",
        )
        assertEquals("aggregate must index every parsed event", report.totalEvents > 0, true)
        assertTrue("report markdown must be non-empty", report.toMarkdown().isNotBlank())

        val mdFile = File(reportOut, "perf-baseline-report.md")
        mdFile.writeText(report.toMarkdown())
        writeSummary(
            reportOut,
            "T9 perf gate: REPORT-ONLY PASS — ${jsonlFiles.size} file(s), " +
                "${report.totalEvents} events, report=${mdFile.path}",
        )

        // enforced 模式（默认关闭；真机基线就位后由 CI 环境显式开启）
        if (System.getenv("PERF_GATE_ENFORCE") == "true") {
            enforceAgainstSnapshot(baselineDir, report)
        }
    }

    // ── helpers ────────────────────────────────────────────────────

    private fun resolveBaselineDir(): File {
        System.getProperty("perf.baseline.dir")?.let { p ->
            return File(p)
        }
        // gradle 测试工作目录 = 仓库根/src/android/app，向上找仓库根的 docs/
        var dir: File? = File(".").absoluteFile
        var guard = 0
        while (dir != null && guard < 8) {
            val candidate = File(dir, "docs/stability/perf-baseline")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
            guard++
        }
        return File("../../../docs/stability/perf-baseline")
    }

    private fun enforceAgainstSnapshot(baselineDir: File, report: PerfBaselineReport.BaselineReport) {
        val snapshotFile = File(baselineDir, SNAPSHOT_FILE)
        assertTrue(
            "PERF_GATE_ENFORCE=true requires $SNAPSHOT_FILE in ${baselineDir.path}",
            snapshotFile.isFile,
        )
        val snapshot: Map<String, Double> = org.json.JSONObject(snapshotFile.readText())
            .let { obj -> obj.keys().asSequence().associateWith { obj.getDouble(it) } }

        val regressions = mutableListOf<String>()
        for (et in report.eventTypes) {
            for (m in et.metrics) {
                val before = snapshot[m.metricName] ?: continue
                if (!before.isFinite() || before <= 0.0) continue
                val delta = (m.p95 - before) / before * 100.0
                if (delta > THRESHOLD_PCT) {
                    regressions += "${m.metricName}: p95 ${before} → ${m.p95} (+${"%.1f".format(delta)}%)"
                }
            }
        }
        assertTrue(
            "T9 perf gate FAILED — P95 regression > ${THRESHOLD_PCT}%:\n  " +
                regressions.joinToString("\n  "),
            regressions.isEmpty(),
        )
    }

    private fun writeSummary(dir: File, text: String) {
        val f = File(dir, "gate-summary.txt")
        f.writeText(text)
        println("========== T9 perf gate ==========")
        text.lineSequence().forEach { println(it) }
        println("==================================")
    }
}