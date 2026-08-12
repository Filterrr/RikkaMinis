package com.openminis.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.openminis.app.mcp.oauth.MCPOAuthConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MCPRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var repository: MCPRepository
    private lateinit var mcpDir: File
    private lateinit var serversFile: File

    @BeforeEach
    fun setUp() {
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempDir
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0

        mcpDir = File(tempDir, "minis-global/mcp-servers")
        mcpDir.mkdirs()
        serversFile = File(mcpDir, "servers.json")

        repository = MCPRepository(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `load returns empty list when servers file does not exist`() = runTest {
        val servers = repository.servers.first()
        assertTrue(servers.isEmpty())
    }

    @Test
    fun `load returns empty list when servers file is empty`() = runTest {
        serversFile.writeText("")
        repository.load()
        val servers = repository.servers.first()
        assertTrue(servers.isEmpty())
    }

    @Test
    fun `load returns empty list when servers file contains invalid JSON`() = runTest {
        serversFile.writeText("invalid json")
        repository.load()
        val servers = repository.servers.first()
        assertTrue(servers.isEmpty())
    }

    @Test
    fun `load returns empty list when servers file does not have mcpServers key`() = runTest {
        serversFile.writeText("""{"other": {}}""")
        repository.load()
        val servers = repository.servers.first()
        assertTrue(servers.isEmpty())
    }

    @Test
    fun `load parses servers correctly`() = runTest {
        val json = """
            {
                "mcpServers": {
                    "server1": {
                        "command": "python",
                        "args": ["script.py"],
                        "enabled": true,
                        "createdAt": 1000
                    },
                    "server2": {
                        "url": "http://example.com",
                        "enabled": false,
                        "createdAt": 2000
                    }
                }
            }
        """.trimIndent()
        serversFile.writeText(json)
        repository.load()
        val servers = repository.servers.first()
        assertEquals(2, servers.size)
        assertEquals("server2", servers[0].id) // sorted by createdAt desc
        assertEquals("server1", servers[1].id)
    }

    @Test
    fun `reloadFromDisk reloads servers from file`() = runTest {
        val json = """
            {
                "mcpServers": {
                    "server1": {
                        "command": "python",
                        "createdAt": 1000
                    }
                }
            }
        """.trimIndent()
        serversFile.writeText(json)
        repository.reloadFromDisk()
        val servers = repository.servers.first()
        assertEquals(1, servers.size)
        assertEquals("server1", servers[0].id)
    }

    @Test
    fun `add inserts a new server`() = runTest {
        val server = MCPRepository.MCPServerConfig(
            id = "test-server",
            command = "python",
            args = listOf("script.py"),
            createdAt = 1000
        )
        val result = repository.add(server)
        assertTrue(result)
        val servers = repository.servers.first()
        assertEquals(1, servers.size)
        assertEquals("test-server", servers[0].id)
    }

    @Test
    fun `add returns false for blank id`() = runTest {
        val server = MCPRepository.MCPServerConfig(
            id = "",
            command = "python",
            createdAt = 1000
        )
        val result = repository.add(server)
        assertFalse(result)
        val servers = repository.servers.first()
        assertTrue(servers.isEmpty())
    }

    @Test
    fun `add replaces existing server with same id`() = runTest {
        val server1 = MCPRepository.MCPServerConfig(
            id = "test-server",
            command = "python",
            createdAt = 1000
        )
        repository.add(server1)
        val server2 = MCPRepository.MCPServerConfig(
            id = "test-server",
            command = "node",
            createdAt = 2000
        )
        repository.add(server2)
        val servers = repository.servers.first()
        assertEquals(1, servers.size)
        assertEquals("node", servers[0].command)
    }

    @Test
    fun `update modifies existing server`() = runTest {
        val server = MCPRepository.MCPServerConfig(
            id = "test-server",
            command = "python",
            createdAt = 1000
        )
        repository.add(server)
        val updatedServer = server.copy(command = "node", url = "http://example.com")
        val result = repository.update(updatedServer)
        assertTrue(result)
        val servers = repository.servers.first()
        assertEquals(1, servers.size)
        assertEquals("node", servers[0].command)
        assertEquals("http://example.com", servers[0].url)
    }

    @Test
    fun `update returns false for non-existent server`() = runTest {
        val server = MCPRepository.MCPServerConfig(
            id = "non-existent",
            command = "python",
            createdAt = 1000
        )
        val result = repository.update(server)
        assertFalse(result)
    }

    @Test
    fun `delete removes a server`() = runTest {
        val server = MCPRepository.MCPServerConfig(
            id = "test-server",
            command = "python",
            createdAt = 1000
        )
        repository.add(server)
        repository.delete("test-server")
        val servers = repository.servers.first()
        assertTrue(servers.isEmpty())
    }

    @Test
    fun `setEnabled updates server enabled state`() = runTest {
        val server = MCPRepository.MCPServerConfig(
            id = "test-server",
            command = "python",
            enabled = true,
            createdAt = 1000
        )
        repository.add(server)
        repository.setEnabled("test-server", false)
        val servers = repository.servers.first()
        assertFalse(servers[0].enabled)
    }

    @Test
    fun `toggle flips enabled state`() = runTest {
        val server = MCPRepository.MCPServerConfig(
            id = "test-server",
            command = "python",
            enabled = true,
            createdAt = 1000
        )
        repository.add(server)
        repository.toggle("test-server")
        var servers = repository.servers.first()
        assertFalse(servers[0].enabled)
        repository.toggle("test-server")
        servers = repository.servers.first()
        assertTrue(servers[0].enabled)
    }

    @Test
    fun `toggle does nothing for non-existent server`() = runTest {
        val server = MCPRepository.MCPServerConfig(
            id = "test-server",
            command = "python",
            enabled = true,
            createdAt = 1000
        )
        repository.add(server)
        repository.toggle("non-existent")
        val servers = repository.servers.first()
        assertEquals(1, servers.size)
        assertTrue(servers[0].enabled)
    }

    @Test
    fun `exportServerJSON returns valid JSON`() = runTest {
        val server = MCPRepository.MCPServerConfig(
            id = "test-server",
            command = "python",
            args = listOf("script.py"),
            enabled = true,
            createdAt = 1000
        )
        val json = repository.exportServerJSON(server)
        val root = JSONObject(json)
        assertTrue(root.has("mcpServers"))
        val mcpServers = root.getJSONObject("mcpServers")
        assertTrue(mcpServers.has("test-server"))
        val entry = mcpServers.getJSONObject("test-server")
        assertEquals("python", entry.getString("command"))
    }

    @Test
    fun `importJSON imports servers from JSON`() = runTest {
        val json = """
            {
                "mcpServers": {
                    "imported-server": {
                        "command": "python",
                        "args": ["script.py"],
                        "enabled": true
                    }
                }
            }
        """.trimIndent()
        val imported = repository.importJSON(json)
        assertEquals(1, imported.size)
        assertEquals("imported-server", imported[0].id)
        val servers = repository.servers.first()
        assertEquals(1, servers.size)
        assertEquals("imported-server", servers[0].id)
    }

    @Test
    fun `importJSON returns empty list for invalid JSON`() = runTest {
        val imported = repository.importJSON("invalid json")
        assertTrue(imported.isEmpty())
    }

    @Test
    fun `importJSON merges with existing servers`() = runTest {
        val existingServer = MCPRepository.MCPServerConfig(
            id = "existing-server",
            command = "node",
            createdAt = 1000
        )
        repository.add(existingServer)
        val json = """
            {
                "mcpServers": {
                    "new-server": {
                        "command": "python",
                        "createdAt": 2000
                    }
                }
            }
        """.trimIndent()
        repository.importJSON(json)
        val servers = repository.servers.first()
        assertEquals(2, servers.size)
    }

    @Test
    fun `importJSON replaces existing server with same id`() = runTest {
        val existingServer = MCPRepository.MCPServerConfig(
            id = "test-server",
            command = "node",
            createdAt = 1000
        )
        repository.add(existingServer)
        val json = """
            {
                "mcpServers": {
                    "test-server": {
                        "command": "python",
                        "createdAt": 2000
                    }
                }
            }
        """.trimIndent()
        repository.importJSON(json)
        val servers = repository.servers.first()
        assertEquals(1, servers.size)
        assertEquals("python", servers[0].command)
    }

    @Test
    fun `previewImport returns count of importable servers`() = runTest {
        val json = """
            {
                "mcpServers": {
                    "server1": {
                        "command": "python"
                    },
                    "server2": {
                        "url": "http://example.com"
                    }
                }
            }
        """.trimIndent()
        val count = repository.previewImport(json)
        assertEquals(2, count)
    }

    @Test
    fun `previewImport returns 0 for invalid JSON`() = runTest {
        val count = repository.previewImport("invalid")
        assertEquals(0, count)
    }

    @Test
    fun `mcpPromptFragment returns null when no servers enabled`() = runTest {
        val sessionId = "test-session"
        val fragment = repository.mcpPromptFragment(sessionId)
        assertNull(fragment)
    }

    @Test
    fun `mcpPromptFragment returns formatted string with enabled servers`() = runTest {
        val server = MCPRepository.MCPServerConfig(
            id = "test-server",
            command = "python",
            note = "Test note",
            enabled = true,
            createdAt = 1000
        )
        repository.add(server)
        val sessionId = "test-session"
        val fragment = repository.mcpPromptFragment(sessionId)
        assertNotNull(fragment)
        assertTrue(fragment!!.contains("test-server"))
        assertTrue(fragment.contains("Test note"))
    }

    @Test
    fun `mcpPromptFragment truncates long notes`() = runTest {
        val longNote = "A".repeat(300)
        val server = MCPRepository.MCPServerConfig(
            id = "test-server",
            command = "python",
            note = longNote,
            enabled = true,
            createdAt = 1000
        )
        repository.add(server)
        val sessionId = "test-session"
        val fragment = repository.mcpPromptFragment(sessionId)
        assertNotNull(fragment)
        assertTrue(fragment!!.contains("…"))
    }

    @Test
    fun `MCPServerConfig isStdio returns true when command is set`() {
        val server = MCPRepository.MCPServerConfig(
            id = "test",
            command = "python",
            createdAt = 1000
        )
        assertTrue(server.isStdio)
    }

    @Test
    fun `MCPServerConfig isStdio returns false when command is null`() {
        val server = MCPRepository.MCPServerConfig(
            id = "test",
            url = "http://example.com",
            createdAt = 1000
        )
        assertFalse(server.isStdio)
    }

    @Test
    fun `MCPServerConfig transportSummary returns command with args for stdio`() {
        val server = MCPRepository.MCPServerConfig(
            id = "test",
            command = "python",
            args = listOf("script.py", "--flag"),
            createdAt = 1000
        )
        assertEquals("python script.py --flag", server.transportSummary)
    }

    @Test
    fun `MCPServerConfig transportSummary returns url for non-stdio`() {
        val server = MCPRepository.MCPServerConfig(
            id = "test",
            url = "http://example.com",
            createdAt = 1000
        )
        assertEquals("http://example.com", server.transportSummary)
    }
}