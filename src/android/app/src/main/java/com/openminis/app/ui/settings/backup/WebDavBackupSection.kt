package com.openminis.app.ui.settings.backup

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.ConfigBackup
import com.openminis.app.backup.WebDavBackupItem
import com.openminis.app.backup.WebDavClient
import com.openminis.app.backup.WebDavConfig
import com.openminis.app.backup.WebDavConfigStore
import com.openminis.app.backup.WebDavException
import com.openminis.app.backup.WebDavSync
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-webdav-in-backup-screen] The fork's WebDAV feature, embedded in the
 * Backup & Restore screen's Backup tab instead of living on a screen of its
 * own. Two capabilities ride here:
 *
 *  - manual push/restore of the light JSON config document to a WebDAV
 *    server (Nextcloud / 坚果云 / Synology / alist …), with the same
 *    pre-restore safety snapshot the local path uses;
 *  - the multi-device auto-sync master switch (read at startup by
 *    MinisApp.syncMultiDeviceIfEnabled through MultiDeviceSync.isEnabled).
 *
 * [T-backup-scope-picker] The upload now carries a CONTENT PICKER: which
 * sections the pushed document should contain (settings / providers / env
 * vars / skills / memory / MCP / chats). The choice persists in
 * `backup_prefs` so a repeat upload does not re-ask; import tolerates any
 * missing section, so a scoped push restores cleanly and simply leaves the
 * omitted sections alone on the receiving device.
 *
 * State and side effects mirror the original standalone screen — including
 * the notifications for transfers that outlive the composable — so moving it
 * here changes WHERE it is reached from, not how it behaves.
 */
