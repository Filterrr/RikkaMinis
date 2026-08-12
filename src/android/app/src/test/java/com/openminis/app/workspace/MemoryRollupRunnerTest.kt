package com.openminis.app.workspace

import com.openminis.app.workspace.MemoryRollupRunner.Outcome
import com.openminis.app.workspace.MemoryRollupEngine.ROLLUP_FILE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.UUID

/**
 * JVM integration tests for [MemoryRollupRunner] against a real temp directory.
 * Verifies the full I/O pipeline: reading yesterday's log, distilling, writing
 * the rollup file, and idempotency. The source log is never modified.
 */
class MemoryRollupRunnerTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "t6-test-${UUID.randomUUID().take(8)}")
    private val memoryDir = File(tempDir, "minis-global/memory")

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Fixed clock so "yesterday" is always 2026-08-11. */
    private val fixedClock = { Date(1723411200000L) } // 2026-08-12 12:00:00 GMT

    // ─── ROLLED_UP ────────────────────────────────────────────────────

    @Test fun rolledUp_producesRollupFile_withStableRules() {
        // Write today's log (not yesterday's — runner reads yesterday)
        // runner reads yesterday = 2026-08-11
        val yesterdayLog = """
            <!-- 2026-08-11 09:00:00 -->
            ## 分支隔离纪律
            任何代码修改必须在独立分支上完成，不得直接在 main 工作

            <!-- 2026-08-11 10:00:00 -->
            ## 踩坑
            顶层扩展函数 toProviderConfig 需要显式 import

            <!-- 2026-08-11 11:00:00 -->
            ## 待办事项
            待用户确认方案后再动

        """.trimIndent()
        memoryDir.mkdirs()
        File(memoryDir, "2026-08-11.md").writeText(yesterdayLog)

        val runner = MemoryRollupRunner(memoryDir, clock = fixedClock)
        val outcome = runner.runOnce()

        assertEquals(Outcome.ROLLED_UP, outcome)

        // Rollup file written
        val rollupFile = File(memoryDir, ROLLUP_FILE)
        assertTrue(rollupFile.exists())
        val content = rollupFile.readText()

        // Contains the stable rules (convention + lesson)
        assertTrue(content.contains("## Rollup 2026-08-11"))
        assertTrue(content.contains("### 约定与纪律"))
        assertTrue(content.contains("分支隔离纪律"))
        assertTrue(content.contains("### 经验与知识点"))
        assertTrue(content.contains("toProviderConfig"))

        // Does NOT contain the transient to-do
        assertFalse(content.contains("待办事项"))

        // Original log untouched
        assertEquals(yesterdayLog, File(memoryDir, "2026-08-11.md").readText())
    }

    // ─── SKIPPED_ALREADY ──────────────────────────────────────────────

    @Test fun alreadyRolledUp_skipsIdempotently() {
        memoryDir.mkdirs()
        File(memoryDir, "2026-08-11.md").writeText("## 纪律\n必须使用分支")
        // Pre-existing rollup file with the date section
        File(memoryDir, ROLLUP_FILE).writeText("## Rollup 2026-08-11\n\n### 约定与纪律\n- **纪律**：必须使用分支\n")

        val runner = MemoryRollupRunner(memoryDir, clock = fixedClock)
        assertEquals(Outcome.SKIPPED_ALREADY, runner.runOnce())

        // File unchanged (no second append)
        assertEquals(
            "## Rollup 2026-08-11\n\n### 约定与纪律\n- **纪律**：必须使用分支\n",
            File(memoryDir, ROLLUP_FILE).readText(),
        )
    }

    // ─── NO_LOG_YESTERDAY ─────────────────────────────────────────────

    @Test fun noLogYesterday_returnsNoLog() {
        memoryDir.mkdirs()
        // Only today's log, no yesterday's
        File(memoryDir, "2026-08-12.md").writeText("## 今天\n内容")

        val runner = MemoryRollupRunner(memoryDir, clock = fixedClock)
        assertEquals(Outcome.NO_LOG_YESTERDAY, runner.runOnce())

        // No rollup file created
        assertFalse(File(memoryDir, ROLLUP_FILE).exists())
    }

    @Test fun emptyLogYesterday_returnsNoLog() {
        memoryDir.mkdirs()
        File(memoryDir, "2026-08-11.md").writeText("")
        assertEquals(Outcome.NO_LOG_YESTERDAY, MemoryRollupRunner(memoryDir, clock = fixedClock).runOnce())
    }

    // ─── NOTHING_TO_DISTILL ───────────────────────────────────────────

    @Test fun nothingToDistill_whenAllEntriesAreTransient() {
        memoryDir.mkdirs()
        File(memoryDir, "2026-08-11.md").writeText("""
            <!-- 2026-08-11 09:00:00 -->
            **待办**：CI 绿 → 合并

            <!-- 2026-08-11 10:00:00 -->
            未实施：待用户确认方案

        """.trimIndent())

        val runner = MemoryRollupRunner(memoryDir, clock = fixedClock)
        assertEquals(Outcome.NOTHING_TO_DISTILL, runner.runOnce())

        // No rollup file created (nothing to write)
        assertFalse(File(memoryDir, ROLLUP_FILE).exists())
    }

    // ─── Source log invariants ────────────────────────────────────────

    @Test fun sourceLog_neverModified_afterRollup() {
        memoryDir.mkdirs()
        val log = "<!-- 2026-08-11 08:00:00 -->\n## 规则\n必须使用分支\n"
        File(memoryDir, "2026-08-11.md").writeText(log)

        MemoryRollupRunner(memoryDir, clock = fixedClock).runOnce()
        assertEquals(log, File(memoryDir, "2026-08-11.md").readText())
    }

    @Test fun multipleRuns_idempotent() {
        memoryDir.mkdirs()
        File(memoryDir, "2026-08-11.md").writeText("## 规则\n必须使用分支")
        val runner = MemoryRollupRunner(memoryDir, clock = fixedClock)

        assertEquals(Outcome.ROLLED_UP, runner.runOnce())
        assertEquals(Outcome.SKIPPED_ALREADY, runner.runOnce())
        assertEquals(Outcome.SKIPPED_ALREADY, runner.runOnce())
    }

    @Test fun rollupFile_accumulatesMultipleDays() {
        memoryDir.mkdirs()

        // Day 1: yesterday = 2026-08-11
        File(memoryDir, "2026-08-11.md").writeText("## 纪律\n使用分支")
        val clock1 = { Date(1723411200000L) } // 2026-08-12
        assertEquals(Outcome.ROLLED_UP, MemoryRollupRunner(memoryDir, clock = clock1).runOnce())

        // Day 2: yesterday = 2026-08-12 (time advanced by 24h)
        File(memoryDir, "2026-08-12.md").writeText("## 经验\n根因是跨层 gap")
        val clock2 = { Date(1723497600000L) } // 2026-08-13
        assertEquals(Outcome.ROLLED_UP, MemoryRollupRunner(memoryDir, clock = clock2).runOnce())

        val content = File(memoryDir, ROLLUP_FILE).readText()
        assertTrue(content.contains("## Rollup 2026-08-11"))
        assertTrue(content.contains("## Rollup 2026-08-12"))
        assertTrue(content.contains("使用分支"))
        assertTrue(content.contains("根因是跨层 gap"))
    }
}