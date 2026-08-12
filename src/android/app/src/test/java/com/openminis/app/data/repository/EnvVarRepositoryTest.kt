package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.util.EncryptedPrefsFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EnvVarRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var repository: EnvVarRepository

    @BeforeEach
    fun setUp() {
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.filesDir } returns tempDir
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor

        mockkObject(EncryptedPrefsFactory)
        every { EncryptedPrefsFactory.safeCreate(context, "env_var_values") } returns prefs

        repository = EnvVarRepository(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(EncryptedPrefsFactory)
    }

    // ---- isValidKey ----

    @Test
    fun `isValidKey returns true for valid keys`() {
        assertTrue(repository.isValidKey("ABC"))
        assertTrue(repository.isValidKey("A"))
        assertTrue(repository.isValidKey("A_1"))
        assertTrue(repository.isValidKey("Abc_123"))
        assertTrue(repository.isValidKey("Z"))
    }

    @Test
    fun `isValidKey returns false for invalid keys`() {
        assertFalse(repository.isValidKey(""))
        assertFalse(repository.isValidKey("1ABC"))
        assertFalse(repository.isValidKey("_ABC"))
        assertFalse(repository.isValidKey("A-B"))
        assertFalse(repository.isValidKey("A B"))
        assertFalse(repository.isValidKey("A.B"))
    }

    // ---- isDuplicateKey ----

    @Test
    fun `isDuplicateKey returns false when no entries exist`() {
        assertFalse(repository.isDuplicateKey("ABC"))
    }

    @Test
    fun `isDuplicateKey returns true after add (case-insensitive)`() {
        repository.add("ABC", "value")
        assertTrue(repository.isDuplicateKey("abc"))
        assertTrue(repository.isDuplicateKey("ABC"))
    }

    @Test
    fun `isDuplicateKey returns false when excluding by id`() {
        repository.add("ABC", "value")
        val entry = repository.entries.first().first()
        assertFalse(repository.isDuplicateKey("ABC", excludeId = entry.id))
    }

    @Test
    fun `isDuplicateKey returns true for different id with same key`() {
        repository.add("ABC", "value")
        repository.add("DEF", "value2")
        val firstId = repository.entries.first().first().id
        assertTrue(repository.isDuplicateKey("DEF", excludeId = firstId))
    }

    // ---- add ----

    @Test
    fun `add returns true and adds entry for valid key`() {
        val result = repository.add("ABC", "value")
        assertTrue(result)
        assertEquals(1, repository.entries.first().size)
    }

    @Test
    fun `add returns false for invalid key`() {
        assertFalse(repository.add("1ABC", "value"))
        assertTrue(repository.entries.first().isEmpty())
    }

    @Test
    fun `add returns false for duplicate key`() {
        repository.add("ABC", "v1")
        assertFalse(repository.add("abc", "v2"))
        assertEquals(1, repository.entries.first().size)
    }

    @Test
    fun `add stores sanitized value in encrypted prefs`() {
        repository.add("ABC", "va\tlue\u0001ue")
        verify { editor.putString("ABC", "va\tlueue") }
    }

    @Test
    fun `add normalizes key to uppercase`() {
        repository.add("abc", "value")
        val entry = repository.entries.first().first()
        assertEquals("ABC", entry.key)
    }

    @Test
    fun `add trims key before normalizing`() {
        assertTrue(repository.add("  abc  ", "value"))
        val entry = repository.entries.first().first()
        assertEquals("ABC", entry.key)
    }

    @Test
    fun `add trims note`() {
        repository.add("ABC", "value", "  note  ")
        val entry = repository.entries.first().first()
        assertEquals("note", entry.note)
    }

    @Test
    fun `add default note is empty`() {
        repository.add("ABC", "value")
        val entry = repository.entries.first().first()
        assertEquals("", entry.note)
    }

    @Test
    fun `add generates unique id`() {
        repository.add("ABC", "value")
        repository.add("DEF", "value2")
        val entries = repository.entries.first()
        assertNotEquals(entries[0].id, entries[1].id)
    }

    @Test
    fun `add sets createdAt timestamp`() {
        val before = System.currentTimeMillis()
        repository.add("ABC", "value")
        val after = System.currentTimeMillis()
        val entry = repository.entries.first().first()
        assertTrue(entry.createdAt in before..after)
    }

    // ---- update ----

    @Test
    fun `update returns false for nonexistent id`() {
        assertFalse(repository.update("nonexistent", "NEW", "value"))
    }

    @Test
    fun `update returns false for invalid key`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        assertFalse(repository.update(id, "1BAD", "v2"))
    }

    @Test
    fun `update returns false for duplicate key from another entry`() {
        repository.add("ABC", "v1")
        repository.add("DEF", "v2")
        val id = repository.entries.first().first().id
        assertFalse(repository.update(id, "def", "v3"))
    }

    @Test
    fun `update succeeds when key unchanged`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        assertTrue(repository.update(id, "ABC", "v2"))
    }

    @Test
    fun `update succeeds when key changed to new unique key`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        assertTrue(repository.update(id, "NEW", "v2"))
        val entry = repository.entries.first().first()
        assertEquals("NEW", entry.key)
    }

    @Test
    fun `update removes old key from prefs when key changes`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        repository.update(id, "NEW", "v2")
        verify { editor.remove("ABC") }
    }

    @Test
    fun `update does not remove old key when key unchanged`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        repository.update(id, "ABC", "v2")
        verify(exactly = 0) { editor.remove(any()) }
    }

    @Test
    fun `update stores new value in prefs`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        repository.update(id, "NEW", "v2")
        verify { editor.putString("NEW", "v2") }
    }

    @Test
    fun `update sanitizes new value`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        repository.update(id, "ABC", "va\tl\u0001ue")
        verify { editor.putString("ABC", "va\tlue") }
    }

    @Test
    fun `update updates note`() {
        repository.add("ABC", "v1", "old")
        val id = repository.entries.first().first().id
        repository.update(id, "ABC", "v2", "  new  ")
        val entry = repository.entries.first().first()
        assertEquals("new", entry.note)
    }

    @Test
    fun `update normalizes new key to uppercase`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        repository.update(id, "new", "v2")
        val entry = repository.entries.first().first()
        assertEquals("NEW", entry.key)
    }

    @Test
    fun `update preserves id`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        repository.update(id, "NEW", "v2")
        assertEquals(id, repository.entries.first().first().id)
    }

    // ---- delete ----

    @Test
    fun `delete removes entry from list`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        repository.delete(id)
        assertTrue(repository.entries.first().isEmpty())
    }

    @Test
    fun `delete removes key from prefs`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        repository.delete(id)
        verify { editor.remove("ABC") }
    }

    @Test
    fun `delete nonexistent id does nothing`() {
        repository.delete("nonexistent")
        assertTrue(repository.entries.first().isEmpty())
    }

    @Test
    fun `delete only removes targeted entry`() {
        repository.add("ABC", "v1")
        repository.add("DEF", "v2")
        val firstId = repository.entries.first().first().id
        repository.delete(firstId)
        val remaining = repository.entries.first()
        assertEquals(1, remaining.size)
        assertEquals("DEF", remaining[0].key)
    }

    // ---- getValue ----

    @Test
    fun `getValue returns value from prefs`() {
        every { prefs.getString("ABC", null) } returns "stored"
        assertEquals("stored", repository.getValue("ABC"))
    }

    @Test
    fun `getValue returns null when not present`() {
        every { prefs.getString("ABC", null) } returns null
        assertNull(repository.getValue("ABC"))
    }

    // ---- allAsDict ----

    @Test
    fun `allAsDict returns empty map when no entries`() {
        assertTrue(repository.allAsDict().isEmpty())
    }

    @Test
    fun `allAsDict returns all entries with values`() {
        repository.add("ABC", "v1")
        repository.add("DEF", "v2")
        every { prefs.getString("ABC", null) } returns "v1"
        every { prefs.getString("DEF", null) } returns "v2"
        val dict = repository.allAsDict()
        assertEquals(2, dict.size)
        assertEquals("v1", dict["ABC"])
        assertEquals("v2", dict["DEF"])
    }

    @Test
    fun `allAsDict skips entries with null values`() {
        repository.add("ABC", "v1")
        repository.add("DEF", "v2")
        every { prefs.getString("ABC", null) } returns "v1"
        every { prefs.getString("DEF", null) } returns null
        val dict = repository.allAsDict()
        assertEquals(1, dict.size)
        assertEquals("v1", dict["ABC"])
    }

    // ---- entries StateFlow ----

    @Test
    fun `entries is empty initially`() {
        assertTrue(repository.entries.first().isEmpty())
    }

    @Test
    fun `entries reflects additions`() {
        repository.add("ABC", "v1")
        assertEquals(1, repository.entries.first().size)
        repository.add("DEF", "v2")
        assertEquals(2, repository.entries.first().size)
    }

    @Test
    fun `entries reflects deletions`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        repository.delete(id)
        assertTrue(repository.entries.first().isEmpty())
    }

    @Test
    fun `entries reflects updates`() {
        repository.add("ABC", "v1")
        val id = repository.entries.first().first().id
        repository.update(id, "NEW", "v2", "note")
        val entry = repository.entries.first().first()
        assertEquals("NEW", entry.key)
        assertEquals("note", entry.note)
    }

    // ---- metadata persistence ----

    @Test
    fun `metadata is persisted across instances`() {
        repository.add("ABC", "v1", "note1")
        repository.add("DEF", "v2")

        val newRepo = EnvVarRepository(context)
        val entries = newRepo.entries.first()
        assertEquals(2, entries.size)
        val keys = entries.map { it.key }.sorted()
        assertEquals(listOf("ABC", "DEF"), keys)
        assertEquals("note1", entries.find { it.key == "ABC" }?.note)
    }

    @Test
    fun `loadMetadata handles missing file gracefully`() {
        val repo = EnvVarRepository(context)
        assertTrue(repo.entries.first().isEmpty())
    }

    @Test
    fun `loadMetadata preserves id and createdAt`() {
        repository.add("ABC", "v1")
        val original = repository.entries.first().first()

        val newRepo = EnvVarRepository(context)
        val loaded = newRepo.entries.first().first()
        assertEquals(original.id, loaded.id)
        assertEquals(original.key, loaded.key)
        assertEquals(original.createdAt, loaded.createdAt)
    }

    @Test
    fun `metadata file is created after add`() {
        repository.add("ABC", "v1")
        val metadataFile = File(tempDir, "env-vars.json")
        assertTrue(metadataFile.exists())
    }
}