package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM 单测：[BrowserUseManager.cappedFullPageHeightPx] 纯函数。
 *
 * [fix/fullpage-bitmap-byte-cap] 背景：full_page 截图原先只有 32768px 高度上限，
 * 1440×32768 ARGB_8888 位图 ≈ 180MB 单次 native 分配——「大附件工具调用时
 * RSS 飙升」的直接来源。新增 48MB 位图字节预算后，超高请求按实际捕获宽度
 * 二次截断。
 */
class BrowserUseManagerByteCapTest {

    @Test
    fun `capped full page height respects bitmap byte budget`() {
        // 32768px 高 × 1440px 宽曾经放行 ~180MB 单次 native 位图分配。
        // 现在必须被 48MB 字节预算压到 8738px（48MB / (1440×4)）。
        val capped = BrowserUseManager.cappedFullPageHeightPx(32768L, 1440L)
        val expected = (48L * 1024 * 1024) / (1440L * 4L)  // 8738
        assertEquals("width=1440 应压到 $expected px", expected, capped)
        assertTrue("位图字节数必须 ≤ 48MB", capped * 1440L * 4L <= 48L * 1024 * 1024)

        // 窄视口（360px）：48MB 预算足够高 → 不被字节预算进一步压缩。
        assertEquals(32768L, BrowserUseManager.cappedFullPageHeightPx(32768L, 360L))
        // 短页面：不做任何截断。
        assertEquals(1200L, BrowserUseManager.cappedFullPageHeightPx(1200L, 1440L))
        // 退化输入：宽度未知（0）→ 原样返回，由调用方兜底。
        assertEquals(32768L, BrowserUseManager.cappedFullPageHeightPx(32768L, 0L))
    }
}
