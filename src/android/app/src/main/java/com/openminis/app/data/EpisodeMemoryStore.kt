package com.openminis.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 经验记忆（Episodic Memory）存储 — 纯文本 JSONL，无数据库、无向量、无模型调用。
 *
 * 设计原则（对应 2026-08-06 方案）：
 *  - **写入不判断，读取才判断**：`record()` 无条件追加；过滤/排序全部发生在
 *    `retrieve()`（查询明确、规则机械）。
 *  - **纯机械提取**：经验 = 一次完整回合（意图 + 工具序列 + 结果），数据由
 *    agent loop 运行时已有，此处只做结构化搬运，零 API 调用。
 *  - **价值靠使用结果验证**：`applyFeedback()` 对检索命中的条目做 ±1 计数，
 *    反复成功复用的经验自然浮到前排，失败过的沉底（"给记忆装 CI"）。
 *  - **可审计**：文件是纯文本 JSONL，可读、可 grep、可手改、可 git 跟踪。
 *
 * 文件格式（单行一个 JSON，`v` = 验证计数器）：
 * ```
 * {"t":1754567890,"q":"...","tools":[{"n":"file_edit","ok":true}],
 *  "ok":true,"dur":18000,"reply":"...","sid":"abc","v":0}
 * ```
 *
 * 线程模型：调用方保证在 IO 线程调用（Hook A/B 都位于 ChatViewModel 的
 * Dispatchers.IO 上下文中）。内部不做同步。
 */
