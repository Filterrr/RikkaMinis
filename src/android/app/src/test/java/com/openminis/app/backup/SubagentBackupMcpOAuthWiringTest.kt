package com.openminis.app.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import com.openminis.app.tools.SubagentSourceText
import java.io.File

/**
 * [feat/subagent-roster-routing] The MCP OAuth material must survive a
 * backup round-trip.
 *
 * The wire format has carried `mcpOAuth` for a while, and
 * `MCPOAuthStore` has held the tokens — but nothing ever joined them.
 * The exporter did not write the field and the importer did not read it,
 * so a backup carried every provider key and every environment variable
 * and NONE of the MCP authorizations. A restored backup silently left
 * every MCP server needing a fresh OAuth round-trip.
 *
 * This is source-level because the exporter and the importer need
 * Android's Context and Base64, which the local JVM script cannot
 * provide.
 */
class SubagentBackupMcpOAuthWiringTest {

    private fun source(rel: String): String? {
        val root = com.openminis.app.ProductionSources.mainRoot() ?: return null
        val f = File(root, rel)
        if (!f.isFile) return null
        return SubagentSourceText.stripComments(f.readText())
    }

    @Test
    fun `the exporter writes the MCP OAuth secrets`() {
        val exporter = source("backup/BackupExporter.kt") ?: return
        assertTrue(
            "the exporter must fill the mcpOAuth field of the secrets file",
            exporter.contains("mcpOAuth = mcpSecrets"),
        )
        assertTrue(
            "the exporter must collect the MCP OAuth material",
            exporter.contains("MCPOAuthStore.allServers"),
        )
    }

    @Test
    fun `the importer restores the MCP OAuth secrets`() {
        val importer = source("backup/BackupImporter.kt") ?: return
        assertTrue(
            "the importer must read the mcpOAuth field",
            importer.contains("secrets.mcpOAuth"),
        )
        assertTrue(
            "the importer must write the MCP OAuth material back",
            importer.contains("MCPOAuthStore.restore"),
        )
    }

    /**
     * The store must be able to answer "what do you have at all?" — the
     * export needs to enumerate the servers, and the store only ever
     * answered "what do you have for THIS server".
     */
    @Test
    fun `the OAuth store can enumerate its servers`() {
        val store = source("mcp/oauth/MCPOAuthStore.kt") ?: return
        assertTrue(
            "the store must be able to list the servers it holds material for",
            store.contains("fun allServers(context: Context): List<String>"),
        )
    }

    /**
     * Without the refresh token, a restored authorization works until the
     * access token expires and then stops — with no way to recover,
     * because the refresh token was never in the backup.
     */
    @Test
    fun `the wire format carries the refresh token`() {
        val format = source("backup/BackupFormat.kt") ?: return
        assertTrue(
            "the wire format must carry the refresh token",
            format.contains("refreshToken"),
        )
        assertTrue(
            "the wire format must carry the expiry",
            format.contains("expiresAtMs"),
        )
    }
}
