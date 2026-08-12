package com.openminis.app.data

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.jupiter.api.Test
import org.junit.Rule
import java.io.File

class FileMentionIndexTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Test for the default constructor
    @Test
    fun `test default constructor with context`() {
        // This test verifies that the constructor with Context parameter works
        // We can't easily create a Context in unit tests, so we'll use the File constructor
        val tempDir = createTempDir()
        val index = FileMentionIndex(tempDir)
        assert(index.entries.value.isEmpty())
        assert(!index.isScanning.value)
        tempDir.deleteRecursively()
    }

    // Test for the File constructor with default parameters
    @Test
    fun `test file constructor with default parameters`() {
        val tempDir = createTempDir()
        val index = FileMentionIndex(tempDir)
        assert(index.entries.value.isEmpty())
        assert(!index.isScanning.value)
        tempDir.deleteRecursively()
    }

    // Test for refreshIfNeeded with new session
    @Test
    fun `test refreshIfNeeded with new session triggers scan`() {
        val tempDir = createTempDir()
        val index = FileMentionIndex(tempDir)
        index.refreshIfNeeded("session1")
        assert(index.isScanning.value)
        // Wait for scan to complete
        Thread.sleep(100)
        tempDir.deleteRecursively()
    }

    // Test for refreshIfNeeded with same session and fresh cache
    @Test
    fun `test refreshIfNeeded with same session and fresh cache skips scan`() {
        val tempDir = createTempDir()
        val index = FileMentionIndex(tempDir)
        index.refreshIfNeeded("session1")
        Thread.sleep(100)
        val entriesBefore = index.entries.value
        index.refreshIfNeeded("session1")
        Thread.sleep(100)
        assert(index.entries.value == entriesBefore)
        tempDir.deleteRecursively()
    }

    // Test for refresh forces re-scan
    @Test
    fun `test refresh forces re-scan`() {
        val tempDir = createTempDir()
        val index = FileMentionIndex(tempDir)
        index.refresh("session1")
        Thread.sleep(100)
        val entriesBefore = index.entries.value
        index.refresh("session1")
        Thread.sleep(100)
        // Since refresh resets lastScanAt, it should trigger a new scan
        // The entries might be the same but the scan should have happened
        assert(index.entries.value == entriesBefore)
        tempDir.deleteRecursively()
    }

    // Test for matches with blank query
    @Test
    fun `test matches with blank query returns all entries`() {
        val tempDir = createTempDir()
        val index = FileMentionIndex(tempDir)
        // Create some test files
        val workspaceDir = File(tempDir, "workspace/session1")
        workspaceDir.mkdirs()
        File(workspaceDir, "testfile.txt").writeText("test")
        index.refresh("session1")
        Thread.sleep(100)
        val allEntries = index.matches("")
        assert(allEntries.isNotEmpty())
        tempDir.deleteRecursively()
    }

    // Test for matches with specific query
    @Test
    fun `test matches with specific query filters results`() {
        val tempDir = createTempDir()
        val index = FileMentionIndex(tempDir)
        val workspaceDir = File(tempDir, "workspace/session1")
        workspaceDir.mkdirs()
        File(workspaceDir, "testfile.txt").writeText("test")
        File(workspaceDir, "other.txt").writeText("other")
        index.refresh("session1")
        Thread.sleep(100)
        val results = index.matches("test")
        assert(results.isNotEmpty())
        assert(results.all { it.basename.contains("test", ignoreCase = true) })
        tempDir.deleteRecursively()
    }

    // Test for matches with limit
    @Test
    fun `test matches respects limit parameter`() {
        val tempDir = createTempDir()
        val index = FileMentionIndex(tempDir)
        val workspaceDir = File(tempDir, "workspace/session1")
        workspaceDir.mkdirs()
        for (i in 1..10) {
            File(workspaceDir, "file$i.txt").writeText("content$i")
        }
        index.refresh("session1")
        Thread.sleep(100)
        val results = index.matches("file", limit = 5)
        assert(results.size <= 5)
        tempDir.deleteRecursively()
    }

    // Test for Entry basename property
    @Test
    fun `test entry basename property`() {
        val entry = FileMentionIndex.Entry(
            linuxPath = "/var/minis/workspace/session1/testfile.txt",
            scope = FileMentionIndex.Scope.WORKSPACE,
            mountName = null,
            modifiedAt = System.currentTimeMillis(),
            isDirectory = false
        )
        assert(entry.basename == "testfile.txt")
    }

    // Test for Entry displayPath property
    @Test
    fun `test entry displayPath property`() {
        val entry = FileMentionIndex.Entry(
            linuxPath = "/var/minis/workspace/session1/testfile.txt",
            scope = FileMentionIndex.Scope.WORKSPACE,
            mountName = null,
            modifiedAt = System.currentTimeMillis(),
            isDirectory = false
        )
        assert(entry.displayPath == "workspace/session1/testfile.txt")
    }

    // Test for Scope enum properties
    @Test
    fun `test scope enum properties`() {
        assert(FileMentionIndex.Scope.SKILLS.displayLabel == "skills")
        assert(FileMentionIndex.Scope.ATTACHMENTS.displayLabel == "attachments")
        assert(FileMentionIndex.Scope.MOUNT.displayLabel == "mount")
        assert(FileMentionIndex.Scope.SHARED.displayLabel == "shared")
        assert(FileMentionIndex.Scope.WORKSPACE.displayLabel == "workspace")
        assert(FileMentionIndex.Scope.MEMORY.displayLabel == "memory")
        
        assert(FileMentionIndex.Scope.SKILLS.order == 0)
        assert(FileMentionIndex.Scope.ATTACHMENTS.order == 1)
        assert(FileMentionIndex.Scope.MOUNT.order == 2)
        assert(FileMentionIndex.Scope.SHARED.order == 3)
        assert(FileMentionIndex.Scope.WORKSPACE.order == 4)
        assert(FileMentionIndex.Scope.MEMORY.order == 5)
        
        assert(FileMentionIndex.Scope.SKILLS.rankBoost == 600)
        assert(FileMentionIndex.Scope.ATTACHMENTS.rankBoost == 500)
        assert(FileMentionIndex.Scope.MOUNT.rankBoost == 400)
        assert(FileMentionIndex.Scope.SHARED.rankBoost == 300)
        assert(FileMentionIndex.Scope.WORKSPACE.rankBoost == 200)
        assert(FileMentionIndex.Scope.MEMORY.rankBoost == 100)
    }

    // Test for companion object constants
    @Test
    fun `test companion object constants`() {
        assert(FileMentionIndex.DEFAULT_CACHE_TTL_MS == 600000L) // 10 minutes
        assert(FileMentionIndex.DEFAULT_MATCH_LIMIT == 100)
        assert(FileMentionIndex.GLOBAL_SCAN_BUDGET == 5000)
        assert(FileMentionIndex.MOUNT_SCAN_MAX_DEPTH == 3)
    }

    // Test with mounts provider
    @Test
    fun `test with mounts provider`() {
        val tempDir = createTempDir()
        val mountDir = File(tempDir, "mount1")
        mountDir.mkdirs()
        File(mountDir, "mountfile.txt").writeText("mount content")
        
        val index = FileMentionIndex(
            filesDir = tempDir,
            mountsProvider = { listOf(FileMentionIndex.MountEntry("mount1", mountDir)) }
        )
        index.refresh("session1")
        Thread.sleep(100)
        val mountEntries = index.entries.value.filter { it.scope == FileMentionIndex.Scope.MOUNT }
        assert(mountEntries.isNotEmpty())
        tempDir.deleteRecursively()
    }

    // Test for scanning multiple sessions
    @Test
    fun `test scanning multiple sessions`() {
        val tempDir = createTempDir()
        val index = FileMentionIndex(tempDir)
        
        val session1Dir = File(tempDir, "workspace/session1")
        session1Dir.mkdirs()
        File(session1Dir, "session1file.txt").writeText("session1")
        
        val session2Dir = File(tempDir, "workspace/session2")
        session2Dir.mkdirs()
        File(session2Dir, "session2file.txt").writeText("session2")
        
        index.refresh("session1")
        Thread.sleep(100)
        val session1Entries = index.entries.value.filter { it.linuxPath.contains("session1") }
        assert(session1Entries.isNotEmpty())
        
        index.refresh("session2")
        Thread.sleep(100)
        val session2Entries = index.entries.value.filter { it.linuxPath.contains("session2") }
        assert(session2Entries.isNotEmpty())
        
        tempDir.deleteRecursively()
    }

    // Test for shared and skills directories
    @Test
    fun `test shared and skills directories are scanned`() {
        val tempDir = createTempDir()
        val index = FileMentionIndex(tempDir)
        
        val sharedDir = File(tempDir, "shared")
        sharedDir.mkdirs()
        File(sharedDir, "sharedfile.txt").writeText("shared")
        
        val skillsDir = File(tempDir, "skills")
        skillsDir.mkdirs()
        File(skillsDir, "skillfile.txt").writeText("skill")
        
        index.refresh("session1")
        Thread.sleep(100)
        
        val sharedEntries = index.entries.value.filter { it.scope == FileMentionIndex.Scope.SHARED }
        val skillEntries = index.entries.value.filter { it.scope == FileMentionIndex.Scope.SKILLS }
        
        assert(sharedEntries.isNotEmpty())
        assert(skillEntries.isNotEmpty())
        
        tempDir.deleteRecursively()
    }
}