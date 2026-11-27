package com.openminis.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

/**
 * [T-scheduled-tasks] 闹钟到点后的出口。
 *
 * 做两件事：
 *  1. 排下一次（daily）或把自己删掉（一次性）；
 *  2. 发一条通知，点进去走 `minis://run` 深链把 prompt 预填到会话里。
 *
 * **为什么不是"到点自动跑一轮"**：那需要一个不依赖 ChatViewModel 的 turn
 * 运行器（预算、工具审批、trace 全都要另起一套），是独立的一整块工程；而
 * 在这个环境里没有真机验证，硬做出来的无人值守执行既不可靠也不可测。
 * 通知型在 Doze 下是可靠的，且覆盖了大多数真实用途。
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.openminis.app.SCHEDULE_FIRE"
        const val EXTRA_TASK_ID = "task_id"
        private const val CHANNEL_ID = "scheduled_tasks"
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val task = ScheduledTaskStore.load(context).firstOrNull { it.id == taskId } ?: return
        if (!task.enabled) return

        try {
            if (task.daily) {
                // 设备关机 / 长时间休眠后 triggerAt 可能已过期很久：
                // 一路推进到下一个未来时刻，而不是补跑一串过去的日子。
                var next = task.triggerAtMillis + DAY_MS
                while (next <= System.currentTimeMillis()) next += DAY_MS
                val updated = task.copy(triggerAtMillis = next)
                ScheduledTaskStore.upsert(context, updated)
                ScheduledTaskScheduler.schedule(context, updated)
            } else {
                ScheduledTaskStore.remove(context, task.id)
            }
        } catch (e: Exception) {
            AppLogger.warning("ScheduledTasks", "reschedule failed for ${task.id}: ${e.message}")
        }

        notifyUser(context, task)
    }

    private fun notifyUser(context: Context, task: ScheduledTask) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) {
            // 通知被关掉时没有第二条通道可用；记一行日志，别假装发了。
            AppLogger.warning("ScheduledTasks", "notifications disabled; task ${task.id} fired silently")
            return
        }
        ensureChannel(context)

        val link = buildString {
            append("minis://run?prompt=").append(Uri.encode(task.prompt))
            task.sessionId?.takeIf { it.isNotBlank() }?.let { append("&session=").append(Uri.encode(it)) }
        }
        val content = PendingIntent.getActivity(
            context,
            task.id.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(task.label)
            .setContentText(task.prompt.lineSequence().firstOrNull().orEmpty().take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.prompt.take(500)))
            .setAutoCancel(true)
            .setContentIntent(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { nm.notify(task.id.hashCode(), notif) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.scheduled_tasks_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.scheduled_tasks_channel_desc)
            },
        )
    }
}