package com.openminis.app.tools

/**
 * [T-subagent-model-spread] Batch spreading for sub-agent model selection.
 *
 * User intent this implements (the fork's request): 多子 agent 并发时，如果
 * 使用的是模型组（含多个模型 a、b、c…），每个子 agent 应该请求不同的成员 —
 * 1 号子 agent → a、2 号 → b、3 号 → c，以此类推 — instead of every child
 * inheriting the parent's single active member.
 *
 * Scope of the machinery (all repository-free, JVM-unit-testable):
 *
 *  1. [Assignment] — one spawn call's pre-computed member pick, produced by the
 *     conversation layer at batch-dispatch time (where the batch structure and
 *     emission order are known) and consumed by the runner at spawn time.
 *     Passing the pick DOWN instead of claiming it inside concurrent spawn
 *     coroutines is what makes "1 号 → a, 2 号 → b" deterministic: the batch is
 *     fanned out via async{}, so an in-runner claim order would race.
 *
 *  2. [planBatchMemberIndexes] — the pure rotation rule. Anchored at the
 *     parent's active member (so a lone spawn would get exactly what inherit
 *     gave it — batches of one are not spread at all, see below), rotated per
 *     call in emission order, and — this is the load-bearing part — it SKIPS
 *     members currently occupied by other live sub-agent runs. That is what
 *     makes "每个运行中的子 agent 请求不同的模型" hold beyond a single batch:
 *     a second turn's fan-out continues on free members instead of restarting
 *     at member[0] and piling onto runs that are still going.
 *
 *  3. [resolveSpawnModelPlan] — cross-tier resolution of an EXPLICIT `model`
 *     argument, which may name a single model OR a model group. Strict
 *     precedence is pinned by the call order (and by tests): a value that
 *     matches ANY model in the catalog (uuid → provider model id → display
 *     name) resolves to that model and the group reading is never consulted;
 *     only a value that matched no model falls through to the group tiers
 *     (`group:<name|id>`, then a bare name/id). A bare token that is BOTH a
 *     model name and a group name therefore always resolves as the model —
 *     unambiguous, never silently reinterpreted.
 *
 * What is deliberately NOT spread:
 *   - batches of fewer than 2 spawns (a single spawn inherits the parent's
 *     provider exactly as before this feature);
 *   - spawns that pass an explicit `model` (a pin is a pin; only the implicit
 *     "run like the parent" form rotates).
 *
 * Deferred by design: teaching the sub-agent loop to walk the group's fallback
 * chain itself on failure. A run keeps its assigned member for life; recovery
 * from a member that dies mid-run stays with the surrounding recovery policy
 * (the run fails / times out and the parent re-spawns), rather than a private
 * half-copy of the main loop's retry + health + credential-rotation ladder
 * inside every child. The [Assignment] shape leaves room to revisit this later.
 */
object SubagentGroupSpread {

    /**
     * One spawn call's pre-computed group-member assignment.
     *
     * @param groupId the model group being spread over (for the result text
     *   and future recovery work).
     * @param groupName display name, so spawn/approval/result surfaces can say
     *   which group the child came from without a repository lookup.
     * @param members the group's routable members as of batch-build time, in
     *   declaration order. A snapshot: the runner walks FORWARD from
     *   [memberIndex] if the assigned member cannot be instantiated (key
     *   removed between build and dispatch), then falls back to the parent
     *   provider if no member builds.
     * @param memberIndex the assigned index into [members].
     */
    data class Assignment(
        val groupId: String,
        val groupName: String,
        val members: List<SubagentModelResolver.Candidate>,
        val memberIndex: Int,
    ) {
        /** The assigned candidate, or null when the snapshot is empty. */
        val member: SubagentModelResolver.Candidate? get() = members.getOrNull(memberIndex)

        /** Model-facing / record-facing description, e.g. "group 'fast' → GPT-5 mini (member 2 of 3)". */
        fun describe(): String {
            val m = member ?: return "model group '$groupName' (no member)"
            return "model group '$groupName' → ${m.describe()} (member ${memberIndex + 1} of ${members.size})"
        }
    }

    /**
     * Pure rotation rule for one batch (or one turn's fan-out).
     *
     * @param members the group's routable members, declaration order.
     * @param anchorIndex the rotation's start index — the parent's active
     *   member when it is one of [members], else 0. Anchoring there makes the
     *   first pick of an idle fan-out equal to what "inherit the parent"
     *   would have chosen, so the feature is invisible for single spawns and
     *   strictly additive for batches.
     * @param callCount number of spawn calls to assign, in emission order.
     * @param liveEntryIds entry ids currently occupied by LIVE sub-agent runs
     *   (queued or running) — skipped when possible, so concurrently-running
     *   children hold distinct members. Stale/foreign ids in the set are
     *   harmless (they simply match no member).
     * @return one chosen member index per call. When every member is occupied
     *   the rule degrades to plain round-robin (`rotating[slot % n]`) — a
     *   collision is preferable to an unbuildable or missing assignment.
     *
     * Deterministic: same inputs → same output. No internal state — the live
     * set is supplied by the caller, so nothing needs releasing when runs end.
     */
    fun planBatchMemberIndexes(
        members: List<SubagentModelResolver.Candidate>,
        anchorIndex: Int,
        callCount: Int,
        liveEntryIds: Set<String>,
    ): List<Int> {
        val n = members.size
        if (n == 0 || callCount <= 0) return emptyList()
        val rotating = IntArray(n) { (anchorIndex + it) % n }
        val occupied = liveEntryIds.toMutableSet()
        val out = ArrayList<Int>(callCount)
        for (slot in 0 until callCount) {
            var chosen = -1
            for (i in 0 until n) {
                val idx = rotating[i]
                if (members[idx].entryId !in occupied) {
                    chosen = idx
                    break
                }
            }
            if (chosen < 0) chosen = rotating[slot % n]
            out.add(chosen)
            occupied.add(members[chosen].entryId)
        }
        return out
    }

