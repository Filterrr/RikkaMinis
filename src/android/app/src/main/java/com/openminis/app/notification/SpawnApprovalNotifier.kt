package com.openminis.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.SpawnDecision
import com.openminis.app.tools.SpawnApprovalRequest

/**
 * [T-notif-inline-decision] Posts a "sub-agent needs approval" notification
 * when a `spawn_agent` parks on the user's answer while the app is
 * backgrounded, and attaches Allow-once / Deny buttons that answer the ask
 * straight from the shade.
 *
 * ## Why this exists
 *
 * The spawn gate (`SpawnApprovalQueue`) is per-chat ViewModel state with a
 * five-minute waiter timeout that resolves to DENY. Before this notifier, a
 * backgrounded user saw NOTHING: the dialog is in-app only, so the park
 * silently expired and the parent agent reported a denied spawn it could not
 * explain. The notification converts that dead five minutes into a decision
 * the user can actually make — and the buttons mean they do not even have to
 * leave what they were doing.
 *
 * ## Relationship to the in-app dialog
 *
 * Both routes terminate in the same queue: the dialog calls
 * [com.openminis.app.tools.SpawnApprovalQueue.resolve] via the ViewModel, the
 * notification goes through [NotificationDecisionRouter] to the same call.
 * The queue's exactly-once semantics make the race harmless — whichever lands
 * first wins, the loser delivers nothing. [ApprovalNotices] guarantees both
 * routes also clear the same notification slot.
 *
 * "Always allow" is deliberately NOT offered as a notification button: it is
 * a durable grant that should be a deliberate act in the app, not a stray tap
 * on a lock screen.
 */
class SpawnApprovalNotifier(
    private val context: Context,
    private val backgroundSettings: BackgroundSettingsRepository,
    private val isAppForeground: () -> Boolean,
) {

    init {
        ensureChannel()
    }

    /**
     * Post the "sub-agent awaiting approval" notice for [request] if — and only
     * if — the app is backgrounded and task notifications are enabled. Returns
     * true when a notification was actually posted.
     *
     * [sessionId] is what the body tap opens (`minis://session/<id>`), so the
     * user lands in the conversation the spawn belongs to rather than the
     * session list.
     */
    fun notifyIfBackgrounded(request: SpawnApprovalRequest, sessionId: String): Boolean {
        if (isAppForeground()) return false
        if (!backgroundSettings.taskNotificationsEnabled.value) {
            AppLogger.info(TAG, "spawn-notify skipped id=${request.id} — taskNotificationsEnabled=false")
            return false
        }

        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return false
        }

        // Body: who runs, on what, and where the answer goes. The task preview
        // is already capped at 600 chars by the queue; clamp again so a long
        // task cannot push the buttons off the shade.
        val task = request.taskPreview.trim().replace('\n', ' ').take(BODY_TASK_CHARS)
        val body = context.getString(
            R.string.notif_spawn_approval_body,
            request.skillName,
            request.modelLabel.ifBlank { context.getString(R.string.notif_spawn_approval_unknown_model) },
            modeLabel(request),
        ) + "\n" + task

        val contentIntent = PendingIntent.getActivity(
            context,
            request.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                // Land in the owning conversation via the existing
                // minis://session/<id> route (DeepLinkHandler.OpenSession).
                data = Uri.parse("minis://session/$sessionId")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_spawn_approval_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.notif_decision_deny),
                decisionPendingIntent(request.id, approve = false),
            )
            .addAction(
                android.R.drawable.ic_menu_save,
                context.getString(R.string.notif_decision_allow_once),
                decisionPendingIntent(request.id, approve = true),
            )
            // A parked spawn freezes the parent turn, so this is genuinely
            // time-sensitive: heads-up priority and no auto-cancel (the queue
            // clears it when the ask resolves, whichever route answered).
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()

        return try {
            nm.notify(ApprovalNotices.SPAWN_TAG, ApprovalNotices.notificationId(request.id), notification)
            AppLogger.info(TAG, "spawn-notify post id=${request.id} skill=${request.skillName}")
            true
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "spawn-notify denied (POST_NOTIFICATIONS not granted)")
            false
        }
    }

    /** Clear the notice for [askId] — called from every resolve path. */
    fun cancel(askId: String) {
        ApprovalNotices.cancel(context, askId)
    }

    /**
     * A broadcast PendingIntent that answers the ask from the shade. Distinct
     * request codes for allow / deny so the two never alias onto one another
     * (they share an action and an id extra).
     */
    private fun decisionPendingIntent(askId: String, approve: Boolean): PendingIntent {
        val intent = Intent(context, NotificationDecisionReceiver::class.java).apply {
            action = NotificationDecisionReceiver.ACTION_DECIDE
            putExtra(NotificationDecisionReceiver.EXTRA_ID, askId)
            putExtra(NotificationDecisionReceiver.EXTRA_APPROVE, approve)
        }
        return PendingIntent.getBroadcast(
            context,
            askId.hashCode() * 31 + if (approve) 1 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_spawn_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_spawn_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SpawnApprovalNotifier"
        const val CHANNEL_ID = "minis_spawn_approval"

        /** Cap on the task line shown under the header. */
        private const val BODY_TASK_CHARS = 160

        /**
         * Map an in-app decision to the boolean the broadcast extra carries —
         * shared with the receiver so the two can never disagree.
         */
        fun approveFlagFor(decision: SpawnDecision): Boolean =
            NotificationDecisionReceiver.approveFlagFor(decision)
    }

    /** Human label for the run mode. */
    private fun modeLabel(request: SpawnApprovalRequest): String =
        context.getString(
            if (request.detached) R.string.notif_spawn_approval_mode_background
            else R.string.notif_spawn_approval_mode_foreground,
        )
}
