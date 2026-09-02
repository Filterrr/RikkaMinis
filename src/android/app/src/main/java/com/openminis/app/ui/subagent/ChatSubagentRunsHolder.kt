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

    fun push(runs: StateFlow<List<SubagentRunRegistry.Run>>) {
        currentRuns = runs
    }
}
