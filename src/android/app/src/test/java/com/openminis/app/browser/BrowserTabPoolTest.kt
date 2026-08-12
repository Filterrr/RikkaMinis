package com.openminis.app.browser

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class BrowserTabPoolTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var pool: BrowserTabPool

    @BeforeEach
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        pool = BrowserTabPool(context)
    }

    @Test
    fun `middleTruncated - short name returns unchanged`() {
        val name = "Short"
        assertEquals(name, BrowserTabPool.middleTruncated(name))
    }

    @Test
    fun `middleTruncated - long name is truncated with ellipsis`() {
        val name = "ThisIsAVeryLongFileNameThatNeedsTruncation"
        val result = BrowserTabPool.middleTruncated(name)
        assertTrue(result.length < name.length)
        assertTrue(result.contains("…"))
        assertEquals(22, result.take(22).length)
        assertEquals(10, result.takeLast(10).length)
    }

    @Test
    fun `middleTruncated - custom head and tail lengths`() {
        val name = "ThisIsAVeryLongFileNameThatNeedsTruncation"
        val result = BrowserTabPool.middleTruncated(name, head = 10, tail = 5)
        assertTrue(result.length < name.length)
        assertTrue(result.contains("…"))
        assertEquals(10, result.take(10).length)
        assertEquals(5, result.takeLast(5).length)
    }

    @Test
    fun `idleTimeoutMinutes - default value is 15`() {
        assertEquals(15, pool.idleTimeoutMinutes)
    }

    @Test
    fun `setIdleTimeoutMinutes - valid value updates timeout`() {
        pool.setIdleTimeoutMinutes(30)
        assertEquals(30, pool.idleTimeoutMinutes)
    }

    @Test
    fun `setIdleTimeoutMinutes - clamps to minimum`() {
        pool.setIdleTimeoutMinutes(0)
        assertEquals(BrowserTabPool.MIN_IDLE_TIMEOUT_MINUTES, pool.idleTimeoutMinutes)
    }

    @Test
    fun `setIdleTimeoutMinutes - clamps to maximum`() {
        pool.setIdleTimeoutMinutes(1000)
        assertEquals(BrowserTabPool.MAX_IDLE_TIMEOUT_MINUTES, pool.idleTimeoutMinutes)
    }

    @Test
    fun `downloadBadgeCount - counts downloading and unseen entries`() {
        val entries = listOf(
            BrowserTabPool.DownloadEntry(
                id = 1,
                filename = "file1.pdf",
                destination = null,
                bytesDone = 0,
                totalBytes = 100,
                state = BrowserTabPool.DownloadState.DOWNLOADING,
                startedAt = 0
            ),
            BrowserTabPool.DownloadEntry(
                id = 2,
                filename = "file2.pdf",
                destination = null,
                bytesDone = 100,
                totalBytes = 100,
                state = BrowserTabPool.DownloadState.COMPLETED,
                startedAt = 0,
                seen = false
            ),
            BrowserTabPool.DownloadEntry(
                id = 3,
                filename = "file3.pdf",
                destination = null,
                bytesDone = 100,
                totalBytes = 100,
                state = BrowserTabPool.DownloadState.FAILED,
                startedAt = 0,
                seen = true
            )
        )
        assertEquals(2, pool.downloadBadgeCount(entries))
    }

    @Test
    fun `downloadBadgeCount - no entries returns zero`() {
        assertEquals(0, pool.downloadBadgeCount(emptyList()))
    }

    @Test
    fun `resolvedViewportSize - returns profile viewport when no custom or session viewport`() {
        val (width, height) = pool.resolvedViewportSize()
        assertEquals(UserAgentProfile.MOBILE_CHROME.viewportSize.first, width)
        assertEquals(UserAgentProfile.MOBILE_CHROME.viewportSize.second, height)
    }

    @Test
    fun `resolvedViewportSize - returns custom viewport when set`() = runTest {
        pool.setGlobalViewport(800, 600)
        val (width, height) = pool.resolvedViewportSize()
        assertEquals(800, width)
        assertEquals(600, height)
    }

    @Test
    fun `resolvedViewportSize - returns session viewport when set`() = runTest {
        pool.setSessionViewport(1024, 768)
        val (width, height) = pool.resolvedViewportSize()
        assertEquals(1024, width)
        assertEquals(768, height)
    }

    @Test
    fun `hasCustomViewport - false by default`() {
        assertFalse(pool.hasCustomViewport())
    }

    @Test
    fun `hasCustomViewport - true after setting global viewport`() = runTest {
        pool.setGlobalViewport(800, 600)
        assertTrue(pool.hasCustomViewport())
    }

    @Test
    fun `hasCustomViewport - true after setting session viewport`() = runTest {
        pool.setSessionViewport(1024, 768)
        assertTrue(pool.hasCustomViewport())
    }

    @Test
    fun `isAgentBusy - false when no tabs in use`() = runTest {
        pool.newTabFromUI()
        assertFalse(pool.isAgentBusy)
    }

    @Test
    fun `activeManager - returns null when no tabs`() {
        assertNull(pool.activeManager)
    }

    @Test
    fun `activeManager - returns manager of selected tab`() = runTest {
        val tab = pool.newTabFromUI()
        assertNotNull(pool.activeManager)
        assertEquals(tab?.manager, pool.activeManager)
    }

    @Test
    fun `selectTab - selects existing tab`() = runTest {
        val tab1 = pool.newTabFromUI()
        val tab2 = pool.newTabFromUI()
        pool.selectTab(tab1!!.id)
        assertEquals(tab1.id, pool.selectedTabId.value)
    }

    @Test
    fun `selectTab - ignores non-existent tab`() = runTest {
        val tab = pool.newTabFromUI()
        pool.selectTab(999)
        assertEquals(tab!!.id, pool.selectedTabId.value)
    }

    @Test
    fun `newTabFromUI - creates new tab`() = runTest {
        val tab = pool.newTabFromUI()
        assertNotNull(tab)
        assertEquals(1, pool.tabs.value.size)
    }

    @Test
    fun `newTabFromUI - respects max tabs limit`() = runTest {
        repeat(BrowserTabPool.MAX_TABS) {
            pool.newTabFromUI()
        }
        val extraTab = pool.newTabFromUI()
        assertNull(extraTab)
        assertEquals(BrowserTabPool.MAX_TABS, pool.tabs.value.size)
    }

    @Test
    fun `closeTabFromUI - removes tab`() = runTest {
        val tab = pool.newTabFromUI()
        pool.closeTabFromUI(tab!!.id)
        assertEquals(0, pool.tabs.value.size)
    }

    @Test
    fun `closeTabFromUI - selects first remaining tab`() = runTest {
        val tab1 = pool.newTabFromUI()
        val tab2 = pool.newTabFromUI()
        pool.selectTab(tab1!!.id)
        pool.closeTabFromUI(tab1.id)
        assertEquals(tab2!!.id, pool.selectedTabId.value)
    }

    @Test
    fun `ensureTabForUI - creates tab when none exist`() = runTest {
        val tab = pool.ensureTabForUI()
        assertNotNull(tab)
        assertEquals(1, pool.tabs.value.size)
    }

    @Test
    fun `ensureTabForUI - returns existing tab`() = runTest {
        val existing = pool.newTabFromUI()
        val tab = pool.ensureTabForUI()
        assertEquals(existing!!.id, tab.id)
    }

    @Test
    fun `selectOrCreateTabForURL - selects matching tab`() = runTest {
        val url = "https://example.com"
        val tab = pool.selectOrCreateTabForURL(url)
        assertNotNull(tab)
        assertEquals(url, tab?.manager?.currentURL?.value)
        
        val sameTab = pool.selectOrCreateTabForURL(url)
        assertEquals(tab!!.id, sameTab!!.id)
    }

    @Test
    fun `selectOrCreateTabForURL - creates new tab for different URL`() = runTest {
        pool.selectOrCreateTabForURL("https://example.com")
        val tab2 = pool.selectOrCreateTabForURL("https://other.com")
        assertNotNull(tab2)
        assertEquals(2, pool.tabs.value.size)
    }

    @Test
    fun `releaseAllTabs - marks all tabs as not in use`() = runTest {
        val tab = pool.newTabFromUI()
        tab!!.inUse = true
        pool.releaseAllTabs()
        assertFalse(pool.tabs.value[0].inUse)
    }

    @Test
    fun `evictIdleTabs - removes idle tabs`() = runTest {
        val tab = pool.newTabFromUI()
        tab!!.lastActivityDate = java.util.Date(System.currentTimeMillis() - 20 * 60 * 1000)
        pool.evictIdleTabs()
        assertEquals(0, pool.tabs.value.size)
    }

    @Test
    fun `evictIdleTabs - keeps active tabs`() = runTest {
        val tab = pool.newTabFromUI()
        tab!!.inUse = true
        pool.evictIdleTabs()
        assertEquals(1, pool.tabs.value.size)
    }

    @Test
    fun `setSession - sets session and loads state`() {
        pool.setSession("test-session")
        assertNotNull(pool.activeManager)
    }

    @Test
    fun `setUserAgentFromUI - updates user agent`() {
        pool.setUserAgentFromUI(UserAgentProfile.DESKTOP_CHROME, "custom-ua")
        assertEquals(UserAgentProfile.DESKTOP_CHROME, pool.currentUserAgentProfile.value)
    }

    @Test
    fun `cancelDownload - cancels active download`() = runTest {
        val dest = java.io.File(context.filesDir, "test-download.txt")
        val id = pool.registerDownloadForTest(dest, 100L)
        pool.cancelDownload(id)
        assertNull(pool.activeDownload.value)
    }

    @Test
    fun `markDownloadsSeen - marks non-downloading entries as seen`() {
        val entries = listOf(
            BrowserTabPool.DownloadEntry(
                id = 1,
                filename = "file1.pdf",
                destination = null,
                bytesDone = 100,
                totalBytes = 100,
                state = BrowserTabPool.DownloadState.COMPLETED,
                startedAt = 0,
                seen = false
            )
        )
        pool.markDownloadsSeen()
        assertTrue(pool.downloads.value.all { it.seen || it.state == BrowserTabPool.DownloadState.DOWNLOADING })
    }

    @Test
    fun `clearFinishedDownloads - removes completed downloads`() {
        pool.clearFinishedDownloads()
        assertTrue(pool.downloads.value.none { it.state != BrowserTabPool.DownloadState.DOWNLOADING })
    }

    @Test
    fun `setGlobalViewport - updates viewport state`() = runTest {
        pool.setGlobalViewport(800, 600)
        assertEquals(800, pool.customViewportWidth.value)
        assertEquals(600, pool.customViewportHeight.value)
    }

    @Test
    fun `setSessionViewport - updates session viewport state`() = runTest {
        pool.setSessionViewport(1024, 768)
        assertEquals(1024, pool.sessionViewportWidth.value)
        assertEquals(768, pool.sessionViewportHeight.value)
    }

    @Test
    fun `resetSessionViewport - clears session viewport`() = runTest {
        pool.setSessionViewport(1024, 768)
        pool.resetSessionViewport()
        assertEquals(0, pool.sessionViewportWidth.value)
        assertEquals(0, pool.sessionViewportHeight.value)
    }

    @Test
    fun `execute - new tab action`() = runTest {
        val result = pool.execute(
            BrowserActionInput(action = BrowserAction.NEW_TAB, url = "https://example.com")
        )
        assertTrue(result.text.contains("Opened new tab"))
        assertEquals(1, pool.tabs.value.size)
    }

    @Test
    fun `execute - close tab action`() = runTest {
        val tab = pool.newTabFromUI()
        val result = pool.execute(
            BrowserActionInput(action = BrowserAction.CLOSE_TAB, tabId = tab!!.id)
        )
        assertTrue(result.text.contains("Closed tab"))
        assertEquals(0, pool.tabs.value.size)
    }

    @Test
    fun `execute - list tabs action`() = runTest {
        pool.newTabFromUI()
        val result = pool.execute(
            BrowserActionInput(action = BrowserAction.LIST_TABS)
        )
        assertTrue(result.text.contains("Tab 0"))
    }

    @Test
    fun `execute - set viewport action`() = runTest {
        val result = pool.execute(
            BrowserActionInput(
                action = BrowserAction.SET_VIEWPORT,
                viewportWidth = 800,
                viewportHeight = 600
            )
        )
        assertTrue(result.text.contains("Viewport set to 800x600"))
        assertEquals(800, pool.sessionViewportWidth.value)
        assertEquals(600, pool.sessionViewportHeight.value)
    }

    @Test
    fun `execute - set viewport reset action`() = runTest {
        pool.setSessionViewport(800, 600)
        val result = pool.execute(
            BrowserActionInput(action = BrowserAction.SET_VIEWPORT, reset = true)
        )
        assertTrue(result.text.contains("Viewport reset"))
        assertEquals(0, pool.sessionViewportWidth.value)
        assertEquals(0, pool.sessionViewportHeight.value)
    }

    @Test
    fun `execute - invalid viewport returns error`() = runTest {
        val result = pool.execute(
            BrowserActionInput(
                action = BrowserAction.SET_VIEWPORT,
                viewportWidth = 0,
                viewportHeight = 0
            )
        )
        assertTrue(result.text.contains("requires positive"))
    }

    @Test
    fun `execute - navigates to URL in existing tab`() = runTest {
        val tab = pool.newTabFromUI()
        val result = pool.execute(
            BrowserActionInput(
                action = BrowserAction.GO_TO,
                url = "https://example.com",
                tabId = tab!!.id
            )
        )
        assertNotNull(result.tabId)
        assertEquals(tab.id, result.tabId)
    }

    @Test
    fun `downloads StateFlow - initial state is empty`() {
        assertTrue(pool.downloads.value.isEmpty())
    }

    @Test
    fun `activeDownload StateFlow - initial state is null`() {
        assertNull(pool.activeDownload.value)
    }

    @Test
    fun `tabs StateFlow - initial state is empty`() {
        assertTrue(pool.tabs.value.isEmpty())
    }

    @Test
    fun `selectedTabId StateFlow - initial state is zero`() {
        assertEquals(0, pool.selectedTabId.value)
    }

    @Test
    fun `currentUserAgentProfile StateFlow - initial state is MOBILE_CHROME`() {
        assertEquals(UserAgentProfile.MOBILE_CHROME, pool.currentUserAgentProfile.value)
    }

    @Test
    fun `customViewportWidth StateFlow - initial state is zero`() {
        assertEquals(0, pool.customViewportWidth.value)
    }

    @Test
    fun `customViewportHeight StateFlow - initial state is zero`() {
        assertEquals(0, pool.customViewportHeight.value)
    }

    @Test
    fun `sessionViewportWidth StateFlow - initial state is zero`() {
        assertEquals(0, pool.sessionViewportWidth.value)
    }

    @Test
    fun `sessionViewportHeight StateFlow - initial state is zero`() {
        assertEquals(0, pool.sessionViewportHeight.value)
    }
}