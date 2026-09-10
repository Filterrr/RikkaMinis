package com.openminis.app.diagnostics

import java.util.concurrent.ConcurrentHashMap

/**
 * [feat-provider-health] In-process provider health aggregation.
 *
 * Data already existed but only as JSONL on the perf baseline (release
 * builds never read it back) or inside trace files. This tracker keeps
 * a bounded, ring-buffered per-model record of recent provider attempts
 * so the Settings → Provider Health panel can show, per model:
 * recent TTFB distribution (P50/P95), success rate over the last N
 * attempts, and the last failure reason.
 *
 * Design:
 *  - Pure JVM, no Android deps — unit-testable in the sandbox.
 *  - Ring buffer per modelId, fixed capacity [CAPACITY] (oldest evicted).
 *  - All methods are cheap + non-blocking (ConcurrentHashMap + array
 *    copy-on-write); safe to call from the streaming hot path.
 *  - In-memory only: resets on process death. This is a "what has this
 *    model done for me lately" surface, not an audit log — the perf
 *    baseline JSONL remains the durable record.
 */
object ProviderHealthTracker {

    private const val TAG = "ProviderHealth"
    private const val CAPACITY = 20

    /** One provider attempt (one LLM call, retry or fallback included). */
    data class Attempt(
        val modelId: String,
        val modelDisplayName: String,
        val startedAtMs: Long,
        val ttfbMs: Long?,      // null = failed before first token
        val success: Boolean,
        val failureReason: String? = null,
        val durationMs: Long? = null,
    )

    data class ModelHealth(
        val modelId: String,
        val modelDisplayName: String,
        val attempts: List<Attempt>,
        val successRate: Double,       // 0..1 over recorded attempts
        val ttfbP50Ms: Long?,          // null when no successful attempt recorded
        val ttfbP95Ms: Long?,
        val lastFailureReason: String?,
        val lastUsedAtMs: Long,
    )

    private val history = ConcurrentHashMap<String, ArrayDeque<Attempt>>()

    /** Record an attempt start → returns the token used by the finish calls. */
    fun begin(modelId: String, modelDisplayName: String): Attempt {
        return Attempt(
            modelId = modelId,
            modelDisplayName = modelDisplayName,
            startedAtMs = System.currentTimeMillis(),
            ttfbMs = null,
            success = false,
        )
    }

    /** First token arrived on the wire. */
    fun recordFirstToken(attempt: Attempt, ttfbMs: Long): Attempt =
        attempt.copy(ttfbMs = ttfbMs)

    /** Attempt finished (success or terminal failure). Records into the ring. */
    fun finish(attempt: Attempt, success: Boolean, failureReason: String? = null, durationMs: Long? = null) {
        val rec = attempt.copy(success = success, failureReason = failureReason, durationMs = durationMs)
        val q = history.getOrPut(rec.modelId) { ArrayDeque() }
        synchronized(q) {
            q.addLast(rec)
            while (q.size > CAPACITY) q.removeFirst()
        }
    }

    /** Snapshot for the UI panel, worst-recent-first (highest failure rate, then stalest). */
    fun snapshot(): List<ModelHealth> {
        val now = System.currentTimeMillis()
        return history.map { (modelId, q) ->
            val items = synchronized(q) { q.toList() }
            val successes = items.filter { it.success }
            val ttfbs = successes.mapNotNull { it.ttfbMs }.sorted()
            ModelHealth(
                modelId = modelId,
                modelDisplayName = items.lastOrNull()?.modelDisplayName ?: modelId,
                attempts = items,
                successRate = if (items.isEmpty()) 0.0 else successes.size.toDouble() / items.size,
                ttfbP50Ms = percentile(ttfbs, 0.50),
                ttfbP95Ms = percentile(ttfbs, 0.95),
                lastFailureReason = items.lastOrNull { !it.success }?.failureReason,
                lastUsedAtMs = items.lastOrNull()?.startedAtMs ?: now,
            )
        }.sortedWith(
            compareByDescending<ModelHealth> { 1.0 - it.successRate }
                .thenByDescending { it.lastUsedAtMs },
        )
    }

    /** Drop everything (e.g. user cleared the panel). */
    fun clear() = history.clear()

    private fun percentile(sorted: List<Long>, p: Double): Long? {
        if (sorted.isEmpty()) return null
        val idx = ((sorted.size - 1) * p).let { kotlin.math.ceil(it).toInt() }.coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
