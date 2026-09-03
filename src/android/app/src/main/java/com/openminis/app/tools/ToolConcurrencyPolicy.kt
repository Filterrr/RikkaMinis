package com.openminis.app.tools

/**
 * Tool-call concurrency policy for the agent loop. Ported from OmniBot's
 * AgentToolConcurrencyPolicy (omnimind-ai/OmniBot) — same whitelist-first
 * philosophy:
 *
 *  - Default = SERIAL_BARRIER. Only tools on the explicit whitelist below
 *    (pure-read, no shared mutable side-effects) are declared PARALLEL_SAFE.
 *  - browser_use is classified at the *action* granularity: read-only
 *    actions (get_text / screenshot / get_page_info / get_readable /
 *    get_backbone / list_tabs / get_cookies) are PARALLEL_SAFE; anything
 *    that changes page or tab state (navigate / click / type / scroll /
 *    fetch / new_tab / close_tab / ...) stays SERIAL_BARRIER. This mirrors
 *    OmniBot: "browser_use get_text/screenshot can run in parallel,
 *    navigate/click are serial".
 *
 * Why RikkaMinis can safely parallelize these:
 *  - file_read / read_image / memory_get are pure reads with no shared
 *    mutable state.
 *  - BrowserTabPool.execute is already concurrency-safe: calls are
 *    serialized per-tab via a per-tab Mutex, and different tabs run
 *    concurrently.
 *  - Serial tools (shell_execute, file_write, file_edit, memory_write,
 *    browser_use state-changing actions) are heavy or have side effects,
 *    so keeping them on the serial barrier preserves ordering guarantees
 *    the agent loop relies on (loop detector sequence, result ordering).
 */
object ToolConcurrencyPolicy {

    /**
     * Tool names that may run concurrently in one batch. These must be
     * *pure reads with no shared mutable side effects*. Anything that
     * touches ChatViewModel state, the filesystem for writing, or the
     * terminal is excluded.
     *
     * Note memory_get is deliberately NOT whitelisted even though it is
     * read-semantics: it appends to `_memoryToolRecords` (a StateFlow)
     * via check-then-act, which would race if run concurrently.
     */
    private val PARALLEL_SAFE_TOOLS: Set<String> = setOf(
        "file_read",
        "read_image",
    )

    /**
     * [T-subagent-serial] spawn_agent is deliberately NOT parallel-safe and
     * [T-subagent-parallel] Updated: spawn_agent now forms its OWN parallel
     * batch group (isParallelWithSelf) — multiple spawns fan out via async{},
     * bounded by the per-chat SubagentDispatchLimiter. Each run owns its
     * registry entry and stream; shared shell/browser resources stay safe
     * (shell commands are separate ExecutionCoordinator commands, browser
     * actions serialize per-tab in the pool).
     */
    // spawn_agent: not parallel-safe with reads, but parallel-with-self.

    /**
     * browser_use actions that never mutate page/tab state and may run
     * concurrently with each other. Mirrors OmniBot's
     * BROWSER_USE_PARALLEL_SAFE_ACTIONS = { get_text, screenshot } plus the
     * other purely-read actions RikkaMinis exposes; state-changing actions
     * (navigate / click / type / scroll / fetch / new_tab / close_tab /
     * set_cookies / ...) stay serial.
     */
    private val BROWSER_READ_ACTIONS: Set<String> = setOf(
        "get_text",
        "screenshot",
        "get_page_info",
        "get_readable",
        "get_backbone",
        "list_tabs",
        "get_cookies",
    )

    /** The action says which of these tools is mutating. */
    fun isParallelSafe(toolName: String, argsJson: String?): Boolean {
        if (toolName == "browser_use") {
            val action = parseAction(argsJson) ?: return false
            return action in BROWSER_READ_ACTIONS
        }
        // [T-subagent-parallel] spawn_agent runs concurrently with OTHER
        // spawn_agent calls (each holds its own registry run + stream; the
        // worker's executionMutex interleaves the model calls). It still
        // must not be batched with plain parallel-safe reads — a long spawn
        // would head-of-line block the whole batch in one scope.
        if (toolName == "spawn_agent") return false
        return toolName in PARALLEL_SAFE_TOOLS
    }

    /**
     * [T-subagent-parallel] True when [toolName] may run CONCURRENTLY with
     * other calls of the SAME tool (spawn_agent-only parallel group).
     * Used by the dispatch loop to fan a run of spawn_agent calls out via
     * async{} while keeping the result order stable.
     */
    fun isParallelWithSelf(toolName: String): Boolean = toolName == "spawn_agent"

    /**
     * Greedy partitioning that merges consecutive PARALLEL_SAFE calls into
     * one batch and makes every SERIAL_BARRIER call its own singleton batch,
     * preserving original order. Ported from OmniBot
     * AgentToolConcurrencyPolicy.partitionToolCalls (greedy, order-preserving).
     *
     * [T-subagent-parallel] A consecutive run of spawn_agent calls forms its
     * own parallel batch too (via [isParallelWithSelf]) — the dispatch loop
     * fans it out, the per-chat limiter bounds actual concurrency.
     *
     * @param calls list of (id, toolName, argsJson) in the order the model
     *   emitted them.
     * @return batches in original order; a batch is either one serial call
     *   or a run of N>=1 parallel-safe calls (or N>=1 spawn_agent calls).
     */
    fun partitionToolCalls(
        calls: List<Triple<String, String, String>>,
    ): List<List<Triple<String, String, String>>> {
        if (calls.isEmpty()) return emptyList()
        val batches = mutableListOf<List<Triple<String, String, String>>>()
        val current = mutableListOf<Triple<String, String, String>>()
        var currentGroup: BatchKind = BatchKind.SERIAL
        for (call in calls) {
            val group = when {
                isParallelSafe(call.second, call.third) -> BatchKind.READ_PARALLEL
                isParallelWithSelf(call.second) -> BatchKind.SPAWN_PARALLEL
                else -> BatchKind.SERIAL
            }
            if (current.isEmpty()) {
                current.add(call)
                currentGroup = group
            } else if (group != BatchKind.SERIAL && group == currentGroup) {
                current.add(call)
            } else {
                batches.add(current.toList())
                current.clear()
                current.add(call)
                currentGroup = group
            }
        }
        if (current.isNotEmpty()) {
            batches.add(current.toList())
        }
        return batches
    }

    private enum class BatchKind { SERIAL, READ_PARALLEL, SPAWN_PARALLEL }

    private fun parseAction(argsJson: String?): String? {
        if (argsJson.isNullOrBlank()) return null
        return try {
            org.json.JSONObject(argsJson).optString("action", "").trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}