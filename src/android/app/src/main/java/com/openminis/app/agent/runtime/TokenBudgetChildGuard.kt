package com.openminis.app.agent.runtime

import com.openminis.app.tools.SubagentBudgetGuard

/**
 * [T-subagent-budget-inheritance] Adapter from the parent loop's
 * [AgentExecutionBudget] down to the child-facing [SubagentBudgetGuard]
 * surface. One instance per SPAWN (the runner calls the factory per spawn),
 * because each child holds its own reservation and settles independently.
 *
 * Contract rules honoured (runtime-contract.md §5):
 *  - rule 4: a child may only receive quota from the parent's REMAINDER, so
 *    a theoretical ceiling larger than what is left is CLAMPED to the
 *    remainder, not rejected — refusing spawns because a model declared a
 *    big maxTurns would be refusing work the pool could easily fund;
 *  - rule 3: an unused reservation is released; consumed counts never roll
 *    back — settle books the ACTUAL spend and returns the unused remainder;
 *  - rule 7: with `maxEstimatedTokens == null` (today's T7 default) the
 *    budget declines to count tokens at all — every method here degenerates
 *    into a no-op ALLOW, so wiring this in changes no current behaviour; it
 *    becomes live the moment the token dimension is enabled.
 *
 * The PARENT's turn / tool-call counters are deliberately unreachable from
 * here: 5 spawns × 48 turns must not be able to cut the parent's own loop
 * off mid-plan by consuming its progress budget. A child moves tokens (and
 * shares the deadline) only.
 */
class TokenBudgetChildGuard(
    private val budget: AgentExecutionBudget,
) : SubagentBudgetGuard {

    /** What THIS spawn reserved; 0 until [reserve] runs. */
    private var reserved = 0L

    /** [settle] runs exactly once — a re-run must not re-book the spend. */
    private var settled = false

    override fun spawnBlockReason(): String? {
        if (budget.isExpired()) return "the parent run's execution deadline has already elapsed"
        val remaining = budget.remaining().estimatedTokensRemaining
        // null = token dimension disabled → never block on it.
        if (remaining != null && remaining <= 0L) {
            return "the parent's token budget is exhausted (0 tokens remain for children)"
        }
        return null
    }

    override fun reserve(worstCaseChildTokens: Long) {
        require(worstCaseChildTokens >= 0L) { "child reservation cannot be negative" }
        val remaining = budget.remaining().estimatedTokensRemaining
        val ask = if (remaining == null) worstCaseChildTokens else minOf(worstCaseChildTokens, remaining)
        if (ask <= 0L) return
        // tryReserveChildBudget is authoritative: it re-checks expiry and the
        // pool under its own lock, so a concurrent sibling that reserved first
        // can legitimately turn this ask down to Denied. Denied ⇒ no
        // reservation ⇒ [settle] is a no-op (reserved stays 0).
        if (budget.tryReserveChildBudget(ask) is BudgetDecision.Allowed) {
            reserved = ask
        }
    }

    override fun settle(totalTokensUsed: Long) {
        if (settled) return
        settled = true
        val actual = totalTokensUsed.coerceAtLeast(0L)
        // A spawn whose reservation was refused (Denied under pressure from
        // siblings) books NOTHING, even if the run then spent tokens. That is
        // a deliberate strategy choice, not an oversight: the alternative is
        // "spend is billed only when it fits", which lets a burst of late
        // runs escape the ledger entirely, or forces a refusal we already
        // declined. Refusal-at-spawn is the enforcement point; once the parent
        // let work start unreserved, its pool view simply under-counts.
        if (reserved <= 0L) return
        // 1) Book within the reservation — consumeChildTokens refuses amounts
        //    ABOVE it (verified by AgentExecutionBudgetTest's child matrix), so
        //    the first pass is strictly capped.
        var outstanding = actual
        if (reserved > 0L) {
            val bookable = minOf(outstanding, reserved)
            if (bookable > 0L && budget.consumeChildTokens(bookable) is BudgetDecision.Allowed) {
                outstanding -= bookable
                reserved -= bookable
            }
        }
        // 2) An under-estimate must still cost the parent: chase the excess
        //    through a FRESH reservation while the pool allows it, one attempt
        //    only (this is settlement, not a negotiation). If the pool cannot
        //    absorb it, the excess is lost by legitimate refusal — never
        //    silently discounted as if the run were free.
        if (outstanding > 0L &&
            budget.tryReserveChildBudget(outstanding) is BudgetDecision.Allowed
        ) {
            if (budget.consumeChildTokens(outstanding) is BudgetDecision.Allowed) {
                outstanding = 0L
            } else {
                // Could not book after all — give the reservation straight back.
                budget.releaseChildBudget(outstanding)
            }
        }
        // 3) Return whatever was reserved but not used.
        if (reserved > 0L) budget.releaseChildBudget(reserved)
        reserved = 0L
    }
}
