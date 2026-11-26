package com.openminis.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-session-search] Contract tests for the session-search menu entry.
 *
 * The "..." menu is data-driven (ChatMenuPrefs key → ChatActionCatalog spec →
 * ChatScreen `when` branch), so a key that is missing from DEFAULT_ORDER or
 * from the action pool silently disappears from every existing install's
 * persisted order (normalizeOrder only appends known keys). These assertions
 * pin the three things that would break that chain:
 *
 *  1. the key is known to the pool at all;
 *  2. it is in the default order, so a fresh install renders it;
 *  3. it defaults to visible-in-menu / not-pinned-in-footer, matching the
 *     other menu-only entries.
 */
class SessionSearchMenuEntryTest {

    @Test
    fun searchSessionIsInActionPool() {
        assertTrue(ChatMenuPrefs.SEARCH_SESSION in ChatMenuPrefs.ALL_ENTRIES)
    }

    @Test
    fun searchSessionIsInDefaultMenuOrder() {
        assertTrue(ChatMenuPrefs.SEARCH_SESSION in ChatMenuPrefs.DEFAULT_ORDER)
    }

    @Test
    fun searchSessionDefaultsToMenuVisibleAndUnpinned() {
        assertTrue(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.SEARCH_SESSION))
        assertFalse(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.SEARCH_SESSION))
    }

    @Test
    fun legacyOrderGainsSearchSessionAtTheEnd() {
        // An order persisted before this entry existed must not lose the new
        // row: normalizeOrder appends every missing known key.
        val legacy = ChatMenuPrefs.TERMINAL + "," + ChatMenuPrefs.EXPORT
        val resolved = ChatMenuPrefs.normalizeOrder(legacy, ChatMenuPrefs.ALL_ENTRIES)
        assertEquals(ChatMenuPrefs.ALL_ENTRIES.size, resolved.size)
        assertTrue(ChatMenuPrefs.SEARCH_SESSION in resolved)
        assertEquals(ChatMenuPrefs.SEARCH_SESSION, resolved.last())
    }

    @Test
    fun sanitizeKeepsSearchSessionEvenWhenCallerOmitsIt() {
        val dirty = listOf(ChatMenuPrefs.EXPORT)
        val sanitized = ChatMenuPrefs.sanitizeForWrite(dirty, ChatMenuPrefs.ALL_ENTRIES)
        assertEquals(ChatMenuPrefs.ALL_ENTRIES.size, sanitized.size)
        assertTrue(ChatMenuPrefs.SEARCH_SESSION in sanitized)
    }
}