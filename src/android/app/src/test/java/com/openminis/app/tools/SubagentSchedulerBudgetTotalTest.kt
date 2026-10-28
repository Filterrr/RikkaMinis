package com.openminis.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 *   t=0.7s   M releases. The release frees the GLOBAL permit FIRST, so W —
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
 *
 * ## Rewritten for the post-merge API
 *
 * [fix/scheduler-test-api-after-merge] This test was written against the
 * scheduler's OLD public surface — `tryAcquire(skillId, limit,
 * waitBudgetMs)` and `release(skillId, limit)` — which exposed the
 * two-level acquire directly. PR #81 closed that surface: the acquire is
 * now private (`tryAcquirePair` / `releasePair`) and the only entry point
 * is `run(skillId, skillLimit, deadlineNanos, maxQueueWaitMs) { ... }`.
 *
 * The two PRs did not conflict in git — neither touched the other's lines
 * — so both merged, and the test was left calling an API that no longer
 * existed. main did not compile.
 *
 * The behaviour under test is unchanged and still reachable through
 * `run`: the shared deadline lives in `tryAcquirePair`, which `run` calls
 * with a single `waitMs` derived from ONE deadline. So this test now
 * drives the same interleaving through `run`, and still fails if the two
 * levels ever start budgeting separately again.
 *
 * The mechanism is the same too: `run`'s `finally` calls `releasePair`,
 * which frees the GLOBAL permit first and the skill permit second — the
 * ordering the interleaving depends on.
 */
class SubagentSchedulerBudgetTotalTest {

    @Test
    fun `two level acquire does not exceed the total wait budget`() = runBlocking {
        val scheduler = SubagentScheduler(globalLimit = 1)
        val budgetMs = 1_000L
        val skillReleaseAtMs = 700L

        // M holds the only global permit AND the target skill's permit.
        // Its block is the "work", and its END is the release: `run`
        // frees the global permit first, then the skill permit.
        val m = launch(Dispatchers.Default) {
            scheduler.run("skillM", 1) {
                delay(skillReleaseAtMs)
            }
        }

        // W parks on the global permit and will absorb the permit M frees.
        // Its own skill permit is free, so it goes straight to the global
        // wait — exactly where the target will end up behind it.
        val w = launch(Dispatchers.Default) {
            try {
                scheduler.run("skillW", 1, maxQueueWaitMs = 60_000L) {
                    delay(60_000L)
                }
            } catch (_: SubagentScheduler.QueueTimeoutException) {
                // cancelled below
            }
        }
        delay(250) // let W reach the global wait

        val start = System.nanoTime()
        var gaveUp = false
        try {
            scheduler.run("skillM", 1, maxQueueWaitMs = budgetMs) {
                // must not be reached
            }
        } catch (_: SubagentScheduler.QueueTimeoutException) {
            gaveUp = true
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        // The global permit is never free, so the target must give up.
        assertTrue("the target must give up, not hang", gaveUp)

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
        m.cancel()
    }
}
