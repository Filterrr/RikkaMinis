package com.openminis.app.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-subagent-budget-inheritance] F13 wiring tests for the parent→child token
 * budget adapter.
 *
 * The behaviour that matters is the LEDGER, not the refusal: under today's T7
 * default (maxEstimatedTokens = null) nothing ever blocks, and that is the
 * point — wiring inheritance in must not change a single current behaviour.
 * These tests pin both halves: disabled-token budgets stay inert, and once
 * enabled, a child's ACTUAL spend (never an over-estimate) lands in the
 * parent's used counter while unused reservation returns to the pool.
 */
class TokenBudgetChildGuardTest {

    private fun budget(
        maxEstimatedTokens: Long? = null,
        deadline: Long = Long.MAX_VALUE / 2,
        startedAt: Long = 0L,
        clock: () -> Long = { 0L },
    ) = AgentExecutionBudget(
        startedAtMonotonicMs = startedAt,
        deadlineMonotonicMs = deadline,
        maxTurns = 100,
        maxProviderAttempts = 100,
        maxToolCalls = 100,
        maxShellCommands = 100,
        maxCompactionCalls = 100,
        maxConcurrentTools = 5,
        maxEstimatedTokens = maxEstimatedTokens,
        monotonicClock = clock,
    )

    @Test
    fun `disabled token budget is fully inert`() {
        val b = budget(maxEstimatedTokens = null)
        val g = TokenBudgetChildGuard(b)
        assertNull(g.spawnBlockReason())
        g.reserve(1_000_000L)
        g.settle(5_000L)
        val snap = b.snapshot()
        assertEquals("no counting when disabled", null, snap.estimatedTokensUsed)
        assertEquals("no phantom reservation", 0L, snap.reservedChildTokens)
    }

    @Test
    fun `reservation is clamped to the parent remainder, not rejected`() {
        val b = budget(maxEstimatedTokens = 10_000L)
        val g = TokenBudgetChildGuard(b)
        // Contract rule 4: a child only gets quota from the REMAINDER — an
        // absurd ask must clamp, because refusing spawns over declared ceilings
        // would block work the pool could fund.
        g.reserve(999_999L)
        assertEquals(10_000L, b.snapshot().reservedChildTokens)
        assertEquals(0L, b.snapshot().estimatedTokensUsed!!)
    }

    @Test
    fun `settle books actual spend and returns the unused remainder`() {
        val b = budget(maxEstimatedTokens = 10_000L)
        val g = TokenBudgetChildGuard(b)
        g.reserve(8_000L)
        g.settle(3_000L)
        val snap = b.snapshot()
        assertEquals(3_000L, snap.estimatedTokensUsed)
        assertEquals(0L, snap.reservedChildTokens)
        // The pool absorbed exactly the 3_000 the child really spent; the
        // unused 5_000 of reservation went back for siblings — remaining is
        // cap(10_000) - used(3_000) - reserved(0).
        assertEquals(7_000L, b.remaining().estimatedTokensRemaining)
    }

    @Test
    fun `settle with zero spend is a bare release`() {
        val b = budget(maxEstimatedTokens = 10_000L)
        val g = TokenBudgetChildGuard(b)
        g.reserve(4_000L)
        g.settle(0L)
        val snap = b.snapshot()
        assertEquals(0L, snap.estimatedTokensUsed)
        assertEquals(0L, snap.reservedChildTokens)
    }

    @Test
    fun `over-spend beyond the reservation is not silently dropped`() {
        val b = budget(maxEstimatedTokens = 10_000L)
        val g = TokenBudgetChildGuard(b)
        g.reserve(2_000L)
        // Provider reported more than reserved (under-estimate): the parent
        // ledger must absorb it while the pool allows.
        g.settle(6_000L)
        val snap = b.snapshot()
        assertEquals(6_000L, snap.estimatedTokensUsed)
        assertEquals(0L, snap.reservedChildTokens)
    }

    @Test
    fun `two children share the pool without double-spending`() {
        val b = budget(maxEstimatedTokens = 10_000L)
        val g1 = TokenBudgetChildGuard(b)
        val g2 = TokenBudgetChildGuard(b)
        g1.reserve(8_000L)
        // g2 arrives after the pool committed 8_000 to g1 — it may only claim
        // the 2_000 remainder.
        g2.reserve(8_000L)
        assertEquals(10_000L, b.snapshot().reservedChildTokens)
        g1.settle(1_000L)
        g2.settle(1_000L)
        val snap = b.snapshot()
        assertEquals(2_000L, snap.estimatedTokensUsed)
        assertEquals(0L, snap.reservedChildTokens)
    }

    @Test
    fun `exhausted pool blocks further spawns with a reason`() {
        val b = budget(maxEstimatedTokens = 10_000L)
        repeat(10) {
            val g = TokenBudgetChildGuard(b)
            g.reserve(1_000L)
            g.settle(1_000L)
        }
        val g = TokenBudgetChildGuard(b)
        assertNotNull("pool is empty — the spawn must be refused", g.spawnBlockReason())
    }

    @Test
    fun `expired parent deadline blocks spawning even with tokens left`() {
        val b = budget(maxEstimatedTokens = 10_000L, startedAt = 0L, deadline = 500L, clock = { 600L })
        val g = TokenBudgetChildGuard(b)
        assertNotNull(g.spawnBlockReason())
    }

    @Test
    fun `double settle is idempotent`() {
        val b = budget(maxEstimatedTokens = 10_000L)
        val g = TokenBudgetChildGuard(b)
        g.reserve(2_000L)
        g.settle(2_000L)
        g.settle(2_000L)
        assertEquals(2_000L, b.snapshot().estimatedTokensUsed)
    }

    @Test
    fun `a child whose reservation was refused books nothing`() {
        val b = budget(maxEstimatedTokens = 5_000L)
        val holder = TokenBudgetChildGuard(b)
        holder.reserve(5_000L)
        val late = TokenBudgetChildGuard(b)
        // Pool already fully reserved → the late reserve() is Denied silently.
        late.reserve(1_000L)
        late.settle(900L)
        assertEquals(0L, b.snapshot().estimatedTokensUsed)
        assertEquals("holder's reservation survives", 5_000L, b.snapshot().reservedChildTokens)
    }
}