    // ── explicit `model` argument: cross-tier resolution ─────────────────

    /**
     * Result of resolving ONE spawn's `model` argument into a dispatch plan.
     * The runner does exactly one thing with each outcome: dispatch to a
     * concrete candidate, or return the failure text.
     */
    sealed class ModelPlan {
        /** No `model` arg — inherit the parent's provider (pre-existing path). */
        data object Inherit : ModelPlan()

        /** Named a single model — same semantics as before this feature. */
        data class SingleModel(val candidate: SubagentModelResolver.Candidate) : ModelPlan()

        /** Named a model group — the runner uses its preferred usable member. */
        data class Mapped(val groupId: String, val groupName: String) : ModelPlan()

        /** Group resolved but every member is gone / disabled / hidden. */
        data class GroupUnusable(val message: String) : ModelPlan()

        /** Anything unresolvable — the message is model-facing and says so. */
        data class Failed(val message: String, val options: List<String>) : ModelPlan()
    }

    /**
     * Cross-tier resolution of the raw `model` argument, in strict precedence
     * order:
     *
     *   1. whole argument as a model (uuid → model id → display name) — wins
     *      whenever anything matches;
     *   2. `group:<name|id>` (opt-in, unambiguous);
     *   3. the whole argument as a bare group name / id;
     *   4. failure that names what WOULD have matched (models + groups).
     *
     * @param rawModel the verbatim tool argument (may be "", " ", null).
     * @param modelCatalog from `Deps.modelCatalog()`.
     * @param groupLookup finds a group by name/id — returns (id, name) or null.
     * @param groupMembers live members of that group (enabled instances,
     *   non-hidden entries) — [] means the group exists but is unusable.
     * @param knownGroupNames configured group names, appended (bounded) to
     *   failure text so a typo is correctable in one retry.
     */
    fun resolveSpawnModelPlan(
        rawModel: String?,
        modelCatalog: List<SubagentModelResolver.Candidate>,
        groupLookup: (String) -> Pair<String, String>?,
        groupMembers: (String) -> List<SubagentModelResolver.Candidate>,
        knownGroupNames: () -> List<String> = { emptyList() },
    ): ModelPlan {
        val query = rawModel?.trim().orEmpty()
        if (query.isEmpty()) return ModelPlan.Inherit

        // Tier 1: exact model match on the WHOLE argument, before any group
        // reading. See the precedence contract in the class KDoc.
        return when (val direct = SubagentModelResolver.resolve(query, modelCatalog)) {
            is SubagentModelResolver.Result.Resolved -> ModelPlan.SingleModel(direct.candidate)
            is SubagentModelResolver.Result.Failed ->
                // Fall through to the group reading; keep the failure for the
                // case where no group matches either.
                viaGroup(query, direct, groupLookup, groupMembers, knownGroupNames)
            SubagentModelResolver.Result.Inherit ->
                viaGroup(query, null, groupLookup, groupMembers, knownGroupNames)
        }
    }

    private fun viaGroup(
        query: String,
        modelFailure: SubagentModelResolver.Result.Failed?,
        groupLookup: (String) -> Pair<String, String>?,
        groupMembers: (String) -> List<SubagentModelResolver.Candidate>,
        knownGroupNames: () -> List<String>,
    ): ModelPlan {
        val parsed = SubagentModelArg.parse(query)
        val groupQuery: String? = when (parsed) {
            is SubagentModelArg.Result.Spread -> parsed.query
            is SubagentModelArg.Result.Malformed -> return ModelPlan.Failed(parsed.message, emptyList())
            SubagentModelArg.Result.Single -> return ModelPlan.Inherit
        }
        val group = groupQuery?.let(groupLookup)
        if (group != null) {
            val (id, name) = group
            val members = groupMembers(id)
            if (members.isEmpty()) {
                return ModelPlan.GroupUnusable(
                    "model group \"$name\" has no usable members right now (all members " +
                        "disabled, hidden, or behind a disabled provider). Spawn aborted; " +
                        "no sub-agent run was created. Pick a single model instead " +
                        "(model uuid / model id / display name).",
                )
            }
            return ModelPlan.Mapped(groupId = id, groupName = name)
        }
        // Neither a model nor a group matched: fail with everything the caller
        // could have meant. The resolver already produced a good list; the
        // group suffix adds the group names.
        return modelFailure?.let {
            ModelPlan.Failed(it.message + groupSuffix(knownGroupNames), it.options)
        } ?: ModelPlan.Failed(
            "model \"$query\" did not match any configured model" +
                groupSuffix(knownGroupNames) +
                ". Spawn aborted; no sub-agent run was created.",
            emptyList(),
        )
    }

    /** Bounded, model-facing alias list for failures — same budget spirit as the model hint. */
    private fun groupSuffix(knownGroupNames: () -> List<String>): String {
        val names = knownGroupNames().filter { it.isNotBlank() }.take(10)
        return if (names.isEmpty()) "" else " (model groups: " + names.joinToString(", ") + ")"
    }
}
