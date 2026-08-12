package com.openminis.app.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatActionCatalogTest {

    @Test
    fun `ALL should contain 12 items`() {
        assertEquals(12, ChatActionCatalog.ALL.size)
    }

    @Test
    fun `ALL should contain all expected keys`() {
        val expectedKeys = listOf(
            ChatMenuPrefs.TERMINAL,
            ChatMenuPrefs.BROWSER,
            ChatMenuPrefs.CHAT_FILES,
            ChatMenuPrefs.COMPACT,
            ChatMenuPrefs.THINKING,
            ChatMenuPrefs.SESSION_SKILLS,
            ChatMenuPrefs.SESSION_MCPS,
            ChatMenuPrefs.SESSION_MEMORY,
            ChatMenuPrefs.SLASH_COMMANDS,
            ChatMenuPrefs.EXPORT,
            ChatMenuPrefs.TOKEN_USAGE,
            ChatMenuPrefs.SETTINGS
        )
        val actualKeys = ChatActionCatalog.ALL.map { it.key }
        assertEquals(expectedKeys, actualKeys)
    }

    @Test
    fun `ALL should have correct defaultMenuVisible values`() {
        val visibleTrueKeys = listOf(
            ChatMenuPrefs.TERMINAL,
            ChatMenuPrefs.BROWSER,
            ChatMenuPrefs.CHAT_FILES,
            ChatMenuPrefs.COMPACT,
            ChatMenuPrefs.THINKING,
            ChatMenuPrefs.SESSION_SKILLS,
            ChatMenuPrefs.SESSION_MCPS,
            ChatMenuPrefs.SESSION_MEMORY,
            ChatMenuPrefs.SLASH_COMMANDS,
            ChatMenuPrefs.EXPORT
        )
        val visibleFalseKeys = listOf(
            ChatMenuPrefs.TOKEN_USAGE,
            ChatMenuPrefs.SETTINGS
        )
        for (spec in ChatActionCatalog.ALL) {
            if (spec.key in visibleTrueKeys) {
                assertTrue(spec.defaultMenuVisible) { "${spec.key} should have defaultMenuVisible true" }
            } else if (spec.key in visibleFalseKeys) {
                assertTrue(!spec.defaultMenuVisible) { "${spec.key} should have defaultMenuVisible false" }
            }
        }
    }

    @Test
    fun `ALL should have correct defaultPinned values`() {
        val pinnedTrueKeys = listOf(
            ChatMenuPrefs.TOKEN_USAGE,
            ChatMenuPrefs.SETTINGS
        )
        val pinnedFalseKeys = listOf(
            ChatMenuPrefs.TERMINAL,
            ChatMenuPrefs.BROWSER,
            ChatMenuPrefs.CHAT_FILES,
            ChatMenuPrefs.COMPACT,
            ChatMenuPrefs.THINKING,
            ChatMenuPrefs.SESSION_SKILLS,
            ChatMenuPrefs.SESSION_MCPS,
            ChatMenuPrefs.SESSION_MEMORY,
            ChatMenuPrefs.SLASH_COMMANDS,
            ChatMenuPrefs.EXPORT
        )
        for (spec in ChatActionCatalog.ALL) {
            if (spec.key in pinnedTrueKeys) {
                assertTrue(spec.defaultPinned) { "${spec.key} should have defaultPinned true" }
            } else if (spec.key in pinnedFalseKeys) {
                assertTrue(!spec.defaultPinned) { "${spec.key} should have defaultPinned false" }
            }
        }
    }

    @Test
    fun `spec should return ChatActionSpec for existing key`() {
        val result = ChatActionCatalog.spec(ChatMenuPrefs.TERMINAL)
        assertNotNull(result)
        assertEquals(ChatMenuPrefs.TERMINAL, result?.key)
    }

    @Test
    fun `spec should return null for non-existing key`() {
        val result = ChatActionCatalog.spec("non_existent_key")
        assertNull(result)
    }

    @Test
    fun `spec should return correct spec for each key`() {
        for (expectedSpec in ChatActionCatalog.ALL) {
            val actualSpec = ChatActionCatalog.spec(expectedSpec.key)
            assertNotNull(actualSpec)
            assertEquals(expectedSpec.key, actualSpec?.key)
            assertEquals(expectedSpec.titleRes, actualSpec?.titleRes)
            assertEquals(expectedSpec.icon, actualSpec?.icon)
            assertEquals(expectedSpec.defaultMenuVisible, actualSpec?.defaultMenuVisible)
            assertEquals(expectedSpec.defaultPinned, actualSpec?.defaultPinned)
        }
    }

    @Test
    fun `isChatActionAvailable should return skillsAvailable for SESSION_SKILLS`() {
        assertTrue(isChatActionAvailable(ChatMenuPrefs.SESSION_SKILLS, true, false, false))
        assertTrue(!isChatActionAvailable(ChatMenuPrefs.SESSION_SKILLS, false, true, true))
    }

    @Test
    fun `isChatActionAvailable should return mcpsAvailable for SESSION_MCPS`() {
        assertTrue(isChatActionAvailable(ChatMenuPrefs.SESSION_MCPS, false, true, false))
        assertTrue(!isChatActionAvailable(ChatMenuPrefs.SESSION_MCPS, true, false, true))
    }

    @Test
    fun `isChatActionAvailable should return memoryAvailable for SESSION_MEMORY`() {
        assertTrue(isChatActionAvailable(ChatMenuPrefs.SESSION_MEMORY, false, false, true))
        assertTrue(!isChatActionAvailable(ChatMenuPrefs.SESSION_MEMORY, true, true, false))
    }

    @Test
    fun `isChatActionAvailable should return true for other keys`() {
        val otherKeys = listOf(
            ChatMenuPrefs.TERMINAL,
            ChatMenuPrefs.BROWSER,
            ChatMenuPrefs.CHAT_FILES,
            ChatMenuPrefs.COMPACT,
            ChatMenuPrefs.THINKING,
            ChatMenuPrefs.SLASH_COMMANDS,
            ChatMenuPrefs.EXPORT,
            ChatMenuPrefs.TOKEN_USAGE,
            ChatMenuPrefs.SETTINGS
        )
        for (key in otherKeys) {
            assertTrue(isChatActionAvailable(key, false, false, false))
            assertTrue(isChatActionAvailable(key, true, true, true))
        }
    }
}