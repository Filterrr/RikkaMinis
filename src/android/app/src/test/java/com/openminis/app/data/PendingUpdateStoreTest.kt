package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.PendingUpdateStore.PendingUpdate
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class PendingUpdateStoreTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("pending_update", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        PendingUpdateStore.init(context)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun setPending_savesUpdateToPrefs() {
        val pending = PendingUpdate(
            targetVersionName = "1.0.0",
            apkPath = "/path/to/apk",
            apkSize = 1024L,
            sha256 = "abc123",
            downloadedAtMs = System.currentTimeMillis()
        )

        PendingUpdateStore.setPending(context, pending)

        val raw = prefs.getString("pending", null)
        assert(raw != null)
        assert(raw!!.contains("targetVersionName"))
        assert(raw.contains("1.0.0"))
        assert(raw.contains("/path/to/apk"))
        assert(raw.contains("1024"))
        assert(raw.contains("abc123"))
    }

    @Test
    fun getPending_returnsSavedUpdate() {
        val pending = PendingUpdate(
            targetVersionName = "2.0.0",
            apkPath = "/path/to/apk2",
            apkSize = 2048L,
            sha256 = "def456",
            downloadedAtMs = System.currentTimeMillis()
        )

        PendingUpdateStore.setPending(context, pending)
        val result = PendingUpdateStore.getPending(context)

        assert(result != null)
        assert(result!!.targetVersionName == "2.0.0")
        assert(result.apkPath == "/path/to/apk2")
        assert(result.apkSize == 2048L)
        assert(result.sha256 == "def456")
    }

    @Test
    fun getPending_returnsNull_whenNoPendingUpdate() {
        prefs.edit().clear().commit()
        val result = PendingUpdateStore.getPending(context)
        assert(result == null)
    }

    @Test
    fun getPending_returnsNull_whenExpired() {
        val expiredTime = System.currentTimeMillis() - (25L * 60L * 60L * 1000L)
        val pending = PendingUpdate(
            targetVersionName = "3.0.0",
            apkPath = "/path/to/apk3",
            apkSize = 4096L,
            sha256 = "ghi789",
            downloadedAtMs = expiredTime
        )

        PendingUpdateStore.setPending(context, pending)
        val result = PendingUpdateStore.getPending(context)

        assert(result == null)
        assert(prefs.getString("pending", null) == null)
    }

    @Test
    fun clearPending_removesSavedUpdate() {
        val pending = PendingUpdate(
            targetVersionName = "4.0.0",
            apkPath = "/path/to/apk4",
            apkSize = 8192L,
            sha256 = "jkl012",
            downloadedAtMs = System.currentTimeMillis()
        )

        PendingUpdateStore.setPending(context, pending)
        PendingUpdateStore.clearPending(context)

        assert(prefs.getString("pending", null) == null)
    }

    @Test
    fun verify_returnsFile_whenValid() {
        val testFile = File(context.cacheDir, "test_apk.apk")
        testFile.writeBytes(ByteArray(1024))

        val pending = PendingUpdate(
            targetVersionName = "5.0.0",
            apkPath = testFile.absolutePath,
            apkSize = 1024L,
            sha256 = null,
            downloadedAtMs = System.currentTimeMillis()
        )

        val result = PendingUpdateStore.verify(pending)
        assert(result != null)
        assert(result!!.absolutePath == testFile.absolutePath)

        testFile.delete()
    }

    @Test
    fun verify_returnsNull_whenFileMissing() {
        val pending = PendingUpdate(
            targetVersionName = "6.0.0",
            apkPath = "/nonexistent/path/to/apk",
            apkSize = 1024L,
            sha256 = null,
            downloadedAtMs = System.currentTimeMillis()
        )

        val result = PendingUpdateStore.verify(pending)
        assert(result == null)
    }

    @Test
    fun verify_returnsNull_whenSizeMismatch() {
        val testFile = File(context.cacheDir, "test_apk2.apk")
        testFile.writeBytes(ByteArray(2048))

        val pending = PendingUpdate(
            targetVersionName = "7.0.0",
            apkPath = testFile.absolutePath,
            apkSize = 1024L,
            sha256 = null,
            downloadedAtMs = System.currentTimeMillis()
        )

        val result = PendingUpdateStore.verify(pending)
        assert(result == null)

        testFile.delete()
    }

    @Test
    fun verify_returnsNull_whenSha256Mismatch() {
        val testFile = File(context.cacheDir, "test_apk3.apk")
        testFile.writeBytes(ByteArray(1024))

        val pending = PendingUpdate(
            targetVersionName = "8.0.0",
            apkPath = testFile.absolutePath,
            apkSize = 1024L,
            sha256 = "wronghash",
            downloadedAtMs = System.currentTimeMillis()
        )

        val result = PendingUpdateStore.verify(pending)
        assert(result == null)

        testFile.delete()
    }

    @Test
    fun verify_returnsFile_whenSha256Matches() {
        val testFile = File(context.cacheDir, "test_apk4.apk")
        testFile.writeBytes(ByteArray(1024))
        val expectedHash = PendingUpdateStore.sha256(testFile)

        val pending = PendingUpdate(
            targetVersionName = "9.0.0",
            apkPath = testFile.absolutePath,
            apkSize = 1024L,
            sha256 = expectedHash,
            downloadedAtMs = System.currentTimeMillis()
        )

        val result = PendingUpdateStore.verify(pending)
        assert(result != null)
        assert(result!!.absolutePath == testFile.absolutePath)

        testFile.delete()
    }

    @Test
    fun sha256_returnsCorrectHash() {
        val testFile = File(context.cacheDir, "test_sha256.txt")
        testFile.writeText("Hello World")

        val hash = PendingUpdateStore.sha256(testFile)
        assert(hash == "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e")

        testFile.delete()
    }

    @Test
    fun getPending_withNullSha256() {
        val pending = PendingUpdate(
            targetVersionName = "10.0.0",
            apkPath = "/path/to/apk10",
            apkSize = 1024L,
            sha256 = null,
            downloadedAtMs = System.currentTimeMillis()
        )

        PendingUpdateStore.setPending(context, pending)
        val result = PendingUpdateStore.getPending(context)

        assert(result != null)
        assert(result!!.sha256 == null)
    }

    @Test
    fun getPending_withMalformedJson() {
        prefs.edit().putString("pending", "invalid json").commit()
        val result = PendingUpdateStore.getPending(context)

        assert(result == null)
        assert(prefs.getString("pending", null) == null)
    }
}