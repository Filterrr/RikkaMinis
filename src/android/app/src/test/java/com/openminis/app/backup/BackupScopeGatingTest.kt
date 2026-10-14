package com.openminis.app.backup

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-webdav-scope-unified] The WebDAV upload pushes a document whose sections
 * follow the Backup & Restore screen's "Include" selection — the SAME
 * checkboxes the local package export uses. There is exactly one place a user
 * states what "backed up" means; this test pins that there is no second
 * picker hiding in the WebDAV panel, and that the two vocabularies still
 * translate cleanly.
 *
 * ConfigBackup.export needs Android repositories to run, so the contract is
 * pinned at source level (the technique the other backup source-grep tests
 * use).
 */
class BackupScopeGatingTest {

    private val src by lazy { ProductionSources.read("backup/ConfigBackup.kt") }
    private val screen by lazy {
        ProductionSources.read("ui/settings/backup/WebDavBackupSection.kt")
    }
    private val shell by lazy {
        ProductionSources.read("ui/settings/backup/BackupAndRestoreScreen.kt")
    }

    // -- The unified selection ---------------------------------------------

    @Test
    fun `the WebDAV panel takes the Include selection instead of its own`() {
        assertTrue(
            "the panel must accept the Backup tab's category selection",
            screen.contains("selectedCategories: Set<BackupCategory>"),
        )
        assertTrue(
            "the backup screen must pass its `selected` set down",
            shell.contains("selectedCategories = selected"),
        )
        assertTrue(
            "the panel must derive its section keys from that selection",
            screen.contains("keysFor(selectedCategories)"),
        )
    }

    @Test
    fun `there is no second content picker in the WebDAV panel`() {
        // The regression this guards: two pickers that disagree, so a user who
        // unchecked Providers locally still ships API keys to a server.
        for (marker in listOf(
            "BackupScope.ALL",          // the picker's option list
            "putStringSet(\"webdav_scope\"",
            "WebDavScopeDialog",
            "webdav_scope_title",
        )) {
            assertFalse("the scope picker must not come back: $marker", screen.contains(marker))
        }
    }

    @Test
    fun `the upload reports what it will contain`() {
        // The row's subtitle states the scope, so "upload" is not a black box.
        assertTrue(
            "the upload row must show the derived scope summary",
            screen.contains("webdav_upload_sub") && screen.contains("webDavScopeSummary(selectedCategories)"),
        )
        assertTrue(
            "the summary must count the same categories the picker shows",
            screen.contains("BackupCategory.backupable"),
        )
    }

    @Test
    fun `the chat window control survived the panel move`() {
        // The old WebDAV screen owned the "Chat history window" picker; the
        // panel read the preference but had no way to change it after the
        // move. Both halves must be here: the row, and the persistence.
        assertTrue("the window row must be present", screen.contains("backup_chat_window_title"))
        assertTrue(
            "the selection must persist under chat_window_days",
            screen.contains("putInt(\"chat_window_days\""),
        )
    }

    @Test
    fun `the upload still confirms credentials`() {
        // The remote copy is as sensitive as a local file; the confirmation
        // must survive the move into this screen.
        assertTrue("a credentials confirmation must gate the upload", screen.contains("showSecretWarning"))
        assertTrue("both answers must be offered", screen.contains("backup_secret_without"))
    }

    // -- Category → section mapping ----------------------------------------

    @Test
    fun `the panel keeps the single mutual-exclusion gate`() {
        // [refactor-backup-gate] The old screen's gate existed because two
        // concurrent payload builds stack two 70MB+ documents and blow the
        // heap. It moves with the feature: one flag, claimed before each
        // launch, cleared in a finally on Main, and the entry buttons read it.
        assertTrue("the panel must have the gate", screen.contains("var operationBusy"))
        assertEquals(
            "three guarded paths (upload / restore / delete)",
            3, Regex(Regex.escape("operationBusy = true")).findAll(screen).count(),
        )
        assertEquals(
            "every claim has a matching Main-thread clear",
            3,
            Regex(Regex.escape("withContext(Dispatchers.Main) { operationBusy = false }"))
                .findAll(screen).count(),
        )
        assertTrue(
            "the upload button must read the gate",
            screen.contains("!operationBusy"),
        )
        assertTrue(
            "the remote-restore path must check the gate",
            screen.contains("if (!operationBusy) {"),
        )
        assertTrue(
            "the gate rejects with the busy message",
            screen.contains("backup_err_busy"),
        )
    }

