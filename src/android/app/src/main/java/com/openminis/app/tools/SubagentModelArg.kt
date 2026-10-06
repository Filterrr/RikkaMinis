package com.openminis.app.tools

/**
 * [T-subagent-model-spread] Parser for the `spawn_agent(model = …)` argument
 * when it names a MODEL GROUP instead of a single model.
 *
 * The problem it solves: a spawn batch (several `spawn_agent` calls in one
 * parent turn) that inherits the parent's model sends ALL children to the
 * same model. When the parent is bound to a multi-member model group, the
 * user's intent for that group — spread the load — is exactly what the
 * child fleet should honor: child 1 → member a, child 2 → member b, child 3
 * → member c, …（轮询）. This parser is the recognition half; the pure
 * planner that turns the members into a per-call assignment lives in
 * [SubagentGroupSpread].
 *
 * RECOGNISED SYNTAX — deliberately narrow:
 *   1. an OPT-IN marker token `group:<name-or-id>`              → Spread
 *   2. a BARE group name or id (no `group:` prefix)             → Spread
 *   3. anything else                                            → Single
 *
 * (2) exists because models naturally forget the prefix — the tool
 * description names the available groups, and "run it on cheap-pool" is the
 * obvious call. The alternative reading of a bare token — "this is a model
 * name" — is resolved by precedence, not by refusal: [SubagentModelResolver] is
 * consulted FIRST with the whole raw argument, and it iterates EVERY tier
 * (uuid → model id → display name) before it gives up. So when a token
 * matches both a context-free model name and a self-chosen group name, the
 * cross-tier guarantee [resolveSpawnModelPlan] pins is: a value that matches
 * ANY model ALWAYS resolves to that model, and group parsing only ever sees
 * values the model tier rejected. The reverse direction is preserved too:
 * when a token matches only a group, the resolver fails it loudly and the
 * plan resolves it as a group. The two readings never silently disagree —
 * whichever tier matches is the one that is announced.
 *
 * What this deliberately does NOT do:
 *   - no wildcards, no prefix matching, no fuzzy match of group names
 *     (mirrors the resolver's exactness; a typo'd `groupId` must fail loudly
 *     with the known options rather than quietly spreading across the wrong
 *     group);
 *   - no comma lists (`"a,b"` names no single model or group — both tiers
 *     reject it loudly, which is the correct outcome; a fan-out is expressed
 *     as ONE spawn per task, and the spread assigns members).
 *
 * Kept as a pure function (no Android, no repository) so the grammar is
 * JVM-unit-testable; [resolveSpawnModelPlan] composes it with the model
 * catalog, and the runner owns repository lookups + provider construction.
 */
object SubagentModelArg {

    /** The opt-in prefix that forces the group reading. */
    const val GROUP_PREFIX = "group:"

    sealed class Result {
        /** No group syntax — the caller keeps the existing model-resolution path. */
        data object Single : Result()

        /**
         * A group was named. [query] is NOT prefixed with `group:` anymore
         * and is matched by the caller against the live group list.
         */
        data class Spread(val query: String) : Result()

        /** `group:` present but empty ("group:", "group:  ") — a typo, not a wildcard. */
        data class Malformed(val message: String) : Result()
    }

    fun parse(rawModel: String?): Result {
        val query = rawModel?.trim().orEmpty()
        if (query.isEmpty()) return Result.Single
        if (query.startsWith(GROUP_PREFIX, ignoreCase = true)) {
            val name = query.substring(GROUP_PREFIX.length).trim()
            return if (name.isEmpty()) {
                Result.Malformed(
                    "model \"$query\" names no group — the '${GROUP_PREFIX}' prefix needs a " +
                        "group name or group id after it (e.g. \"${GROUP_PREFIX}fast-pool\"). " +
                        "Spawn aborted; no sub-agent run was created.",
                )
            } else {
                Result.Spread(name)
            }
        }
        // No prefix: the call site consults the MODEL resolver FIRST and only
        // parses this bare form as a group when no model matched — see the
        // precedence contract in the class KDoc.
        return Result.Spread(query)
    }
}