@Composable
fun WebDavBackupSection(
    providerRepository: ProviderRepository,
    envVarRepo: EnvVarRepository?,
    skillRepo: SkillRepository?,
    memoryRepo: MemoryRepository?,
    mcpRepo: MCPRepository?,
    chatRepo: ChatRepository?,
    // [T-webdav-scope-unified] The Backup tab's "Include" selection — the
    // SAME set the local package export uses. The WebDAV push has no picker of
    // its own; what the user ticked there is what gets pushed (see
    // ConfigBackup.BackupScope.keysFor for the category → section mapping).
    selectedCategories: Set<BackupCategory> = BackupCategory.backupable.toSet(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
    }
    // [T-chat-window-control] How far back chat history reaches when Chats is
    // included. Lived on the old WebDAV screen; it moved here with the rest —
    // without it the setting is unchangeable (the value is still read by the
    // local export path and by this panel's push).
    var chatWindowDays by remember { mutableStateOf(prefs.getInt("chat_window_days", 90)) }
    var showWindowDialog by remember { mutableStateOf(false) }

    // ---- WebDAV remote backup state ----
    val webDavStore = remember { WebDavConfigStore(context) }
    val webDavHttpClient = remember { WebDavClient.defaultClient() }
    val scope = rememberCoroutineScope()
    // Transfers that MUST complete even if the user leaves this screen run on
    // the app-scoped scope instead, so navigating away cannot cancel them.
    val application = remember { context.applicationContext as MinisApp }
    val notifier = remember { application.backgroundTaskNotifier }
    var webDavConfig by remember { mutableStateOf(webDavStore.load()) }
    var showWebDavConfig by remember { mutableStateOf(false) }
    var showRemoteList by remember { mutableStateOf(false) }
    var showSecretWarning by remember { mutableStateOf(false) }
    var multiDeviceSyncEnabled by remember {
        mutableStateOf(com.openminis.app.backup.MultiDeviceSync.isEnabled(context))
    }
    var showSyncSecretsWarning by remember { mutableStateOf(false) }

    // [T-webdav-scope-unified] The document's section keys, derived from the
    // Backup tab's category selection on every recomposition — nothing extra
    // to persist, and the two can never drift.
    val effectiveScope = com.openminis.app.backup.ConfigBackup.BackupScope
        .keysFor(selectedCategories)

    var remoteItems by remember { mutableStateOf<List<WebDavBackupItem>>(emptyList()) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var deletePending by remember { mutableStateOf<WebDavBackupItem?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // [refactor-backup-gate] SINGLE mutual-exclusion flag for every backup /
    // restore path in this section (upload, remote restore, snapshot restore,
    // remote delete). Two concurrent payload builds stack two 70MB+ documents
    // and blow the heap — this is the class of bug the old screen's gate
    // existed for, and it moves here with the feature. Snapshot exports are
    // the one intentional exception: they are short and best-effort.
    var operationBusy by remember { mutableStateOf(false) }

    // [fix-audit-p0-2] Local pre-restore snapshots, newest first. Every
    // WebDAV restore writes one (see [restoreWithSnapshot]) so a mistaken
    // restore is always rollback-able; this lists them and feeds a chosen
    // snapshot back through the same restore path. Moved here with the rest
    // of the WebDAV feature — it is the safety net for exactly the restores
    // this section performs.
    val snapshotDir = remember { File(context.filesDir, "backup-snapshots") }
    var snapshotFiles by remember { mutableStateOf(ConfigBackup.listSnapshots(snapshotDir)) }
    var snapshotRestoreTarget by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) {
        snapshotFiles = withContext(Dispatchers.IO) { ConfigBackup.listSnapshots(snapshotDir) }
    }

    /**
     * [T-webdav-restore-snapshot] Snapshot-then-restore, exactly as the local
     * path does: the CURRENT config is written to a local pre-restore snapshot
     * before the incoming document is applied, so a mistaken restore can be
     * rolled back from the Backup & Restore screen's snapshot list.
     */
    val restoreWithSnapshot: (String) -> Unit = doRestore@{ json ->
        // The gate is the last line of defense behind the button-level guards:
        // reject while another payload build is in flight.
        if (operationBusy) {
            errorMessage = context.getString(R.string.backup_err_busy)
            return@doRestore
        }
        operationBusy = true
        application.applicationScope.launch {
            try {
                val payload = withContext(Dispatchers.Default) {
                    ConfigBackup.export(
                        providerRepo = providerRepository,
                        includeSecrets = true,
                        envVarRepo = envVarRepo,
                        skillRepo = skillRepo,
                        memoryRepo = memoryRepo,
                        mcpRepo = mcpRepo,
                        chatRepo = chatRepo,
                        chatWindowDays = chatWindowDays,
                    )
                }
                var snapshotNote: String? = null
                runCatching {
                    val dir = File(context.filesDir, "backup-snapshots")
                    ConfigBackup.writeSnapshot(dir, payload)
                }.onFailure {
                    // [fix-audit-p0-2] The safety net's absence is itself worth
                    // knowing: a user restoring without a rollback point should
                    // hear about it (the old screen surfaced this too).
                    snapshotNote = context.getString(R.string.backup_snapshot_failed)
                }
                if (snapshotNote != null) {
                    withContext(Dispatchers.Main) { errorMessage = snapshotNote }
                }
            } catch (_: Throwable) {
                // Best-effort: a failed snapshot never blocks the restore.
            }
            var restoredOk = true
            var restoredMsg: String? = null
            try {
                val report = withContext(Dispatchers.Default) {
                    ConfigBackup.import(
                        providerRepo = providerRepository,
                        json = json,
                        envVarRepo = envVarRepo,
                        skillRepo = skillRepo,
                        memoryRepo = memoryRepo,
                        mcpRepo = mcpRepo,
                        chatRepo = chatRepo,
                    )
                }
                restoredMsg = context.getString(
                    R.string.backup_restored_notify_body,
                    report?.providersImported ?: 0,
                    report?.groupsImported ?: 0,
                )
                // [fix-audit-p0-4] import() catches its own mid-way failures and
                // reports them through ImportResult.fatal — it does not throw.
                // A fatal means SOME stages landed and the restore did not
                // complete; reporting it as a clean success would hide exactly
                // the state the snapshot exists to roll back. Surface it and
                // push the user at the rollback entry point.
                if (report?.fatal != null) {
                    restoredOk = false
                    restoredMsg = context.getString(R.string.backup_done_fatal) +
                        "\n" + report.fatal
                    withContext(Dispatchers.Main) {
                        errorMessage = restoredMsg
                    }
                }
            } catch (t: Throwable) {
                restoredOk = false
                restoredMsg = t.message ?: context.getString(R.string.backup_err_import)
                withContext(Dispatchers.Main) {
                    errorMessage = t.message ?: context.getString(R.string.backup_err_import)
                }
            }
            notifier.notifyWorkCompleted(
                tag = "webdav-restore",
                title = context.getString(
                    if (restoredOk) R.string.webdav_notify_title_restored
                    else R.string.webdav_notify_title_failed,
                ),
                body = restoredMsg ?: "",
            )
            // The restore just wrote a fresh pre-restore snapshot — refresh
            // the list so the new rollback point is visible without leaving
            // and re-entering the screen.
            snapshotFiles = withContext(Dispatchers.IO) { ConfigBackup.listSnapshots(snapshotDir) }
            withContext(Dispatchers.Main) { operationBusy = false }
        }
    }

    /** Upload with the Backup tab's current category selection. */
    val uploadWithScope: (Boolean) -> Unit = upload@{ withSecrets ->
        if (operationBusy) {
            errorMessage = context.getString(R.string.backup_err_busy)
            return@upload
        }
        operationBusy = true
        application.applicationScope.launch {
            try {
                val payload = withContext(Dispatchers.Default) {
                    ConfigBackup.export(
                        providerRepo = providerRepository,
                        includeSecrets = withSecrets,
                        envVarRepo = envVarRepo,
                        skillRepo = skillRepo,
                        memoryRepo = memoryRepo,
                        mcpRepo = mcpRepo,
                        chatRepo = chatRepo,
                        chatWindowDays = chatWindowDays,
                        categories = effectiveScope,
                    )
                }
                val cfg = webDavConfig
                if (cfg == null) {
                    withContext(Dispatchers.Main) {
                        errorMessage = context.getString(R.string.webdav_server_not_configured)
                        notifier.notifyWorkCompleted(
                            tag = "webdav-upload",
                            title = context.getString(R.string.webdav_notify_title_failed),
                            body = context.getString(R.string.webdav_server_not_configured),
                        )
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        WebDavSync.backup(config = cfg, payload = payload, client = webDavHttpClient)
                    }
                    withContext(Dispatchers.Main) {
                        val failures = runCatching {
                            org.json.JSONObject(payload).optInt("readFailures", 0)
                        }.getOrDefault(0)
                        val msg = if (failures > 0) {
                            context.getString(R.string.webdav_uploaded) + "\n" +
                                context.getString(R.string.backup_export_incomplete, failures)
                        } else {
                            context.getString(R.string.webdav_uploaded)
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        notifier.notifyWorkCompleted(
                            tag = "webdav-upload",
                            title = context.getString(R.string.webdav_notify_title),
                            body = msg,
                        )
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    errorMessage = webDavErrorMessage(context, t)
                    notifier.notifyWorkCompleted(
                        tag = "webdav-upload",
                        title = context.getString(R.string.webdav_notify_title_failed),
                        body = webDavErrorMessage(context, t),
                    )
                }
            } finally {
                withContext(Dispatchers.Main) { operationBusy = false }
            }
        }
    }

    val openRemoteList: () -> Unit = {
        val cfg = webDavConfig
        if (cfg != null) {
            // [fix-audit-p1-1] This is also invoked from the delete flow's
            // IO-context coroutine, so every Compose state write below hops to
            // Main explicitly — writing snapshot state from Dispatchers.IO
            // races recomposition.
            scope.launch {
                withContext(Dispatchers.Main) {
                    showRemoteList = true
                    remoteLoading = true
                    remoteError = null
                }
                try {
                    val items = withContext(Dispatchers.IO) {
                        WebDavSync.listBackupFiles(cfg, webDavHttpClient)
                    }
                    withContext(Dispatchers.Main) { remoteItems = items }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        remoteError = webDavErrorMessage(context, t)
                    }
                } finally {
                    withContext(Dispatchers.Main) { remoteLoading = false }
                }
            }
        }
    }

    SettingsSection(
        header = stringResource(R.string.webdav_section),
        footer = stringResource(R.string.webdav_section_footer),
        modifier = modifier,
    ) {
        // Multi-device auto-sync master switch.
        SettingsRow(
            title = stringResource(R.string.multidevice_sync_title),
            subtitle = stringResource(R.string.multidevice_sync_sub),
            icon = Icons.Filled.Sync,
            onClick = null,
            showDivider = true,
            trailing = {
                Switch(
                    checked = multiDeviceSyncEnabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            if (com.openminis.app.backup.MultiDeviceSync.hasConfirmedSecretsSync(context)) {
                                multiDeviceSyncEnabled = true
                                prefs.edit().putBoolean(
                                    com.openminis.app.backup.MultiDeviceSync.PREF_KEY_ENABLED, true,
                                ).apply()
                                application.syncMultiDeviceIfEnabled()
                            } else {
                                showSyncSecretsWarning = true
                            }
                        } else {
                            multiDeviceSyncEnabled = false
                            prefs.edit().putBoolean(
                                com.openminis.app.backup.MultiDeviceSync.PREF_KEY_ENABLED, false,
                            ).apply()
                        }
                    },
                )
            },
        )
        SettingsRow(
            title = stringResource(R.string.webdav_server),
            subtitle = webDavConfig?.url ?: stringResource(R.string.webdav_server_not_configured),
            icon = Icons.Filled.Cloud,
            onClick = { showWebDavConfig = true },
        )
        // How far back chat history reaches, when Chats is included. Its
        // subtitle doubles as the readout, so the current window is visible
        // without opening the picker. The LOCAL export path reads the same
        // preference, so this is one setting for both destinations.
        SettingsRow(
            title = stringResource(R.string.backup_chat_window_title),
            subtitle = if (chatWindowDays == 0) {
                stringResource(R.string.backup_chat_window_off)
            } else {
                stringResource(R.string.backup_chat_window_sub, chatWindowDays)
            },
            icon = Icons.Filled.Cloud,
            onClick = { showWindowDialog = true },
        )
        // [T-webdav-scope-unified] No picker here any more: the pushed document
        // carries exactly what the Backup tab's "Include" section selects. The
        // subtitle spells that out so the upload row is not a black box.
        SettingsRow(
            title = stringResource(R.string.webdav_upload),
            subtitle = stringResource(
                R.string.webdav_upload_sub,
                webDavScopeSummary(selectedCategories),
            ),
            icon = Icons.Filled.CloudUpload,
            onClick = if (webDavConfig != null && !operationBusy) {
                // Credentials default to INCLUDED, and the remote copy is as
                // sensitive as a local file — same confirmation the local
                // export path uses.
                { showSecretWarning = true }
            } else null,
        )
        SettingsRow(
            title = stringResource(R.string.webdav_remote),
            subtitle = stringResource(R.string.webdav_remote_sub),
            icon = Icons.Outlined.CloudDownload,
            onClick = if (webDavConfig != null && !operationBusy) openRemoteList else null,
            showDivider = false,
        )
    }

    // Chat-history window picker (same options and the same persisted
    // preference the local export path reads).
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
                                    prefs.edit().putInt("chat_window_days", days).apply()
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

    // Credentials confirmation before an upload — mirrors the local export
    // flow ("include keys" vs "without keys") because the remote copy is just
    // as sensitive as a file on disk.
    if (showSecretWarning) {
        AlertDialog(
            onDismissRequest = { showSecretWarning = false },
            title = { Text(stringResource(R.string.backup_secret_title)) },
            text = { Text(stringResource(R.string.backup_secret_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showSecretWarning = false
                    uploadWithScope(true)
                }) { Text(stringResource(R.string.backup_secret_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSecretWarning = false
                    uploadWithScope(false)
                }) { Text(stringResource(R.string.backup_secret_without)) }
            },
        )
    }

    // ---- Snapshots (rollback) ---------------------------------------------
    // A second section so the safety net is visible: without it the user is
    // told "a snapshot was saved" with nowhere to restore it from.
    SettingsSection(
        header = stringResource(R.string.backup_snapshot_section_title),
        footer = stringResource(R.string.backup_snapshot_section_footer),
    ) {
        if (snapshotFiles.isEmpty()) {
            Text(
                stringResource(R.string.backup_snapshot_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            snapshotFiles.forEachIndexed { index, file ->
                SettingsRow(
                    title = file.name,
                    subtitle = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.getDefault(),
                    ).format(java.util.Date(file.lastModified())),
                    icon = Icons.Filled.Restore,
                    onClick = { snapshotRestoreTarget = file },
                    showDivider = index < snapshotFiles.size - 1,
                )
            }
        }
    }

    // ---- Dialogs ----------------------------------------------------------

    if (showWebDavConfig) {
        WebDavConfigDialog(
            initial = webDavConfig,
            httpClient = webDavHttpClient,
            onDismiss = { showWebDavConfig = false },
            onSave = { cfg ->
                webDavStore.save(cfg)
                webDavConfig = webDavStore.load()
                showWebDavConfig = false
                Toast.makeText(context, context.getString(R.string.webdav_saved), Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (showRemoteList) {
        webDavConfig?.let { cfg ->
            WebDavRemoteDialog(
                config = cfg,
                items = remoteItems,
                loading = remoteLoading,
                error = remoteError,
                busy = operationBusy,
                onDismiss = { showRemoteList = false },
                onRefresh = openRemoteList,
                onRestore = { item ->
                    // [refactor-backup-gate] The download itself must not hold
                    // the gate (restoreWithSnapshot claims it once the payload
                    // arrives — holding it across the network gap would make
                    // that check see "busy" and refuse this very restore). The
                    // sheet's own busy flag prevents double-taps meanwhile.
                    if (!operationBusy) {
                        // onClick runs on Main — the assignment is safe here;
                        // only the launched coroutine's writes need hopping.
                        remoteLoading = true
                        application.applicationScope.launch {
                            try {
                                val json = withContext(Dispatchers.IO) {
                                    WebDavSync.restore(cfg, item, webDavHttpClient)
                                }
                                withContext(Dispatchers.Main) { showRemoteList = false }
                                restoreWithSnapshot(json)
                            } catch (t: Throwable) {
                                withContext(Dispatchers.Main) {
                                    errorMessage = webDavErrorMessage(context, t)
                                    notifier.notifyWorkCompleted(
                                        tag = "webdav-restore",
                                        title = context.getString(R.string.webdav_notify_title_failed),
                                        body = webDavErrorMessage(context, t),
                                    )
                                }
                            } finally {
                                withContext(Dispatchers.Main) { remoteLoading = false }
                            }
                        }
                    }
                },
                onDelete = { item -> deletePending = item },
            )
        }
    }

    deletePending?.let { item ->
        AlertDialog(
            onDismissRequest = { deletePending = null },
            title = { Text(stringResource(R.string.webdav_delete_confirm_title)) },
            text = { Text(stringResource(R.string.webdav_delete_confirm_body, item.displayName)) },
            confirmButton = {
                TextButton(
                    enabled = !operationBusy,
                    onClick = {
                        val cfg = webDavConfig ?: return@TextButton
                        deletePending = null
                        operationBusy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    WebDavSync.deleteBackupFile(cfg, item, webDavHttpClient)
                                }
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.webdav_deleted),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                openRemoteList()
                            } catch (t: Throwable) {
                                withContext(Dispatchers.Main) {
                                    errorMessage = webDavErrorMessage(context, t)
                                }
                            } finally {
                                withContext(Dispatchers.Main) { operationBusy = false }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.webdav_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletePending = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // First-time confirmation that auto-sync snapshots may contain credentials.
    if (showSyncSecretsWarning) {
        AlertDialog(
            onDismissRequest = { showSyncSecretsWarning = false },
            title = { Text(stringResource(R.string.multidevice_sync_secret_title)) },
            text = { Text(stringResource(R.string.multidevice_sync_secret_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showSyncSecretsWarning = false
                    com.openminis.app.backup.MultiDeviceSync.markSecretsSyncConfirmed(context)
                    multiDeviceSyncEnabled = true
                    prefs.edit().putBoolean(
                        com.openminis.app.backup.MultiDeviceSync.PREF_KEY_ENABLED, true,
                    ).apply()
                    application.syncMultiDeviceIfEnabled()
                }) { Text(stringResource(R.string.multidevice_sync_secret_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showSyncSecretsWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Local-snapshot restore confirmation. Restoring a snapshot goes through
    // the exact same restoreWithSnapshot path as a WebDAV restore — including
    // taking a fresh snapshot of the current config first, so a mistaken
    // rollback is itself rollback-able.
    snapshotRestoreTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { snapshotRestoreTarget = null },
            title = { Text(stringResource(R.string.backup_snapshot_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_snapshot_restore_confirm_text, file.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        snapshotRestoreTarget = null
                        val content = runCatching { file.readText() }.getOrNull()
                        if (content != null) {
                            restoreWithSnapshot(content)
                        } else {
                            errorMessage = context.getString(R.string.backup_err_read)
                        }
                    },
                ) { Text(stringResource(R.string.backup_snapshot_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { snapshotRestoreTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
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
 * [T-webdav-scope-unified] One-line description of what the push will contain,
 * derived from the Backup tab's "Include" selection — "Everything" when every
 * backupable category is on, else "N of M categories". Reads as a sentence
 * fragment because it is spliced into the upload row's subtitle.
 */
@Composable
private fun webDavScopeSummary(selected: Set<BackupCategory>): String {
    val all = BackupCategory.backupable
    val chosen = all.count { it in selected }
    return if (chosen >= all.size) {
        stringResource(R.string.webdav_scope_all)
    } else {
        stringResource(R.string.webdav_scope_count, chosen, all.size)
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
    httpClient: okhttp3.OkHttpClient,
    onDismiss: () -> Unit,
    onSave: (WebDavConfig) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var path by remember { mutableStateOf(initial?.path ?: WebDavConfig.DEFAULT_BACKUP_DIR) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val runTest: () -> Unit = runTest@{
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
                withContext(Dispatchers.IO) { WebDavSync.testConnection(cfg, httpClient) }
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(stringResource(R.string.webdav_url_label)) },
                    placeholder = { Text(stringResource(R.string.webdav_url_placeholder)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text(stringResource(R.string.webdav_username_label)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(R.string.webdav_password_label)) },
                    placeholder = { Text(stringResource(R.string.webdav_password_placeholder)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = path, onValueChange = { path = it },
                    label = { Text(stringResource(R.string.webdav_path_label)) },
                    placeholder = { Text(stringResource(R.string.webdav_path_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = runTest, enabled = !testing) {
                        Text(
                            if (testing) stringResource(R.string.webdav_testing)
                            else stringResource(R.string.webdav_test),
                        )
                    }
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp).padding(start = 8.dp),
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
                    onSave(WebDavConfig(url = url, username = username, password = password, path = path))
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Remote backup management sheet — lists `rikkaminis-backup-*.json` files on
 * the WebDAV server (newest first) with size and timestamp, and offers
 * restore / delete per file.
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
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (loading) {
                    Row(
                        modifier = Modifier.padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
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
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                                    TextButton(onClick = { onRestore(item) }, enabled = !busy) {
                                        Text(stringResource(R.string.webdav_restore))
                                    }
                                    TextButton(onClick = { onDelete(item) }, enabled = !busy) {
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_ok)) }
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
