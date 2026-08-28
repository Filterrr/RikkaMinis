package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * TEST-1 / [atomic-counter]: stress test for [ChatStreamOffloadHandler.activeStreams].
 *
 * Replicates the handler's EXACT pairing shape — increment; staging block that
 * may throw (decrement in its catch); work block with decrement in its finally
 * — under 100 concurrent "streams" x many rounds, each stream randomly failing
 * in one of the modes the production flow can fail in (staging failure /
 * cancellation mid-work / provider timeout / normal completion).
 *
 * Invariants asserted:
 *  - after every round the counter is EXACTLY 0;
 *  - the counter NEVER goes negative;
 *  - the counter NEVER exceeds the round's stream count.
 *
 * Regression tripwire: with a `@Volatile var` + `++`/`--` (lost updates under
 * contention) the "exactly 0" and "never negative" invariants break with high
 * probability across this many rounds; with the AtomicInteger they hold by
 * construction.
 */
class ActiveStreamsStressTest {

    @Test
    fun `counter is exact under 100 concurrent streams with random failure modes`() {
        val rounds = 60
        val streamsPerRound = 100
        val pool = Executors.newFixedThreadPool(64)
        try {
            for (round in 0 until rounds) {
                val start = CountDownLatch(1)
                val done = CountDownLatch(streamsPerRound)
                val negativeSeen = AtomicLong(0)

                repeat(streamsPerRound) { streamIdx ->
                    pool.submit(Runnable {
                        start.await()
                        try {
                            // ── mirror of ChatStreamOffloadHandler.stream() ──
                            ChatStreamOffloadHandler.activeStreams.incrementAndGet()
                            try {
                                // staging block: create-dir failure mode
                                if (streamIdx % 7 == 0) throw IllegalStateException("cannot create run dir")
                            } catch (e: Exception) {
                                ChatStreamOffloadHandler.activeStreams.decrementAndGet()
                                throw RuntimeException("stream staging failed", e)
                            }
                            try {
                                // work block: cancel / provider timeout / normal
                                when (streamIdx % 5) {
                                    1 -> throw RuntimeException("provider timeout")
                                    2 -> throw kotlinx.coroutines.CancellationException("external cancel")
                                    // 0 / 3 / 4: normal completion
                                }
                            } finally {
                                ChatStreamOffloadHandler.activeStreams.decrementAndGet()
                            }
                        } catch (_: Throwable) {
                            // the flow machinery funnels every failure through
                            // the same finally paths — nothing else to do here
                        } finally {
                            done.countDown()
                        }
                    })
                }

                // Sampler: while streams churn, the counter must stay in
                // [0, streamsPerRound] at every observation.
                start.countDown()
                while (done.count > 0) {
                    val sampled = ChatStreamOffloadHandler.activeStreams.get()
                    if (sampled < 0) negativeSeen.incrementAndGet()
                    assertTrue(
                        "round $round: activeStreams=$sampled exceeded stream count",
                        sampled <= streamsPerRound,
                    )
                    // give the pool a chance to make progress
                    Thread.sleep(1)
                }
                assertEquals("round $round: counter must drain to exactly 0", 0, ChatStreamOffloadHandler.activeStreams.get())
                assertEquals("round $round: counter must never go negative", 0, negativeSeen.get())
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
        assertEquals(0, ChatStreamOffloadHandler.activeStreams.get())
    }

    @Test
    fun `counter never drifts when staging always fails`() {
        val pool = Executors.newFixedThreadPool(32)
        try {
            val futures = (0 until 500).map {
                pool.submit<Void> {
                    ChatStreamOffloadHandler.activeStreams.incrementAndGet()
                    try {
                        throw IllegalStateException("cannot create run dir")
                    } catch (_: Exception) {
                        ChatStreamOffloadHandler.activeStreams.decrementAndGet()
                    }
                    null
                }
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(0, ChatStreamOffloadHandler.activeStreams.get())
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }
}
