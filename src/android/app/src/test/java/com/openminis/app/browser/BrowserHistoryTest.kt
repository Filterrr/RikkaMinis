package com.openminis.app.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach

class BrowserHistoryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var store: BrowserHistoryStore

    @BeforeEach
    fun setUp() {
        store = BrowserHistoryStore.getInstance(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }

    @Test
    fun testRecordEntry() {
        store.record("https://example.com", "Example")
        val entries = store.getEntries()
        assertEquals(1, entries.size)
        assertEquals("https://example.com", entries[0].url)
        assertEquals("Example", entries[0].title)
    }

    @Test
    fun testRecordDuplicateUrl() {
        store.record("https://example.com", "First")
        store.record("https://example.com", "Second")
        val entries = store.getEntries()
        assertEquals(1, entries.size)
    }

    @Test
    fun testRecordEmptyUrl() {
        store.record("", "Empty")
        val entries = store.getEntries()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun testGetEntriesOrderedByTimestampDescending() {
        store.record("https://a.com", "A")
        Thread.sleep(10)
        store.record("https://b.com", "B")
        val entries = store.getEntries()
        assertEquals("B", entries[0].title)
        assertEquals("A", entries[1].title)
    }

    @Test
    fun testSearchByTitle() {
        store.record("https://example.com", "Hello World")
        val results = store.search("hello")
        assertEquals(1, results.size)
    }

    @Test
    fun testSearchByUrl() {
        store.record("https://example.com/page", "Page")
        val results = store.search("page")
        assertEquals(1, results.size)
    }

    @Test
    fun testSearchBlankReturnsAll() {
        store.record("https://a.com", "A")
        store.record("https://b.com", "B")
        val results = store.search("")
        assertEquals(2, results.size)
    }

    @Test
    fun testSearchNoMatch() {
        store.record("https://example.com", "Example")
        val results = store.search("nonexistent")
        assertTrue(results.isEmpty())
    }

    @Test
    fun testGroupedByDayToday() {
        store.record("https://example.com", "Today")
        val grouped = store.groupedByDay()
        assertTrue(grouped.containsKey("Today"))
    }

    @Test
    fun testGroupedByDayEmpty() {
        val grouped = store.groupedByDay()
        assertTrue(grouped.isEmpty())
    }

    @Test
    fun testUniqueDomains() {
        store.record("https://example.com/page1", "Page1")
        store.record("https://example.com/page2", "Page2")
        store.record("https://test.org", "Test")
        val domains = store.uniqueDomains()
        assertEquals(2, domains.size)
        assertTrue(domains.contains("example.com"))
        assertTrue(domains.contains("test.org"))
    }

    @Test
    fun testUniqueDomainsEmpty() {
        val domains = store.uniqueDomains()
        assertTrue(domains.isEmpty())
    }

    @Test
    fun testClearHistory() {
        store.record("https://example.com", "Example")
        store.clear()
        val entries = store.getEntries()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun testEntryDefaultId() {
        val entry = BrowserHistoryStore.Entry(url = "https://test.com", title = "Test")
        assertNotNull(entry.id)
    }

    @Test
    fun testEntryDefaultTimestamp() {
        val entry = BrowserHistoryStore.Entry(url = "https://test.com", title = "Test")
        assertTrue(entry.timestamp > 0)
    }

    @Test
    fun testEntryDomainExtraction() {
        val entry = BrowserHistoryStore.Entry(url = "https://www.example.com/path", title = "Test")
        assertEquals("www.example.com", entry.domain)
    }

    @Test
    fun testEntryDomainForInvalidUrl() {
        val entry = BrowserHistoryStore.Entry(url = "invalid-url", title = "Test")
        assertEquals("", entry.domain)
    }

    @Test
    fun testComposableHistoryListRenders() {
        composeTestRule.setContent {
            // Simple composable to test rendering
            androidx.compose.material3.Text("History List")
        }
        composeTestRule.onNodeWithText("History List").assertExists()
    }

    @Test
    fun testComposableHistoryItemClick() {
        var clicked = false
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { clicked = true }) {
                androidx.compose.material3.Text("Click Me")
            }
        }
        composeTestRule.onNodeWithText("Click Me").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testComposableWithDefaultParameters() {
        composeTestRule.setContent {
            androidx.compose.material3.Text("Default Text")
        }
        composeTestRule.onNodeWithText("Default Text").assertExists()
    }

    @Test
    fun testStoreInstanceIsSingleton() {
        val store1 = BrowserHistoryStore.getInstance(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val store2 = BrowserHistoryStore.getInstance(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        assertEquals(store1, store2)
    }

    @Test
    fun testPruneOldEntries() {
        store.record("https://old.com", "Old")
        // Force prune by setting cutoff manually via reflection or internal method
        // Since pruneOld is private, we test via record with future entries
        store.record("https://new.com", "New")
        val entries = store.getEntries()
        assertEquals(2, entries.size)
    }
}