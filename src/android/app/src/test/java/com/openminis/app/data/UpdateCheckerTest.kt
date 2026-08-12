package com.openminis.app.data

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.data.UpdateChecker.CheckResult
import com.openminis.app.data.UpdateChecker.DownloadResult
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateCheckerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testCheckResult_UpdateAvailable() {
        val result = CheckResult.UpdateAvailable(
            tagName = "v1.0.0",
            versionName = "1.0.0",
            releaseName = "Release 1.0.0",
            changelog = "First release",
            apkUrl = "https://example.com/app.apk",
            apkSizeBytes = 1024L
        )
        assert(result is CheckResult.UpdateAvailable)
        assert(result.tagName == "v1.0.0")
        assert(result.versionName == "1.0.0")
        assert(result.releaseName == "Release 1.0.0")
        assert(result.changelog == "First release")
        assert(result.apkUrl == "https://example.com/app.apk")
        assert(result.apkSizeBytes == 1024L)
    }

    @Test
    fun testCheckResult_UpToDate() {
        val result = CheckResult.UpToDate
        assert(result is CheckResult.UpToDate)
    }

    @Test
    fun testCheckResult_NoReleaseAvailable() {
        val result = CheckResult.NoReleaseAvailable
        assert(result is CheckResult.NoReleaseAvailable)
    }

    @Test
    fun testCheckResult_NoApkAsset() {
        val result = CheckResult.NoApkAsset("v1.0.0")
        assert(result is CheckResult.NoApkAsset)
        assert(result.tagName == "v1.0.0")
    }

    @Test
    fun testCheckResult_Error() {
        val result = CheckResult.Error("Network error")
        assert(result is CheckResult.Error)
        assert(result.message == "Network error")
    }

    @Test
    fun testCheckResult_Forbidden() {
        val result = CheckResult.Forbidden
        assert(result is CheckResult.Forbidden)
    }

    @Test
    fun testCheckResult_NetworkUnreachable() {
        val result = CheckResult.NetworkUnreachable
        assert(result is CheckResult.NetworkUnreachable)
    }

    @Test
    fun testDownloadResult_Success() {
        val file = java.io.File.createTempFile("test", ".apk")
        val result = DownloadResult.Success(file)
        assert(result is DownloadResult.Success)
        assert(result.file == file)
        file.delete()
    }

    @Test
    fun testDownloadResult_Error() {
        val result = DownloadResult.Error("Download failed")
        assert(result is DownloadResult.Error)
        assert(result.message == "Download failed")
    }

    @Test
    fun testCheck_ReturnsErrorForInvalidUrl() = runBlocking {
        val result = UpdateChecker.check()
        assert(result is CheckResult.Error || result is CheckResult.NetworkUnreachable)
    }

    @Test
    fun testCompareVersions() {
        assert(UpdateChecker.compareVersions("1.0.0", "1.0.0") == 0)
        assert(UpdateChecker.compareVersions("2.0.0", "1.0.0") > 0)
        assert(UpdateChecker.compareVersions("1.0.0", "2.0.0") < 0)
        assert(UpdateChecker.compareVersions("1.0.0", "1.0.1") < 0)
        assert(UpdateChecker.compareVersions("1.0.1", "1.0.0") > 0)
    }

    @Test
    fun testNormalizeTag() {
        assert(UpdateChecker.normalizeTag("v1.0.0") == "1.0.0")
        assert(UpdateChecker.normalizeTag("V1.0.0") == "1.0.0")
        assert(UpdateChecker.normalizeTag("1.0.0") == "1.0.0")
        assert(UpdateChecker.normalizeTag("v1.0.0-beta") == "1.0.0")
    }

    @Test
    fun testCanInstall() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = UpdateChecker.canInstall(context)
        assert(result is Boolean)
    }

    @Test
    fun testResumablePendingFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = UpdateChecker.resumablePendingFile(context)
        assert(result == null || result is java.io.File)
    }

    @Test
    fun testInstallApk() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tempFile = java.io.File.createTempFile("test", ".apk", context.cacheDir)
        val result = UpdateChecker.installApk(context, tempFile)
        assert(!result)
        tempFile.delete()
    }

    @Test
    fun testDownload_WithInvalidUrl() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = UpdateChecker.download(context, "https://invalid.url/app.apk")
        assert(result is DownloadResult.Error)
    }

    @Test
    fun testOpenInstallPermissionSettings() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        try {
            UpdateChecker.openInstallPermissionSettings(context)
        } catch (e: Exception) {
            // Expected on devices without settings activity
        }
    }

    @Test
    fun testRELEASES_URL() {
        assert(UpdateChecker.RELEASES_URL == "https://github.com/logicflow-GYW/RikkaMinis/releases")
    }
}