package com.openminis.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * T180-bg-notif: posts task-completion notifications when an agent
 * session finishes while the app is backgrounded. Mirrors iOS
 * `BackgroundKeepAliveManager.postBackgroundTaskNotification` (L274).
 *
 * Trigger contract: this class is hooked into [com.openminis.app.service.SessionActivityTracker]
 * so a session transitioning from active → inactive (i.e. its agent
 * loop completed) deterministically reaches `notifyTaskCompleted`. The
 * tracker itself is the single source of truth for "is this session
 * still streaming" — using its callback avoids invading
 * `ChatViewModel`, whose 4 stream-finally blocks would all need the
 * same hook.
 *
 * Behaviour rules:
 * - Skip silently if the user has disabled Task Notifications
 *   ([BackgroundSettingsRepository.taskNotificationsEnabled] = false).
 * - Foreground vs background:
 *     - Backgrounded: post the tray notification (tap deep-links back to
 *       the chat) AND drive a direct hardware vibration.
 *     - Foreground: the user is already looking at the chat, so no tray
 *       notification — but still buzz the vibrator as a "task done" cue.
 * - Tap on the notification deep-links into the originating chat via
 *   `minis://session/<sessionId>` (existing
 *   `DeepLinkHandler.OpenSession` path).
 *
 * On Android the absence of `responseSummary` from the spec is
 * intentional: extracting plain text from a `parts_json` blob is
 * fragile; the notification's deep-link opens the chat where the
 * full response is rendered, matching the user's likely intent.
 */
