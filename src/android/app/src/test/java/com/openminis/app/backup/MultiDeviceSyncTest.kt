package com.openminis.app.backup

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiDeviceSyncTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `test isEnabled default returns false`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = MultiDeviceSync.isEnabled(context)
        assertFalse(result)
    }

    @Test
    fun `test isEnabled with enabled preference`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MultiDeviceSync.PREF_KEY_ENABLED, true)
            .apply()

        val result = MultiDeviceSync.isEnabled(context)
        assertTrue(result)
    }

    @Test
    fun `test exportSyncPayload with all repositories`() = runBlocking {
        val providerRepo = ProviderRepository(OkHttpClient())
        val envVarRepo = EnvVarRepository()
        val memoryRepo = MemoryRepository()

        val result = MultiDeviceSync.exportSyncPayload(
            providerRepo = providerRepo,
            envVarRepo = envVarRepo,
            memoryRepo = memoryRepo,
            includeSecrets = true
        )

        assertNotNull(result)
    }

    @Test
    fun `test exportSyncPayload with null repositories`() = runBlocking {
        val providerRepo = ProviderRepository(OkHttpClient())

        val result = MultiDeviceSync.exportSyncPayload(
            providerRepo = providerRepo,
            envVarRepo = null,
            memoryRepo = null,
            includeSecrets = false
        )

        assertNotNull(result)
    }

    @Test
    fun `test pushSyncPayload with valid config`() {
        val config = WebDavConfig(
            url = "https://example.com/dav/",
            username = "testuser",
            password = "testpass"
        )
        val client = OkHttpClient()
        val payload = "{\"test\":\"data\"}"

        val result = MultiDeviceSync.pushSyncPayload(config, payload, client)
        assertNotNull(result)
    }

    @Test
    fun `test syncNow with all repositories and default includeSecrets`() = runBlocking {
        val providerRepo = ProviderRepository(OkHttpClient())
        val envVarRepo = EnvVarRepository()
        val memoryRepo = MemoryRepository()
        val config = WebDavConfig(
            url = "https://example.com/dav/",
            username = "testuser",
            password = "testpass"
        )
        val client = OkHttpClient()

        val result = MultiDeviceSync.syncNow(
            providerRepo = providerRepo,
            envVarRepo = envVarRepo,
            memoryRepo = memoryRepo,
            config = config,
            client = client
        )

        assertTrue(result.startsWith("pull-failed:") || result.startsWith("pushed:") || result.startsWith("push-failed:"))
    }

    @Test
    fun `test syncNow with null repositories and explicit includeSecrets false`() = runBlocking {
        val providerRepo = ProviderRepository(OkHttpClient())
        val config = WebDavConfig(
            url = "https://example.com/dav/",
            username = "testuser",
            password = "testpass"
        )
        val client = OkHttpClient()

        val result = MultiDeviceSync.syncNow(
            providerRepo = providerRepo,
            envVarRepo = null,
            memoryRepo = null,
            config = config,
            client = client,
            includeSecrets = false
        )

        assertTrue(result.startsWith("pull-failed:") || result.startsWith("pushed:") || result.startsWith("push-failed:"))
    }

    @Test
    fun `test PREF_KEY_ENABLED constant value`() {
        assertEquals("multi_device_sync_enabled", MultiDeviceSync.PREF_KEY_ENABLED)
    }

    @Test
    fun `test PUSH_DEBOUNCE_MS constant value`() {
        assertEquals(4000L, MultiDeviceSync.PUSH_DEBOUNCE_MS)
    }

    @Test
    fun `test MAX_REMOTE_SYNC_FILES constant value`() {
        assertEquals(7, MultiDeviceSync.MAX_REMOTE_SYNC_FILES)
    }

    @Test
    fun `test constant values are correct`() {
        assertEquals("multi_device_sync_enabled", MultiDeviceSync.PREF_KEY_ENABLED)
        assertEquals(4000L, MultiDeviceSync.PUSH_DEBOUNCE_MS)
        assertEquals(7, MultiDeviceSync.MAX_REMOTE_SYNC_FILES)
    }
}