package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-apply-patch] PatchEngine 的契约测试。
 *
 * 这些用例按"生产里会遇到的坏输入"排，不是按函数排：模型给的 diff 常常
 * 行号是记的、上下文是对的；也会给 CRLF、多文件、新建文件。全有或全无的
 * 语义最容易在边界上被破坏，所以每个失败用例都断言"抛错"，而不是断言
 * "返回了部分结果"。
 *
 * 用 joinToString 拼 diff 而不是 trimIndent：hunk 行必须以一个空格开头，
 * trimIndent 会把它们全部吃掉，测试就变成在测空字符串了。
 */
class PatchEngineTest {

    private fun diff(vararg lines: String): String = lines.joinToString("\n")

    @Test
    fun `applies a single hunk`() {
        val original = diff(
            "line1",
            "line2",
            "line3",
        )
        val patch = PatchEngine.parse(
            diff(
                "--- a/f.txt",
                "+++ b/f.txt",
                "@@ -1,3 +1,3 @@",
                " line1",
                "-line2",
                "+line2-changed",
                " line3",
            ),
        )
        val applied = PatchEngine.applyTo(original, patch.single())
        // 源文件末尾没有换行，补丁也不应该凭空加一个。
        assertEquals("line1\nline2-changed\nline3", applied.content)
        assertEquals(1, applied.added)
        assertEquals(1, applied.removed)
    }

    @Test
    fun `tolerates drifted line numbers because matching is by content`() {
        val original = (1..50).joinToString("\n") { "row$it" }
        // 模型记的行号差了 10 行，上下文本身是对的。
        val patch = PatchEngine.parse(
            diff(
                "--- a/f.txt",
                "+++ b/f.txt",
                "@@ -30,3 +30,3 @@",
                " row31",
                "-row32",
                "+row32-patched",
                " row33",
            ),
        )
        val applied = PatchEngine.applyTo(original, patch.single())
        assertTrue(applied.content.contains("row32-patched"))
        assertTrue(!applied.content.contains("row32\n"))
    }

    @Test
    fun `applies multiple hunks in one file`() {
        val original = (1..20).joinToString("\n") { "v$it" }
        val patch = PatchEngine.parse(
            diff(
                "--- a/f.txt",
                "+++ b/f.txt",
                "@@ -2,3 +2,3 @@",
                " v2",
                "-v3",
                "+v3x",
                " v4",
                "@@ -12,3 +12,3 @@",
                " v12",
                "-v13",
                "+v13x",
                " v14",
            ),
        )
        val applied = PatchEngine.applyTo(original, patch.single())
        assertTrue(applied.content.contains("v3x"))
        assertTrue(applied.content.contains("v13x"))
        // 第一个 hunk 插入后，第二个 hunk 的位置靠 offset 修正。
        assertTrue(!applied.content.contains("v13\n"))
        assertEquals(2, applied.added)
        assertEquals(2, applied.removed)
    }

    @Test
    fun `parses multiple files`() {
        val patch = PatchEngine.parse(
            diff(
                "diff --git a/a.txt b/a.txt",
                "--- a/a.txt",
                "+++ b/a.txt",
                "@@ -1 +1 @@",
                "-old-a",
                "+new-a",
                "diff --git a/b.txt b/b.txt",
                "--- a/b.txt",
                "+++ b/b.txt",
                "@@ -1 +1 @@",
                "-old-b",
                "+new-b",
            ),
        )
        assertEquals(2, patch.size)
        assertEquals("a.txt", patch[0].path)
        assertEquals("b.txt", patch[1].path)
    }

    @Test
    fun `context mismatch fails instead of guessing`() {
        val original = "alpha\nbeta\ngamma"
        val patch = PatchEngine.parse(
            diff(
                "--- a/f.txt",
                "+++ b/f.txt",
                "@@ -1,2 +1,2 @@",
                " alpha",
                "-not-beta",
                "+something",
            ),
        )
        assertThrows(PatchEngine.PatchException::class.java) {
            PatchEngine.applyTo(original, patch.single())
        }
    }

    @Test
    fun `missing file for a non-new-file patch fails`() {
        val patch = PatchEngine.parse(
            diff(
                "--- a/f.txt",
                "+++ b/f.txt",
                "@@ -1,2 +1,2 @@",
                "-a",
                "+b",
            ),
        )
        assertThrows(PatchEngine.PatchException::class.java) {
            PatchEngine.applyTo(null, patch.single())
        }
    }

    @Test
    fun `creates a new file from dev-null headers`() {
        val patch = PatchEngine.parse(
            diff(
                "diff --git a/new.txt b/new.txt",
                "new file mode 100644",
                "--- /dev/null",
                "+++ b/new.txt",
                "@@ -0,0 +1,3 @@",
                "+one",
                "+two",
                "+three",
            ),
        )
        assertEquals("new.txt", patch.single().path)
        assertTrue(patch.single().isNewFile)
        val applied = PatchEngine.applyTo(null, patch.single())
        assertEquals("one\ntwo\nthree\n", applied.content)
        assertEquals(3, applied.added)
    }

    @Test
    fun `crlf input is normalized`() {
        val patch = PatchEngine.parse(
            "--- a/f.txt\r\n+++ b/f.txt\r\n@@ -1,2 +1,2 @@\r\n ctx\r\n-old\r\n+new\r\n",
        )
        val applied = PatchEngine.applyTo("ctx\nold\n", patch.single())
        assertEquals("ctx\nnew\n", applied.content)
    }

    @Test
    fun `rejects a patch with no hunks`() {
        assertThrows(PatchEngine.PatchException::class.java) {
            PatchEngine.parse("just some prose, not a diff")
        }
    }
}