package com.openminis.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [fix/subagent-verdict-consistency-v3] The scheduler's TWO-LEVEL acquire
 * (skill permit, then global permit) used to give each level the FULL
 * waitBudgetMs, so a spawn could wait 2x the budget the caller was promised.
 *
 * The old test for this was NOT sensitive: it never occupied the SKILL
 * permit, so the skill level failed instantly and the global level never
 * ran — the 2x path was never exercised. This test builds the exact
 * interleaving that exposes it:
 *
 *   t=0      M holds the only GLOBAL permit AND the target's SKILL permit.
 *   t=0.2s   W parks on the GLOBAL permit (its own skill permit is free,
 *            so it goes straight to the global wait).
 *   t=0.7s   M releases. `release()` frees the GLOBAL permit FIRST, so W —
 *            already queued — takes it, and the global level stays busy.
 *            Then M's SKILL permit is freed, so the target's skill acquire
 *            SUCCEEDS at t=0.7s.
 *   t=?      The target now needs the GLOBAL permit, which is busy until
 *            the end of the test.
 *
 * OLD code: the global level was handed a fresh, full waitBudgetMs, so the
 * target waited 0.7s + 1.0s = 1.7s.
 * NEW code: the two levels share ONE deadline, so the target gives up at
 * 1.0s (0.7s for the skill + the 0.3s that were left).
 *
 * The 600ms gap between the two behaviours is what makes this sensitive;
 * the old test's gap was zero.
 */
class SubagentSchedulerBudgetTotalTest {

    @Test
    fun `two level acquire does not exceed the total wait budget`() = runBlocking {
        val scheduler = SubagentScheduler(globalLimit = 1)
        val budgetMs = 1_000L
        val skillReleaseAtMs = 700L

        // M holds the only global permit and the target skill's permit.
        assertTrue(
            "the setup must take both permits",
            scheduler.tryAcquire("skillM", 1, waitBudgetMs = 0L),
        )

        // W parks on the global permit and will absorb the permit M frees.
        val w = launch(Dispatchers.Default) {
            scheduler.tryAcquire("skillW", 1, waitBudgetMs = 60_000L)
        }
        delay(250) // let W reach the global wait

        // Free M's permits mid-budget. release() frees the GLOBAL permit
        // first, so the queued W takes it and the global level stays busy.
        launch(Dispatchers.Default) {
            delay(skillReleaseAtMs)
            scheduler.release("skillM", 1)
        }

        val start = System.nanoTime()
        val got = scheduler.tryAcquire("skillM", 1, waitBudgetMs = budgetMs)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        // The global permit is never free, so the target must give up.
        assertFalse("tryAcquire must give up, not hang", got)

        // The skill permit was released when the global level failed.
        assertEquals(
            "a failed acquire must not leak the skill permit",
            1,
            scheduler.availableSkillPermits("skillM"),
        )

        // NEW: ~1000ms (the whole budget). OLD: ~1700ms (budget waited
        // twice). The threshold sits between them.
        assertTrue(
            "two-level acquire must respect the TOTAL budget, took ${elapsedMs}ms " +
                "(budget=${budgetMs}ms)",
            elapsedMs < 1_350L,
        )

        w.cancel()
    }

    private fun assertTrue(message: String, value: Boolean) =
        org.junit.Assert.assertTrue(message, value)
}
