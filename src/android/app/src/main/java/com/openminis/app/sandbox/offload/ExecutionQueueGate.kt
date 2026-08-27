package com.openminis.app.sandbox.offload

/**
 * [head-of-line-blocking fix 2026-08-27] Cancel-aware, bounded wait for the
 * `:modelservice` global execution mutex (ModelExecutionService).
 *
 * Root cause this closes: the worker serializes ALL provider work behind one
 * mutex (TF-F P0-C, correct per native-heap containment). The old
 * `executionMutex.withLock` waited unconditionally and un-cancellably:
 *
 *   1. a request whose client already gave up (the dispatcher writes `cancel`
 *      on its 3-min result timeout, the streaming client in its finally)
 *      still executed IN FULL once the head released the mutex — burning head
 *      time nobody wanted and re-running a request the client had already
 *      re-dispatched in-process;
 *   2. nothing bounded the wait. Behind a wedged/slow head (a 30-minute
 *      generation, or a non-streaming socket that trickles bytes — each OkHttp
 *      read resets its readTimeout) every subsequent request, streaming AND
 *      non-streaming, queued forever. Users see "all new LLM requests
 *      unresponsive"; restarting the app kills the `:modelservice` process —
 *      the ONLY thing that frees the mutex — so restart "fixes" it instantly
 *      while other apps stay fine (the network itself is healthy).
 *
 * Decision loop (pure JVM; clock/sleep/lock injected so the semantics are
 * unit-testable without an Android runtime):
 *   - CANCELLED is checked BEFORE acquisition: a dead client must never
 *     consume serialization head time.
 *   - ACQUIRED: the caller holds the lock and MUST unlock it in a `finally`.
 *   - TIMEOUT: the bounded queue wait expired — the caller must write a
 *     definitive error result (client stops polling) instead of executing.
 */
object ExecutionQueueGate {

    enum class Result { ACQUIRED, CANCELLED, TIMEOUT }

    /**
     * Waits for the execution mutex.
     *
     * @param timeoutMs absolute bound on the queue wait. Must exceed the
     *   longest LEGITIMATE single mutex hold (a full generation stream,
     *   GENERATION_TIMEOUT_SEC = 30 min) plus margin, so a healthy queued
     *   request is never failed while the head is still legitimately working.
     * @param isCancelled returns true when the client cancelled this run
     *   (marker file present). Polled before every lock attempt.
     * @param tryLock non-suspending lock attempt (Mutex.tryLock).
     * @param pollMs sleep between attempts.
     * @param nowMs injectable clock (tests).
     * @param sleep injectable sleeper (tests; production swallows
     *   InterruptedException and keeps polling — the loop is bounded by the
     *   clock, not the sleep).
     */
    fun await(
        timeoutMs: Long,
        isCancelled: () -> Boolean,
        tryLock: () -> Boolean,
        pollMs: Long = 200L,
        nowMs: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Result {
        val deadline = nowMs() + timeoutMs
        while (true) {
            if (isCancelled()) return Result.CANCELLED
            if (tryLock()) return Result.ACQUIRED
            if (nowMs() >= deadline) return Result.TIMEOUT
            sleep(pollMs)
        }
    }
}
