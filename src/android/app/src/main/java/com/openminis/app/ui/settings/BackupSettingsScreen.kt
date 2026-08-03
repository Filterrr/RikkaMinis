package com.openminis.app.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.backup.ConfigBackup
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository

/**
 * Local backup / restore of app configuration — providers, appearance, and the
 * agent-runtime defaults. Scope is intentionally a plain file on the device:
 * no cloud, no WebDAV, nothing that needs an account.
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
            importReport = ConfigBackup.import(
                providerRepo = providerRepository,
                json = json,
                envVarRepo = envVarRepository,
                skillRepo = skillRepository,
                memoryRepo = memoryRepository,
                mcpRepo = mcpRepository,
                chatRepo = chatRepository,
            )
        } catch (t: Throwable) {
            errorMessage = t.message ?: errImport
        }
    }

    SettingsScaffold(title = stringResource(R.string.settings_backup), onBack = onBack) {
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
    }

    // Credentials default to INCLUDED — a restore that drops every API key just
    // moves the work back onto the user. The tradeoff is that the file is
    // sensitive, so it gets an explicit confirmation rather than a silent write.
    if (showSecretWarning) {
        val runExport: (Boolean) -> Unit = { withSecrets ->
            showSecretWarning = false
            try {
                pendingExport = ConfigBackup.export(
                    providerRepo = providerRepository,
                    includeSecrets = withSecrets,
                    envVarRepo = envVarRepository,
                    skillRepo = skillRepository,
                    memoryRepo = memoryRepository,
                    mcpRepo = mcpRepository,
                    chatRepo = chatRepository,
                    chatWindowDays = chatWindowDays,
                )
                exportLauncher.launch(ConfigBackup.suggestedFileName())
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

    // Import is best-effort per item, so the result sheet has to say what did
    // NOT land — otherwise a partially-restored setup looks like a full one.
    importReport?.let { report ->
        AlertDialog(
            onDismissRequest = { importReport = null },
            title = { Text(stringResource(R.string.backup_done_title)) },
            text = {
                Column {
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
                TextButton(onClick = { importReport = null }) {
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
