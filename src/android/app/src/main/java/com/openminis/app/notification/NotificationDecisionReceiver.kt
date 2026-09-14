package com.openminis.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.SpawnDecision

/**
 * [T-notif-inline-decision] Handles the Allow / Deny buttons attached to
 * approval notifications, so a backgrounded user can answer without opening
 * the app.
 *
 * Deliberately a `BroadcastReceiver` rather than an Activity: answering must
 * NOT foreground the app (that is the whole point), and it must work when the
 * process was killed — the receiver is started cold, resolves against whatever
 * state exists, and either delivers the decision or logs a stale tap.
 *
 * The receiver never talks to a gate directly; see [NotificationDecisionRouter]
 * for the seam and the id-namespacing rules.
 */
class NotificationDecisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECIDE) return
        val id = intent.getStringExtra(EXTRA_ID)
        if (id.isNullOrBlank()) {
            AppLogger.warning(TAG, "decision broadcast without an id — ignored")
            return
        }
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)

        val delivered = runCatching { NotificationDecisionRouter.dispatch(id, approve) }
            .onFailure { AppLogger.warning(TAG, "dispatch($id) threw: ${it.message}") }
            .getOrDefault(false)

        // Clear the notice either way: a successfully answered ask must not
        // linger, and a stale one is equally useless to the user.
        runCatching { ApprovalNotices.cancel(context, id) }

        AppLogger.info(
            TAG,
            "decision id=$id approve=$approve delivered=$delivered " +
                "(${if (approve) "allow" else "deny"})",
        )
    }

    companion object {
        private const val TAG = "NotifDecision"

        const val ACTION_DECIDE = "com.openminis.app.action.NOTIFICATION_DECIDE"

        /** Identity of the thing being answered (see [NotificationDecisionRouter]). */
        const val EXTRA_ID = "decision_id"

        /** true = approve/allow, false = reject/deny. */
        const val EXTRA_APPROVE = "decision_approve"

        /** Map a [SpawnDecision] to the boolean the broadcast carries. */
        fun approveFlagFor(decision: SpawnDecision): Boolean =
            decision != SpawnDecision.DENY
    }
}
