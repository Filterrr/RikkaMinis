package com.openminis.app.backup

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-backup-scope-picker] The WebDAV upload lets the user choose what the
 * pushed document contains. Two halves have to hold:
 *
 *  1. the picker's keys and the exporter's gates agree — a key that gates
 *     nothing (or a section reading a different key) silently ships a section
 *     the user unchecked, which is a privacy problem, not a cosmetic one (a
 *     user who unchecks Providers does not want their API keys on a
 *     third-party server);
 *  2. every gated section is genuinely optional on import — the importer
 *     reads each with optJSONArray and skips nulls, so a scoped document
 *     restores cleanly and simply leaves the omitted sections alone.
 *
 * ConfigBackup.export needs Android repositories to run, so the first half is
 * pinned as a source-level contract (the same technique the other backup
 * source-grep tests use).
 */
class BackupScopeGatingTest {

    private val src by lazy { ProductionSources.read("backup/ConfigBackup.kt") }

    // -- The key set --------------------------------------------------------

    @Test
    fun `scope keys are stable persisted strings`() {
        for (key in listOf("settings", "providers", "envVars", "skills", "memory", "mcp", "chats")) {
            assertTrue("BackupScope must define '$key'", src.contains("\"$key\""))
        }
    }

    @Test
    fun `the default is everything`() {
        assertTrue(
            "DEFAULT must be ALL.toSet()",
            src.contains("val DEFAULT: Set<String> = ALL.toSet()"),
        )
        assertTrue(
            "null categories must mean no gating",
            src.contains("categories == null || key in categories"),
        )
    }

    // -- Gate / section agreement -------------------------------------------

    @Test
    fun `every gate names a real scope key`() {
        val gates = Regex("""wanted\(BackupScope\.([A-Z_]+)\)""").findAll(src)
            .map { it.groupValues[1] }.toSet()
        val declared = Regex("""const val ([A-Z_]+) = """).findAll(src)
            .map { it.groupValues[1] }.toSet()
        val unknown = gates - declared
        assertTrue("gates referencing undeclared scopes: $unknown", unknown.isEmpty())
        assertTrue("no gates found at all", gates.isNotEmpty())
    }

    @Test
    fun `the header-driven sections are gated`() {
        val expectations = mapOf(
            "SETTINGS" to "if (!wanted(BackupScope.SETTINGS)) continue",
            "PROVIDERS" to "if (!wanted(BackupScope.PROVIDERS)) continue",
            "ENV_VARS" to "wanted(BackupScope.ENV_VARS)",
            "SKILLS" to "wanted(BackupScope.SKILLS)",
            "MEMORY" to "wanted(BackupScope.MEMORY)",
            "MCP" to "wanted(BackupScope.MCP)",
            "CHATS" to "wanted(BackupScope.CHATS)",
        )
        for ((scope, guard) in expectations) {
            assertTrue(
                "scope $scope has no guard - add `$guard` to ConfigBackup.export",
                src.contains(guard),
            )
        }
    }

    @Test
    fun `the chat gate keeps its existing window check`() {
        assertTrue(
            src.contains("chatRepo != null && chatWindowDays > 0 && wanted(BackupScope.CHATS)"),
        )
    }

    // -- Import tolerance ---------------------------------------------------

    @Test
    fun `the importer reads every section defensively`() {
        for (key in listOf(
            "providers", "groups", "envVars", "skills",
            "memoryFiles", "mcpServers", "chatSessions",
        )) {
            assertTrue(
                "import must tolerate a missing '$key' array",
                src.contains("optJSONArray(\"$key\")"),
            )
        }
    }

    // -- Picker / exporter agreement (screen side) --------------------------

    @Test
    fun `the picker persists the selection and passes it to the exporter`() {
        val screen = ProductionSources.read("ui/settings/backup/WebDavBackupSection.kt")
        assertTrue(
            "the selection must be persisted under webdav_scope",
            screen.contains("\"webdav_scope\""),
        )
        assertTrue(
            "the upload must pass the selection as categories",
            screen.contains("categories = selectedScope"),
        )
        assertTrue(
            "the scope dialog must not allow an empty selection",
            screen.contains("enabled = working.isNotEmpty()"),
        )
    }

    @Test
    fun `every picker key maps to a label`() {
        val screen = ProductionSources.read("ui/settings/backup/WebDavBackupSection.kt")
        val labels = Regex("""BackupScope\.([A-Z_]+) ->""").findAll(screen)
            .map { it.groupValues[1] }.toSet()
        val all = setOf("SETTINGS", "PROVIDERS", "ENV_VARS", "SKILLS", "MEMORY", "MCP", "CHATS")
        assertEquals("every scope needs a picker label", all, labels)
    }

    @Test
    fun `the WebDAV panel lives inside the backup screen`() {
        val shell = ProductionSources.read("ui/settings/backup/BackupAndRestoreScreen.kt")
        assertTrue(
            "the backup tab must render the WebDAV section",
            shell.contains("WebDavBackupSection("),
        )
    }
}
