package com.openminis.app.workspace

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openminis.app.logging.AppLogger
import java.util.Calendar

/**
 * Schedules the daily memory rollup via AlarmManager (T6, modeled after
 * OmniBot's WorkspaceMemoryRollupScheduler — chosen over WorkManager because
 * AlarmManager survives aggresive OEM (HyperOS) background killing far better).
 *
 * Scheduling is chain-based (not setRepeating): each fire schedules the next
 * day's exact alarm. This lets every reschedule re-check exact-alarm
 * permission, so a user granting SCHEDULE_EXACT_ALARM later automatically
 * upgrades back to exact timing. On Android 12+ where exact alarms default to
 * denied, and on any SecurityException, scheduling degrades to
 * [AlarmManager.setAndAllowWhileIdle] (inexact but still a real wakeup alarm).
 *
 * Time is configurable via SharedPreferences (default 03:00) so a Settings UI
 * can be wired up later without changing this class.
 */
class WorkspaceMemoryRollupScheduler(private val context: Context) {

    companion object {
        private const val TAG = "MemoryRollupScheduler"

        const val ACTION_TRIGGER = "com.openminis.app.action.MEMORY_ROLLUP"

        private const val PREFS = "minis_memory_rollup_prefs"
        private const val KEY_HOUR = "rollup_hour"
        private const val KEY_MINUTE = "rollup_minute"
        private const val DEFAULT_HOUR = 3
        private const val DEFAULT_MINUTE = 0

        private const val REQUEST_CODE = 0x4D52 // "MR"
    }

    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // -- Configuration ----------------------------------------------------

    var hour: Int
        get() = prefs.getInt(KEY_HOUR, DEFAULT_HOUR)
        set(value) {
            prefs.edit().putInt(KEY_HOUR, value.coerceIn(0, 23)).apply()
        }

    var minute: Int
        get() = prefs.getInt(KEY_MINUTE, DEFAULT_MINUTE)
        set(value) {
            prefs.edit().putInt(KEY_MINUTE, value.coerceIn(0, 59)).apply()
        }

    /** Update the scheduled time and immediately reschedule. */
    fun setTime(hour: Int, minute: Int) {
        this.hour = hour
        this.minute = minute
        scheduleDaily()
    }

    // -- Scheduling -------------------------------------------------------

    /**
     * Cancel any existing alarm and (re)schedule the next daily trigger.
     * Idempotent — safe to call on every app start, after BOOT_COMPLETED,
     * and after each trigger to chain the following day.
     */
    fun scheduleDaily() {
        try {
            val pendingIntent = buildPendingIntent()
            alarmManager.cancel(pendingIntent)

            val triggerAt = nextTriggerTime(hour, minute)
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
                AppLogger.info(
                    TAG,
                    "exact daily memory rollup scheduled at %02d:%02d".format(hour, minute),
                )
            } else {
                // Android 12+ SCHEDULE_EXACT_ALARM not granted (default) —
                // degrade to an inexact-but-real wakeup alarm.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
                AppLogger.info(
                    TAG,
                    "exact alarm not permitted — degraded to setAndAllowWhileIdle at %02d:%02d".format(
                        hour, minute,
                    ),
                )
            }
        } catch (e: SecurityException) {
            // Belt and braces: some OEMs throw despite canScheduleExactAlarms().
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextTriggerTime(hour, minute),
                    buildPendingIntent(),
                )
            }.onFailure {
                AppLogger.error(TAG, "rollup schedule failed entirely: ${it.message}")
            }
            AppLogger.warning(TAG, "exact alarm denied (${e.message}) — degraded to setAndAllowWhileIdle")
        }
    }

    /** Cancel the rollup alarm entirely (used on receiver reissue / teardown). */
    fun cancel() {
        runCatching {
            alarmManager.cancel(buildPendingIntent())
        }.onFailure { AppLogger.warning(TAG, "cancel failed: ${it.message}") }
    }

    // -- Internal ---------------------------------------------------------

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(context, WorkspaceMemoryRollupReceiver::class.java).apply {
            action = ACTION_TRIGGER
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Next occurrence of HH:MM that is strictly in the future. */
    private fun nextTriggerTime(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }
}