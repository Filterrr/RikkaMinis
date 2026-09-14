package com.openminis.app.notification

import com.openminis.app.tools.SpawnDecision

/**
 * [T-notif-inline-decision] Routing seam for the decision buttons carried by
 * approval notifications, so a backgrounded user can answer WITHOUT opening
 * the app.
 *
 * Two notification families ask for an in-app decision today:
 *
 *  1. **Config approvals** — [ConfigConfirmNotifier], for a minis-config write
 *     parked on [com.openminis.app.config.confirm.ConfigConfirmationGate].
 *  2. **Sub-agent spawn approvals** — [SpawnApprovalNotifier], for a
 *     `spawn_agent` parked on a per-chat
 *     [com.openminis.app.tools.SpawnApprovalQueue].
 *
 * Both are answered by a `PendingIntent.getBroadcast` aimed at
 * [NotificationDecisionReceiver]. That receiver cannot reach the gates itself
 * — one is a global object built to stay Android-free, the other is per-chat
 * ViewModel state — so it routes through this seam: the app installs the two
 * handlers at startup and the receiver only performs a lookup.
 *
 * ## Why the *id* travels instead of the object
 *
 * A `PendingIntent` is minted when the notification is posted, which for a
 * config change can be minutes before the user taps. Shipping the identity and
 * resolving against live state at tap time means:
 *   - an Approve fired after the gate already timed out is a quiet no-op
 *     rather than a resurrected change;
 *   - the process may be cold-started by the receiver and the lookups still
 *     land on current state.
 *
 * Ids are namespaced by prefix ([CONFIG_ID_PREFIX] for the config family;
 * spawn asks use the `spawn-ask-` prefix minted by `SpawnApprovalQueue`), so a
 * replayed signal from an older build can never be misrouted into the wrong
 * gate.
 */
object NotificationDecisionRouter {

    /** Answer a config change by id. Returns true when the id was live. */
    @Volatile
    var onConfigDecision: ((changeId: String, approve: Boolean) -> Boolean)? = null

    /** Answer a sub-agent spawn ask by id. Returns true when the id was live. */
    @Volatile
    var onSpawnDecision: ((askId: String, decision: SpawnDecision) -> Boolean)? = null

    /** Prefix of config-change ids — see [com.openminis.app.config.confirm.PendingConfigChange]. */
    const val CONFIG_ID_PREFIX = "cfg-"

    /** Prefix of sub-agent spawn ask ids — see `SpawnApprovalQueue.submit`. */
    const val SPAWN_ID_PREFIX = "spawn-ask-"

    /** True when [id] belongs to the config-approval family. */
    fun isConfigId(id: String): Boolean = id.startsWith(CONFIG_ID_PREFIX)

    /**
     * Resolve [id] against both families. Returns true when something actually
     * consumed the decision; false lets the receiver log a stale tap instead
     * of pretending it worked.
     */
    fun dispatch(id: String, approve: Boolean): Boolean {
        if (id.isBlank()) return false
        return if (isConfigId(id)) {
            onConfigDecision?.invoke(id, approve) ?: false
        } else {
            val decision = if (approve) SpawnDecision.ALLOW_ONCE else SpawnDecision.DENY
            onSpawnDecision?.invoke(id, decision) ?: false
        }
    }

    /** Test seam: drop both handlers so a fake can be installed in isolation. */
    fun uninstall() {
        onConfigDecision = null
        onSpawnDecision = null
    }
}
