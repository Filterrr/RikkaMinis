package com.openminis.app.backup

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.util.EncryptedPrefsFactory
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WebDavConfigStoreTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var store: WebDavConfigStore

    @BeforeEach
    fun setUp() {
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        mockkStatic(EncryptedPrefsFactory::class)
        every { EncryptedPrefsFactory.safeCreate(any(), any()) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.clear() } returns editor
        every { editor.apply() } just Runs

        store = WebDavConfigStore(context)
    }

    @Test
    fun `load returns null when url is null`() {
        every { prefs.getString("url", null) } returns null

        assertNull(store.load())
    }

    @Test
    fun `load returns config with defaults when only url present`() {
        every { prefs.getString("url", null) } returns "https://example.com/dav"
        every { prefs.getString("username", "") } returns ""
        every { prefs.getString("password", "") } returns ""
        every { prefs.getString("path", WebDavConfig.DEFAULT_BACKUP_DIR) } returns null

        val config = store.load()
        assertNotNull(config)
        assertEquals("https://example.com/dav", config!!.url)
        assertEquals("", config.username)
        assertEquals("", config.password)
        assertEquals(WebDavConfig.DEFAULT_BACKUP_DIR, config.path)
    }

    @Test
    fun `load returns full config`() {
        every { prefs.getString("url", null) } returns "https://example.com/dav"
        every { prefs.getString("username", "") } returns "user1"
        every { prefs.getString("password", "") } returns "pass1"
        every { prefs.getString("path", WebDavConfig.DEFAULT_BACKUP_DIR) } returns "/backups"

        val config = store.load()
        assertNotNull(config)
        assertEquals("https://example.com/dav", config!!.url)
        assertEquals("user1", config.username)
        assertEquals("pass1", config.password)
        assertEquals("/backups", config.path)
    }

    @Test
    fun `save stores trimmed values`() {
        every { prefs.getString("url", null) } returns null

        store.save(
            WebDavConfig(
                url = "  https://example.com/dav  ",
                username = "  user1  ",
                password = "  pass1  ",
                path = "  /backups  ",
            )
        )

        verify { editor.putString("url", "https://example.com/dav") }
        verify { editor.putString("username", "user1") }
        verify { editor.putString("password", "pass1") }
        verify { editor.putString("path", "/backups") }
        verify { editor.apply() }
    }

    @Test
    fun `save uses default path when path is blank`() {
        every { prefs.getString("url", null) } returns null

        store.save(
            WebDavConfig(
                url = "https://example.com/dav",
                username = "user1",
                password = "pass1",
                path = "   ",
            )
        )

        verify { editor.putString("path", WebDavConfig.DEFAULT_BACKUP_DIR) }
    }

    @Test
    fun `save keeps previous password when new password is blank`() {
        every { prefs.getString("url", null) } returns "https://example.com/dav"
        every { prefs.getString("username", "") } returns "user1"
        every { prefs.getString("password", "") } returns "oldpass"
        every { prefs.getString("path", WebDavConfig.DEFAULT_BACKUP_DIR) } returns "/backups"

        store.save(
            WebDavConfig(
                url = "https://example.com/dav",
                username = "user1",
                password = "",
                path = "/backups",
            )
        )

        verify { editor.putString("password", "oldpass") }
    }

    @Test
    fun `save uses empty password when new password blank and no previous config`() {
        every { prefs.getString("url", null) } returns null

        store.save(
            WebDavConfig(
                url = "https://example.com/dav",
                username = "user1",
                password = "",
                path = "/backups",
            )
        )

        verify { editor.putString("password", "") }
    }

    @Test
    fun `save applies all fields`() {
        every { prefs.getString("url", null) } returns null

        store.save(
            WebDavConfig(
                url = "https://example.com/dav",
                username = "user1",
                password = "pass1",
                path = "/backups",
            )
        )

        verify(exactly = 1) { editor.putString("url", "https://example.com/dav") }
        verify(exactly = 1) { editor.putString("username", "user1") }
        verify(exactly = 1) { editor.putString("password", "pass1") }
        verify(exactly = 1) { editor.putString("path", "/backups") }
        verify(exactly = 1) { editor.apply() }
    }

    @Test
    fun `clear clears and applies editor`() {
        store.clear()

        verify(exactly = 1) { editor.clear() }
        verify(exactly = 1) { editor.apply() }
    }
}