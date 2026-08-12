package com.openminis.app.data.repository

import com.openminis.app.data.db.WebAppShortcutDao
import com.openminis.app.data.db.WebAppShortcutEntity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WebAppShortcutRepositoryTest {

    private lateinit var fakeDao: FakeWebAppShortcutDao
    private lateinit var repository: WebAppShortcutRepository

    @BeforeEach
    fun setUp() {
        fakeDao = FakeWebAppShortcutDao()
        repository = WebAppShortcutRepository(fakeDao)
    }

    @Test
    fun `create inserts entity via dao and returns entity with generated id`() = runBlocking {
        val entity = repository.create(
            htmlPath = "html/path",
            pathScope = WebAppShortcutRepository.SCOPE_SHARED,
            scopeContext = "ctx",
            title = "Title",
            iconRef = "iconRef",
            iconCachePath = "cache/path",
            sourceSessionId = "session-1"
        )

        assertNotNull(entity.id)
        assertTrue(entity.id.isNotEmpty())
        assertEquals("html/path", entity.htmlPath)
        assertEquals(WebAppShortcutRepository.SCOPE_SHARED, entity.pathScope)
        assertEquals("ctx", entity.scopeContext)
        assertEquals("Title", entity.title)
        assertEquals("iconRef", entity.iconRef)
        assertEquals("cache/path", entity.iconCachePath)
        assertEquals("session-1", entity.sourceSessionId)
        assertTrue(entity.createdAt > 0)

        assertEquals(1, fakeDao.inserted.size)
        assertSame(entity, fakeDao.inserted.first())
    }

    @Test
    fun `create works with null optional fields`() = runBlocking {
        val entity = repository.create(
            htmlPath = "html/path",
            pathScope = WebAppShortcutRepository.SCOPE_MOUNT,
            scopeContext = null,
            title = "Title",
            iconRef = "iconRef",
            iconCachePath = null,
            sourceSessionId = null
        )

        assertNull(entity.scopeContext)
        assertNull(entity.iconCachePath)
        assertNull(entity.sourceSessionId)
        assertEquals(WebAppShortcutRepository.SCOPE_MOUNT, entity.pathScope)
        assertEquals(1, fakeDao.inserted.size)
    }

    @Test
    fun `create generates unique ids`() = runBlocking {
        val e1 = repository.create(
            htmlPath = "p1",
            pathScope = WebAppShortcutRepository.SCOPE_SESSION_ATTACHMENT,
            scopeContext = null,
            title = "t1",
            iconRef = "r1",
            iconCachePath = null,
            sourceSessionId = null
        )
        val e2 = repository.create(
            htmlPath = "p2",
            pathScope = WebAppShortcutRepository.SCOPE_SESSION_ATTACHMENT,
            scopeContext = null,
            title = "t2",
            iconRef = "r2",
            iconCachePath = null,
            sourceSessionId = null
        )

        assertNotEquals(e1.id, e2.id)
    }

    @Test
    fun `get returns entity from dao when present`() = runBlocking {
        val entity = WebAppShortcutEntity(
            id = "id-1",
            htmlPath = "p",
            pathScope = WebAppShortcutRepository.SCOPE_SHARED,
            scopeContext = null,
            title = "t",
            iconRef = "r",
            iconCachePath = null,
            createdAt = 123L,
            sourceSessionId = null
        )
        fakeDao.storage["id-1"] = entity

        val result = repository.get("id-1")

        assertEquals(entity, result)
        assertEquals("id-1", fakeDao.lastGetById)
    }

    @Test
    fun `get returns null when dao returns null`() = runBlocking {
        val result = repository.get("missing")

        assertNull(result)
        assertEquals("missing", fakeDao.lastGetById)
    }

    @Test
    fun `list returns all entities from dao`() = runBlocking {
        val e1 = WebAppShortcutEntity(
            id = "id-1",
            htmlPath = "p1",
            pathScope = WebAppShortcutRepository.SCOPE_SHARED,
            scopeContext = null,
            title = "t1",
            iconRef = "r1",
            iconCachePath = null,
            createdAt = 1L,
            sourceSessionId = null
        )
        val e2 = WebAppShortcutEntity(
            id = "id-2",
            htmlPath = "p2",
            pathScope = WebAppShortcutRepository.SCOPE_MOUNT,
            scopeContext = null,
            title = "t2",
            iconRef = "r2",
            iconCachePath = null,
            createdAt = 2L,
            sourceSessionId = null
        )
        fakeDao.storage["id-1"] = e1
        fakeDao.storage["id-2"] = e2

        val result = repository.list()

        assertEquals(2, result.size)
        assertTrue(result.contains(e1))
        assertTrue(result.contains(e2))
    }

    @Test
    fun `list returns empty list when dao has no entities`() = runBlocking {
        val result = repository.list()

        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `delete calls dao deleteById with given id`() = runBlocking {
        fakeDao.storage["id-1"] = WebAppShortcutEntity(
            id = "id-1",
            htmlPath = "p",
            pathScope = WebAppShortcutRepository.SCOPE_SHARED,
            scopeContext = null,
            title = "t",
            iconRef = "r",
            iconCachePath = null,
            createdAt = 1L,
            sourceSessionId = null
        )

        repository.delete("id-1")

        assertEquals("id-1", fakeDao.lastDeletedById)
        assertFalse(fakeDao.storage.containsKey("id-1"))
    }

    @Test
    fun `delete on missing id still calls dao`() = runBlocking {
        repository.delete("missing")

        assertEquals("missing", fakeDao.lastDeletedById)
    }

    @Test
    fun `update calls dao update with given entity`() = runBlocking {
        val entity = WebAppShortcutEntity(
            id = "id-1",
            htmlPath = "p",
            pathScope = WebAppShortcutRepository.SCOPE_SHARED,
            scopeContext = null,
            title = "updated-title",
            iconRef = "r",
            iconCachePath = null,
            createdAt = 1L,
            sourceSessionId = null
        )

        repository.update(entity)

        assertEquals(1, fakeDao.updated.size)
        assertSame(entity, fakeDao.updated.first())
    }

    @Test
    fun `companion constants have expected values`() {
        assertEquals("session_attachment", WebAppShortcutRepository.SCOPE_SESSION_ATTACHMENT)
        assertEquals("shared", WebAppShortcutRepository.SCOPE_SHARED)
        assertEquals("mount", WebAppShortcutRepository.SCOPE_MOUNT)
    }

    private class FakeWebAppShortcutDao : WebAppShortcutDao {
        val storage = mutableMapOf<String, WebAppShortcutEntity>()
        val inserted = mutableListOf<WebAppShortcutEntity>()
        val updated = mutableListOf<WebAppShortcutEntity>()
        var lastGetById: String? = null
        var lastDeletedById: String? = null

        override suspend fun insert(entity: WebAppShortcutEntity) {
            storage[entity.id] = entity
            inserted.add(entity)
        }

        override suspend fun getById(id: String): WebAppShortcutEntity? {
            lastGetById = id
            return storage[id]
        }

        override suspend fun getAll(): List<WebAppShortcutEntity> {
            return storage.values.toList()
        }

        override suspend fun deleteById(id: String) {
            lastDeletedById = id
            storage.remove(id)
        }

        override suspend fun update(entity: WebAppShortcutEntity) {
            storage[entity.id] = entity
            updated.add(entity)
        }
    }
}