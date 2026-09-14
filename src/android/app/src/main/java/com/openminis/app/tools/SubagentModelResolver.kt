package com.openminis.app.tools

/**
 * [T-subagent-model-routing] Resolve a `spawn_agent` `model` argument to a
 * concrete catalog entry so a sub-agent can run on a cheaper model than its
 * parent ("cheap work does not have to share the main model").
 *
 * Ported from the ExTV/rikkahub-agent `SubAgentModelResolver` design: accept a
 * model **uuid**, a **provider model id**, or a **display name**
 * (case-insensitive exact match), and FAIL LOUDLY with the valid options when
 * the value is unknown or ambiguous. Silently falling back to the parent's
 * model is the failure mode this deliberately avoids — a typo'd cheap model
 * would otherwise bill against the expensive one.
 *
 * Kept as a pure function over a [Candidate] list (no Android, no repository
 * reference) so the precedence + ambiguity rules are JVM-unit-testable; the
 * caller owns catalog lookup and provider construction.
 */
object SubagentModelResolver {

    /** One routable model as offered to a sub-agent dispatch. */
    data class Candidate(
        /** Provider instance the model belongs to (needed to rebuild a provider). */
        val instanceId: String,
        /** ModelEntry uuid — the stable "model uuid" form users paste. */
        val entryId: String,
        /** Provider-side model id, e.g. "claude-sonnet-4-5". */
        val modelId: String,
        /** Human display name, e.g. "Claude Sonnet 4.5". */
        val displayName: String,
    ) {
        /** Labels shown in errors and in the spawn tool's parameter description. */
        fun describe(): String = "$displayName ($modelId)"
    }

    sealed class Result {
        /** No `model` given — inherit the parent's provider unchanged. */
        data object Inherit : Result()

        /** Unique match — caller builds a provider for [candidate]. */
        data class Resolved(val candidate: Candidate) : Result()

        /**
         * Unknown or ambiguous value. [message] is model-facing: it states the
         * offending input and lists what WOULD have matched, so the parent can
         * retry with a valid name instead of guessing again.
         */
        data class Failed(val message: String, val options: List<String>) : Result()
    }

    /**
     * Resolution order (first hit wins):
     *   1. entry uuid           — exact, case-insensitive (uuids are opaque)
     *   2. provider model id    — exact, case-insensitive
     *   3. display name         — exact, case-insensitive
     * A tier that matches MORE THAN ONE candidate is ambiguous → [Result.Failed]
     * listing those candidates. We never "pick the first match".
     */
    fun resolve(rawModel: String?, candidates: List<Candidate>): Result {
        val query = rawModel?.trim().orEmpty()
        if (query.isEmpty()) return Result.Inherit

        val byUuid = candidates.filter { it.entryId.equals(query, ignoreCase = true) }
        if (byUuid.size == 1) return Result.Resolved(byUuid.first())
        if (byUuid.size > 1) return ambiguous(query, byUuid)

        val byModelId = candidates.filter { it.modelId.equals(query, ignoreCase = true) }
        if (byModelId.size == 1) return Result.Resolved(byModelId.first())
        if (byModelId.size > 1) return ambiguous(query, byModelId)

        val byName = candidates.filter { it.displayName.equals(query, ignoreCase = true) }
        if (byName.size == 1) return Result.Resolved(byName.first())
        if (byName.size > 1) return ambiguous(query, byName)

        return Result.Failed(
            message = "model \"$query\" did not match any configured model. " +
                optionsSummary(candidates),
            options = candidates.map { it.describe() },
        )
    }

    private fun ambiguous(query: String, matches: List<Candidate>): Result.Failed =
        Result.Failed(
            message = "model \"$query\" matches multiple models — disambiguate with the " +
                "model uuid: " + matches.joinToString(", ") { it.describe() },
            options = matches.map { it.describe() },
        )

    /** Bounded candidate list for error text — a 200-model catalog must not flood the prompt. */
    private fun optionsSummary(candidates: List<Candidate>, limit: Int = 25): String =
        if (candidates.isEmpty()) {
            "no models are configured"
        } else if (candidates.size <= limit) {
            "available: " + candidates.joinToString(", ") { it.describe() }
        } else {
            "available (first $limit of ${candidates.size}): " +
                candidates.take(limit).joinToString(", ") { it.describe() }
        }
}
