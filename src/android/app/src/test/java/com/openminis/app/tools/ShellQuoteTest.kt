package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-shell-job] 转义的契约测试。
 *
 * 这不是样式问题：`shell_job` 把用户/模型给的命令原样塞进 `sh -c '...'`，
 * 转义错一个字符，"一条命令"就变成"两条命令"。所以断言的是**输出形态**，
 * 不是"看起来还行"。
 */
class ShellQuoteTest {

    @Test
    fun `plain command is wrapped in single quotes`() {
        assertEquals("'ls -la'", ShellQuote.single("ls -la"))
    }

    @Test
    fun `embedded single quote is split and reopened`() {
        // 'echo ' + '\'' + 'hi' + '\'' + ''
        assertEquals("'echo '\\''hi'\\'''", ShellQuote.single("echo 'hi'"))
    }

    @Test
    fun `an injection attempt stays inside one quoted string`() {
        val evil = "'; rm -rf /; echo '"
        val quoted = ShellQuote.single(evil)
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
        // 每一段内部都不允许出现裸的单引号：中间出现的必须是 '\'' 这种断开形式。
        val inner = quoted.substring(1, quoted.length - 1)
        var i = 0
        while (i < inner.length) {
            if (inner[i] == '\'') {
                assertEquals("bare single quote at $i", "'\\''", inner.substring(i, i + 4))
                i += 4
            } else {
                i++
            }
        }
    }

    @Test
    fun `newlines and dollars are preserved verbatim`() {
        val raw = "echo \"${'$'}HOME\"\nls"
        assertEquals("'echo \"${'$'}HOME\"\nls'", ShellQuote.single(raw))
    }

    @Test
    fun `empty string produces an empty quoted string`() {
        assertEquals("''", ShellQuote.single(""))
        assertFalse(ShellQuote.single("x").isEmpty())
    }

    @Test
    fun `double quotes are not special inside single quotes`() {
        assertEquals("'echo \"hi\"'", ShellQuote.single("echo \"hi\""))
    }
}