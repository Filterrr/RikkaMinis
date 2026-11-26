package com.openminis.app.tools

/**
 * [T-apply-patch] Unified-diff 解析与应用引擎。
 *
 * 纯 Kotlin、无 Android 依赖，所以本地 JVM 单测能直接跑（项目约定：能被
 * 单测覆盖的逻辑不放在 Android 依赖的文件里）。
 *
 * 为什么另起一个引擎而不是让模型逐条 file_edit：一次改十处就要十次工具
 * 调用，每次都要先把 old_string 原样抄对；改动之间没有原子性，中途失败
 * 会留下半套修改。unified diff 是模型最熟、最便宜的格式（它天然能把
 * 上下文带进补丁里），一次提交、一次校验、要么全成要么全不改。
 *
 * 容错策略：hunk 的行号允许漂移（模型经常按自己记忆里的行号写），所以
 * 匹配以**上下文块内容**为准，行号只用来定位搜索起点（±[SEARCH_RADIUS]）。
 * 内容对不上就是硬错误——宁可整份补丁失败，也不猜"大概是想改这里"。
 */
object PatchEngine {

    /** 一行补丁：' ' 上下文 / '-' 删除 / '+' 新增。 */
    data class HunkLine(val kind: Char, val text: String)

    data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<HunkLine>,
    )

    data class FilePatch(
        val path: String,
        val hunks: List<Hunk>,
        val isNewFile: Boolean = false,
        val isDeleteFile: Boolean = false,
    )

    data class AppliedFile(
        val path: String,
        val content: String,
        val added: Int,
        val removed: Int,
    )

    class PatchException(message: String) : Exception(message)

    /** 行号漂移容错半径（行）。 */
    private const val SEARCH_RADIUS = 200

    private val HUNK_HEADER = Regex("""^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@""")

    /**
     * 解析一份 unified diff。支持：
     *  - `diff --git` / `index` / `new file mode` 之类的 git 头（忽略）；
     *  - `--- a/x` + `+++ b/x`（取 +++ 的路径，剥 a/ b/ 前缀）；
     *  - 多文件；
     *  - `\ No newline at end of file`（忽略）。
     *
     * 不支持：删除文件、二进制补丁、重命名（modes）——遇到直接抛错，
     * 让模型换一种做法，而不是静默做半件事。
     */
    fun parse(diff: String): List<FilePatch> {
        val raw = diff.replace("\r\n", "\n").split("\n")
        val out = mutableListOf<FilePatch>()
        var currentPath: String? = null
        var isNew = false
        var isDelete = false
        var hunks = mutableListOf<Hunk>()
        var i = 0

        fun flush() {
            val path = currentPath
            if (path != null && path != "/dev/null" && hunks.isNotEmpty()) {
                out += FilePatch(path, hunks.toList(), isNew, isDelete)
            }
            hunks = mutableListOf()
        }

        while (i < raw.size) {
            val line = raw[i]
            when {
                line.startsWith("diff --git ") -> {
                    flush()
                    currentPath = null
                    isNew = false
                    isDelete = false
                    i++
                }

                line.startsWith("new file mode") -> {
                    isNew = true
                    i++
                }

                line.startsWith("deleted file mode") -> {
                    isDelete = true
                    i++
                }

                line.startsWith("--- ") -> {
                    val p = stripPrefix(line.removePrefix("--- ").trim())
                    if (p == "/dev/null") {
                        // 新文件：路径由 +++ 决定。
                        currentPath = null
                        isNew = true
                    } else {
                        // 以 --- 为准起一个新文件块；+++ 会覆盖成目标路径。
                        flush()
                        currentPath = p
                    }
                    i++
                }

                line.startsWith("+++ ") -> {
                    val p = stripPrefix(line.removePrefix("+++ ").trim())
                    if (p != "/dev/null") currentPath = p
                    i++
                }

                line.startsWith("@@") -> {
                    val m = HUNK_HEADER.find(line)
                        ?: throw PatchException("malformed hunk header: $line")
                    val oldStart = m.groupValues[1].toInt()
                    val oldCount = m.groupValues[2].ifBlank { "1" }.toInt()
                    val newStart = m.groupValues[3].toInt()
                    val newCount = m.groupValues[4].ifBlank { "1" }.toInt()
                    i++
                    val lines = mutableListOf<HunkLine>()
                    while (i < raw.size) {
                        val l = raw[i]
                        when {
                            l.startsWith("\\") -> { i++ } // "\ No newline at end of file"
                            l.startsWith("@@") || l.startsWith("diff --git ") ||
                                l.startsWith("--- ") || l.startsWith("+++ ") -> break
                            l.isEmpty() -> {
                                // split() 的产物：文件以换行结尾时最后会多出一个
                                // 空串。它不是上下文行——把它算进去会让 hunk 多匹配
                                // 一行，整个补丁必然对不上（CRLF 用例就是这么炸的）。
                                if (i == raw.size - 1) {
                                    i++
                                    break
                                }
                                // 但编辑器确实会用真正的空行表示空上下文行。
                                lines += HunkLine(' ', "")
                                i++
                            }
                            else -> {
                                val kind = l[0]
                                if (kind != ' ' && kind != '-' && kind != '+') break
                                lines += HunkLine(kind, l.substring(1))
                                i++
                            }
                        }
                    }
                    if (lines.isEmpty()) throw PatchException("empty hunk at @@ -$oldStart,$oldCount @@")
                    hunks += Hunk(oldStart, oldCount, newStart, newCount, lines)
                }

                else -> i++
            }
        }
        flush()
        if (out.isEmpty()) throw PatchException("no hunks found — is this a unified diff?")
        return out
    }

    /**
     * 把 [patch] 应用到 [original] 上（null = 文件不存在）。返回新内容与
     * 增删行数，**不落盘**——落盘由调用方在所有文件都成功后统一做。
     *
     * 抛 [PatchException] 表示某处上下文对不上；调用方必须当整体失败处理，
     * 不能只写成功的那几个文件。
     */
    fun applyTo(original: String?, patch: FilePatch): AppliedFile {
        if (patch.isDeleteFile) {
            throw PatchException(
                "${patch.path}: deleting files via apply_patch is not supported — use shell_execute rm",
            )
        }
        if (original == null && !patch.isNewFile) {
            throw PatchException(
                "${patch.path}: file does not exist (a new file needs a 'new file mode' / /dev/null header)",
            )
        }

        val source = original ?: ""
        val trailingNewline = source.endsWith("\n") || source.isEmpty()
        val lines = if (source.isEmpty()) {
            mutableListOf<String>()
        } else {
            source.removeSuffix("\n").split("\n").toMutableList()
        }

        var offset = 0
        var added = 0
        var removed = 0

        for (hunk in patch.hunks) {
            val oldBlock = hunk.lines.filter { it.kind != '+' }.map { it.text }
            val newBlock = hunk.lines.filter { it.kind != '-' }.map { it.text }
            val expected = (hunk.oldStart - 1 + offset).coerceAtLeast(0)

            val at = if (oldBlock.isEmpty()) {
                // 纯新增：插到期望位置（越界就追加到末尾）。
                expected.coerceIn(0, lines.size)
            } else {
                findBlock(lines, oldBlock, expected)
                    ?: throw PatchException(
                        "${patch.path}: hunk at @@ -${hunk.oldStart},${hunk.oldCount} " +
                            "+${hunk.newStart},${hunk.newCount} @@ does not match the file. " +
                            "First expected line: \"${oldBlock.firstOrNull()?.take(120) ?: ""}\"",
                    )
            }

            val head = if (at > 0) lines.subList(0, at) else emptyList()
            val tail = if (at + oldBlock.size < lines.size) lines.subList(at + oldBlock.size, lines.size) else emptyList()
            val merged = ArrayList<String>(head.size + newBlock.size + tail.size)
            merged.addAll(head)
            merged.addAll(newBlock)
            merged.addAll(tail)
            lines.clear()
            lines.addAll(merged)

            offset += newBlock.size - oldBlock.size
            added += hunk.lines.count { it.kind == '+' }
            removed += hunk.lines.count { it.kind == '-' }
        }

        val body = lines.joinToString("\n")
        val content = if (trailingNewline && body.isNotEmpty()) "$body\n" else body
        return AppliedFile(patch.path, content, added, removed)
    }

    /**
     * 定位上下文块：先在期望位置精确尝试，再以小步长向两侧扩散。
     * 只按内容匹配，不看行号——行号漂移是常态。
     */
    private fun findBlock(lines: List<String>, block: List<String>, expected: Int): Int? {
        if (block.isEmpty()) return expected.coerceIn(0, lines.size)
        val maxStart = lines.size - block.size
        if (maxStart < 0) return null
        val base = expected.coerceIn(0, maxStart)
        if (matchesAt(lines, block, base)) return base
        var d = 1
        while (d <= SEARCH_RADIUS) {
            val up = base - d
            if (up >= 0 && matchesAt(lines, block, up)) return up
            val down = base + d
            if (down <= maxStart && matchesAt(lines, block, down)) return down
            d++
        }
        return null
    }

    private fun matchesAt(lines: List<String>, block: List<String>, at: Int): Boolean {
        if (at < 0 || at + block.size > lines.size) return false
        for (k in block.indices) {
            if (lines[at + k] != block[k]) return false
        }
        return true
    }

    private fun stripPrefix(path: String): String {
        val cleaned = path.trim().removeSurrounding("\"")
        return when {
            cleaned == "/dev/null" -> cleaned
            cleaned.startsWith("a/") || cleaned.startsWith("b/") -> cleaned.substring(2)
            else -> cleaned
        }
    }
}