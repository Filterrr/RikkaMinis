package com.openminis.app.config.audit

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RobolectricExtension
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaField

@ExtendWith(RobolectricExtension::class)
@Config(manifest = Config.NONE)
class ConfigAuditLogTest {

    private lateinit var context: Context
    private lateinit var auditLog: ConfigAuditLog

    @BeforeEach
    fun setUp() {
        // Reset singleton
        val companion = ConfigAuditLog::class.companionObjectInstance
        val instField = companion?.let { cls ->
            cls::class.declaredMemberProperties.find { it.name == "INSTANCE" }
        }?.javaField
        instField?.apply {
            isAccessible = true
            set(companion, null)
        }
        context = RuntimeEnvironment.getApplication().applicationContext
        auditLog = ConfigAuditLog.init(context)
    }

    @AfterEach
    fun tearDown() {
        // Clean up database
        auditLog.clearAll()
        // Reset singleton
        val companion = ConfigAuditLog::class.companionObjectInstance
        val instField = companion?.let { cls ->
            cls::class.declaredMemberProperties.find { it.name == "INSTANCE" }
        }?.javaField
        instField?.apply {
            isAccessible = true
            set(companion, null)
        }
    }

    @Test
    fun `init and get should return the same singleton instance`() {
        val instance1 = ConfigAuditLog.init(context)
        val instance2 = ConfigAuditLog.get()
        assert(instance1 === instance2)
    }

    @Test
    fun `revision should start at 0`() = runBlocking {
        val rev = auditLog.revision.first()
        assert(rev == 0)
    }

    @Test
    fun `append should increase revision and store entry`() {
        val entry = createEntry("id1")
        val initialRev = runBlocking { auditLog.revision.first() }

        auditLog.append(entry)

        val revAfter = runBlocking { auditLog.revision.first() }
        assert(revAfter == initialRev + 1)

        val stored = auditLog.get("id1")
        assert(stored != null)
        assert(stored.id == "id1")
        assert(stored.scope == "testScope")
        assert(stored.caption == "test caption")
    }

    @Test
    fun `append with replace should overwrite existing entry`() {
        val entry1 = createEntry("id1", key = "key1")
        auditLog.append(entry1)

        val entry2 = createEntry("id1", key = "key2")
        auditLog.append(entry2)

        val stored = auditLog.get("id1")
        assert(stored?.key == "key2")
    }

    @Test
    fun `markReverted should update status and bump revision`() {
        val entry = createEntry("id_mark")
        auditLog.append(entry)
        val revBefore = runBlocking { auditLog.revision.first() }

        auditLog.markReverted("id_mark")

        val revAfter = runBlocking { auditLog.revision.first() }
        assert(revAfter == revBefore + 1)

        val stored = auditLog.get("id_mark")
        assert(stored?.status == ConfigAuditStatus.REVERTED)
    }

    @Test
    fun `clearAll should remove all entries and bump revision`() {
        auditLog.append(createEntry("c1"))
        auditLog.append(createEntry("c2"))
        val revBefore = runBlocking { auditLog.revision.first() }

        auditLog.clearAll()

        val revAfter = runBlocking { auditLog.revision.first() }
        assert(revAfter == revBefore + 1)
        assert(auditLog.recent().isEmpty())
        assert(auditLog.get("c1") == null)
        assert(auditLog.get("c2") == null)
    }

    @Test
    fun `recent should return entries in descending order of at`() {
        val entry1 = createEntry("r1", at = 1000L)
        val entry2 = createEntry("r2", at = 2000L)
        val entry3 = createEntry("r3", at = 1500L)
        auditLog.append(entry1)
        auditLog.append(entry2)
        auditLog.append(entry3)

        val recent = auditLog.recent()
        assert(recent.size == 3)
        assert(recent[0].id == "r2") // latest
        assert(recent[1].id == "r3")
        assert(recent[2].id == "r1")
    }

    @Test
    fun `recent with scope filter should only return matching entries`() {
        val entry1 = createEntry("s1", scope = "scopeA")
        val entry2 = createEntry("s2", scope = "scopeB")
        val entry3 = createEntry("s3", scope = "scopeA")
        auditLog.append(entry1)
        auditLog.append(entry2)
        auditLog.append(entry3)

        val filtered = auditLog.recent(scope = "scopeA")
        assert(filtered.size == 2)
        assert(filtered.all { it.scope == "scopeA" })
    }

    @Test
    fun `recent limit should cap results`() {
        repeat(5) { i ->
            auditLog.append(createEntry("lim$i", at = i.toLong()))
        }
        val limited = auditLog.recent(limit = 3)
        assert(limited.size == 3)
    }

    @Test
    fun `get should return null for non-existent id`() {
        assert(auditLog.get("nonexistent") == null)
    }

    @Test
    fun `usage should return correct count and capacity`() {
        assert(auditLog.usage().count == 0)
        assert(auditLog.usage().capacity == 1000)

        auditLog.append(createEntry("u1"))
        auditLog.append(createEntry("u2"))

        val usage = auditLog.usage()
        assert(usage.count == 2)
        assert(usage.capacity == 1000)
    }

    @Test
    fun `max rows cleanup should keep only latest entries`() {
        // Insert more than MAX_ROWS (1000) entries
        val count = 1010
        repeat(count) { i ->
            auditLog.append(createEntry("cleanup_$i", at = i.toLong()))
        }

        val recent = auditLog.recent()
        // Should have at most 1000 entries
        assert(recent.size <= 1000)
        // Should keep the latest ones (highest at)
        val firstId = recent.first().id
        assert(firstId == "cleanup_${count - 1}")
    }

    @Test
    fun `revision should be consistent across multiple operations`() = runBlocking {
        val rev0 = auditLog.revision.first()
        auditLog.append(createEntry("rev1"))
        val rev1 = auditLog.revision.first()
        auditLog.append(createEntry("rev2"))
        val rev2 = auditLog.revision.first()
        auditLog.markReverted("rev1")
        val rev3 = auditLog.revision.first()
        auditLog.clearAll()
        val rev4 = auditLog.revision.first()

        assert(rev0 == 0)
        assert(rev1 == 1)
        assert(rev2 == 2)
        assert(rev3 == 3)
        assert(rev4 == 4)
    }

    // Helper to create a ConfigAuditEntry for testing
    private fun createEntry(
        id: String,
        at: Long = System.currentTimeMillis(),
        actor: ConfigAuditActor = ConfigAuditActor("system"),
        sessionId: String? = null,
        scope: String = "testScope",
        key: String = "testKey",
        oldValueJSON: String = "\"old\"",
        newValueJSON: String = "\"new\"",
        confirmedAt: Long? = null,
        status: ConfigAuditStatus = ConfigAuditStatus.PENDING,
        revertOf: String? = null,
        caption: String? = "test caption"
    ): ConfigAuditEntry = ConfigAuditEntry(
        id = id,
        at = at,
        actor = actor,
        sessionId = sessionId,
        scope = scope,
        key = key,
        oldValueJSON = oldValueJSON,
        newValueJSON = newValueJSON,
        confirmedAt = confirmedAt,
        status = status,
        revertOf = revertOf,
        caption = caption
    )
}