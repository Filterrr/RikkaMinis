package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fold-consecutive-tools] 折叠规则的契约测试。
 *
 * 用例表逐条对齐上游 OpenMinis 的 `ToolFoldPlannerTests.swift`：块序列用
 * 字母表示（T 成功工具 / F 失败 / C 取消 / R 未结束 / K thinking /
 * t 非空正文 / e 空文本 / B 屏障 / o 单行备注），断言折叠区间与计数。
 *
 * 规则改错会直接影响聊天列表的形状，所以这里不做"差不多"的断言。
 */
class ToolFoldPlannerTest {

    private fun parse(s: String): List<ToolFoldPlanner.Input> = s.map { c ->
        when (c) {
            'T' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.Outcome.SUCCEEDED)
            'F' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.Outcome.FAILED)
            'C' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.Outcome.CANCELLED)
            'R' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.Outcome.UNFINISHED)
            'K' -> ToolFoldPlanner.Input.Thinking
            'e' -> ToolFoldPlanner.Input.Text(isEmpty = true)
            'B' -> ToolFoldPlanner.Input.Barrier
            else -> ToolFoldPlanner.Input.Text(isEmpty = false)
        }
    }

    private fun show(s: String): String {
        val chars = s.toList()
        val inputs = ToolFoldPlanner.markingNotes(parse(s)) { chars[it] == 'o' }
        val groups = ToolFoldPlanner.plan(inputs, enabled = true)
        if (groups.isEmpty()) return "none"
        return groups.joinToString(",") {
            "${it.range.first}-${it.range.last}:${it.toolCount}/${it.failedCount}/${it.cancelledCount}"
        }
    }

    @Test
    fun `threshold and the reply requirement`() {
        assertEquals("0-2:3/0/0", show("TTTt"))
        assertEquals("0-1:2/0/0", show("TTt"))
        assertEquals("none", show("Tt"))
        assertEquals(2, ToolFoldPlanner.MINIMUM_TOOL_COUNT)
        // text before the run is not part of it
        assertEquals("1-4:4/0/0", show("tTTTTt"))
        // a run that ends on tools, or on empty text, never folds
        assertEquals("none", show("tTTTT"))
        assertEquals("none", show("TTTe"))
    }

    @Test
    fun `thinking and empty text fold along without counting`() {
        assertEquals("0-4:3/0/0", show("KTKTTt"))
        assertEquals("0-4:3/0/0", show("TKTKTt"))
        assertEquals("0-3:2/0/0", show("TKKTt"))
        // one tool + thinking is still below the threshold
        assertEquals("none", show("TKKt"))
        assertEquals("none", show("KKKt"))
        assertEquals("0-3:3/0/0", show("TTeTt"))
    }

    @Test
    fun `an unfinished tool or a barrier ends the run`() {
        assertEquals("none", show("TTTRt"))
        // an unfinished tool ends the run that came before it, and the next call
        // starts a fresh one: R T T T t folds 1-3 (range ends at the last TOOL,
        // not at the reply that closes the run).
        assertEquals("1-3:3/0/0", show("RTTTt"))
    }

    @Test
    fun `failures and cancellations are counted`() {
        assertEquals("0-3:4/1/1", show("TFCTt"))
    }

    @Test
    fun `two runs separated by a reply fold separately`() {
        assertEquals("0-2:3/0/0,4-7:4/0/0", show("TTTtTTTTt"))
    }

    @Test
    fun `lead thinking before the first tool folds with the run`() {
        assertEquals("0-2:2/0/0", show("KTTt"))
        assertEquals("0-4:2/0/0", show("KKeTTt"))
        assertEquals("none", show("KTt"))
    }

    @Test
    fun `disabled means nothing folds`() {
        val inputs = parse("TTTt")
        assertTrue(ToolFoldPlanner.plan(inputs, enabled = false).isEmpty())
    }

    @Test
    fun `single line detection ignores surrounding whitespace`() {
        assertTrue(ToolFoldPlanner.isSingleLine("  Now checking the logs.  \n"))
        assertFalse(ToolFoldPlanner.isSingleLine("line one\nline two"))
        assertTrue(ToolFoldPlanner.isSingleLine("\n\nOne line after blank lines\n\n"))
        assertTrue(ToolFoldPlanner.isSingleLine(""))
    }

    @Test
    fun `reply length gate`() {
        assertEquals(0, ToolFoldPlanner.nonEmptyLineCount(""))
        assertEquals(1, ToolFoldPlanner.nonEmptyLineCount("Checking the logs now."))
        assertEquals(2, ToolFoldPlanner.nonEmptyLineCount("First line\nSecond line"))
        assertFalse(ToolFoldPlanner.isFoldSeparator("Now let me check the logs."))
        assertTrue(ToolFoldPlanner.isFoldSeparator("Found it.\nThe config is wrong."))
    }
}
