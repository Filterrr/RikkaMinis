package com.openminis.app.data.storage

import android.content.Context
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.io.File
import java.nio.file.Files

class SessionFileStoreTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var store: SessionFileStore

    @BeforeEach
    fun setUp() {
        context = Mockito.mock(Context::class.java)
        tempDir = Files.createTempDirectory("testStorage").toFile()
        Mockito.`when`(context.filesDir).thenReturn(tempDir)
        store = SessionFileStore(context)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `sessionDir returns correct path`() {
        val sessionId = "12345"
        val expected = File(File(tempDir, "minis-sessions"), sessionId)
        assertEquals(expected, store.sessionDir(sessionId))
    }

    @Test
    fun `sizeOf returns 0 if dir does not exist`() {
        val nonExistentDir = File(tempDir, "nonexistent")
        assertEquals(0L, store.sizeOf(nonExistentDir))
    }

    @Test
    fun `sizeOf calculates total size of files`() {
        val dir = File(tempDir, "sizedir")
        dir.mkdirs()
        File(dir, "file1.txt").writeText("hello")
        File(dir, "sub").mkdirs()
        File(File(dir, "sub"), "file2.txt").writeText("world!")
        assertEquals(11L, store.sizeOf(dir))
    }

    @Test
    fun `mediaSize returns 0 if mediaRoot does not exist`() {
        assertEquals(0L, store.mediaSize("12345"))
    }

    @Test
    fun `mediaSize calculates size for specific session`() {
        val mediaRoot = File(tempDir, "media")
        mediaRoot.mkdirs()
        val sessionId = "12345"
        val sessionMediaDir = File(mediaRoot, sessionId)
        sessionMediaDir.mkdirs()
        File(sessionMediaDir, "media1.dat").writeText("audio")
        File(sessionMediaDir, "media2.dat").writeText("video")
        
        val otherSessionDir = File(mediaRoot, "67890")
        otherSessionDir.mkdirs()
        File(otherSessionDir, "media3.dat").writeText("other")

        assertEquals(10L, store.mediaSize(sessionId))
    }

    @Test
    fun `sessionSubdirSizes returns empty map if dir does not exist`() {
        assertTrue(store.sessionSubdirSizes("nonexistent").isEmpty())
    }

    @Test
    fun `sessionSubdirSizes returns sizes of subdirectories`() {
        val sessionId = "12345"
        val sessionDir = store.sessionDir(sessionId)
        sessionDir.mkdirs()
        
        val sub1 = File(sessionDir, "sub1")
        sub1.mkdirs()
        File(sub1, "f1.txt").writeText("123")
        
        val sub2 = File(sessionDir, "sub2")
        sub2.mkdirs()
        File(sub2, "f2.txt").writeText("12345")

        val sizes = store.sessionSubdirSizes(sessionId)
        assertEquals(2, sizes.size)
        assertEquals(3L, sizes["sub1"])
        assertEquals(5L, sizes["sub2"])
    }

    @Test
    fun `deleteSessionFiles removes session dir and media`() {
        val sessionId = "12345"
        val sessionDir = store.sessionDir(sessionId)
        sessionDir.mkdirs()
        File(sessionDir, "file.txt").writeText("data")

        val mediaRoot = File(tempDir, "media")
        val sessionMediaDir = File(mediaRoot, sessionId)
        sessionMediaDir.mkdirs()
        File(sessionMediaDir, "media.dat").writeText("media")

        store.deleteSessionFiles(sessionId)

        assertFalse(sessionDir.exists())
        assertFalse(sessionMediaDir.exists())
    }

    @Test
    fun `scanOrphans identifies orphan sessions and media`() {
        val liveId = "11111111-1111-1111-1111-111111111111"
        val orphanId = "22222222-2222-2222-2222-222222222222"
        
        val liveDir = store.sessionDir(liveId)
        liveDir.mkdirs()
        File(liveDir, "live.txt").writeText("live")

        val orphanDir = store.sessionDir(orphanId)
        orphanDir.mkdirs()
        File(orphanDir, "orphan.txt").writeText("orphan")

        val mediaRoot = File(tempDir, "media")
        mediaRoot.mkdirs()
        
        val liveMedia = File(mediaRoot, liveId)
        liveMedia.mkdirs()
        File(liveMedia, "m.dat").writeText("liveMedia")

        val orphanMedia = File(mediaRoot, orphanId)
        orphanMedia.mkdirs()
        File(orphanMedia, "m.dat").writeText("orphanMedia")

        val report = store.scanOrphans(setOf(liveId))
        
        assertEquals(1, report.sessionDirs)
        assertEquals(6L, report.sessionBytes)
        assertEquals(11L, report.mediaBytes)
        assertTrue(report.sessionIds.contains(orphanId))
    }

    @Test
    fun `reclaimOrphans deletes orphan sessions and media`() {
        val liveId = "11111111-1111-1111-1111-111111111111"
        val orphanId = "22222222-2222-2222-2222-222222222222"
        
        val orphanDir = store.sessionDir(orphanId)
        orphanDir.mkdirs()
        File(orphanDir, "orphan.txt").writeText("orphan")

        val mediaRoot = File(tempDir, "media")
        val orphanMedia = File(mediaRoot, orphanId)
        orphanMedia.mkdirs()
        File(orphanMedia, "m.dat").writeText("orphanMedia")

        val report = store.reclaimOrphans(setOf(liveId))
        
        assertFalse(orphanDir.exists())
        assertFalse(orphanMedia.exists())
        assertEquals(1, report.sessionDirs)
        assertEquals(6L, report.sessionBytes)
        assertEquals(11L, report.mediaBytes)
    }

    @Test
    fun `mediaSizesBySessionBrief returns sizes for given sessions`() {
        val sessionId1 = "11111111-1111-1111-1111-111111111111"
        val sessionId2 = "22222222-2222-2222-2222-222222222222"
        
        val mediaRoot = File(tempDir, "media")
        mediaRoot.mkdirs()
        
        val media1 = File(mediaRoot, sessionId1)
        media1.mkdirs()
        File(media1, "m1.dat").writeText("12345")

        val media2 = File(mediaRoot, sessionId2)
        media2.mkdirs()
        File(media2, "m2.dat").writeText("123")

        val sizes = store.mediaSizesBySessionBrief(setOf(sessionId1, sessionId2))
        assertEquals(2, sizes.size)
        assertEquals(5L, sizes[sessionId1])
        assertEquals(3L, sizes[sessionId2])
    }
}