class EpisodeMemoryStore(
    private val file: File,
    private val maxEntries: Int = 1000,
    private val scanLimit: Int = 500,
    private val minScore: Int = 2,
    private val maxInject: Int = 3,
    private val maxInjectChars: Int = 2048,
) {

    /** 单次工具调用（名称 + 该调用成败）。 */
    data class ToolCall(val n: String, val ok: Boolean)

    /** 一次完整回合的经验记录（写入格式）。 */
    data class ExchangeRecord(
        val query: String,
        val tools: List<ToolCall>,
        val ok: Boolean,
        val durationMs: Long,
        val reply: String,
        val sessionId: String,
    )

    /** 一条已入库的经验（读取格式）。 */
    data class Episode(
        val t: Long,
        val q: String,
        val tools: List<ToolCall>,
        val ok: Boolean,
        val durMs: Long,
        val reply: String,
        val sid: String,
        val v: Int = 0,
    )

    /** 检索命中：index 是该条目在文件中的行号（供 applyFeedback 使用）。 */
    data class Retrieved(val index: Int, val episode: Episode, val score: Int)

    // ─────────────────────────── 写入端（笨） ───────────────────────────

    /**
     * 追加一条经验。若最后一条经验的 query 与本次相同则原位更新（避免
     * 重复提问产生重复条目）；否则追加。超过 [maxEntries] 时滚动删除最旧。
     */
    fun record(exchange: ExchangeRecord) {
        val lines = readLines()
        if (lines.isNotEmpty()) {
            val last = parseLine(lines.last())
            if (last != null && last.q == exchange.query) {
                // 近重复：原位替换，保留原有验证计数 v（用过的经验不因重放而贬值）
                lines[lines.size - 1] = toLine(exchange, last.v)
            } else {
                lines.add(toLine(exchange, 0))
            }
        } else {
            lines.add(toLine(exchange, 0))
        }
        // 滚动删除最旧
        val excess = lines.size - maxEntries
        val trimmed = if (excess > 0) lines.drop(excess) else lines
        writeLines(trimmed)
    }

    /** 清空全部经验。 */
    fun clear() {
        file.delete()
    }

    /** 当前条目数。 */
    fun size(): Int = readLines().size

    /** 全量快照（供设置页查看/调试；返回最近 [maxEntries] 条）。 */
    fun snapshot(): List<Episode> = readLines().mapNotNull { parseLine(it) }

    // ─────────────────────────── 读取端（聪明） ───────────────────────────

    /**
     * 检索与 query 最相关的经验。打分 = 分词后按命中位置加权
     * （q 命中 +2、reply/tools 命中 +1），低于 [minScore] 不返回；
     * 排序按 score ↓ → 验证计数 v ↓ → 时间 t ↓；截取 [maxInject] 条。
     * 只扫描最近 [scanLimit] 条（顺序读，毫秒级）。
     */
    fun retrieve(query: String): List<Retrieved> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        // 失败信号词：query 含这些词时，失败经验才参与检索（作为警告而非方法）
        val qLower = query.lowercase()
        val wantFailures = FAILURE_SIGNALS.any { it in qLower }
        val lines = readLines()
        val start = (lines.size - scanLimit).coerceAtLeast(0)
        val scored = mutableListOf<Retrieved>()
        for (i in start until lines.size) {
            val ep = parseLine(lines[i]) ?: continue
            if (!ep.ok && !wantFailures) continue // 失败经验默认不露
            val s = score(tokens, ep)
            if (s >= minScore) scored.add(Retrieved(i, ep, s))
        }
        scored.sortWith(
            compareByDescending<Retrieved> { it.episode.ok }   // 成功经验优先，失败经验作为警告排后
                .thenByDescending { it.score }
                .thenByDescending { it.episode.v }
                .thenByDescending { it.episode.t }
        )
        return scored.take(maxInject)
    }

    /** 反馈：对检索命中的条目做验证计数更新（ok=true → +1，否则 -1）。 */
    fun applyFeedback(indices: List<Int>, ok: Boolean) {
        if (indices.isEmpty()) return
        val lines = readLines()
        var changed = false
        for (idx in indices) {
            if (idx < 0 || idx >= lines.size) continue
            val ep = parseLine(lines[idx]) ?: continue
            val newV = (ep.v + if (ok) 1 else -1).coerceAtLeast(-9).coerceAtMost(99)
            if (newV != ep.v) {
                val obj = parseToJson(lines[idx]) ?: continue
                obj.put("v", newV)
                lines[idx] = obj.toString()
                changed = true
            }
        }
        if (changed) writeLines(lines)
    }

    // ─────────────────────────── 纯函数（可 JVM 测试） ───────────────────────────

    companion object {
        /** 失败信号词：query 含这些词时，失败经验才参与检索（作为警告而非方法）。 */
        private val FAILURE_SIGNALS = listOf(
            "失败", "报错", "错误", "出错", "不行", "修", "问题", "坏",
            "error", "fail", "exception", "crash", "bug", "404", "502",
        )

        /** 高频功能字/停用词：不参与打分（避免"帮我查一下"式噪音命中）。 */
        private val STOP_WORDS = setOf(
            // 中文单字（CJK 逐字分词，停用表只能含单字）
            "的", "了", "吗", "呢", "啊", "呀", "吧", "是", "在", "我", "你", "他", "她", "它",
            "这", "那", "哪", "谁", "请", "帮", "怎", "么", "如", "何", "什", "为", "个",
            "和", "与", "及", "或", "把", "被", "让", "给", "对", "从", "向", "到", "于",
            "里", "中", "都", "也", "还", "又", "就", "才", "很", "太", "更", "最",
            "一", "下", "有", "没", "要", "想", "能", "会", "好", "不",
            // 英文高频
            "the", "a", "an", "is", "are", "to", "of", "in", "on", "for", "and", "with",
            "how", "what", "why", "can", "do", "does", "please", "help", "me", "you",
            "it", "this", "that",
        )

        /** 分词：CJK 逐字、拉丁/数字连串，lowercase，滤停用词与单字符拉丁噪音。 */
        fun tokenize(raw: String): List<String> {
            val s = raw.lowercase()
            val tokens = mutableListOf<String>()
            val lat = StringBuilder()
            fun flushLat() {
                if (lat.isNotEmpty()) {
                    tokens.add(lat.toString())
                    lat.clear()
                }
            }
            for (ch in s) {
                when {
                    ch.isLetterOrDigit() && ch.code < 0x2E80 -> lat.append(ch)
                    ch.code in 0x4E00..0x9FFF -> { // CJK 单字
                        flushLat()
                        tokens.add(ch.toString())
                    }
                    else -> flushLat()
                }
            }
            flushLat()
            return tokens.filter { it !in STOP_WORDS }
                .filter { it.length > 1 || it[0].code in 0x4E00..0x9FFF }
        }

        /**
         * 打分：query token 命中 q +2、命中 reply/tool 名 +1。
         * 中文字命中 q 与英文词同等权重（中文无空格分词，单字即最小单元）。
         */
        fun score(tokens: List<String>, ep: Episode): Int {
            var s = 0
            for (t in tokens) {
                if (t in ep.q) s += 2
                else if (t in ep.reply) s += 1
                else if (ep.tools.any { t in it.n }) s += 1
            }
            return s
        }

        /** 构建注入块（压缩为一行一条，带来源时间与成败标记）。 */
        fun buildInjectionBlock(retrieved: List<Retrieved>, maxChars: Int = 2048): String {
            if (retrieved.isEmpty()) return ""
            val sb = StringBuilder()
            sb.append("<experience-memory>\n")
            sb.append("以下是系统过去处理类似问题的经验记录（本地经验文件，可查证）。仅作参考，不要照搬；过时或不适用时请说明原因。\n")
            for (r in retrieved) {
                val ep = r.episode
                val date = java.text.SimpleDateFormat("MM-dd", java.util.Locale.US)
                    .format(java.util.Date(ep.t))
                val tools = if (ep.tools.isEmpty()) "" else
                    " 工具:" + ep.tools.joinToString(",") { t -> "${t.n}(${if (t.ok) "✓" else "✗"})" }
                val okMark = if (ep.ok) "成功" else "失败"
                sb.append("[$date] 查询:${ep.q} → 结果:$okMark 耗时:${ep.durMs / 1000}s$tools 复用次数:${ep.v}\n")
                if (ep.reply.isNotBlank()) {
                    sb.append("  要点:").append(ep.reply.take(120)).append("\n")
                }
            }
            sb.append("</experience-memory>")
            val out = sb.toString()
            return if (out.length <= maxChars) out
            else out.take(maxChars) + "\n</experience-memory>"
        }

        /** JSONL 行 → Episode。单行解析失败返回 null（容错：坏行跳过）。 */
        fun parseLine(line: String): Episode? {
            val obj = parseToJson(line) ?: return null
            return try {
                val toolsArr = obj.optJSONArray("tools") ?: JSONArray()
                val tools = (0 until toolsArr.length()).map { i ->
                    val t = toolsArr.getJSONObject(i)
                    ToolCall(t.optString("n", ""), t.optBoolean("ok", true))
                }
                Episode(
                    t = obj.optLong("t", 0L),
                    q = obj.optString("q", ""),
                    tools = tools,
                    ok = obj.optBoolean("ok", true),
                    durMs = obj.optLong("dur", 0L),
                    reply = obj.optString("reply", ""),
                    sid = obj.optString("sid", ""),
                    v = obj.optInt("v", 0),
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun parseToJson(line: String): JSONObject? = try {
            JSONObject(line)
        } catch (_: Exception) {
            null
        }

        /** ExchangeRecord → JSONL 行。 */
        fun toLine(exchange: ExchangeRecord, v: Int = 0): String {
            val obj = JSONObject()
            obj.put("t", System.currentTimeMillis())
            obj.put("q", exchange.query.take(200))
            val toolsArr = JSONArray()
            for (t in exchange.tools) {
                val tObj = JSONObject()
                tObj.put("n", t.n)
                tObj.put("ok", t.ok)
                toolsArr.put(tObj)
            }
            obj.put("tools", toolsArr)
            obj.put("ok", exchange.ok)
            obj.put("dur", exchange.durationMs)
            obj.put("reply", exchange.reply.take(500))
            obj.put("sid", exchange.sessionId)
            obj.put("v", v)
            return obj.toString()
        }
    }

    // ─────────────────────────── 文件 IO ───────────────────────────

    private fun readLines(): MutableList<String> {
        if (!file.exists()) return mutableListOf()
        return try {
            file.readLines().toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeLines(lines: List<String>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n")
        } catch (_: Exception) {
            // 写入失败静默：经验记录不能影响主流程
        }
    }
}
