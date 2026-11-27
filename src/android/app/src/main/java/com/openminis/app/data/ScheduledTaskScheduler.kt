package com.openminis.app.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * [T-scheduled-tasks] 精确闹钟调度。
 *
 * 只用系统的 AlarmManager：crontab / nohup 在 app 被挂起时不会跑，而
 * `setExactAndAllowWhileIdle` 是唯一能在 Doze 下把 app 叫醒的原生手段。
 *
 * 权限：Android 12+ 需要 SCHEDULE_EXACT_ALARM，用户可能拒过。没有权限时
 * 降级为 `setAndAllowWhileIdle`（不精确，系统可能合并/延后），而不是静默
 * 不排——延后总比永不触发好。
 */
internal object ScheduledTaskScheduler {

    private const val TAG = "ScheduledTaskScheduler"

    fun requestCodeFor(taskId: String): Int = taskId.hashCode()

    fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager(context).canScheduleExactAlarms()
    }

    /** 为单个任务排上下一次触发。 */
    fun schedule(context: Context, task: ScheduledTask) {
        if (!task.enabled) return cancel(context, task.id)
        val am = alarmManager(context)
        val pi = pendingIntent(context, task)
        val at = task.triggerAtMillis
        try {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            // 权限在这一刻被撤销：退到不精确的路径，别让任务消失。
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            Log.w(TAG, "exact alarm refused for ${task.id}: ${e.message}")
        }
    }

    fun cancel(context: Context, taskId: String) {
        val am = alarmManager(context)
        val pi = PendingIntent.getBroadcast(
            context,
            requestCodeFor(taskId),
            Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = ScheduleAlarmReceiver.ACTION_FIRE
                putExtra(ScheduleAlarmReceiver.EXTRA_TASK_ID, taskId)
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    /**
     * 重新排所有启用的任务。开机 / 应用启动 / 时区变化后调用——
     * 闹钟不是持久化的，进程被杀后必须重排。
     */
    fun rescheduleAll(context: Context): Int {
        val tasks = ScheduledTaskStore.load(context)
        var n = 0
        for (t in tasks) {
            if (!t.enabled) continue
            // 已经错过的一次性任务不再补跑：用户没在场，跑了也没人用。
            if (!t.daily && t.triggerAtMillis <= System.currentTimeMillis()) {
                ScheduledTaskStore.remove(context, t.id)
                continue
            }
            schedule(context, t)
            n++
        }
        return n
    }

    private fun pendingIntent(context: Context, task: ScheduledTask): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCodeFor(task.id),
            Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = ScheduleAlarmReceiver.ACTION_FIRE
                putExtra(ScheduleAlarmReceiver.EXTRA_TASK_ID, task.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}