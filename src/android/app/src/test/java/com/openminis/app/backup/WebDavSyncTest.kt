package com.openminis.app.backup

import androidx.compose.ui.test.junit4.createComposeRule
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

class WebDavSyncTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `testConnection verifies connection with default client`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)
            mockedStatic.`when`<WebDavClient> { WebDavClient.defaultClient() }.thenReturn(mockClient)

            WebDavSync.testConnection(config, mockClient)
            Mockito.verify(mockWebDavClient).testConnection()
        }
    }

    @Test
    fun `testConnection uses default client when not provided`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val mockWebDavClient = mock<WebDavClient>()

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, any()) }.thenReturn(mockWebDavClient)
            mockedStatic.`when`<WebDavClient> { WebDavClient.defaultClient() }.thenReturn(mock())

            WebDavSync.testConnection(config)
            Mockito.verify(mockWebDavClient).testConnection()
        }
    }

    @Test
    fun `backup creates file with correct prefix and suffix`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val payload = "{\"test\":\"data\"}"
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)

            WebDavSync.backup(config, payload, mockClient)

            Mockito.verify(mockWebDavClient).ensureCollectionExists()
            Mockito.verify(mockWebDavClient).put(
                Mockito.matches("^${WebDavSync.BACKUP_PREFIX}.*${WebDavSync.BACKUP_SUFFIX}$"),
                any(),
                Mockito.eq("application/json")
            )
        }
    }

    @Test
    fun `backup uses default client when not provided`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val payload = "test"
        val mockWebDavClient = mock<WebDavClient>()

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, any()) }.thenReturn(mockWebDavClient)
            mockedStatic.`when`<WebDavClient> { WebDavClient.defaultClient() }.thenReturn(mock())

            WebDavSync.backup(config, payload)
            Mockito.verify(mockWebDavClient).ensureCollectionExists()
        }
    }

    @Test
    fun `listBackupFiles returns sorted backup files`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        val now = Instant.now()
        val items = listOf(
            WebDavResource(
                href = "/backup-20240101-120000.json",
                displayName = "rikkaminis-backup-20240101-120000.json",
                isCollection = false,
                contentLength = 100L,
                lastModified = now
            ),
            WebDavResource(
                href = "/backup-20230101-120000.json",
                displayName = "openminis-backup-20230101-120000.json",
                isCollection = false,
                contentLength = 200L,
                lastModified = now.minusSeconds(31536000)
            ),
            WebDavResource(
                href = "/collection/",
                displayName = "collection",
                isCollection = true,
                contentLength = 0L,
                lastModified = now
            )
        )

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)
            whenever(mockWebDavClient.list()).thenReturn(items)

            val result = WebDavSync.listBackupFiles(config, mockClient)

            assertTrue(result.isNotEmpty())
            assertTrue(result.all { it.displayName.startsWith(WebDavSync.BACKUP_PREFIX) || it.displayName.startsWith(WebDavSync.LEGACY_BACKUP_PREFIX) })
            assertTrue(result.all { it.displayName.endsWith(WebDavSync.BACKUP_SUFFIX) })
            assertTrue(result.zipWithNext().all { it.first.lastModified >= it.second.lastModified })
        }
    }

    @Test
    fun `listBackupFiles returns empty list when no backup files`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)
            whenever(mockWebDavClient.list()).thenReturn(emptyList())

            val result = WebDavSync.listBackupFiles(config, mockClient)

            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `restore retrieves file content`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val item = WebDavBackupItem(
            href = "/rikkaminis-backup-20240101-120000.json",
            displayName = "rikkaminis-backup-20240101-120000.json",
            size = 100L,
            lastModified = Instant.now()
        )
        val expectedContent = "{\"test\":\"data\"}"
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)
            whenever(mockWebDavClient.get(item.displayName)).thenReturn(expectedContent.toByteArray())

            val result = WebDavSync.restore(config, item, mockClient)

            assertNotNull(result)
            assertTrue(result.contains("test"))
            assertTrue(result.contains("data"))
        }
    }

    @Test
    fun `deleteBackupFile removes file from WebDAV`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val item = WebDavBackupItem(
            href = "/rikkaminis-backup-20240101-120000.json",
            displayName = "rikkaminis-backup-20240101-120000.json",
            size = 100L,
            lastModified = Instant.now()
        )
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)

            WebDavSync.deleteBackupFile(config, item, mockClient)

            Mockito.verify(mockWebDavClient).delete(item.displayName)
        }
    }

    @Test
    fun `deleteBackupFile with subdir uses subdir path`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val item = WebDavBackupItem(
            href = "/sync/rikkaminis-sync-20240101-120000.json",
            displayName = "rikkaminis-sync-20240101-120000.json",
            size = 100L,
            lastModified = Instant.now()
        )
        val subdir = "sync"
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)

            WebDavSync.deleteBackupFile(config, item, mockClient, subdir)

            Mockito.verify(mockWebDavClient).delete("$subdir/${item.displayName}")
        }
    }

    @Test
    fun `pushSync creates sync file in sync subdirectory`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val payload = "{\"sync\":\"data\"}"
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)

            val result = WebDavSync.pushSync(config, payload, mockClient)

            Mockito.verify(mockWebDavClient).ensureCollectionExists()
            Mockito.verify(mockWebDavClient).ensureCollectionExists(WebDavSync.SYNC_SUBDIR)
            Mockito.verify(mockWebDavClient).put(
                Mockito.matches("^${WebDavSync.SYNC_SUBDIR}/${WebDavSync.SYNC_PREFIX}.*${WebDavSync.BACKUP_SUFFIX}$"),
                any(),
                Mockito.eq("application/json")
            )
            assertNotNull(result)
            assertTrue(result.startsWith(WebDavSync.SYNC_PREFIX))
            assertTrue(result.endsWith(WebDavSync.BACKUP_SUFFIX))
        }
    }

    @Test
    fun `listSyncFiles returns sorted sync files`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        val now = Instant.now()
        val items = listOf(
            WebDavResource(
                href = "/sync/rikkaminis-sync-20240101-120000.json",
                displayName = "rikkaminis-sync-20240101-120000.json",
                isCollection = false,
                contentLength = 100L,
                lastModified = now
            ),
            WebDavResource(
                href = "/sync/rikkaminis-sync-20230101-120000.json",
                displayName = "rikkaminis-sync-20230101-120000.json",
                isCollection = false,
                contentLength = 200L,
                lastModified = now.minusSeconds(31536000)
            )
        )

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)
            whenever(mockWebDavClient.list(WebDavSync.SYNC_SUBDIR)).thenReturn(items)

            val result = WebDavSync.listSyncFiles(config, mockClient)

            assertTrue(result.isNotEmpty())
            assertTrue(result.all { it.displayName.startsWith(WebDavSync.SYNC_PREFIX) })
            assertTrue(result.all { it.displayName.endsWith(WebDavSync.BACKUP_SUFFIX) })
            assertTrue(result.zipWithNext().all { it.first.lastModified >= it.second.lastModified })
        }
    }

    @Test
    fun `listSyncFiles returns empty list when 404`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)
            whenever(mockWebDavClient.list(WebDavSync.SYNC_SUBDIR)).thenThrow(WebDavException(404))

            val result = WebDavSync.listSyncFiles(config, mockClient)

            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `listSyncFiles throws on non-404 error`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)
            whenever(mockWebDavClient.list(WebDavSync.SYNC_SUBDIR)).thenThrow(WebDavException(500))

            assertThrows<WebDavException> {
                WebDavSync.listSyncFiles(config, mockClient)
            }
        }
    }

    @Test
    fun `pullLatestSync returns latest sync file content`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        val now = Instant.now()
        val items = listOf(
            WebDavResource(
                href = "/sync/rikkaminis-sync-20240101-120000.json",
                displayName = "rikkaminis-sync-20240101-120000.json",
                isCollection = false,
                contentLength = 100L,
                lastModified = now
            )
        )
        val expectedContent = "{\"sync\":\"latest\"}"

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)
            whenever(mockWebDavClient.list(WebDavSync.SYNC_SUBDIR)).thenReturn(items)
            whenever(mockWebDavClient.get("${WebDavSync.SYNC_SUBDIR}/${items[0].displayName}")).thenReturn(expectedContent.toByteArray())

            val result = WebDavSync.pullLatestSync(config, mockClient)

            assertNotNull(result)
            assertTrue(result!!.contains("sync"))
            assertTrue(result.contains("latest"))
        }
    }

    @Test
    fun `pullLatestSync returns null when no sync files`() {
        val config = WebDavConfig(
            url = "https://example.com",
            username = "user",
            password = "pass"
        )
        val mockClient = mock<OkHttpClient>()
        val mockWebDavClient = mock<WebDavClient>()

        Mockito.mockStatic(WebDavClient::class.java).use { mockedStatic ->
            mockedStatic.`when`<WebDavClient> { WebDavClient(config, mockClient) }.thenReturn(mockWebDavClient)
            whenever(mockWebDavClient.list(WebDavSync.SYNC_SUBDIR)).thenReturn(emptyList())

            val result = WebDavSync.pullLatestSync(config, mockClient)

            assertTrue(result == null)
        }
    }

    @Test
    fun `backup file prefix constant is correct`() {
        assertTrue(WebDavSync.BACKUP_PREFIX == "rikkaminis-backup-")
    }

    @Test
    fun `legacy backup prefix constant is correct`() {
        assertTrue(WebDavSync.LEGACY_BACKUP_PREFIX == "openminis-backup-")
    }

    @Test
    fun `sync prefix constant is correct`() {
        assertTrue(WebDavSync.SYNC_PREFIX == "rikkaminis-sync-")
    }

    @Test
    fun `backup suffix constant is correct`() {
        assertTrue(WebDavSync.BACKUP_SUFFIX == ".json")
    }

    @Test
    fun `sync subdir constant is correct`() {
        assertTrue(WebDavSync.SYNC_SUBDIR == "sync")
    }
}