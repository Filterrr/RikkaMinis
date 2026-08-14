package com.openminis.app.data.routing

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy

/**
 * Pure-JVM group routing engine — decides "which member to use now" and "in
 * which order to fall back", owning per-member runtime health.
 *
 * Same pattern as ContextCompactor / ToolFailureHook: no Android dependencies,
 * all time controlled by the injectable [clock] so unit tests advance time
 * deterministically. ChatViewModel instantiates one (like ToolFailureHook) and
 * delegates its inline routing decisions here.
 *
 * Phase 1: pure extraction of the decisions that used to live inline in
 * ChatViewModel (`resolveProviderFromGroup` / `buildFallbackProviders`) —
 * zero behavior change. The health map is consulted but never populated yet,
 * so [isUsable] always returns true. Phase 2 wires [recordResult] into the
 * loop's error classification and the health gate becomes real.
 */
class GroupRouter(
    /** Clock in epoch-millis (default: System.currentTimeMillis). */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val health = mutableMapOf<String, MemberHealth>()

    // ── selection ──────────────────────────────────────────────────────────

    /**
     * Select the member to use for a new resolution.
     *
     * @param group the group being resolved
     * @param members pre-filtered candidates (enabled instances with
     *   credentials — the caller filters, e.g. `enabledMemberEntries`)
     * @param preferredEntryId prior session binding ("user picked this entry
     *   inside the group last time"); honored only when still in [members],
     *   otherwise the first usable member so the session can proceed on a
     *   now-degraded group
     * @param stickyEntryId last-used entry — the loadBalance rotation anchor
     *   (rotates one step past it); ignored by the other strategies
     * @return the chosen entry id, or null when [members] is empty or none is
     *   currently usable
     */
    fun select(
        group: ModelGroup,
        members: List<ModelEntry>,
        preferredEntryId: String? = null,
        stickyEntryId: String? = null,
    ): String? {
        if (members.isEmpty()) return null
        val usable = members.filter { isUsable(it.id) }
        if (usable.isEmpty()) return null
        return when {
            preferredEntryId != null ->
                usable.firstOrNull { it.id == preferredEntryId }?.id ?: usable.first().id
            group.strategy == RoutingStrategy.loadBalance -> {
                val lastIdx = usable.indexOfFirst { it.id == stickyEntryId }
                usable[(lastIdx + 1) % usable.size].id
            }
            else -> usable.first().id
        }
    }

    /**
     * Ordered fallback candidates for [group]: starts from the member AFTER
     * [activeEntryId] and cycles the group.
     *
     * [P0-fallback-anchor] semantics — anchor by the ACTUAL active entry id,
     * NOT by model.id — because a group can hold several entries for the SAME
     * modelId behind different instances/endpoints (e.g. deepseek-v4-flash via
     * a dead hub.oaifree.com key + via api.deepseek.com). Matching by modelId
     * returns the FIRST entry with that modelId, which may sit earlier than
     * the entry actually in use — the fallback chain would start from the
     * wrong point and even re-include the current entry itself, causing
     * repeated calls to the failing provider.
     *
     * When [activeEntryId] isn't in the group, falls back to a modelId match
     * via [modelIdOf] (entryId → model.id), or starts from index 1 when no
     * match either.
     *
     * The caller still filters the returned order (disabled instance, missing
     * credential, provider creation failure) — this is pure ordering.
     */
    fun fallbackOrder(
        group: ModelGroup,
        activeEntryId: String?,
        primaryModelId: String,
        modelIdOf: (String) -> String?,
    ): List<String> {
        val members = group.memberEntryIds
        val currentIdx = if (activeEntryId != null && members.contains(activeEntryId)) {
            members.indexOf(activeEntryId)
        } else {
            members.indexOfFirst { modelIdOf(it) == primaryModelId }
        }
        val order = mutableListOf<String>()
        for (offset in 1 until members.size) {
            val idx = if (currentIdx >= 0) (currentIdx + offset) % members.size else offset
            order.add(members[idx])
        }
        return order
    }

    // ── health ─────────────────────────────────────────────────────────────

    /**
     * Record a request outcome, updating the member's runtime health.
     *
     * Phase 2 wires the demotion transitions here:
     *  - RateLimited  → Cooling(now + retryAfter ?: default)
     *  - ServerError  → failure counter → OpenCircuit at threshold
     *  - AuthError    → Dead
     *  - Success      → Healthy (clear demotion)
     */
    fun recordResult(entryId: String, outcome: RouteOutcome) {
        // Phase 2: not yet wired — no demotion, no promotion.
    }

    /** True when the member may be selected now (no entry = healthy). */
    fun isUsable(entryId: String): Boolean = health[entryId]?.isUsable(clock()) ?: true

    /** Current health of a member (no entry = healthy). */
    fun healthOf(entryId: String): MemberHealth = health[entryId] ?: MemberHealth.Healthy
}
