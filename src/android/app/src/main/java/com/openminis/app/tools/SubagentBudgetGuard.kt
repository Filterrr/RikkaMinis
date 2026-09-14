package com.openminis.app.tools

/**
 * [T-subagent-budget-inheritance] The parent agent loop's execution budget,
 * as seen by the sub-agent runtime.
 *
 * Closes the F13 gap (`docs/stability/failure-matrix.md` — "subagent 预算继
 * 承"; `docs/stability/runtime-contract.md:115` — "retry、fallback、compact、
 * subagent 都消耗同一份父预算"). Before this a sub-agent's spend was invisible
 * to the parent loop: a parent could exhaust its context window on delegated
 * work without its own compaction / budget logic ever noticing, because the
 * child ran on a separate stream with separate usage.
 *
 * Deliberately a three-method interface rather than handing an
 * `AgentExecutionBudget` to the runner:
 *  - a child must not be able to consume the PARENT's turn / tool-call
 *    counters (those are the parent loop's own progress; 5 spawns × 48 turns
 *    would otherwise cut the parent off mid-plan); a child moves TOKENS and
 *    the shared deadline only;
 *  - reserve / settle are split across the run's life so a spawn that dies
 *    before execution (queue timeout, cancel-before-start) can never leak a
 *    reservation: the reservation is taken INSIDE the loop's try, released in
 *    its finally;
 *  - the tools layer stays free of the budget type's policy, which keeps the
 *    policy unit-testable in the adapter that implements this.
 *
 * inert today by design: T7 constructs its budget with
 * `maxEstimatedTokens = null`, and AgentExecutionBudget's own rule 7 says a
 * disabled token dimension counts nothing and fabricates no number — so every
 * method here is a permissive no-op until the token dimension is enabled.
 * Wiring it now means sub-agents participate the moment upstream F13 turns it
 * on, with no further runner changes.
 */
interface SubagentBudgetGuard {

    /**
     * Pure question, no reservation: non-null when the parent is in NO shape
     * to accept a new child at all (shared deadline expired, or zero token
     * headroom left even after clamping). Checked at spawn time, BEFORE any
     * run row / pill / journal exists — a refusal must leave nothing behind.
     */
    fun spawnBlockReason(): String?

    /**
     * Take a reservation for one child run against the parent's remaining
     * pool. [worstCaseChildTokens] is the child's theoretical ceiling
     * (maxTurns × maxOutputTokens); per contract rule 4 (a child may only get
     * quota from the parent's REMAINDER) implementations clamp the estimate
     * to what is left rather than reject a large ask — over-estimating is
     * corrected at settle time.
     */
    fun reserve(worstCaseChildTokens: Long)

    /**
     * Book the run's ACTUAL spend and release the reservation. Called exactly
     * once from the loop's finally with whatever the provider reported (0/0
     * when it never did, which nets out to a bare release). Over-spend beyond
     * the reservation is booked as far as the parent pool allows and never
     * silently dropped — an over-spending child must still cost the parent,
     * or the ledger reads as a discount.
     */
    fun settle(totalTokensUsed: Long)
}
