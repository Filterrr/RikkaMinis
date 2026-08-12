package com.openminis.app.data.repository

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var repository: MemoryRepository

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val todayStr: String get() = dateFormat.format(Date())

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        repository = MemoryRepository(tempDir)
    }

    @Test
    fun `memoryDirectory returns the correct directory`() {
        assertEquals(tempDir, repository.memoryDirectory())
    }

    @Test
    fun `init creates the directory if it does not exist`() {
        val newDir = File(tempDir, "subdir")
        assertFalse(newDir.exists())
        val repo = MemoryRepository(newDir)
        assertTrue(newDir.exists())
        assertEquals(newDir, repo.memoryDirectory())
    }

    @Test
    fun `dailyLogSizeSummary returns null when no files exist`() {
        assertNull(repository.dailyLogSizeSummary())
    }

    @Test
    fun `dailyLogSizeSummary returns summary when files exist`() {
        val file1 = File(tempDir, "2023-01-01.md").apply { writeText("a".repeat(100)) }
        val file2 = File(tempDir, "2023-01-02.md").apply { writeText("b".repeat(50)) }
        val summary = repository.dailyLogSizeSummary()
        assertNotNull(summary)
        assertTrue(summary!!.contains("2023-01-01"))
        assertTrue(summary.contains("100 B"))
        assertTrue(summary.contains("total 2 logs"))
    }

    @Test
    fun `dailyLogSizeSummary excludes GLOBAL file and rollup file`() {
        File(tempDir, "GLOBAL.md").apply { writeText("global") }
        File(tempDir, "2023-01-01.md").apply { writeText("test") }
        val summary = repository.dailyLogSizeSummary()
        assertNotNull(summary)
        assertTrue(summary!!.contains("total 1 logs"))
    }

    @Test
    fun `largestDailyLogBytes returns 0 when no files`() {
        assertEquals(0L, repository.largestDailyLogBytes())
    }

    @Test
    fun `largestDailyLogBytes returns largest file size`() {
        File(tempDir, "2023-01-01.md").apply { writeText("a".repeat(100)) }
        File(tempDir, "2023-01-02.md").apply { writeText("b".repeat(200)) }
        assertEquals(200L, repository.largestDailyLogBytes())
    }

    @Test
    fun `writeMemory returns error for blank content`() {
        val result = repository.writeMemory("")
        assertTrue(result.startsWith("Error"))
    }

    @Test
    fun `writeMemory saves content to today's file`() {
        val result = repository.writeMemory("Test content")
        val todayFile = File(tempDir, "$todayStr.md")
        assertTrue(todayFile.exists())
        assertTrue(result.contains("Memory saved to $todayStr.md"))
        assertTrue(todayFile.readText().contains("Test content"))
    }

    @Test
    fun `writeMemory appends to existing file`() {
        repository.writeMemory("First entry")
        repository.writeMemory("Second entry")
        val todayFile = File(tempDir, "$todayStr.md")
        val content = todayFile.readText()
        assertTrue(content.contains("First entry"))
        assertTrue(content.contains("Second entry"))
    }

    @Test
    fun `writeMemory returns error on IO failure`() {
        val readOnlyDir = File(tempDir, "readonly").apply { mkdirs(); setReadOnly() }
        val repo = MemoryRepository(readOnlyDir)
        val result = repo.writeMemory("test")
        assertTrue(result.startsWith("Error writing memory"))
    }

    @Test
    fun `getMemory with empty keywords and all scope returns all files`() {
        File(tempDir, "2023-01-01.md").apply { writeText("Line 1\nLine 2") }
        File(tempDir, "2023-01-02.md").apply { writeText("Line A\nLine B") }
        val result = repository.getMemory("", "all")
        assertTrue(result.contains("2023-01-01.md"))
        assertTrue(result.contains("2023-01-02.md"))
    }

    @Test
    fun `getMemory with keywords filters results`() {
        File(tempDir, "2023-01-01.md").apply { writeText("apple banana cherry") }
        File(tempDir, "2023-01-02.md").apply { writeText("apple date") }
        val result = repository.getMemory("apple banana", "daily")
        assertTrue(result.contains("2023-01-01.md"))
        assertFalse(result.contains("2023-01-02.md"))
    }

    @Test
    fun `getMemory returns no matches message when no keywords found`() {
        File(tempDir, "2023-01-01.md").apply { writeText("apple") }
        val result = repository.getMemory("nonexistent", "daily")
        assertTrue(result.startsWith("No matches found"))
    }

    @Test
    fun `getMemory returns no files message when no files exist`() {
        val result = repository.getMemory("test", "daily")
        assertEquals("No memory files found.", result)
    }

    @Test
    fun `loadGlobalMemoryFragment returns null when GLOBAL file does not exist`() {
        assertNull(repository.loadGlobalMemoryFragment())
    }

    @Test
    fun `loadGlobalMemoryFragment returns null when GLOBAL file is empty`() {
        File(tempDir, "GLOBAL.md").writeText("")
        assertNull(repository.loadGlobalMemoryFragment())
    }

    @Test
    fun `loadGlobalMemoryFragment returns content when GLOBAL file exists`() {
        File(tempDir, "GLOBAL.md").writeText("Important context")
        val result = repository.loadGlobalMemoryFragment()
        assertNotNull(result)
        assertTrue(result!!.contains("Important context"))
        assertTrue(result.contains("GLOBAL.md"))
    }

    @Test
    fun `loadRecentDailyMemoryFragment returns null when no daily files`() {
        assertNull(repository.loadRecentDailyMemoryFragment())
    }

    @Test
    fun `loadRecentDailyMemoryFragment returns content from recent files`() {
        File(tempDir, "$todayStr.md").apply { writeText("Today's memory") }
        val result = repository.loadRecentDailyMemoryFragment()
        assertNotNull(result)
        assertTrue(result!!.contains("Today's daily log"))
        assertTrue(result.contains("Today's memory"))
    }

    @Test
    fun `loadRecentDailyMemoryFragment includes yesterday's file`() {
        val yesterday = dateFormat.format(Date(System.currentTimeMillis() - 86400000))
        File(tempDir, "$yesterday.md").apply { writeText("Yesterday's memory") }
        val result = repository.loadRecentDailyMemoryFragment()
        assertNotNull(result)
        assertTrue(result!!.contains("Yesterday's daily log"))
    }

    @Test
    fun `listAllFiles returns global file even when it does not exist`() {
        val files = repository.listAllFiles()
        assertTrue(files.any { it.isGlobal })
    }

    @Test
    fun `listAllFiles returns daily files sorted by name descending`() {
        File(tempDir, "2023-01-02.md").writeText("content")
        File(tempDir, "2023-01-01.md").writeText("content")
        val files = repository.listAllFiles()
        val dailyFiles = files.filter { !it.isGlobal }
        assertEquals(2, dailyFiles.size)
        assertEquals("2023-01-02.md", dailyFiles[0].name)
        assertEquals("2023-01-01.md", dailyFiles[1].name)
    }

    @Test
    fun `loadGlobalMd returns empty string when GLOBAL file does not exist`() {
        assertEquals("", repository.loadGlobalMd())
    }

    @Test
    fun `loadGlobalMd returns content when GLOBAL file exists`() {
        File(tempDir, "GLOBAL.md").writeText("test content")
        assertEquals("test content", repository.loadGlobalMd())
    }

    @Test
    fun `saveGlobalMd writes content to GLOBAL file`() {
        repository.saveGlobalMd("saved content")
        assertEquals("saved content", File(tempDir, "GLOBAL.md").readText())
    }

    @Test
    fun `readFile returns content of existing file`() {
        File(tempDir, "test.md").writeText("file content")
        assertEquals("file content", repository.readFile("test.md"))
    }

    @Test
    fun `readFile returns empty string for non-existent file`() {
        assertEquals("", repository.readFile("nonexistent.md"))
    }

    @Test
    fun `saveFile writes content to specified file`() {
        repository.saveFile("custom.md", "custom content")
        assertEquals("custom content", File(tempDir, "custom.md").readText())
    }

    @Test
    fun `deleteFile returns false for GLOBAL file`() {
        File(tempDir, "GLOBAL.md").writeText("global")
        assertFalse(repository.deleteFile("GLOBAL.md"))
        assertTrue(File(tempDir, "GLOBAL.md").exists())
    }

    @Test
    fun `deleteFile deletes non-global file and returns true`() {
        File(tempDir, "test.md").writeText("test")
        assertTrue(repository.deleteFile("test.md"))
        assertFalse(File(tempDir, "test.md").exists())
    }

    @Test
    fun `deleteFile returns false for non-existent file`() {
        assertFalse(repository.deleteFile("nonexistent.md"))
    }

    @Test
    fun `revokeEntry returns NotFound when content does not exist`() {
        val result = repository.revokeEntry("nonexistent content")
        assertTrue(result is MemoryRepository.EntryMutationResult.NotFound)
    }

    @Test
    fun `revokeEntry removes matching entry from today's file`() {
        repository.writeMemory("Entry to revoke")
        repository.writeMemory("Keep this entry")
        val result = repository.revokeEntry("Entry to revoke")
        assertTrue(result is MemoryRepository.EntryMutationResult.Success)
        assertEquals(todayStr, (result as MemoryRepository.EntryMutationResult.Success).dateStr)
        val fileContent = File(tempDir, "$todayStr.md").readText()
        assertFalse(fileContent.contains("Entry to revoke"))
        assertTrue(fileContent.contains("Keep this entry"))
    }

    @Test
    fun `replaceEntryBody returns NotFound when old content does not exist`() {
        val result = repository.replaceEntryBody("old", "new")
        assertTrue(result is MemoryRepository.EntryMutationResult.NotFound)
    }

    @Test
    fun `replaceEntryBody replaces matching entry body`() {
        repository.writeMemory("Original content")
        val result = repository.replaceEntryBody("Original content", "Replaced content")
        assertTrue(result is MemoryRepository.EntryMutationResult.Success)
        assertEquals(todayStr, (result as MemoryRepository.EntryMutationResult.Success).dateStr)
        val fileContent = File(tempDir, "$todayStr.md").readText()
        assertFalse(fileContent.contains("Original content"))
        assertTrue(fileContent.contains("Replaced content"))
    }

    @Test
    fun `revokeEntry returns IOError on write failure`() {
        repository.writeMemory("Test content")
        val readOnlyDir = File(tempDir, "readonly").apply { mkdirs(); setReadOnly() }
        val repo = MemoryRepository(readOnlyDir)
        val result = repo.revokeEntry("Test content")
        assertTrue(result is MemoryRepository.EntryMutationResult.IOError)
    }

    @Test
    fun `replaceEntryBody returns IOError on write failure`() {
        repository.writeMemory("Original content")
        val readOnlyDir = File(tempDir, "readonly").apply { mkdirs(); setReadOnly() }
        val repo = MemoryRepository(readOnlyDir)
        val result = repo.replaceEntryBody("Original content", "New content")
        assertTrue(result is MemoryRepository.EntryMutationResult.IOError)
    }
}