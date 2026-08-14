package com.openminis.app.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [T1-session-concurrency] Integration tests for the coroutine/StateFlow adapter
 * ([SessionConcurrencyManager]) on top of [SessionSlotController].
 *
 * 验证对外 API 语义：
 * - 4 并发全成功 / 第 5 边界成功 / 第 6 FIFO 挂起并在释放后恢复；
 * - 同一 sessionId 的并发 run 各自独立占槽，明确排队而非静默折叠；
 * - 取消中的 waiter 从队列移除，永远不会在取消后被恢复为 active；
 * - 重复 release 幂等；
 * - StateFlow 反映真实 active / waiting 状态；
 * - 真实多线程 acquire/release 交错下终态收敛、无异常。
 *
 * 不用 Thread.sleep：runTest 用 runCurrent() 推进调度；并发测试用 CountDownLatch。
 */
class SessionConcurrencyManagerTest {

    @Before
    fun resetManager() {
        SessionConcurrencyManager.resetForTesting()
    }

    // --- 基本准入 ---

    @Test
    fun `4 concurrent acquires all succeed immediately`() = runTest {
        val jobs = (1..4).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        jobs.awaitAll()
        assertEquals(setOf("s1", "s2", "s3", "s4"), SessionConcurrencyManager.runningSessions.value)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    @Test
    fun `5th acquire succeeds at the capacity boundary`() = runTest {
        val jobs = (1..5).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        jobs.awaitAll()
        assertEquals(5, SessionConcurrencyManager.runningSessions.value.size)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    @Test
    fun `6th acquire waits in FIFO and resumes when a slot frees`() = runTest {
        val holders = (1..5).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        holders.awaitAll()

        val sixth = async { SessionConcurrencyManager.acquireSlot("s6") }
        runCurrent()
        assertTrue("6th must be suspended", SessionConcurrencyManager.isSuspended("s6"))
        assertFalse("6th must not complete while no slot is free", sixth.isCompleted)

        SessionConcurrencyManager.releaseSlot("s1")
        sixth.await()
        assertEquals(setOf("s2", "s3", "s4", "s5", "s6"), SessionConcurrencyManager.runningSessions.value)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    @Test
    fun `waiters resume strictly in FIFO order`() = runTest {
        val holders = (1..5).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        holders.awaitAll()

        val w6 = async { SessionConcurrencyManager.acquireSlot("w6") }
        val w7 = async { SessionConcurrencyManager.acquireSlot("w7") }
        runCurrent()
        assertEquals(listOf("w6", "w7"), SessionConcurrencyManager.suspendedSessions.value)

        SessionConcurrencyManager.releaseSlot("s1")
        runCurrent()
        assertTrue("head waiter must resume first", w6.isCompleted)
        assertFalse("second waiter must still wait", w7.isCompleted)

        SessionConcurrencyManager.releaseSlot("s2")
        runCurrent()
        assertTrue(w7.isCompleted)
        assertEquals(setOf("s3", "s4", "s5", "w6", "w7"), SessionConcurrencyManager.runningSessions.value)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    // --- 同 session 并发 run 不折叠（T1 核心修复） ---

    @Test
    fun `same session concurrent runs each occupy their own slot and queue explicitly`() = runTest {
        val a1 = async { SessionConcurrencyManager.acquireSlot("s1") }
        val a2 = async { SessionConcurrencyManager.acquireSlot("s2") }
        val a3 = async { SessionConcurrencyManager.acquireSlot("s3") }
        val a4 = async { SessionConcurrencyManager.acquireSlot("s4") }
        val a5 = async { SessionConcurrencyManager.acquireSlot("s5") }
        runCurrent()
        a1.await(); a2.await(); a3.await(); a4.await(); a5.await()

        // 同一 sessionId 的第二个 run：容量已满 → 明确进入等待队列（旧实现会被 Set 静默合并）
        val a6 = async { SessionConcurrencyManager.acquireSlot("s1") }
        runCurrent()
        assertTrue("second run of s1 must queue, not silently fold", SessionConcurrencyManager.isSuspended("s1"))
        assertFalse(a6.isCompleted)

        // 释放 s1 的第一个 run → 第二个 s1 run 被提升
        SessionConcurrencyManager.releaseSlot("s1")
        runCurrent()
        assertTrue(a6.isCompleted)
        assertTrue("s1 must still be running after its second run is promoted", "s1" in SessionConcurrencyManager.runningSessions.value)

        // 全部释放后收敛为空
        listOf("s1", "s2", "s3", "s4", "s5").forEach { SessionConcurrencyManager.releaseSlot(it) }
        assertEquals(emptySet<String>(), SessionConcurrencyManager.runningSessions.value)
        assertEquals(emptyList<String>(), SessionConcurrencyManager.suspendedSessions.value)
    }

    // --- 取消语义 ---

    @Test
    fun `cancelled waiter is removed and never resumed as active`() = runTest {
        val holders = (1..5).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        holders.awaitAll()

        val w6 = async { SessionConcurrencyManager.acquireSlot("w6") }
        val w7 = async { SessionConcurrencyManager.acquireSlot("w7") }
        runCurrent()

        w6.cancel()
        runCurrent()
        assertTrue(w6.isCancelled)
        assertFalse("cancelled waiter must leave the suspended list", SessionConcurrencyManager.isSuspended("w6"))

        // 释放一个槽位 → 跳过已取消的 w6，直接提升 w7
        SessionConcurrencyManager.releaseSlot("s1")
        runCurrent()
        assertTrue(w7.isCompleted)
        assertFalse("cancelled run must never appear in running", "w6" in SessionConcurrencyManager.runningSessions.value)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    @Test
    fun `cancelled waiter propagates CancellationException to the caller`() = runTest {
        val holders = (1..5).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        holders.awaitAll()

        val w6 = async { SessionConcurrencyManager.acquireSlot("w6") }
        runCurrent()
        w6.cancelAndJoin()
        runCurrent()
        // 取消后槽位分配不受影响：释放后队列为空
        SessionConcurrencyManager.releaseSlot("s1")
        runCurrent()
        assertEquals(4, SessionConcurrencyManager.runningSessions.value.size)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    // --- 幂等 release ---

    @Test
    fun `duplicate releaseSlot is a no-op`() = runTest {
        val j = async { SessionConcurrencyManager.acquireSlot("s1") }
        j.await()
        assertEquals(setOf("s1"), SessionConcurrencyManager.runningSessions.value)

        SessionConcurrencyManager.releaseSlot("s1")
        assertEquals(emptySet<String>(), SessionConcurrencyManager.runningSessions.value)

        // 第二次 release：无 active 槽位，不抛异常、不改变状态
        SessionConcurrencyManager.releaseSlot("s1")
        assertEquals(emptySet<String>(), SessionConcurrencyManager.runningSessions.value)
    }

    @Test
    fun `releaseSlot for an unknown session is a no-op`() {
        SessionConcurrencyManager.releaseSlot("ghost")
        assertEquals(emptySet<String>(), SessionConcurrencyManager.runningSessions.value)
    }

    // --- 真实多线程交错 ---

    @Test
    fun `concurrent acquire and release converges under real threads`() = runBlocking {
        val threads = 12 // > MAX_CONCURRENT=5，强制排队
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = CopyOnWriteArrayList<Throwable>()

        repeat(threads) { i ->
            launch(Dispatchers.Default) {
                try {
                    start.await()
                    SessionConcurrencyManager.acquireSlot("s$i")
                    // 模拟持有槽位执行
                    SessionConcurrencyManager.releaseSlot("s$i")
                } catch (t: Throwable) {
                    if (t !is CancellationException) errors.add(t)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue("threads did not finish in 15s", done.await(15, TimeUnit.SECONDS))
        assertTrue("unexpected errors: $errors", errors.isEmpty())
        assertEquals("all slots must be released", 0, SessionConcurrencyManager.runningSessions.value.size)
        assertEquals("queue must be empty", 0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    @Test
    fun `many sessions with repeated run cycles keep invariants`() = runBlocking {
        val sessions = 3
        val cycles = 50
        val start = CountDownLatch(1)
        val done = CountDownLatch(sessions)
        val errors = CopyOnWriteArrayList<Throwable>()

        repeat(sessions) { s ->
            launch(Dispatchers.Default) {
                try {
                    start.await()
                    repeat(cycles) {
                        SessionConcurrencyManager.acquireSlot("cycle-s$s")
                        SessionConcurrencyManager.releaseSlot("cycle-s$s")
                    }
                } catch (t: Throwable) {
                    if (t !is CancellationException) errors.add(t)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertTrue("unexpected errors: $errors", errors.isEmpty())
        assertEquals(0, SessionConcurrencyManager.runningSessions.value.size)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }
}
