package com.openminis.app.config.confirm

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigConfirmationGateTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() = runBlocking(testDispatcher) {
        while (ConfigConfirmationGate.pending.value != null) {
            ConfigConfirmationGate.userReject()
            testDispatcher.scheduler.advanceUntilIdle()
        }
        ConfigConfirmationGate.backgroundNotifier = null
        ConfigConfirmationGate.cancelNotification = null
        Dispatchers.resetMain()
    }

    private fun mockChange(id: String): PendingConfigChange {
        val mock = mockk<PendingConfigChange>()
        every { mock.id } returns id
        return mock
    }

    @Test
    fun `requestConfirmation sets pending and returns Approved on userApprove`() = runTest(testDispatcher) {
        val change = mockChange("1")
        val items = listOf(mockk<PendingConfigChangeItem>())
        val deferred = async { ConfigConfirmationGate.requestConfirmation(change) }
        advanceUntilIdle()

        assertEquals(change, ConfigConfirmationGate.pending.value)
        ConfigConfirmationGate.userApprove(items)
        advanceUntilIdle()

        val outcome = deferred.await()
        assertTrue(outcome is ConfirmOutcome.Approved)
        assertEquals(items, outcome.items)
        assertNull(ConfigConfirmationGate.pending.value)
    }

    @Test
    fun `requestConfirmation sets pending and returns Rejected on userReject`() = runTest(testDispatcher) {
        val change = mockChange("2")
        val deferred = async { ConfigConfirmationGate.requestConfirmation(change) }
        advanceUntilIdle()

        assertEquals(change, ConfigConfirmationGate.pending.value)
        ConfigConfirmationGate.userReject()
        advanceUntilIdle()

        val outcome = deferred.await()
        assertTrue(outcome is ConfirmOutcome.Rejected)
        assertNull(ConfigConfirmationGate.pending.value)
    }

    @Test
    fun `requestConfirmation queues subsequent requests`() = runTest(testDispatcher) {
        val change1 = mockChange("3")
        val change2 = mockChange("4")
        val deferred1 = async { ConfigConfirmationGate.requestConfirmation(change1) }
        val deferred2 = async { ConfigConfirmationGate.requestConfirmation(change2) }
        advanceUntilIdle()

        assertEquals(change1, ConfigConfirmationGate.pending.value)
        ConfigConfirmationGate.userReject()
        advanceUntilIdle()

        val outcome1 = deferred1.await()
        assertTrue(outcome1 is ConfirmOutcome.Rejected)
        assertEquals(change2, ConfigConfirmationGate.pending.value)

        ConfigConfirmationGate.userReject()
        advanceUntilIdle()

        val outcome2 = deferred2.await()
        assertTrue(outcome2 is ConfirmOutcome.Rejected)
        assertNull(ConfigConfirmationGate.pending.value)
    }

    @Test
    fun `requestConfirmation times out after TIMEOUT_MS`() = runTest(testDispatcher) {
        val change = mockChange("5")
        val deferred = async { ConfigConfirmationGate.requestConfirmation(change) }
        advanceUntilIdle()

        assertEquals(change, ConfigConfirmationGate.pending.value)
        advanceTimeBy(ConfigConfirmationGate.TIMEOUT_MS + 1000)
        advanceUntilIdle()

        val outcome = deferred.await()
        assertTrue(outcome is ConfirmOutcome.TimedOut)
        assertNull(ConfigConfirmationGate.pending.value)
    }

    @Test
    fun `backgroundNotifier is called when pending is set`() = runTest(testDispatcher) {
        val change = mockChange("6")
        var notified = false
        ConfigConfirmationGate.backgroundNotifier = {
            notified = true
            true
        }

        val deferred = async { ConfigConfirmationGate.requestConfirmation(change) }
        advanceUntilIdle()

        assertTrue(notified)
        ConfigConfirmationGate.userReject()
        advanceUntilIdle()
        deferred.await()
    }

    @Test
    fun `cancelNotification is called on resolve`() = runTest(testDispatcher) {
        val change = mockChange("7")
        var cancelledId: String? = null
        ConfigConfirmationGate.cancelNotification = {
            cancelledId = it
        }

        val deferred = async { ConfigConfirmationGate.requestConfirmation(change) }
        advanceUntilIdle()

        ConfigConfirmationGate.userReject()
        advanceUntilIdle()

        assertEquals("7", cancelledId)
        deferred.await()
    }

    @Test
    fun `notifyPending calls backgroundNotifier if not already notified`() = runTest(testDispatcher) {
        val change = mockChange("8")
        var notified = false
        ConfigConfirmationGate.backgroundNotifier = {
            notified = true
            true
        }

        val deferred = async { ConfigConfirmationGate.requestConfirmation(change) }
        advanceUntilIdle()

        assertTrue(notified)
        notified = false

        ConfigConfirmationGate.notifyPending()
        advanceUntilIdle()

        // Should not notify again because already in notifiedIds
        assertEquals(false, notified)

        ConfigConfirmationGate.userReject()
        advanceUntilIdle()
        deferred.await()
    }
}