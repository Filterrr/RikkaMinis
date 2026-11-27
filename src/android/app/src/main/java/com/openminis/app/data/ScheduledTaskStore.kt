package com.openminis.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [T-scheduled-tasks] 定时任务的数据层。
 *
 * 存在应用私有的 `filesDir/schedules.json`，不放沙箱：这些是**设备级**的
 * 触发规则（闹钟、通知），不是某个会话的工作区内容。放沙箱里会让"哪个会话
 * 挂载了哪个目录"变成依赖。
 *
 * 第一版只做 `mode = "notify"`：到点发一条带 prompt 的通知，点一下进会话并
 * 预填。**没有** headless 自动执行——那需要一个不依赖 ChatViewModel 的 turn
 * 运行器，是独立的一整块；字段先留着，免得以后改数据模型。
 */
data class ScheduledTask(
    val id: String,
    val label: String,
    /** 到点时要送进会话的提示词。 */
    val prompt: String,
    /** 目标会话；null = 新建草稿。 */
    val sessionId: String? = null,
    /** 下一次触发（epoch ms）。 */
    val triggerAtMillis: Long,
    /** true = 每天同一时刻重复；false = 一次性。 */
    val daily: Boolean = false,
    /** 执行方式。目前只有 "notify"。 */
    val mode: String = MODE_NOTIFY,
    val enabled: Boolean = true,
) {
    companion object {
        const val MODE_NOTIFY = "notify"
        const val MAX_PROMPT_CHARS = 2000
        const val MAX_TASKS = 50
    }
}

/**
 * 读写 `schedules.json`。纯逻辑 + 文件 IO，没有 Android API 之外的依赖，
 * 便于单测（[parse]/[toJsonArray] 是纯函数）。
 */
internal object ScheduledTaskStore {

    private const val FILE_NAME = "schedules.json"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): List<ScheduledTask> = runCatching {
        val f = file(context)
        if (!f.isFile) return@runCatching emptyList()
        parse(f.readText(Charsets.UTF_8))
    }.getOrDefault(emptyList())

    fun save(context: Context, tasks: List<ScheduledTask>): Boolean = runCatching {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(toJsonArray(tasks.take(ScheduledTask.MAX_TASKS)).toString(), Charsets.UTF_8)
        true
    }.getOrDefault(false)

    fun upsert(context: Context, task: ScheduledTask): List<ScheduledTask> {
        val current = load(context).filterNot { it.id == task.id }
        val next = (current + task).sortedBy { it.triggerAtMillis }
        save(context, next)
        return next
    }

    fun remove(context: Context, id: String): List<ScheduledTask> {
        val next = load(context).filterNot { it.id == id }
        save(context, next)
        return next
    }

    /** 生成新 id（时间 + 随机后缀，便于人眼排序和排查）。 */
    fun newId(): String =
        "task-" + System.currentTimeMillis().toString(36) + "-" +
            UUID.randomUUID().toString().take(4)

    // ── 纯序列化（可单测） ────────────────────────────────────────────────

    fun parse(text: String): List<ScheduledTask> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "").trim()
            val prompt = o.optString("prompt", "").trim()
            if (id.isEmpty() || prompt.isEmpty()) return@mapNotNull null
            ScheduledTask(
                id = id,
                label = o.optString("label", "").trim().ifBlank { prompt.take(40) },
                prompt = prompt.take(ScheduledTask.MAX_PROMPT_CHARS),
                sessionId = o.optString("sessionId", "").trim().takeIf { it.isNotEmpty() },
                triggerAtMillis = o.optLong("triggerAtMillis", 0L),
                daily = o.optBoolean("daily", false),
                mode = o.optString("mode", ScheduledTask.MODE_NOTIFY),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }.getOrDefault(emptyList())

    fun toJsonArray(tasks: List<ScheduledTask>): JSONArray {
        val arr = JSONArray()
        for (t in tasks) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("label", t.label)
                    .put("prompt", t.prompt)
                    .put("sessionId", t.sessionId ?: JSONObject.NULL)
                    .put("triggerAtMillis", t.triggerAtMillis)
                    .put("daily", t.daily)
                    .put("mode", t.mode)
                    .put("enabled", t.enabled),
            )
        }
        return arr
    }
}