    @Test
    fun `every package category maps to a document section`() {
        // keysFor is the single translation point between the two
        // vocabularies; each category it knows must name a real section key.
        val body = src.substringAfter("fun keysFor(categories: Set<BackupCategory>)")
            .substringBefore("return out")
        assertTrue("keysFor must map providers", body.contains("BackupCategory.PROVIDERS in categories"))
        assertTrue("keysFor must map env vars", body.contains("BackupCategory.ENVIRONMENT_VARIABLES in categories"))
        assertTrue("keysFor must map skills", body.contains("BackupCategory.SKILLS in categories"))
        assertTrue("keysFor must map memory", body.contains("BackupCategory.MEMORY in categories"))
        assertTrue("keysFor must map MCP", body.contains("BackupCategory.MCP_SERVERS in categories"))
        assertTrue("keysFor must map chats", body.contains("BackupCategory.CHATS in categories"))
    }

    @Test
    fun `settings always travel`() {
        // The registry-backed scalars have no package category; they are the
        // document's reason to exist, so keysFor seeds them unconditionally.
        val body = src.substringAfter("fun keysFor(categories: Set<BackupCategory>)")
            .substringBefore("return out")
        // Seeded in the set's initialiser, before any category check runs —
        // pinned as "the first thing the function does", not as a particular
        // syntax (`mutableSetOf(SETTINGS)` vs `out += SETTINGS`).
        val seed = body.indexOf("SETTINGS")
        val firstCategoryCheck = body.indexOf("BackupCategory.")
        assertTrue("SETTINGS must be seeded at all", seed >= 0)
        assertTrue(
            "settings must be seeded before the first category check " +
                "(seed=$seed, firstCheck=$firstCategoryCheck)",
            firstCategoryCheck < 0 || seed < firstCategoryCheck,
        )
    }

    @Test
    fun `shared files map to nothing`() {
        // The light JSON document embeds no file trees, so SHARED_FILES has no
        // counterpart — and must not silently gate some other section.
        val body = src.substringAfter("fun keysFor(categories: Set<BackupCategory>)")
            .substringBefore("return out")
        assertFalse(
            "SHARED_FILES must not map to a document section",
            body.contains("SHARED_FILES"),
        )
    }

    // -- Exporter side -----------------------------------------------------

    @Test
    fun `null categories still mean no gating`() {
        assertTrue(src.contains("categories == null || key in categories"))
    }

    @Test
    fun `every gate names a real scope key`() {
        val gates = Regex("""wanted\(BackupScope\.([A-Z_]+)\)""").findAll(src)
            .map { it.groupValues[1] }.toSet()
        val declared = Regex("""const val ([A-Z_]+) = """).findAll(src)
            .map { it.groupValues[1] }.toSet()
        assertTrue("unknown scopes: ${gates - declared}", (gates - declared).isEmpty())
        assertTrue("no gates found", gates.isNotEmpty())
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
            assertTrue("scope $scope has no guard — add `$guard`", src.contains(guard))
        }
    }

    @Test
    fun `the chat gate keeps its window check`() {
        assertTrue(
            src.contains("chatRepo != null && chatWindowDays > 0 && wanted(BackupScope.CHATS)"),
        )
    }

    @Test
    fun `the importer reads every section defensively`() {
        for (key in listOf(
            "providers", "groups", "envVars", "skills",
            "memoryFiles", "mcpServers", "chatSessions",
        )) {
            assertTrue("import must tolerate a missing '$key' array", src.contains("optJSONArray(\"$key\")"))
        }
    }
}
