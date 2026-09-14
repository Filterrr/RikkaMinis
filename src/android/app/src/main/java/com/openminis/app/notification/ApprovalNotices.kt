package com.openminis.app.notification

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * [T-notif-inline-decision] Notification-tag/id bookkeeping shared by the
 * approval notifications and the receiver that answers them.
 *
 * Both the poster (`ConfigConfirmNotifier`, [SpawnApprovalNotifier]) and the
 * answer path ([NotificationDecisionReceiver], and the in-app dialogs that can
 * also resolve the same item) need to clear exactly the same slot. Keeping the
 * derivation here means a tap in the notification shade and a tap in the app
 * cannot disagree about which entry to remove.
 *
 * The id derivation is intentionally the SAME expression the notifiers already
 * used (`change.id.hashCode()`), so an upgrade picks up the new buttons on
 * existing channels without orphaning a previously-posted notice.
 */
internal object ApprovalNotices {

    /** Notification tag for config-approval notices (unchanged from pre-buttons builds). */
    const val CONFIG_TAG = "config-confirm"

    /** Notification tag for sub-agent spawn-approval notices. */
    const val SPAWN_TAG = "subagent-approval"

    /** The notification id used for [itemId] under [tag]. */
    fun notificationId(itemId: String): Int = itemId.hashCode()

    /**
     * Cancel the notice for [itemId]. The tag is derived from the id family so
     * callers do not have to know which notifier posted it — a config id and a
     * spawn id can never collide on a tag.
     */
    fun cancel(context: Context, itemId: String) {
        val tag = if (NotificationDecisionRouter.isConfigId(itemId)) CONFIG_TAG else SPAWN_TAG
        runCatching {
            NotificationManagerCompat.from(context).cancel(tag, notificationId(itemId))
        }
    }
}
