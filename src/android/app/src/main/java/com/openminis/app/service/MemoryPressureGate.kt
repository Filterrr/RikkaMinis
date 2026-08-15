package com.openminis.app.service

import kotlinx.coroutines.delay
import java.io.File

/**
 * 进程级 RSS 水线门卫（纯 JVM 可测，无 Android 依赖）。
 *
 * 背景（2026-08-15 早上 OOM 崩溃分析）：`com.openminis.app` 在 08:25-08:27
 * 连续 3 次 `pthread_create (1040KB stack) failed`——这是 **native 内存耗尽**
 * 而非 Java heap OOM。现有 [com.openminis.app.sandbox.ExecutionCoordinator]
 * 的 P2-app-native-oom 防御只看 `Debug.getNativeHeapAllocatedSize()`（app
 * native heap），对 **进程 RSS 中的线程栈 / mmap 部分完全盲区**（崩溃时
 * native heap 可能是 100MB 而 RSS 已 280MB+）。本类补上 RSS 维度。
 *
 * 阈值依据（实测）：PID 23721 正常运行 RSS≈277MB；崩溃进程 native heap
 * 364MB 时 mmap 失败。取 ELEVATED=280MB（预警）、CRITICAL=320MB（硬门槛），
 * 留出缓冲。
 *
 * 可测试性：rssReader / reclaimHook / pressureListener 全部可注入，
 * 生产路径在 [com.openminis.app.MinisApp] 装配。
 */
enum class MemoryPressureLevel { NORMAL, ELEVATED, CRITICAL }

object MemoryPressureGate {

    /** 预警水线（MB）：超过后新 session 准入短暂等待 + 触发回收。 */
    const val ELEVATED_RSS_MB = 280L

    /** 硬门槛（MB）：超过后新 session 准入触发全局回收并等待恢复。 */
    const val CRITICAL_RSS_MB = 320L

    /** 可注入的 RSS 读取器。生产读 /proc/self/status；测试注入 fake。 */
    @Volatile
    var rssReader: () -> Long = { readRssFromProc() }

    /**
     * 可注入的全局回收动作。生产（MinisApp 装配）：回收 idle shells +
     * 释放 WebView 标签 + System.gc()。测试注入 spy。
     */
    @Volatile
    var reclaimHook: () -> Unit = {}

    /**
     * 可注入的压力通知。生产（MinisApp 装配）接到 AppLogger；
     * 测试注入计数器。只在 ELEVATED / CRITICAL 时触发。
     */
    @Volatile
    var pressureListener: (MemoryPressureLevel, Long) -> Unit = { _, _ -> }

    /** 当前压力级别。 */
    fun level(): MemoryPressureLevel = levelFor(rssReader())

    /** 纯函数分级（可单测）。 */
    fun levelFor(rssMB: Long): MemoryPressureLevel = when {
        rssMB >= CRITICAL_RSS_MB -> MemoryPressureLevel.CRITICAL
        rssMB >= ELEVATED_RSS_MB -> MemoryPressureLevel.ELEVATED
        else -> MemoryPressureLevel.NORMAL
    }

    /** 读取当前进程 VmRSS（MB）。读失败返回 0（安全侧：不触发降级）。 */
    fun readRssFromProc(): Long {
        return try {
            parseVmRss(File("/proc/self/status").readText())
        } catch (t: Throwable) {
            0L
        }
    }

    /** 从 /proc/self/status 文本解析 VmRSS（MB）。无 VmRSS 行返回 0。 */
    internal fun parseVmRss(statusText: String): Long {
        val line = statusText.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return 0L
        val kb = line.substringAfter(":").trim().substringBefore(" kB").trim().toLongOrNull() ?: 0L
        return kb / 1024L
    }

    /** 触发一次全局回收 + 短暂等待让回收生效。 */
    suspend fun reclaimAndWait(waitMs: Long = 2_000L) {
        reclaimHook()
        delay(waitMs)
    }

    /** 通知压力事件（只对非 NORMAL 级别；NORMAL 静默）。 */
    fun notify(level: MemoryPressureLevel) {
        if (level != MemoryPressureLevel.NORMAL) {
            pressureListener(level, rssReader())
        }
    }
}