package com.openminis.app.data.storage

import android.content.Context
import com.openminis.app.data.model.MediaRef
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class MediaStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var mediaStore: MediaStore

    @BeforeEach
    fun setUp() {
        context = Mockito.mock(Context::class.java)
        whenever(context.filesDir).thenReturn(tempDir)
        mediaStore = MediaStore(context)
    }

    @Test
    fun `saveMedia should create file and return MediaRef with correct data`() {
        val data = "test data".toByteArray()
        val mimeType = "image/jpeg"
        val sessionId = "session1"
        val originalFileName = "photo.jpg"

        val ref = mediaStore.saveMedia(data, mimeType, sessionId, originalFileName)

        assertNotNull(ref)
        assertTrue(ref.relativePath.endsWith(".jpg"))
        val file = File(tempDir, ref.relativePath)
        assertTrue(file.exists())
        assertArrayEquals(data, file.readBytes())
    }

    @Test
    fun `saveMedia without originalFileName should use extension from mimeType`() {
        val data = "test".toByteArray()
        val mimeType = "image/png"
        val sessionId = "session2"

        val ref = mediaStore.saveMedia(data, mimeType, sessionId)

        assertNotNull(ref)
        assertTrue(ref.relativePath.endsWith(".png"))
        val file = File(tempDir, ref.relativePath)
        assertTrue(file.exists())
    }

    @Test
    fun `saveMediaStreamed should save data and return MediaRef`() {
        val data = "streamed data".toByteArray()
        val inputStream = ByteArrayInputStream(data)
        val mimeType = "application/pdf"
        val sessionId = "session3"
        val originalFileName = "doc.pdf"

        val ref = mediaStore.saveMediaStreamed(inputStream, mimeType, sessionId, originalFileName)

        assertNotNull(ref)
        assertTrue(ref.relativePath.endsWith(".pdf"))
        val file = File(tempDir, ref.relativePath)
        assertTrue(file.exists())
        assertArrayEquals(data, file.readBytes())
    }

    @Test
    fun `saveMediaStreamed with closed stream should return null`() {
        val data = "fail".toByteArray()
        val inputStream = ByteArrayInputStream(data).also { it.close() }
        val mimeType = "text/plain"
        val sessionId = "session4"

        val ref = mediaStore.saveMediaStreamed(inputStream, mimeType, sessionId)

        assertNull(ref)
    }

    @Test
    fun `loadMedia should return bytes for existing file`() {
        val data = "existing".toByteArray()
        val mimeType = "application/octet-stream"
        val sessionId = "session5"
        val ref = mediaStore.saveMedia(data, mimeType, sessionId)

        val loaded = mediaStore.loadMedia(ref)
        assertNotNull(loaded)
        assertArrayEquals(data, loaded)
    }

    @Test
    fun `loadMedia should return null for non-existent file`() {
        val fakeRef = MediaRef(
            id = "nonexistent",
            relativePath = "2023/01/01/sessionX/nonexistent.bin",
            mimeType = "application/octet-stream",
            originalFileName = null
        )
        val loaded = mediaStore.loadMedia(fakeRef)
        assertNull(loaded)
    }

    @Test
    fun `deleteSessionMedia should remove all directories with matching sessionId`() {
        // Create directories and files under different dates
        val sessionDir1 = File(tempDir, "2023/01/01/session1")
        val sessionDir2 = File(tempDir, "2023/01/01/session2")
        val sessionDir3 = File(tempDir, "2023/02/01/session1")
        val file1 = File(sessionDir1, "file1.txt")
        val file2 = File(sessionDir2, "file2.txt")
        val file3 = File(sessionDir3, "file3.txt")
        file1.parentFile?.mkdirs()
        file1.writeText("content1")
        file2.parentFile?.mkdirs()
        file2.writeText("content2")
        file3.parentFile?.mkdirs()
        file3.writeText("content3")

        mediaStore.deleteSessionMedia("session1")

        assertFalse(sessionDir1.exists())
        assertFalse(sessionDir3.exists())
        assertTrue(sessionDir2.exists())
        assertTrue(file2.exists())
    }
}