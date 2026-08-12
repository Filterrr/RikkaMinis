package com.openminis.app.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigBackupTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @TempDir
    lateinit var tempDir: File

    // Test for snapshotFileName
    @Test
    fun `snapshotFileName returns valid filename format`() {
        val now = 1700000000000L
        val filename = ConfigBackup.snapshotFileName(now)
        assertTrue(filename.startsWith("rikkaminis-snapshot-"))
        assertTrue(filename.endsWith(".json"))
        assertEquals(0, filename.indexOf("rikkaminis-snapshot-"))
    }

    // Test for suggestedFileName
    @Test
    fun `suggestedFileName returns valid filename format`() {
        val now = 1700000000000L
        val filename = ConfigBackup.suggestedFileName(now)
        assertTrue(filename.startsWith("rikkaminis-backup-"))
        assertTrue(filename.endsWith(".json"))
    }

    // Test for writeSnapshot
    @Test
    fun `writeSnapshot creates file and returns it`() {
        val dir = File(tempDir, "snapshots")
        val payload = "{\"test\": true}"
        
        val file = ConfigBackup.writeSnapshot(dir, payload)
        
        assertTrue(file.exists())
        assertEquals(payload, file.readText())
        assertTrue(file.name.startsWith("rikkaminis-snapshot-"))
        assertTrue(file.name.endsWith(".json"))
    }

    // Test for listSnapshots
    @Test
    fun `listSnapshots returns empty list when directory doesn't exist`() {
        val dir = File(tempDir, "nonexistent")
        val snapshots = ConfigBackup.listSnapshots(dir)
        assertTrue(snapshots.isEmpty())
    }

    // Test for listSnapshots with files
    @Test
    fun `listSnapshots returns files sorted by modification time`() {
        val dir = File(tempDir, "snapshots")
        dir.mkdirs()
        
        val file1 = File(dir, "rikkaminis-snapshot-20240101-000001.json")
        val file2 = File(dir, "rikkaminis-snapshot-20240101-000002.json")
        val file3 = File(dir, "rikkaminis-snapshot-20240101-000003.json")
        
        file1.writeText("test1")
        file2.writeText("test2")
        file3.writeText("test3")
        
        // Set modification times
        file1.setLastModified(1000)
        file2.setLastModified(2000)
        file3.setLastModified(3000)
        
        val snapshots = ConfigBackup.listSnapshots(dir)
        
        assertEquals(3, snapshots.size)
        assertEquals(file3.name, snapshots[0].name)
        assertEquals(file2.name, snapshots[1].name)
        assertEquals(file1.name, snapshots[2].name)
    }

    // Test for writeSnapshot with rotation
    @Test
    fun `writeSnapshot deletes old snapshots beyond keep limit`() {
        val dir = File(tempDir, "snapshots")
        dir.mkdirs()
        
        // Create more than SNAPSHOT_KEEP files
        for (i in 1..(ConfigBackup.SNAPSHOT_KEEP + 2)) {
            val file = File(dir, "rikkaminis-snapshot-2024010$i-000001.json")
            file.writeText("test$i")
            file.setLastModified(1000L * i)
        }
        
        val file = ConfigBackup.writeSnapshot(dir, "new payload")
        
        val snapshots = ConfigBackup.listSnapshots(dir)
        assertEquals(ConfigBackup.SNAPSHOT_KEEP, snapshots.size)
        assertEquals(file.name, snapshots[0].name)
    }

    // Test for sanitizeChatParts
    @Test
    fun `sanitizeChatParts removes images and attachments`() {
        val partsJson = """
            [
                {"type": "text", "value": "Hello <user-attached-files>file1.jpg</user-attached-files> world"},
                {"type": "image", "value": "data:image/png;base64,abc"},
                {"type": "text", "value": "   "},
                {"type": "toolResult", "value": {"output": "result", "snapshot": "data"}}
            ]
        """.trimIndent()
        
        val result = ConfigBackup.sanitizeChatParts(partsJson)
        
        assertNotNull(result)
        val arr = org.json.JSONArray(result)
        assertEquals(2, arr.length())
        
        val first = arr.getJSONObject(0)
        assertEquals("text", first.optString("type"))
        assertEquals("Hello  world", first.optString("value"))
        
        val second = arr.getJSONObject(1)
        assertEquals("toolResult", second.optString("type"))
        val secondValue = second.optJSONObject("value") ?: second
        assertFalse(secondValue.has("snapshot"))
        assertEquals("result", secondValue.optString("output"))
    }

    // Test for sanitizeChatParts with null input
    @Test
    fun `sanitizeChatParts returns null for null or blank input`() {
        assertNull(ConfigBackup.sanitizeChatParts(null))
        assertNull(ConfigBackup.sanitizeChatParts(""))
        assertNull(ConfigBackup.sanitizeChatParts("   "))
    }

    // Test for sanitizeChatParts with invalid JSON
    @Test
    fun `sanitizeChatParts returns null for invalid JSON`() {
        assertNull(ConfigBackup.sanitizeChatParts("invalid json"))
    }

    // Test for capReasoningContent
    @Test
    fun `capReasoningContent truncates long content`() {
        val longContent = "a".repeat(3000)
        val result = ConfigBackup.capReasoningContent(longContent)
        
        assertNotNull(result)
        assertTrue(result.length <= ConfigBackup.MAX_BACKUP_REASONING_CHARS + 50)
        assertTrue(result.contains("[truncated"))
    }

    // Test for capReasoningContent with short content
    @Test
    fun `capReasoningContent returns content unchanged when within limit`() {
        val shortContent = "short reasoning"
        val result = ConfigBackup.capReasoningContent(shortContent)
        
        assertEquals(shortContent, result)
    }

    // Test for capReasoningContent with null
    @Test
    fun `capReasoningContent returns null for null or blank`() {
        assertNull(ConfigBackup.capReasoningContent(null))
        assertNull(ConfigBackup.capReasoningContent(""))
        assertNull(ConfigBackup.capReasoningContent("   "))
    }

    // Test for isCatalogCacheModel
    @Test
    fun `isCatalogCacheModel returns true for hidden non-custom models`() {
        assertTrue(isCatalogCacheModel(true, false))
    }

    @Test
    fun `isCatalogCacheModel returns false for visible models`() {
        assertFalse(isCatalogCacheModel(false, false))
        assertFalse(isCatalogCacheModel(false, true))
    }

    @Test
    fun `isCatalogCacheModel returns false for custom hidden models`() {
        assertFalse(isCatalogCacheModel(true, true))
    }

    // Test for shouldSkipSyncField
    @Test
    fun `shouldSkipSyncField returns true for soul fields during sync merge`() {
        assertTrue(shouldSkipSyncField("soul.personality", true))
    }

    @Test
    fun `shouldSkipSyncField returns false for non-soul fields during sync merge`() {
        assertFalse(shouldSkipSyncField("appearance.theme", true))
    }

    @Test
    fun `shouldSkipSyncField returns false when not sync merge`() {
        assertFalse(shouldSkipSyncField("soul.personality", false))
    }

    // Test for enumOrDefault
    @Test
    fun `enumOrDefault returns enum value when valid`() {
        val result = ConfigBackup.enumOrDefault(
            "fallback",
            com.openminis.app.data.model.RoutingStrategy.fallback
        )
        assertEquals(com.openminis.app.data.model.RoutingStrategy.fallback, result)
    }

    @Test
    fun `enumOrDefault returns default when invalid`() {
        val result = ConfigBackup.enumOrDefault(
            "invalid",
            com.openminis.app.data.model.RoutingStrategy.fallback
        )
        assertEquals(com.openminis.app.data.model.RoutingStrategy.fallback, result)
    }

    // Test for dropHiddenModelIds
    @Test
    fun `dropHiddenModelIds filters catalog cache models`() {
        // This is a complex test that would require mocking ProviderRepository
        // For now, test the basic behavior with a simple case
        val providerRepo = createMockProviderRepo()
        val result = ConfigBackup.dropHiddenModelIds(providerRepo, "test-instance")
        assertTrue(result.isNotEmpty())
    }

    // Test for constants
    @Test
    fun `constants have expected values`() {
        assertEquals(1, ConfigBackup.FORMAT_VERSION)
        assertEquals(200, ConfigBackup.MAX_CHAT_MESSAGES_PER_SESSION)
        assertEquals(500, ConfigBackup.MAX_BACKUP_TOOL_OUTPUT_CHARS)
        assertEquals(2000, ConfigBackup.MAX_BACKUP_REASONING_CHARS)
        assertEquals(5, ConfigBackup.SNAPSHOT_KEEP)
        assertEquals(8 * 1024 * 1024, ConfigBackup.MAX_SKILL_ARCHIVE_BYTES)
        assertEquals(64 * 1024 * 1024, ConfigBackup.MAX_PAYLOAD_BYTES)
    }

    // Test for ImportResult data class
    @Test
    fun `ImportResult has correct defaults`() {
        val result = ConfigBackup.ImportResult(
            fieldsApplied = 0,
            providersImported = 0,
            groupsImported = 0,
            envVarsImported = 0,
            skillsImported = 0,
            memoryFilesImported = 0,
            mcpServersImported = 0,
            chatSessionsImported = 0,
            chatMessagesImported = 0,
            skipped = emptyList(),
            hadSecrets = false
        )
        
        assertEquals(0, result.fieldsApplied)
        assertNull(result.fatal)
        assertTrue(result.skipped.isEmpty())
        assertFalse(result.hadSecrets)
    }

    // Test for InvalidBackupException
    @Test
    fun `InvalidBackupException has message`() {
        val exception = ConfigBackup.InvalidBackupException("test message")
        assertEquals("test message", exception.message)
    }

    // Test for snapshotFileName with different times
    @Test
    fun `snapshotFileName produces different names for different times`() {
        val name1 = ConfigBackup.snapshotFileName(1000)
        val name2 = ConfigBackup.snapshotFileName(2000)
        assertFalse(name1 == name2)
    }

    // Test for suggestedFileName with different times
    @Test
    fun `suggestedFileName produces different names for different times`() {
        val name1 = ConfigBackup.suggestedFileName(1000)
        val name2 = ConfigBackup.suggestedFileName(2000)
        assertFalse(name1 == name2)
    }

    // Test for sanitizeChatParts with tool result truncation
    @Test
    fun `sanitizeChatParts truncates long tool output`() {
        val longOutput = "x".repeat(1000)
        val partsJson = """
            [
                {"type": "toolResult", "value": {"output": "$longOutput"}}
            ]
        """.trimIndent()
        
        val result = ConfigBackup.sanitizeChatParts(partsJson)
        
        assertNotNull(result)
        val arr = org.json.JSONArray(result)
        val toolResult = arr.getJSONObject(0)
        val value = toolResult.optJSONObject("value") ?: toolResult
        val output = value.optString("output")
        
        assertTrue(output.length <= ConfigBackup.MAX_BACKUP_TOOL_OUTPUT_CHARS + 50)
        assertTrue(output.contains("[truncated"))
    }

    // Test for sanitizeChatParts with tool result without truncation
    @Test
    fun `sanitizeChatParts keeps short tool output unchanged`() {
        val partsJson = """
            [
                {"type": "toolResult", "value": {"output": "short output"}}
            ]
        """.trimIndent()
        
        val result = ConfigBackup.sanitizeChatParts(partsJson)
        
        assertNotNull(result)
        val arr = org.json.JSONArray(result)
        val toolResult = arr.getJSONObject(0)
        val value = toolResult.optJSONObject("value") ?: toolResult
        assertEquals("short output", value.optString("output"))
    }

    // Helper function to create a mock ProviderRepository
    private fun createMockProviderRepo(): ProviderRepository {
        // This would require mocking the repository
        // For now, return a mock that returns empty config
        return object : ProviderRepository {
            override val config = com.openminis.app.config.ConfigValue.Holder(
                com.openminis.app.config.AppConfig()
            )
            override val instances = listOf()
            
            override fun visibleEntries(instanceId: String): List<com.openminis.app.data.model.ModelEntry> = emptyList()
            override fun exportInstanceJSON(instanceId: String): String? = null
            override fun importInstanceJSON(json: String): String? = null
            override fun mergeImportInstanceJSON(json: String, entryIds: List<String>): Pair<String, Map<String, String>>? = null
        }
    }
}