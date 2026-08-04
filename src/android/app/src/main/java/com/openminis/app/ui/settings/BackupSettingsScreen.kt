package com.openminis.app.ui.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.backup.ConfigBackup
import com.openminis.app.backup.WebDavBackupItem
import com.openminis.app.backup.WebDavClient
import com.openminis.app.backup.WebDavConfig
import com.openminis.app.backup.WebDavConfigStore
import com.openminis.app.backup.WebDavException
import com.openminis.app.backup.WebDavSync
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Local backup / restore of app configuration — providers, appearance, and the
 * agent-runtime defaults — plus WebDAV remote backup (upload / list / restore /
 * delete against any WebDAV server: Nextcloud, 坚果云, Synology, …).
 *
 * Chat history is included, but deliberately kept LIGHT: only the last
 * `chatWindowDays` of activity, text-only message parts (media/attachments are
 * dropped), capped per session. This keeps "restore my setup" from becoming an
 * unpredictably heavy operation while still carrying conversations across
 * devices. The window is user-adjustable; 0 disables chat history entirely.
 */
@Composable
fun BackupSettingsScreen(
    providerRepository: ProviderRepository,
    envVarRepository: EnvVarRepository? = null,
    skillRepository: SkillRepository? = null,
    memoryRepository: MemoryRepository? = null,
    mcpRepository: MCPRepository? = null,
    chatRepository: com.openminis.app.data.repository.ChatRepository? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val chatPrefs = remember {
        context.getSharedPreferences("backup_prefs", android.content.Context.MODE_PRIVATE)
    }
    var chatWindowDays by remember {
        mutableStateOf(chatPrefs.getInt("chat_window_days", 90))
    }
    var showWindowDialog by remember { mutableStateOf(false) }
    // Payload is built BEFORE the file picker opens, then written in the
    // callback: SAF gives us a write handle, not a chance to compute content.
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var showSecretWarning by remember { mutableStateOf(false) }
    var importReport by remember { mutableStateOf<ConfigBackup.ImportResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ---- WebDAV remote backup state ----
    val webDavStore = remember { WebDavConfigStore(context) }
    val webDavHttpClient = remember { WebDavClient.defaultClient() }
    val scope = rememberCoroutineScope()
    var webDavConfig by remember { mutableStateOf(webDavStore.load()) }
    var showWebDavConfig by remember { mutableStateOf(false) }
    var showRemoteList by remember { mutableStateOf(false) }
    var webDavBusy by remember { mutableStateOf(false) }
    // When true, the next secret-warning confirmation uploads to WebDAV
    // instead of launching the SAF file picker.
    var webDavUploadPending by remember { mutableStateOf(false) }
    var remoteItems by remember { mutableStateOf<List<WebDavBackupItem>>(emptyList()) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var deletePending by remember { mutableStateOf<WebDavBackupItem?>(null) }
    var snapshotNote by remember { mutableStateOf<String?>(null) }

    val savedToast = stringResource(R.string.backup_saved)
    val errWriteFmt = stringResource(R.string.backup_err_write)
    val errGenerateFmt = stringResource(R.string.backup_err_generate)
    val errRead = stringResource(R.string.backup_err_read)
    val errImport = stringResource(R.string.backup_err_import)
    val errUnknown = stringResource(R.string.backup_err_unknown)

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(payload.toByteArray())
            }
            Toast.makeText(context, savedToast, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            errorMessage = String.format(errWriteFmt, t.message ?: errUnknown)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText()
                ?: throw IllegalStateException(errRead)
            restoreWithSnapshot(json)
        } catch (t: Throwable) {
            errorMessage = t.message ?: errImport
        }
    }

    // Restore-with-safety-net: before applying an imported config (local file
    // or WebDAV) snapshot the CURRENT config so the user can always roll back
    // after a mistaken restore. Best-effort: a failed snapshot never blocks
    // the restore, it only reports via snapshotNote.
    val restoreWithSnapshot: (String) -> Unit = { json ->
        snapshotNote = null
        webDavBusy = true
        scope.launch {
            try {
                val payload = withContext(Dispatchers.Default) {
                    ConfigBackup.export(
                        providerRepo = providerRepository,
                        includeSecrets = true,
                        envVarRepo = envVarRepository,
                        skillRepo = skillRepository,
                        memoryRepo = memoryRepository,
                        mcpRepo = mcpRepository,
                        chatRepo = chatRepository,
                        chatWindowDays = chatWindowDays,
                    )
                }
                val cfg = webDavConfig
                if (cfg != null && cfg.isConfigured) {
                    withContext(Dispatchers.IO) {
                        WebDavSync.backup(cfg, payload, webDavHttpClient)
                    }
                    snapshotNote = context.getString(R.string.backup_snapshot_webdav)
                } else {
                    val dir = File(context.filesDir, "backup-snapshots").apply { mkdirs() }
                    File(dir, ConfigBackup.suggestedFileName()).writeText(payload)
                    snapshotNote = context.getString(R.string.backup_snapshot_local)
                }
            } catch (t: Throwable) {
                snapshotNote = context.getString(R.string.backup_snapshot_failed)
            }
            try {
                importReport = withContext(Dispatchers.Default) {
                    ConfigBackup.import(
                        providerRepo = providerRepository,
                        json = json,
                        envVarRepo = envVarRepository,
                        skillRepo = skillRepository,
                        memoryRepo = memoryRepository,
                        mcpRepo = mcpRepository,
                        chatRepo = chatRepository,
                    )
                }
            } catch (t: Throwable) {
                errorMessage = t.message ?: errImport
            } finally {
                webDavBusy = false
            }
        }
    }

    // Fetch the remote backup list and open the management sheet.
    val openRemoteList: () -> Unit = {
        val cfg = webDavConfig
        if (cfg == null) {
            errorMessage = context.getString(R.string.webdav_configure_first)
            return@openRemoteList
        }
        showRemoteList = true
        remoteLoading = true
        remoteError = null
        scope.launch {
            try {
                remoteItems = withContext(Dispatchers.IO) {
                    WebDavSync.listBackupFiles(cfg, webDavHttpClient)
                }
            } catch (t: Throwable) {
                remoteError = webDavErrorMessage(context, t)
            } finally {
                remoteLoading = false
            }
        }
    }

    // top-level page: rely on system back gesture / bottom nav (no back arrow)
    SettingsScaffold(title = stringResource(R.string.settings_backup), onBack = null) {
        SettingsSection(
            header = stringResource(R.string.backup_section_local),
            footer = stringResource(R.string.backup_section_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.backup_export),
                subtitle = stringResource(R.string.backup_export_sub),
                icon = Icons.Default.Download,
                onClick = { showSecretWarning = true },
            )
            SettingsRow(
                title = stringResource(R.string.backup_chat_window_title),
                subtitle = stringResource(R.string.backup_chat_window_sub, chatWindowDays),
                icon = Icons.Outlined.History,
                onClick = { showWindowDialog = true },
            )
            SettingsRow(
                title = stringResource(R.string.backup_import),
                subtitle = stringResource(R.string.backup_import_sub),
                icon = Icons.Default.Upload,
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                showDivider = false,
            )
        }
        SettingsSection(
            header = stringResource(R.string.webdav_section),
            footer = stringResource(R.string.webdav_section_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.webdav_server),
                subtitle = webDavConfig?.url
                    ?: stringResource(R.string.webdav_server_not_configured),
                icon = Icons.Filled.Cloud,
                onClick = { showWebDavConfig = true },
            )
            SettingsRow(
                title = stringResource(R.string.webdav_upload),
                subtitle = stringResource(R.string.webdav_upload_sub),
                icon = Icons.Filled.CloudUpload,
                onClick = if (webDavConfig != null && !webDavBusy) {
                    { webDavUploadPending = true; showSecretWarning = true }
                } else {
                    null
                },
            )
            SettingsRow(
                title = stringResource(R.string.webdav_remote),
                subtitle = stringResource(R.string.webdav_remote_sub),
                icon = Icons.Outlined.CloudDownload,
                onClick = if (webDavConfig != null && !webDavBusy) openRemoteList else null,
                showDivider = false,
            )
        }
    }

    // Credentials default to INCLUDED — a restore that drops every API key just
    // moves the work back onto the user. The tradeoff is that the file is
    // sensitive, so it gets an explicit confirmation rather than a silent write.
    // The same warning guards WebDAV uploads: the remote copy is as sensitive
    // as the local file. An export with keys goes through the same flow; only
    // the destination differs (SAF picker vs. WebDAV PUT).
    if (showSecretWarning) {
        val runExport: (Boolean) -> Unit = { withSecrets ->
            showSecretWarning = false
            val toWebDav = webDavUploadPending
            webDavUploadPending = false
            try {
                val payload = ConfigBackup.export(
                    providerRepo = providerRepository,
                    includeSecrets = withSecrets,
                    envVarRepo = envVarRepository,
                    skillRepo = skillRepository,
                    memoryRepo = memoryRepository,
                    mcpRepo = mcpRepository,
                    chatRepo = chatRepository,
                    chatWindowDays = chatWindowDays,
                )
                if (toWebDav) {
                    val cfg = webDavConfig ?: return@runExport
                    webDavBusy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                WebDavSync.backup(
                                    config = cfg,
                                    payload = payload,
                                    client = webDavHttpClient,
                                )
                            }
                            Toast.makeText(
                                context,
                                context.getString(R.string.webdav_uploaded),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (t: Throwable) {
                            errorMessage = webDavErrorMessage(context, t)
                        } finally {
                            webDavBusy = false
                        }
                    }
                } else {
                    pendingExport = payload
                    exportLauncher.launch(ConfigBackup.suggestedFileName())
                }
            } catch (t: Throwable) {
                errorMessage = String.format(errGenerateFmt, t.message ?: errUnknown)
            }
        }
        AlertDialog(
            onDismissRequest = { showSecretWarning = false },
            title = { Text(stringResource(R.string.backup_secret_title)) },
            text = { Text(stringResource(R.string.backup_secret_body)) },
            confirmButton = {
                TextButton(onClick = { runExport(true) }) {
                    Text(stringResource(R.string.backup_secret_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { runExport(false) }) {
                    Text(stringResource(R.string.backup_secret_without))
                }
            },
        )
    }

    // WebDAV server configuration sheet (URL / username / password / folder).
    if (showWebDavConfig) {
        WebDavConfigDialog(
            initial = webDavConfig,
            httpClient = webDavHttpClient,
            onDismiss = { showWebDavConfig = false },
            onSave = { cfg ->
                webDavStore.save(cfg)
                webDavConfig = webDavStore.load()
                showWebDavConfig = false
                Toast.makeText(
                    context,
                    context.getString(R.string.webdav_saved),
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }

    // Remote backup management sheet: list, restore, delete.
    if (showRemoteList) {
        webDavConfig?.let { cfg ->
            WebDavRemoteDialog(
                config = cfg,
                items = remoteItems,
                loading = remoteLoading,
                error = remoteError,
                busy = webDavBusy,
                onDismiss = { showRemoteList = false },
                onRefresh = openRemoteList,
                onRestore = { item ->
                    if (!webDavBusy) {
                        scope.launch {
                            try {
                                val json = withContext(Dispatchers.IO) {
                                    WebDavSync.restore(cfg, item, webDavHttpClient)
                                }
                                showRemoteList = false
                                restoreWithSnapshot(json)
                            } catch (t: Throwable) {
                                errorMessage = webDavErrorMessage(context, t)
                            }
                        }
                    }
                },
                onDelete = { item -> deletePending = item },
            )
        }
    }

    // Delete confirmation — destructive, remote, irreversible.
    deletePending?.let { item ->
        AlertDialog(
            onDismissRequest = { deletePending = null },
            title = { Text(stringResource(R.string.webdav_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.webdav_delete_confirm_body, item.displayName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cfg = webDavConfig ?: return@TextButton
                        deletePending = null
                        webDavBusy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    WebDavSync.deleteBackupFile(cfg, item, webDavHttpClient)
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.webdav_deleted),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                openRemoteList()
                            } catch (t: Throwable) {
                                errorMessage = webDavErrorMessage(context, t)
                            } finally {
                                webDavBusy = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.webdav_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletePending = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Import is best-effort per item, so the result sheet has to say what did
    // NOT land — otherwise a partially-restored setup looks like a full one.
    importReport?.let { report ->
        AlertDialog(
            onDismissRequest = { importReport = null; snapshotNote = null },
            title = { Text(stringResource(R.string.backup_done_title)) },
            text = {
                Column {
                    snapshotNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Text(
                        stringResource(
                            R.string.backup_done_summary,
                            report.fieldsApplied,
                            report.providersImported,
                        )
                    )
                    if (report.groupsImported > 0) {
                        Text(
                            stringResource(R.string.backup_done_groups, report.groupsImported),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (report.envVarsImported > 0) {
                        Text(
                            stringResource(R.string.backup_done_env_vars, report.envVarsImported),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (report.skillsImported > 0) {
                        Text(
                            stringResource(R.string.backup_done_skills, report.skillsImported),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (report.memoryFilesImported > 0) {
                        Text(
                            stringResource(
                                R.string.backup_done_memory_files,
                                report.memoryFilesImported,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (report.mcpServersImported > 0) {
                        Text(
                            stringResource(
                                R.string.backup_done_mcp_servers,
                                report.mcpServersImported,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (report.chatSessionsImported > 0 || report.chatMessagesImported > 0) {
                        Text(
                            stringResource(
                                R.string.backup_done_chat,
                                report.chatSessionsImported,
                                report.chatMessagesImported,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (report.skipped.isNotEmpty()) {
                        Text(
                            stringResource(R.string.backup_done_skipped, report.skipped.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // Cap the list: a backup restored onto a much older
                        // build can skip dozens of fields, and an unbounded
                        // dialog would run off the screen.
                        for (line in report.skipped.take(8)) {
                            Text("• $line", style = MaterialTheme.typography.bodySmall)
                        }
                        if (report.skipped.size > 8) {
                            Text("…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!report.hadSecrets && report.providersImported > 0) {
                        Text(
                            stringResource(R.string.backup_done_no_keys),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        stringResource(R.string.backup_done_restart),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { importReport = null; snapshotNote = null }) {
                    Text(stringResource(R.string.backup_ok))
                }
            },
        )
    }

    if (showWindowDialog) {
        AlertDialog(
            onDismissRequest = { showWindowDialog = false },
            title = { Text(stringResource(R.string.backup_chat_window_title)) },
            text = {
                Column {
                    listOf(0, 30, 90, 180, 365).forEach { days ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chatWindowDays = days
                                    chatPrefs.edit().putInt("chat_window_days", days).apply()
                                    showWindowDialog = false
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                if (days == 0) {
                                    stringResource(R.string.backup_chat_window_off)
                                } else {
                                    stringResource(R.string.backup_chat_window_days, days)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (days == chatWindowDays) {
                                Text("✓", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text(stringResource(R.string.backup_err_title)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text(stringResource(R.string.backup_ok))
                }
            },
        )
    }
}

/**
 * WebDAV server configuration dialog — URL, username, password and the backup
 * folder path. The "Test connection" button PROPFINDs the folder and reports
 * success/failure inline before the user commits to saving.
 */
@Composable
private fun WebDavConfigDialog(
    initial: WebDavConfig?,
    httpClient: OkHttpClient,
    onDismiss: () -> Unit,
    onSave: (WebDavConfig) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var path by remember {
        mutableStateOf(initial?.path ?: WebDavConfig.DEFAULT_BACKUP_DIR)
    }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val runTest: () -> Unit = {
        val cfg = WebDavConfig(
            url = url,
            username = username,
            password = password.ifBlank { initial?.password.orEmpty() },
            path = path,
        )
        if (cfg.url.isBlank() || cfg.username.isBlank()) {
            testResult = context.getString(R.string.webdav_err_invalid_url)
            return@runTest
        }
        testing = true
        testResult = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WebDavSync.testConnection(cfg, httpClient)
                }
                testResult = context.getString(R.string.webdav_test_ok)
            } catch (t: Throwable) {
                testResult = webDavErrorMessage(context, t)
            } finally {
                testing = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.webdav_config_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.webdav_url_label)) },
                    placeholder = {
                        Text(stringResource(R.string.webdav_url_placeholder))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.webdav_username_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.webdav_password_label)) },
                    placeholder = {
                        if (initial?.password.isNullOrEmpty().not()) {
                            Text(stringResource(R.string.webdav_password_hint))
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(stringResource(R.string.webdav_path_label)) },
                    placeholder = {
                        Text(stringResource(R.string.webdav_path_hint))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = runTest, enabled = !testing) {
                        Text(
                            if (testing) {
                                stringResource(R.string.webdav_testing)
                            } else {
                                stringResource(R.string.webdav_test)
                            }
                        )
                    }
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .width(16.dp)
                                .padding(start = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    testResult?.let { result ->
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result == context.getString(R.string.webdav_test_ok)) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isBlank() || username.isBlank()) {
                        testResult = context.getString(R.string.webdav_err_invalid_url)
                        return@TextButton
                    }
                    onSave(
                        WebDavConfig(
                            url = url,
                            username = username,
                            password = password,
                            path = path,
                        )
                    )
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Remote backup management sheet — lists `openminis-backup-*.json` files on the
 * WebDAV server (newest first) with size and timestamp, and offers restore /
 * delete per file. Restore downloads the payload and feeds it straight into
 * [ConfigBackup.import]; delete asks for confirmation first.
 */
@Composable
private fun WebDavRemoteDialog(
    config: WebDavConfig,
    items: List<WebDavBackupItem>,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (WebDavBackupItem) -> Unit,
    onDelete: (WebDavBackupItem) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.webdav_remote)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (loading) {
                    Row(
                        modifier = Modifier.padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(28.dp))
                    }
                } else {
                    error?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRefresh, enabled = !busy) {
                            Text(stringResource(R.string.webdav_retry))
                        }
                    } ?: if (items.isEmpty()) {
                        Text(
                            stringResource(R.string.webdav_remote_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        LazyColumn {
                            items(items, key = { it.displayName }) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                        )
                                        Text(
                                            text = "${formatSize(item.size)} · ${formatInstant(item.lastModified)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = { onRestore(item) },
                                        enabled = !busy,
                                    ) {
                                        Text(stringResource(R.string.webdav_restore))
                                    }
                                    TextButton(
                                        onClick = { onDelete(item) },
                                        enabled = !busy,
                                    ) {
                                        Text(
                                            stringResource(R.string.webdav_delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_ok))
            }
        },
    )
}

/** Maps transport failures to user-facing messages by HTTP status / kind. */
private fun webDavErrorMessage(context: Context, t: Throwable): String {
    val dav = t as? WebDavException
    return when {
        dav != null && (dav.statusCode == 401 || dav.statusCode == 403) ->
            context.getString(R.string.webdav_err_auth)
        dav != null && dav.statusCode == 404 ->
            context.getString(R.string.webdav_err_not_found)
        dav != null && dav.statusCode > 0 ->
            context.getString(R.string.webdav_err_server, dav.statusCode)
        t is IOException ->
            context.getString(
                R.string.webdav_err_network,
                t.message ?: context.getString(R.string.webdav_err_unknown),
            )
        else -> t.message ?: context.getString(R.string.webdav_err_unknown)
    }
}

private val INSTANT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatInstant(instant: Instant): String = INSTANT_FORMATTER.format(instant)