class BackgroundTaskNotifier(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val backgroundSettings: BackgroundSettingsRepository,
    private val isAppForeground: () -> Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ensureChannel()
    }

    /**
     * Called by [com.openminis.app.service.SessionActivityTracker] when a
     * session finishes (active → inactive transition). Looks up the
     * session title via [chatRepository] and posts the notification, off
     * the main thread. No-ops silently if the user has disabled
     * notifications. When the app is backgrounded it posts a tray
     * notification; foreground or background, it drives a direct hardware
     * buzz as a completion cue.
     */
    fun notifyTaskCompleted(sessionId: String, isError: Boolean = false) {
        if (!backgroundSettings.taskNotificationsEnabled.value) return
        val foreground = isAppForeground()

        scope.launch {
            try {
                if (!foreground) {
                    val session = chatRepository.getSession(sessionId)
                    val rawTitle = session?.title?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.notif_task_completed_default_title)
                    val title = if (isError) "❌ $rawTitle" else rawTitle
                    val body = if (isError) {
                        context.getString(R.string.notif_task_failed_body)
                    } else {
                        context.getString(R.string.notif_task_completed_body)
                    }
                    postNotification(sessionId, title, body)
                }
                // [feat-vibrate-task-complete] Direct haptic on completion,
                // regardless of foreground/background. When the app is in
                // the foreground the user is already watching the chat, so
                // no tray notification is posted — but they still get the
                // physical buzz as a "task done" cue (useful when they've
                // glanced away, e.g. with headphones on). MIUI suppresses
                // notification-channel vibration (dumpsys shows
                // VibratorManager marking every Notification-usage vibration
                // "ignored_ringtone_or_notify_miui"), so riding the
                // notification's vibration pattern is unreliable on Xiaomi
                // ROMs. Driving the hardware vibrator directly with
                // VibrationEffect (verified via `cmd vibrator_manager synced
                // oneshot` on the device) is NOT subject to that suppression.
                vibrateCompletion()
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "notifyTaskCompleted failed: ${t.message}")
            }
        }
    }

    /**
     * Generic "background work finished" notification, used by WebDAV
     * backup/restore when the user left the settings screen while the
     * transfer was still running. Unlike [notifyTaskCompleted] it is not
     * tied to a chat session, so the caller supplies the title/body and a
     * notification tag directly; the optional deep-link is opened on tap.
     * Posted off the main thread; silently no-ops when notifications are
     * disabled.
     */
    fun notifyWorkCompleted(
        tag: String,
        title: String,
        body: String,
        deepLink: String? = null,
    ) {
        if (!backgroundSettings.taskNotificationsEnabled.value) return

        scope.launch {
            try {
                val launchIntent = deepLink?.let {
                    Intent(Intent.ACTION_VIEW, Uri.parse(it)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                }
                // Notification id derived from the work tag (Int; never Long —
                // 0x80000000 would infer Long and break the Int overloads of
                // getActivity/notify). Colliding with a session-notification id
                // is vanishingly unlikely and only risks overwriting that one
                // notice in the tray.
                val id = tag.hashCode()
                val pendingIntent = launchIntent?.let {
                    PendingIntent.getActivity(
                        context,
                        id,
                        it,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }

                val nm = NotificationManagerCompat.from(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
                    return@launch
                }
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .build()

                nm.notify(id, notification)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "notifyWorkCompleted failed: ${t.message}")
            }
        }
    }

    /**
     * [feat-vibrate-task-complete] Drive the hardware vibrator directly with a
     * short double-buzz (0ms → 120ms buzz → 90ms gap → 160ms buzz).
     * Deliberately uses the platform `Vibrator` API (not a notification's
     * vibration) because on Xiaomi/MIUI ROMs notification-vibration is
     * suppressed at the VibratorManager layer (every Notification-usage
     * vibration arrives tagged `ignored_ringtone_or_notify_miui`). The direct
     * VibrationEffect path is immune to that, as confirmed on-device. Safe to
     * call on any thread; uses default usage (ALARM-ish) so it is not gated by
     * the caller's notification channel. Errors are swallowed so a vibrator
     * hiccup can never block the completion-notification path.
     */
    private fun vibrateCompletion() {
        try {
            val vibrator =
                ContextCompat.getSystemService(context, Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) return
            val pattern = longArrayOf(0L, 120L, 90L, 160L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "vibrateCompletion failed: ${t.message}")
        }
    }

    private fun postNotification(sessionId: String, title: String, body: String) {
        // Pre-Tiramisu: we don't need the runtime permission, just post.
        // Tiramisu+: NotificationManagerCompat.areNotificationsEnabled
        // is the right gate — POST_NOTIFICATIONS is requested at toggle-on
        // time in Settings (separate flow), and if it was denied we silently
        // skip rather than crash.
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return
        }

        val deepLink = Uri.parse("minis://session/$sessionId")
        val launchIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            // FLAG_ACTIVITY_NEW_TASK because we're posting from a
            // background scope without an Activity context.
            // FLAG_ACTIVITY_CLEAR_TOP so MainActivity (singleTask) reuses
            // the existing instance and routes the deep-link via
            // onNewIntent rather than spawning a duplicate.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        try {
            nm.notify(sessionId.hashCode(), notification)
        } catch (se: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently no-op rather
            // than crashing the agent-completion path.
            AppLogger.info(TAG, "notify denied (POST_NOTIFICATIONS not granted)")
        }
    }

    /**
     * T298: cancel every notification posted on the
     * [CHANNEL_ID] channel. Called from MinisApp's foreground transition
     * (Activity start count 0 → 1) so the user never finds a stale "task
     * completed" entry waiting in the tray when they open the app — they
     * just saw the result, the notification has served its purpose.
     *
     * Implementation: walk [NotificationManager.activeNotifications] (API
     * 23+, lower bound is API 26 for our app) and cancel any whose
     * channelId matches ours. We can't filter by channel directly because
     * each notification id is the session hash — there's no single id to
     * cancel — and `cancelAll()` would also nuke the FG service banner.
     */
    fun cancelAllCompletedNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        try {
            val active = nm.activeNotifications ?: return
            for (sb in active) {
                if (sb.notification?.channelId == CHANNEL_ID) {
                    nm.cancel(sb.tag, sb.id)
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "cancelAllCompletedNotifications failed: ${t.message}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_task_completed_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_task_completed_channel_description)
            // [feat-vibrate-task-complete] Enable a short double-buzz on the
            // task-completion channel so a user who is screen-off / wearing
            // headphones / away still feels when the agent loop finishes. The
            // vibration pattern rides the channel's own notification sound so
            // headphones hear an audio cue too. Toggled independently from
            // sound by the system Settings → Notifications ui (per-channel).
            enableVibration(true)
            setVibrationPattern(longArrayOf(0L, 120L, 90L, 160L))
            setShowBadge(true)
        }
        // Always re-create the channel so vibration settings are applied even
        // when the channel already existed from a prior version — Android
        // respects the app's update unless the user has manually overridden
        // sound/vibration for this channel in system settings.
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TaskNotifier"
        const val CHANNEL_ID = "minis_task_completed_v2"
    }
}
