package com.openminis.app.ui.subagent

import com.openminis.app.tools.SubagentRunRegistry
import kotlinx.coroutines.flow.StateFlow

/**
 * [T-subagent-ui] Bridge between the per-chat [ChatViewModel.subagentRunRegistry]
 * and the nav-level [SubagentDetailScreen] route.
 *
 * The route lives in AppNavigation, which has no ChatViewModel reference
 * (each chat owns one VM keyed by ChatViewModelStore). Rather than threading
 * the live state through NavBackStackEntry arguments (would serialize the
 * whole stream), ChatScreen publishes its registry's runs StateFlow here —
 * the flow object is stable for the VM's lifetime — and the detail page
 * collects it directly. Updates keep flowing while the detail page is on
 * top because the VM outlives the NavBackStackEntry (it is released only
 * when the session is deleted, see SessionListViewModel).
 *
 * This mirrors the existing FilePreviewHolder pattern in AppNavigation
 * (stash reference → navigate → screen reads holder).
 */
object ChatSubagentRunsHolder {
    @Volatile
    var currentRuns: StateFlow<List<SubagentRunRegistry.Run>>? = null

    /**
     * [T-subagent-user-cancel] User-facing cancel, published the same way as
     * [currentRuns] and for the same reason: the detail page is a nav-level
     * route with no ViewModel reference.
     *
     * WHY THE HOLDER CARRIES AN ACTION, not just state: until this existed the
     * UI had no way to stop a run at all — the detail page offered only "back"
     * and the pill only "open". Every cancellation went through the model
     * calling cancel_subagents, which meant a user watching a detached
     * research run burn tokens had to TYPE a request into the chat and hope
     * the model complied. Detached runs are explicitly "nobody is watching"
     * background work, so this was the one case where a manual override
     * matters most and existed least.
     *
     * Held as a plain lambda (not a VM reference) so the nav layer stays
     * decoupled; null when no chat is mounted.
     */
    @Volatile
    var cancelRun: ((String) -> Unit)? = null

    /** Publish the cancel action alongside the runs. */
    fun push(
        runs: StateFlow<List<SubagentRunRegistry.Run>>,
        onCancel: ((String) -> Unit)? = null,
        detachedIds: (() -> Set<String>)? = null,
        session: String? = null,
    ) {
        currentRuns = runs
        cancelRun = onCancel
        detachedRunIds = detachedIds
        sessionId = session
    }

    /** Clear everything (chat torn down) so no stale action outlives its VM. */
    fun clear() {
        currentRuns = null
        cancelRun = null
        detachedRunIds = null
        sessionId = null
    }

    /**
     * [T-subagent-user-cancel] Which runs are DETACHED, published alongside
     * the cancel action so the detail page can decide whether a Stop button is
     * meaningful. Supplied by the chat layer (it owns the orchestration
     * registry); a run absent from the set — or a null set, i.e. no chat
     * mounted — is treated as non-detached, which hides the button rather
     * than offering an action that would be refused.
     */
    @Volatile
    var detachedRunIds: (() -> Set<String>)? = null

    /**
     * [T-subagent-ui-report-md] Session owning the runs.
     *
     * The finished report renders through the shared markdown renderer, which
     * resolves `minis://…` paths and sandbox media through the AMBIENT
     * [com.openminis.app.ui.chat.LocalMarkdownSessionId] — provided by
     * ChatScreen for its own subtree. The detail page is a NAV-LEVEL route
     * outside that subtree and re-provides it for the report body, so a path a
     * sub-agent wrote resolves against the session that spawned it instead of
     * whichever session last booted a shell (the global bind-mount map is
     * last-writer-wins). Null when no chat is mounted — links then stay inert
     * rather than guessing a session.
     */
    @Volatile
    var sessionId: String? = null

    /** True when [runId] is a detached run the user can stop. */
    fun isDetached(runId: String): Boolean =
        detachedRunIds?.invoke()?.contains(runId) == true
}
