package com.openminis.app.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.backup.ConfigBackup
import com.openminis.app.data.repository.ProviderRepository

/**
 * Local backup / restore of app configuration — providers, appearance, and the
 * agent-runtime defaults. Scope is intentionally a plain file on the device:
 * no cloud, no WebDAV, nothing that needs an account.
 *
 * Chat history is NOT included. It lives in Room and is a different (much
 * larger) problem than settings; mixing the two would make a "restore my setup"
 * action unpredictably heavy.
 */
@Composable
fun BackupSettingsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

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
            importReport = ConfigBackup.import(providerRepository, json)
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
