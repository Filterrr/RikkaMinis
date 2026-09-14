package com.openminis.app.config.confirm

import com.openminis.app.config.ConfigRisk
import java.util.UUID

/**
 * One row in a pending change request. A single CLI call may carry
 * multiple changes (`set-batch`); they're presented in a single dialog
 * and confirmed/rejected as a unit (with per-row reject toggles).
 */
data class PendingConfigChangeItem(
    val id: String = UUID.randomUUID().toString(),
    /** Label shown in the row. Comes from the field's [displayName]. */
    val displayName: String,
    /** Dot path; shown in fine print. */
    val path: String,
    /** Old value (humanized). Empty for add-collection rows. */
    val oldDisplay: String,
    /** New value (humanized). Empty for remove rows. */
    val newDisplay: String,
    /** Verb shown in the row (set / append / remove / add / hide / revert). */
    val verb: String,
    val risk: ConfigRisk,
    /** Per-row reject toggle, defaults true (allow). */
    val isApproved: Boolean = true,
)

/** A queued CLI request awaiting user confirmation. */
data class PendingConfigChange(
    /**
     * [T-notif-inline-decision] Prefixed so the notification-decision receiver
     * can tell a config approval apart from a sub-agent spawn approval without
     * a second intent extra (see
     * [com.openminis.app.notification.NotificationDecisionRouter]). The prefix
     * is part of the id, so existing round-trips (queue bookkeeping, the
     * once-per-id notification guard, `resolve`) all stay unchanged.
     */
    val id: String = com.openminis.app.notification.NotificationDecisionRouter.CONFIG_ID_PREFIX +
        UUID.randomUUID().toString(),
    val items: List<PendingConfigChangeItem>,
    val caption: String?,
)

/** Final disposition the gate reports back to the bridge. */
sealed class ConfirmOutcome {
    /** User approved with the given (possibly per-row toggled) item set. */
    data class Approved(val items: List<PendingConfigChangeItem>) : ConfirmOutcome()
    /** User cancelled. */
    object Rejected : ConfirmOutcome()
    /** No user response within the gate's timeout window. */
    object TimedOut : ConfirmOutcome()
}
