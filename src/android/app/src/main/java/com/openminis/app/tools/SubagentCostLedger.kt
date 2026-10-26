package com.openminis.app.tools

/**
 * [feat/subagent-roster-routing] P1-3 — the CHAT-LEVEL ledger of what
 * delegation costs the user.
 *
 * The problem this closes: a parent agent could spawn five full-budget
 * sub-agents and have NO idea what it had spent. The parent's own
 * `AgentExecutionBudget` does not see it — the token dimension is disabled
 * today (`maxEstimatedTokens = null`), and that budget's rule 7 forbids
 * fabricating a token count when the provider cannot supply one. So the
 * parent's view of its own cost was simply incomplete, silently.
 *
 * The rule this class follows, and why: do NOT fake a token count. What we
 * CAN state honestly is an UPPER BOUND — a delegated run cannot exceed
 * `maxTurns × the run model's effective max output tokens` per turn, and
 * the run's own turn count is known. That bound is a real fact about the
 * runtime's design, not an estimate of what the provider reported, so it
 * does not violate rule 7: it is a ceiling, explicitly labelled as such.
 *
 * Two numbers are tracked:
 *  - [worstCaseTokens]: the ceiling — what the delegation COULD cost.
 *  - [reportedTokens]: what the provider actually reported (0 when it
 *    reported nothing, which stays visibly distinct from "unknown" in the
 *    ledger's own [hasReportedUsage] flag).
 *
 * The distinction matters when the user asks "why did that cost so much?".
 * Without it, the app would have to present a ceiling as if it were a
 * measurement.
 */
internal class SubagentCostLedger {

    /** One delegated run's contribution to the chat's delegation cost. */
    data class Entry(
        val runId: String,
        val skillName: String,
        /** The run's CEILING — maxTurns × per-turn max output. */
        val worstCaseTokens: Long,
        /** What the provider reported for this run (0 = none reported). */
        val reportedTokens: Long,
        /** Whether the provider reported anything at all. */
        val hasReportedUsage: Boolean,
    )

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Register a run's ceiling. Called once, at spawn time, with the
     * bound the run's own configuration implies.
     */
    @Synchronized
    fun reserve(runId: String, skillName: String, worstCaseTokens: Long) {
        entries[runId] = Entry(
            runId = runId,
            skillName = skillName,
            worstCaseTokens = worstCaseTokens.coerceAtLeast(0L),
            reportedTokens = 0L,
            hasReportedUsage = false,
        )
    }

    /**
     * Book a run's ACTUAL spend. A provider that reports nothing leaves
     * [hasReportedUsage] false — "unknown" must never read as "free".
     */
    @Synchronized
    fun settle(runId: String, reportedTokens: Long, hasReportedUsage: Boolean) {
        val existing = entries[runId] ?: return
        entries[runId] = existing.copy(
            reportedTokens = reportedTokens.coerceAtLeast(0L),
            hasReportedUsage = hasReportedUsage,
        )
    }

    /** Forget a run (cancelled before it started, or swept). */
    @Synchronized
    fun forget(runId: String) {
        entries.remove(runId)
    }

    @Synchronized
    fun all(): List<Entry> = entries.values.toList()

    /** The chat's total delegation CEILING. */
    @Synchronized
    fun totalWorstCaseTokens(): Long = entries.values.sumOf { it.worstCaseTokens }

    /** The chat's total REPORTED spend. */
    @Synchronized
    fun totalReportedTokens(): Long = entries.values.sumOf { it.reportedTokens }

    /**
     * Whether ANY run reported usage. False means the whole chat's
     * delegation cost is UNKNOWN — a state the UI must render as unknown,
     * not as zero.
     */
    @Synchronized
    fun hasAnyReportedUsage(): Boolean = entries.values.any { it.hasReportedUsage }

    /** The chat's token spend as a display string, or null when unknown. */
    @Synchronized
    fun summary(): String? {
        if (entries.isEmpty()) return null
        if (!hasAnyReportedUsage()) return null
        return "${totalReportedTokens()} tok"
    }

    /**
     * The chat's delegation CEILING as a display string — always available
     * once a run was spawned, because it is a fact about the configuration
     * rather than a measurement.
     */
    @Synchronized
    fun worstCaseSummary(): String? {
        if (entries.isEmpty()) return null
        return "≤${totalWorstCaseTokens()} tok"
    }
}
