package com.openminis.app.agent.shell

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class OnDemandBashTest {

    private lateinit var mockContext: Context
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private val executorMock = mockk<OnDemandBash.Executor>()

    @BeforeEach
    fun setUp() {
        mockContext = mockk()
        mockSharedPreferences = mockk()
        mockEditor = mockk(relaxed = true)
        every { mockContext.getSharedPreferences(any(), any()) } returns mockSharedPreferences
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.putLong(any(), any()) } returns mockEditor
        every { mockEditor.remove(any()) } returns mockEditor
        // mock network reachable by default (true)
        mockkStatic("com.openminis.app.agent.shell.OnDemandBashKt")
        every { OnDemandBash.networkReachable() } returns true
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
        // reset object state
        OnDemandBash.markDisappeared()
    }

    @Test
    fun `ensureBash returns Available when bash is already available`() = runTest {
        // first call makes bash available
        coEvery { executorMock.run("command -v bash >/dev/null 2>&1", 15_000) } returns 0
        val outcome = OnDemandBash.ensureBash(mockContext, executorMock)
        assertEquals(OnDemandBash.Outcome.Available, outcome)
    }

    @Test
    fun `ensureBash returns Available after successful install`() = runTest {
        // bash not present initially
        coEvery { executorMock.run("command -v bash >/dev/null 2>&1", 15_000) } returns 1
            andThen 0 // second call after install succeeds
        coEvery { executorMock.run("apk add bash", 60_000) } returns 0
        // no backoff
        every { mockSharedPreferences.getInt(any(), any()) } returns 0
        every { mockSharedPreferences.getLong(any(), any()) } returns 0L

        val outcome = OnDemandBash.ensureBash(mockContext, executorMock)
        assertEquals(OnDemandBash.Outcome.Available, outcome)
    }

    @Test
    fun `ensureBash returns Unavailable due to backoff`() = runTest {
        coEvery { executorMock.run("command -v bash >/dev/null 2>&1", 15_000) } returns 1
        // simulate backoff: failCount >= MAX_STRIKES
        every { mockSharedPreferences.getInt(any(), any()) } returns 3
        every { mockSharedPreferences.getLong(any(), any()) } returns 0L

        val outcome = OnDemandBash.ensureBash(mockContext, executorMock)
        assertTrue(outcome is OnDemandBash.Outcome.Unavailable)
        assertEquals("bash install disabled after 3 failures (retry manually: apk add bash)", 
                     (outcome as OnDemandBash.Outcome.Unavailable).reason)
    }

    @Test
    fun `ensureBash returns Unavailable when network is unreachable`() = runTest {
        coEvery { executorMock.run("command -v bash >/dev/null 2>&1", 15_000) } returns 1
        every { mockSharedPreferences.getInt(any(), any()) } returns 0
        every { mockSharedPreferences.getLong(any(), any()) } returns 0L
        // make network unreachable
        every { OnDemandBash.networkReachable() } returns false

        val outcome = OnDemandBash.ensureBash(mockContext, executorMock)
        assertTrue(outcome is OnDemandBash.Outcome.Unavailable)
        assertEquals("network/apk mirror unreachable", 
                     (outcome as OnDemandBash.Outcome.Unavailable).reason)
    }

    @Test
    fun `ensureBash returns Unavailable when install fails`() = runTest {
        coEvery { executorMock.run("command -v bash >/dev/null 2>&1", 15_000) } returns 1
        coEvery { executorMock.run("apk add bash", 60_000) } returns 1 // install fails
        every { mockSharedPreferences.getInt(any(), any()) } returns 0
        every { mockSharedPreferences.getLong(any(), any()) } returns 0L

        val outcome = OnDemandBash.ensureBash(mockContext, executorMock)
        assertTrue(outcome is OnDemandBash.Outcome.Unavailable)
        assertTrue((outcome as OnDemandBash.Outcome.Unavailable).reason.startsWith("apk add bash failed"))
    }

    @Test
    fun `ensureBash respects already attempted install in same launch`() = runTest {
        // first call triggers install
        coEvery { executorMock.run("command -v bash >/dev/null 2>&1", 15_000) } returns 1
            andThen 0
        coEvery { executorMock.run("apk add bash", 60_000) } returns 0
        every { mockSharedPreferences.getInt(any(), any()) } returns 0
        every { mockSharedPreferences.getLong(any(), any()) } returns 0L

        val firstOutcome = OnDemandBash.ensureBash(mockContext, executorMock)
        assertEquals(OnDemandBash.Outcome.Available, firstOutcome)

        // second call – bash is now available, so should return Available again
        // but we need to simulate that availability is already set to Available
        // the mock for command -v should return 0
        coEvery { executorMock.run("command -v bash >/dev/null 2>&1", 15_000) } returns 0
        val secondOutcome = OnDemandBash.ensureBash(mockContext, executorMock)
        assertEquals(OnDemandBash.Outcome.Available, secondOutcome)
    }

    @Test
    fun `markDisappeared resets availability to Unknown`() = runTest {
        // first make bash available
        coEvery { executorMock.run("command -v bash >/dev/null 2>&1", 15_000) } returns 0
        OnDemandBash.ensureBash(mockContext, executorMock)
        // then mark as disappeared
        OnDemandBash.markDisappeared()
        // now the next call should check again (availability is Unknown)
        coEvery { executorMock.run("command -v bash >/dev/null 2>&1", 15_000) } returns 1
        coEvery { executorMock.run("apk add bash", 60_000) } returns 0
        every { mockSharedPreferences.getInt(any(), any()) } returns 0
        every { mockSharedPreferences.getLong(any(), any()) } returns 0L

        val outcome = OnDemandBash.ensureBash(mockContext, executorMock)
        assertEquals(OnDemandBash.Outcome.Available, outcome)
    }
}