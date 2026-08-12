package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.File
import java.util.*
import kotlin.test.*

@ExtendWith(MockKExtension::class)
class PhotosOffloadHandlerTest {

    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockContentResolver: ContentResolver

    @MockK
    private lateinit var mockCursor: Cursor

    private lateinit var handler: PhotosOffloadHandler

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        every { mockContext.contentResolver } returns mockContentResolver
        every { mockContext.filesDir } returns File("/tmp/test-files")
        mockkStatic(android.os.Build::class)
        mockkStatic(ContextCompat::class)
        mockkStatic(OffloadPermissionManager::class)
        mockkStatic(AppLogger::class)
        every { AppLogger.info(any(), any()) } returns Unit
        every { AppLogger.warning(any(), any()) } returns Unit
        handler = PhotosOffloadHandler(mockContext)
    }

    @Test
    fun `handle returns help when positional args empty`() {
        val request = createRequest("android-photos")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("Usage"))
    }

    @Test
    fun `handle returns help with help flag`() {
        val request = createRequest("android-photos", "--help")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.body.contains("Usage"))
    }

    @Test
    fun `handle returns help with h flag`() {
        val request = createRequest("android-photos", "-h")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.body.contains("Usage"))
    }

    @Test
    fun `handle returns unknown subcommand error`() {
        val request = createRequest("android-photos", "unknown")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("unknown subcommand"))
    }

    @Test
    fun `handle enforces gate`() {
        mockkStatic(OffloadGate::class)
        every { OffloadGate.enforce(any(), any(), any(), any()) } returns NativeOffloadResult(99, "gate blocked")
        val request = createRequest("android-photos", "list")
        val result = handler.handle(request)
        assertEquals(99, result.exitCode)
        assertEquals("gate blocked", result.body)
    }

    @Test
    fun `handle ensures media permission`() {
        mockkStatic(OffloadGate::class)
        every { OffloadGate.enforce(any(), any(), any(), any()) } returns null
        mockHasMediaPermission(false)
        mockPermissionRequest(OffloadPermissionManager.AndroidPermissionResult.DENIED)
        val request = createRequest("android-photos", "list")
        val result = handler.handle(request)
        assertEquals(77, result.exitCode)
        assertTrue(result.body.contains("permission_denied"))
    }

    @Test
    fun `handle list with type photo`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "list", "--type", "photo", "--limit", "5")
        mockMediaQuery()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertEquals("photo", json.getString("type"))
        assertTrue(json.getInt("count") >= 0)
    }

    @Test
    fun `handle list with type video`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "list", "--type", "video", "--limit", "5")
        mockMediaQuery()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertEquals("video", json.getString("type"))
    }

    @Test
    fun `handle list with type all`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "list", "--type", "all", "--limit", "5")
        mockMediaQuery()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertEquals("all", json.getString("type"))
    }

    @Test
    fun `handle list with invalid type`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "list", "--type", "invalid")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("type must be photo|video|all"))
    }

    @Test
    fun `handle list with days range`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "list", "--days", "7", "--limit", "5")
        mockMediaQuery()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertTrue(json.has("range_start"))
        assertTrue(json.has("range_end"))
    }

    @Test
    fun `handle list with start and end dates`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "list", "--start", "2023-01-01", "--end", "2023-12-31", "--limit", "5")
        mockMediaQuery()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertTrue(json.has("range_start"))
        assertTrue(json.has("range_end"))
    }

    @Test
    fun `handle stats`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "stats")
        mockStatsQuery()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.body.contains("total_photos"))
    }

    @Test
    fun `handle near missing lat`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "near", "--lon", "10.0")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("lat") && result.body.contains("lon"))
    }

    @Test
    fun `handle near missing lon`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "near", "--lat", "50.0")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("lat") && result.body.contains("lon"))
    }

    @Test
    fun `handle near with positional args`() {
        mockSuccessfulPermissionCheck()
        mockMediaLocationPermission(true)
        val request = createRequest("android-photos", "near", "50.0", "10.0", "--radius", "10")
        mockNearQuery()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `handle near requires media location permission`() {
        mockSuccessfulPermissionCheck()
        mockMediaLocationPermission(false)
        mockPermissionRequest(OffloadPermissionManager.AndroidPermissionResult.DENIED)
        val request = createRequest("android-photos", "near", "--lat", "50.0", "--lon", "10.0")
        val result = handler.handle(request)
        assertEquals(77, result.exitCode)
    }

    @Test
    fun `handle albums with type smart`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "albums", "--type", "smart")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertEquals(0, json.getInt("count"))
        assertTrue(json.has("note"))
    }

    @Test
    fun `handle albums with type user`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "albums", "--type", "user")
        mockAlbumQuery()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertTrue(json.getInt("count") >= 0)
    }

    @Test
    fun `handle albums with type all`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "albums", "--type", "all")
        mockAlbumQuery()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertTrue(json.getInt("count") >= 0)
    }

    @Test
    fun `handle albums with invalid type`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "albums", "--type", "invalid")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("type must be user|smart|all"))
    }

    @Test
    fun `handle album with id`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "album", "--id", "123")
        mockAlbumMediaQuery()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertTrue(json.getInt("count") >= 0)
        assertEquals(123, json.getJSONObject("album").getLong("id"))
    }

    @Test
    fun `handle album with name`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "album", "--name", "Camera")
        mockAlbumMediaQuery()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertTrue(json.getInt("count") >= 0)
        assertEquals("Camera", json.getJSONObject("album").getString("name"))
    }

    @Test
    fun `handle album without id or name`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "album")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("id") && result.body.contains("name"))
    }

    @Test
    fun `handle export with id`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "export", "--id", "456", "--size", "original")
        mockExportQuery()
        mockExportStream()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertEquals(456, json.getLong("id"))
        assertTrue(json.has("host_path"))
    }

    @Test
    fun `handle export without id`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "export")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("id"))
    }

    @Test
    fun `handle export with invalid size`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "export", "--id", "456", "--size", "invalid")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("size must be"))
    }

    @Test
    fun `handle export with thumb size`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "export", "--id", "456", "--size", "thumb")
        mockExportQuery()
        mockExportStream()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertEquals("thumb", json.getString("export_size"))
    }

    @Test
    fun `handle export with medium size`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "export", "--id", "456", "--size", "medium")
        mockExportQuery()
        mockExportStream()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertEquals("medium", json.getString("export_size"))
    }

    @Test
    fun `handle export when asset not found`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "export", "--id", "999")
        every { mockContentResolver.query(any(), any(), any(), any(), any()) } returns null
        val result = handler.handle(request)
        assertEquals(1, result.exitCode)
        assertTrue(result.body.contains("not_found"))
    }

    @Test
    fun `handle import without path`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "import")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("path"))
    }

    @Test
    fun `handle import with non-existent file`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "import", "--path", "/nonexistent/file.jpg")
        val result = handler.handle(request)
        assertEquals(1, result.exitCode)
        assertTrue(result.body.contains("no_file"))
    }

    @Test
    fun `handle create-album without name`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "create-album")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("name"))
    }

    @Test
    fun `handle create-album with name`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "create-album", "--name", "Test Album")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertEquals("Test Album", json.getString("name"))
        assertFalse(json.getBoolean("created"))
    }

    @Test
    fun `handle favorite without id`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "favorite")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("id"))
    }

    @Test
    fun `handle favorite on pre-Android 11`() {
        mockSuccessfulPermissionCheck()
        mockBuildVersion(Build.VERSION_CODES.Q)
        val request = createRequest("android-photos", "favorite", "--id", "456")
        val result = handler.handle(request)
        assertEquals(1, result.exitCode)
        assertTrue(result.body.contains("not_supported"))
    }

    @Test
    fun `handle favorite on Android 11+`() {
        mockSuccessfulPermissionCheck()
        mockBuildVersion(Build.VERSION_CODES.R)
        mockFavoriteQuery()
        val request = createRequest("android-photos", "favorite", "--id", "456")
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertEquals(456, json.getLong("id"))
        assertTrue(json.getBoolean("favorite"))
    }

    @Test
    fun `handle favorite when asset not found`() {
        mockSuccessfulPermissionCheck()
        mockBuildVersion(Build.VERSION_CODES.R)
        every { mockContentResolver.query(any(), any(), any(), any(), any()) } returns null
        val request = createRequest("android-photos", "favorite", "--id", "999")
        val result = handler.handle(request)
        assertEquals(1, result.exitCode)
        assertTrue(result.body.contains("not_found"))
    }

    @Test
    fun `handle delete without ids`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "delete")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("ids"))
    }

    @Test
    fun `handle delete without confirm flag`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "delete", "--ids", "1,2,3")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("confirm"))
    }

    @Test
    fun `handle delete with confirm`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "delete", "--ids", "1,2,3", "--confirm")
        mockDeleteQuery()
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.body)
        assertEquals(3, json.getInt("requested_count"))
    }

    @Test
    fun `handle delete with empty ids`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "delete", "--ids", "", "--confirm")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("numeric id"))
    }

    @Test
    fun `handle delete with invalid ids`() {
        mockSuccessfulPermissionCheck()
        val request = createRequest("android-photos", "delete", "--ids", "abc,def", "--confirm")
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("numeric id"))
    }

    @Test
    fun `handle throws SecurityException`() {
        mockkStatic(Off