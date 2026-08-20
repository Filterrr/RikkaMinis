package com.openminis.app.provider

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 纯观测打点：记录每个 provider 调用（sendMessage / streamMessage）执行前后的
 * 主进程 VmRSS 增量，覆盖「聊天收发消息直连 LLM provider」这条路径的泄漏归因。
 *
 * 背景（D-3，2026-08-20）：OffloadRssProbe 只覆盖走 offload socket 的工具 handler
 * （model-use / browser-use / …），但 ChatViewModel 里有若干处**在主进程直接调
 * provider.sendMessage / streamMessage 的聊天主路径**（agent loop、compaction、
 * 标题生成、streamChatTurnOffloaded 的 in-process fallback）——它们绕过 offload
 * socket，native 增长（DirectByteBuffer 响应体、JSON 解析）发生在主进程且**此前
 * 完全没有 RSS 归因**。这是 08-17/19 主进程 OOM 事故里「聊天本身收发消息」的盲区。
 *
 * 设计约束（对齐 OffloadRssProbe / BrowserRssProbe 同构）：
 * - 零副作用：读 /proc/self/status 失败返回 0，绝不 throw、绝不改变调用路径或结果。
 * - 归因：按调用类型（sendMessage / streamMessage）聚合 delta、次数、峰值。
 * - 可 JVM 单测：parseVmRssKb 独立成纯函数。
 * - 单位：kB（VmRSS 原生单位）。
 *
 * 挂点：LLMProvider.sendMessage / streamMessage 是两个**默认实现**（default method），
 * 所有 provider 的具体实现都经它俩进入 —— 在这里打点 = 一处改动覆盖所有 provider 调用。
 * streamMessage 是冷 Flow，打点放在 Flow 被 collect 的前后（真正发请求时），
 * 而非 streamMessage 调用时刻。
 */
object ProviderRssProbe {
    private const val TAG = "ProviderRssProbe"

    /** 单调用类型累计正 delta 超过该值即判定「可疑泄漏源」并 WARN 一次。 */
    private const val LEAK_SUSPECT_KB = 1_024L * 1024L  // 1 GiB 累计

    private data class Stats(
        var count: Int = 0,
        var totalDeltaKb: Long = 0L,
        var peakDeltaKb: Long = 0L,
        var lastDeltaKb: Long = 0L,
        var leaked: Boolean = false,
    ) {
        fun averageDeltaKb(): Long = if (count == 0) 0L else totalDeltaKb / count
    }

    // 调用类型名 → 聚合统计（进程级累计，与 RSS 生命周期对齐）。
    private val byKind = ConcurrentHashMap<String, Stats>()

    /** 读当前进程 VmRSS（kB）。读失败返回 0（安全侧）。 */
    fun rssKb(): Long = try {
        parseVmRssKb(File("/proc/self/status").readText())
    } catch (t: Throwable) {
        0L
    }

    /**
     * 从 /proc/self/status 文本解析 VmRSS（kB）。无 VmRSS 行返回 0。
     * 独立成纯函数以便 JVM 单测。
     */
    internal fun parseVmRssKb(statusText: String): Long {
        val line = statusText.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return 0L
        return line.substringAfter(":").trim().substringBefore(" kB").trim().toLongOrNull() ?: 0L
    }

    /**
     * 记录一次 provider 调用的 VmRSS 增量。kind 用于区分调用类型
     * （"sendMessage" / "streamMessage:<provider>" 等）。
     * 零副作用：所有聚合与日志都无法影响调用结果。
     */
    fun record(kind: String, beforeKb: Long, afterKb: Long) {
        val deltaKb = afterKb - beforeKb
        val st = byKind.computeIfAbsent(kind) { Stats() }
        st.count += 1
        st.totalDeltaKb += deltaKb
        st.lastDeltaKb = deltaKb
        if (deltaKb > st.peakDeltaKb) st.peakDeltaKb = deltaKb

        Log.i(
            TAG,
            "[provider-rss] kind=$kind Δ=${deltaKb}kB " +
                "before=${beforeKb / 1024}MB after=${afterKb / 1024}MB " +
                "cum=${st.totalDeltaKb / 1024}MB(×${st.count}, avg=${st.averageDeltaKb() / 1024}MB)"
        )

        if (!st.leaked && st.totalDeltaKb >= LEAK_SUSPECT_KB) {
            st.leaked = true
            Log.w(
                TAG,
                "[provider-rss] LEAK-SUSPECT kind=$kind totalDelta=${st.totalDeltaKb / 1024}MB " +
                    "over ${st.count} calls (peak=${st.peakDeltaKb / 1024}MB) — 候选聊天路径 native OOM 源头"
            )
        }
    }

    /** 打印全量聚合汇总。返回汇总文本。 */
    fun summary(): String {
        if (byKind.isEmpty()) {
            Log.i(TAG, "[provider-rss] summary: (no provider calls instrumented)")
            return "(no data)"
        }
        val sb = StringBuilder("provider-rss summary:\n")
        byKind.entries
            .sortedByDescending { it.value.totalDeltaKb }
            .forEach { (kind, st) ->
                sb.append(
                    "  $kind: cum=${st.totalDeltaKb / 1024}MB " +
                        "count=${st.count} avg=${st.averageDeltaKb() / 1024}MB " +
                        "peak=${st.peakDeltaKb / 1024}MB last=${st.lastDeltaKb / 1024}MB" +
                        (if (st.leaked) " [LEAK-SUSPECT]" else "")
                ).append('\n')
            }
        Log.i(TAG, "[provider-rss] $sb")
        return sb.toString()
    }

    /** 压测自检用：重置聚合（生产不调用）。 */
    fun reset() {
        byKind.clear()
    }
}
