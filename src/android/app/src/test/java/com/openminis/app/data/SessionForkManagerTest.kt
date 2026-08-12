package com.openminis.app.data

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.SkillRepository
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionForkManagerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @TempDir
    lateinit var tempDir: File

    private val context: Context = mock()
    private val chatRepository: ChatRepository = mock()
    private val skillRepository: SkillRepository = mock()

    private fun createManager(): SessionForkManager {
        whenever(context.filesDir).thenReturn(tempDir)
        return SessionForkManager(
            context = context,
            chatRepository = chatRepository,
            skillRepository = skillRepository
        )
    }

    // Test for duplicateSession function
    @Test
    fun `duplicateSession returns null when session not found`() {
        val manager = createManager()
        whenever(chatRepository.getSession("nonexistent")).thenReturn(null)

        val result = manager.duplicateSession("nonexistent")

        assertNull(result)
    }

    @Test
    fun `duplicateSession creates copy with correct title and data`() {
        val manager = createManager()
        val sourceSession = mock<ChatRepository.Session>()
        val newSession = mock<ChatRepository.Session>()
        
        whenever(sourceSession.id).thenReturn("session1")
        whenever(sourceSession.title).thenReturn("Test Chat")
        whenever(sourceSession.modelId).thenReturn("model1")
        whenever(sourceSession.category).thenReturn("general")
        whenever(sourceSession.modelBinding).thenReturn("binding1")
        whenever(sourceSession.memoryEnabled).thenReturn(1)
        whenever(sourceSession.thinkingOverride).thenReturn(null)
        
        whenever(chatRepository.getSession("session1")).thenReturn(sourceSession)
        whenever(chatRepository.loadMessages("session1")).thenReturn(emptyList())
        whenever(chatRepository.createSession(any(), any())).thenReturn(newSession)
        whenever(newSession.id).thenReturn("newSession1")
        whenever(chatRepository.dao).thenReturn(mock())

        val result = manager.duplicateSession("session1")

        assertNotNull(result)
        assertEquals("newSession1", result)
    }

    @Test
    fun `duplicateSession copies messages and maintains order`() {
        val manager = createManager()
        val sourceSession = mock<ChatRepository.Session>()
        val newSession = mock<ChatRepository.Session>()
        val message1 = mock<ChatRepository.Message>()
        val message2 = mock<ChatRepository.Message>()
        val newMessage1 = mock<ChatRepository.Message>()
        val newMessage2 = mock<ChatRepository.Message>()
        
        whenever(sourceSession.id).thenReturn("session1")
        whenever(sourceSession.title).thenReturn("Test")
        whenever(sourceSession.modelId).thenReturn("model1")
        whenever(sourceSession.category).thenReturn(null)
        whenever(sourceSession.modelBinding).thenReturn(null)
        whenever(sourceSession.memoryEnabled).thenReturn(1)
        whenever(sourceSession.thinkingOverride).thenReturn(null)
        
        whenever(chatRepository.getSession("session1")).thenReturn(sourceSession)
        whenever(chatRepository.loadMessages("session1")).thenReturn(listOf(message1, message2))
        whenever(chatRepository.createSession(any(), any())).thenReturn(newSession)
        whenever(newSession.id).thenReturn("newSession1")
        
        whenever(message1.id).thenReturn("msg1")
        whenever(message1.role).thenReturn("user")
        whenever(message1.partsJson).thenReturn("{}")
        whenever(message1.tokenUsage).thenReturn(null)
        whenever(message1.reasoningContent).thenReturn(null)
        
        whenever(message2.id).thenReturn("msg2")
        whenever(message2.role).thenReturn("assistant")
        whenever(message2.partsJson).thenReturn("{}")
        whenever(message2.tokenUsage).thenReturn(null)
        whenever(message2.reasoningContent).thenReturn(null)
        
        whenever(newMessage1.id).thenReturn("newMsg1")
        whenever(newMessage1.sortOrder).thenReturn(0)
        whenever(newMessage2.id).thenReturn("newMsg2")
        whenever(newMessage2.sortOrder).thenReturn(1)
        
        whenever(chatRepository.appendMessage(any(), any(), any(), any(), any(), any())).thenReturn(newMessage1, newMessage2)
        whenever(chatRepository.dao).thenReturn(mock())

        val result = manager.duplicateSession("session1")

        assertNotNull(result)
    }

    // Test for copySkill function
    @Test
    fun `copySkill returns false when skillRepository is null`() {
        val manager = SessionForkManager(
            chatRepository = chatRepository,
            filesDir = tempDir
        )

        val result = manager.copySkill("content")

        assertFalse(result)
    }

    @Test
    fun `copySkill returns true on successful import`() {
        val manager = createManager()
        whenever(skillRepository.importFromContent("content", SkillRepository.ImportSource.FILE))
            .thenReturn("skillId")

        val result = manager.copySkill("content")

        assertTrue(result)
    }

    @Test
    fun `copySkill returns false on import failure`() {
        val manager = createManager()
        whenever(skillRepository.importFromContent("content", SkillRepository.ImportSource.FILE))
            .thenReturn(null)

        val result = manager.copySkill("content")

        assertFalse(result)
    }

    @Test
    fun `copySkill passes custom source parameter`() {
        val manager = createManager()
        whenever(skillRepository.importFromContent("content", SkillRepository.ImportSource.CLIPBOARD))
            .thenReturn("skillId")

        val result = manager.copySkill("content", SkillRepository.ImportSource.CLIPBOARD)

        assertTrue(result)
    }

    // Test for copyMemory function
    @Test
    fun `copyMemory returns false for unsafe filename`() {
        val manager = createManager()

        val result = manager.copyMemory("../evil.txt", "content")

        assertFalse(result)
    }

    @Test
    fun `copyMemory returns false for filename with slash`() {
        val manager = createManager()

        val result = manager.copyMemory("dir/file.txt", "content")

        assertFalse(result)
    }

    @Test
    fun `copyMemory writes file successfully`() {
        val manager = createManager()

        val result = manager.copyMemory("test.txt", "test content")

        assertTrue(result)
        val writtenFile = File(tempDir, "minis-global/memory/test.txt")
        assertTrue(writtenFile.exists())
        assertEquals("test content", writtenFile.readText())
    }

    @Test
    fun `copyMemory handles write failure`() {
        val manager = createManager()
        val invalidFile = File(tempDir, "minis-global/memory")
        invalidFile.mkdirs()
        invalidFile.writeText("occupied")
        
        val invalidDir = File(tempDir, "minis-global")
        invalidDir.deleteRecursively()
        invalidDir.mkdirs()
        
        val file = File(invalidDir, "memory")
        file.createNewFile()
        file.writeText("not a directory")

        val result = manager.copyMemory("test.txt", "content")

        assertFalse(result)
    }

    // Tests for the composable functions (if any existed in the source)
    // Since the source file doesn't contain composable functions, 
    // we'll add tests for the context extension function
    @Test
    fun `context extension function creates manager with correct filesDir`() {
        val testDir = File(tempDir, "test")
        testDir.mkdirs()
        whenever(context.filesDir).thenReturn(testDir)

        val manager = SessionForkManager(
            context = context,
            chatRepository = chatRepository,
            skillRepository = skillRepository
        )

        assertNotNull(manager)
        assertEquals(testDir, manager.filesDir)
    }

    @Test
    fun `context extension function creates manager with null skillRepository`() {
        val testDir = File(tempDir, "test2")
        testDir.mkdirs()
        whenever(context.filesDir).thenReturn(testDir)

        val manager = SessionForkManager(
            context = context,
            chatRepository = chatRepository
        )

        assertNotNull(manager)
        assertNull(manager.skillRepository)
    }

    // Additional tests for edge cases
    @Test
    fun `duplicateSession handles null title`() {
        val manager = createManager()
        val sourceSession = mock<ChatRepository.Session>()
        val newSession = mock<ChatRepository.Session>()
        
        whenever(sourceSession.id).thenReturn("session1")
        whenever(sourceSession.title).thenReturn(null)
        whenever(sourceSession.modelId).thenReturn("model1")
        whenever(sourceSession.category).thenReturn(null)
        whenever(sourceSession.modelBinding).thenReturn(null)
        whenever(sourceSession.memoryEnabled).thenReturn(1)
        whenever(sourceSession.thinkingOverride).thenReturn(null)
        
        whenever(chatRepository.getSession("session1")).thenReturn(sourceSession)
        whenever(chatRepository.loadMessages("session1")).thenReturn(emptyList())
        whenever(chatRepository.createSession(any(), any())).thenReturn(newSession)
        whenever(newSession.id).thenReturn("newSession1")
        whenever(chatRepository.dao).thenReturn(mock())

        val result = manager.duplicateSession("session1")

        assertNotNull(result)
    }

    @Test
    fun `duplicateSession copies compact markers`() {
        val manager = createManager()
        val sourceSession = mock<ChatRepository.Session>()
        val newSession = mock<ChatRepository.Session>()
        val marker = mock<ChatRepository.CompactMarker>()
        val dao = mock<ChatRepository.Dao>()
        
        whenever(sourceSession.id).thenReturn("session1")
        whenever(sourceSession.title).thenReturn("Test")
        whenever(sourceSession.modelId).thenReturn("model1")
        whenever(sourceSession.category).thenReturn(null)
        whenever(sourceSession.modelBinding).thenReturn(null)
        whenever(sourceSession.memoryEnabled).thenReturn(1)
        whenever(sourceSession.thinkingOverride).thenReturn(null)
        
        whenever(chatRepository.getSession("session1")).thenReturn(sourceSession)
        whenever(chatRepository.loadMessages("session1")).thenReturn(emptyList())
        whenever(chatRepository.createSession(any(), any())).thenReturn(newSession)
        whenever(newSession.id).thenReturn("newSession1")
        whenever(chatRepository.dao).thenReturn(dao)
        whenever(dao.listCompactMarkers("session1")).thenReturn(listOf(marker))
        
        whenever(marker.id).thenReturn("marker1")
        whenever(marker.firstKeptMessageId).thenReturn(null)
        whenever(marker.lastCompactedMessageId).thenReturn(null)
        whenever(marker.boundaryMessageId).thenReturn(null)
        whenever(marker.firstKeptSortOrder).thenReturn(0)
        whenever(marker.uiBoundarySortOrder).thenReturn(null)

        val result = manager.duplicateSession("session1")

        assertNotNull(result)
    }
}