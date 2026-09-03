package com.openminis.app.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * [T-tool-batch-executor] Shared tool-call batch dispatcher for the MAIN
 * agent loop and sub-agent loops alike — single source of execution
 * semantics, both consumers of [ToolConcurrencyPolicy]:
 *
 *  - consecutive PARALLEL_SAFE calls (pure reads: file_read / read_image,
 *    browser_use read-only actions) fan out concurrently;
 *  - a consecutive run of spawn_agent calls forms its own parallel group;
 *  - every other call (SERIAL_BARRIER) executes alone, in order.
 *
 * The batch partition comes from [ToolConcurrencyPolicy.partitionToolCalls]
 * (order-preserving, greedy), so result order ALWAYS matches the model's
 * emission order regardless of internal concurrency — callers can zip the
 * returned list with their input calls index-for-index.
 *
 * Before this class the main loop did all-or-nothing batching (a single
 * serial tool downgraded the WHOLE batch to sequential) and sub-agent
 * loops executed strictly serially; both now share this executor.
 */
object ToolBatchExecutor {

    /** One tool call to execute. [id] must be unique within a batch request. */
    data class Call(val id: String, val name: String, val argsJson: String)

    /**
     * Execute [calls] under the concurrency policy.
     *
     * @param onQueued optional visibility callback, invoked with a call id
     *   right before a SERIAL call that starts behind previously-started
     *   work (the main loop's "⏳ Waiting for previous tool(s)…" cue).
     *   Parallel batch members start together and never fire it. Suspending
     *   so UI consumers can hop to the main dispatcher inside it.
     * @param executeOne executes a single call. Should return a
     *   [ToolExecutionResult]; thrown exceptions propagate and cancel any
     *   concurrent siblings of the same batch.
     * @return results aligned 1:1 (by index) with [calls].
     */
    suspend fun executeBatched(
        calls: List<Call>,
        onQueued: (suspend (callId: String) -> Unit)? = null,
        executeOne: suspend (Call) -> ToolExecutionResult,
    ): List<ToolExecutionResult> {
        if (calls.isEmpty()) return emptyList()
        val byId = calls.associateBy { it.id }
        val batches = ToolConcurrencyPolicy.partitionToolCalls(
            calls.map { Triple(it.id, it.name, it.argsJson) },
        )
        val results = HashMap<String, ToolExecutionResult>(calls.size)
        var firstBatch = true
        for (batch in batches) {
            val batchCalls = batch.mapNotNull { byId[it.first] }
            if (batchCalls.isEmpty()) continue
            if (batchCalls.size == 1) {
                val call = batchCalls[0]
                if (!firstBatch) onQueued?.invoke(call.id)
                results[call.id] = executeOne(call)
            } else {
                // Fan out (READ_PARALLEL or SPAWN_PARALLEL group); siblings
                // start together — no queued cue by design.
                val deferred = coroutineScope {
                    batchCalls.map { call -> async { call.id to executeOne(call) } }
                }
                for (d in deferred) {
                    val (id, result) = d.await()
                    results[id] = result
                }
            }
            firstBatch = false
        }
        return calls.map { call ->
            results[call.id]
                ?: ToolExecutionResult("Error: internal — no result recorded for tool call ${call.id}", false)
        }
    }
}
