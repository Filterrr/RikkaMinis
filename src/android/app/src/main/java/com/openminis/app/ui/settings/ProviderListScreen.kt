package com.openminis.app.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ModelRefreshResult
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onProviderClick: (String) -> Unit,
    // [feat3-lan-discovery] "Add this LAN server as a provider": navigates
    // to ADD_PROVIDER; the picked base URL rides the nav shared state.
    onAddProviderWithLanPick: (String) -> Unit = { onAddProvider() },
) {
    val config by providerRepository.config.collectAsState()
    val instances = config.instances
    val pinnedInstances = instances.filter { it.pinned }
    val context = LocalContext.current

    // [perf-provider-list] Pre-compute per-instance display data once per
    // `instances` change. loadApiKey() hits EncryptedSharedPreferences
    // (synchronous encrypted I/O); naive inline calls inside the forEach
    // re-ran them for EVERY row on EVERY recomposition, stalling the frame
    // during navigation transitions. Caching here means the I/O happens once
    // per instances change, not once per row per recomposition.
    val providerRows: List<ProviderRowData> = remember(instances) {
        instances.map { instance ->
            val apiKey = providerRepository.loadApiKey(instance.id)
            val isConfigured = !apiKey.isNullOrBlank()
            ProviderRowData(
                instance = instance,
                modelCount = providerRepository.visibleEntries(instance.id).size,
                apiKey = apiKey,
                isConfigured = isConfigured,
            )
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    // [feat3-lan-discovery] Controls the LAN scan bottom sheet.
    var lanScanOpen by remember { mutableStateOf(false) }
    // [feat3-lan-discovery] Base URL picked from the LAN scan → passed to
    // the provider-detail screen as the pre-filled custom base for a NEW
    // OpenAI-compatible instance.
    var lanPickedBaseUrl by remember { mutableStateOf<String?>(null) }

    // [provider-list-sync-all] One-tap force refresh of every enabled provider,
    // straight from the top bar. isSyncing shows a spinner in place of the icon
    // so double-taps are naturally gated; the IO dispatcher keeps the awaited
    // repository call off the main thread. Per-instance failures are isolated
    // inside refreshModels() (exceptions map to FAILURE/PRESERVED), so one
    // broken provider never aborts the batch. The explicit allowlist of
    // Throwable mirrors the surrounding screens' blanket-catch posture.
    val syncScope = rememberCoroutineScope()
    var isSyncing by remember { mutableStateOf(false) }

    fun syncSummary(ok: Int, noKey: Int, failed: Int, context: android.content.Context): String =
        if (ok > 0 && noKey == 0 && failed == 0) {
            context.getString(R.string.provider_list_sync_all_success, ok)
        } else {
            context.getString(R.string.provider_list_sync_partial, ok, noKey, failed)
        }


    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri).orEmpty()
        val name = ProviderImportZip.queryDisplayName(context, uri).orEmpty()
        val looksLikeZip = mime == "application/zip" ||
            mime == "application/x-zip-compressed" ||
            name.lowercase().endsWith(".zip")
        try {
            if (looksLikeZip) {
                val toastFailed = context.getString(R.string.import_zip_extract_failed)
                val toastNoSupported = context.getString(R.string.import_zip_no_supported)
                ProviderImportZip.importFromZip(
                    context = context,
                    uri = uri,
                    onImportSingle = { jsonStr -> providerRepository.importInstanceJSON(jsonStr) },
                    onExtractFailed = { Toast.makeText(context, toastFailed, Toast.LENGTH_SHORT).show() },
                    onNoSupported = { Toast.makeText(context, toastNoSupported, Toast.LENGTH_SHORT).show() },
                    onSummary = { ok, total ->
                        val msg = context.getString(R.string.import_zip_summary, ok, total)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                )
            } else {
                val jsonStr = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (jsonStr != null) {
                    val label = providerRepository.importInstanceJSON(jsonStr)
                    if (label != null) {
                        val toastMsg = context.getString(R.string.provider_import_success, label)
                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.provider_import_invalid_file), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.provider_import_read_error), Toast.LENGTH_SHORT).show()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.provider_list_providers),
        onBack = null, // top-level page: rely on system back gesture / bottom nav
        actions = {
            // [provider-list-sync-all] Force-refresh every enabled provider in
            // one tap. Sits before the Add action, matching iOS-style ordering
            // (secondary utility action, then the primary +).
            IconButton(
                onClick = {
                    if (isSyncing) return@IconButton
                    isSyncing = true
                    syncScope.launch {
                        try {
                            val results = withContext(Dispatchers.IO) {
                                providerRepository.refreshAllModelsForce()
                            }
                            val ok = results.count { it.second == ModelRefreshResult.SUCCESS_API }
                            val noKey = results.count { it.second == ModelRefreshResult.NO_KEY }
                            val failed = results.count {
                                it.second == ModelRefreshResult.FAILURE || it.second == ModelRefreshResult.PRESERVED
                            }
                            Toast.makeText(
                                context,
                                syncSummary(ok, noKey, failed, context),
                                Toast.LENGTH_SHORT,
                            ).show()
                            AppLogger.info(
                                "ProviderList",
                                "Sync all finished: ok=$ok noKey=$noKey failed=$failed total=${results.size}",
                            )
                        } catch (ce: java.util.concurrent.CancellationException) {
                            throw ce
                        } catch (ie: java.lang.IllegalStateException) {
                            Toast.makeText(context, context.getString(R.string.provider_list_sync_failed_generic), Toast.LENGTH_SHORT).show()
                            AppLogger.error("ProviderList", "Sync all failed: ${ie.javaClass.simpleName}: ${ie.message}")
                        } catch (re: java.lang.RuntimeException) {
                            Toast.makeText(context, context.getString(R.string.provider_list_sync_failed_generic), Toast.LENGTH_SHORT).show()
                            AppLogger.error("ProviderList", "Sync all failed: ${re.javaClass.simpleName}: ${re.message}")
                        } finally {
                            isSyncing = false
                        }
                    }
                },
                enabled = !isSyncing,
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = stringResource(R.string.provider_list_sync_all),
                    )
                }
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.provider_list_add_provider))
            }
        },
    ) {
        if (instances.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
                Text(
                    text = stringResource(R.string.provider_list_no_providers_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.provider_list_add_a_provider_to_get_started),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        } else {
            // [P0-pinned-providers] Favorites section: pinned instances float
            // to the very top, separate from their providerType group, so the
            // providers the user reaches for most are always one tap away.
            if (pinnedInstances.isNotEmpty()) {
                SettingsSection(header = stringResource(R.string.provider_list_favorites)) {
                    val pinnedRows = providerRows.filter { it.instance.pinned }
                    pinnedRows.forEachIndexed { index, row ->
                        ProviderInstanceRow(
                            instance = row.instance,
                            modelCount = row.modelCount,
                            apiKey = row.apiKey,
                            isConfigured = row.isConfigured,
                            pinned = row.instance.pinned,
                            onTogglePinned = remember(row.instance.id) {
                                { providerRepository.setInstancePinned(row.instance.id, !row.instance.pinned) }
                            },
                            onClick = remember(row.instance.id) { { onProviderClick(row.instance.id) } },
                        )
                        if (index < pinnedRows.size - 1) {
                            val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 38.dp, end = 14.dp)
                                    .height(0.5.dp)
                                    .background(divider),
                            )
                        }
                    }
                }
            }
            val groupedRows = providerRows.filter { !it.instance.pinned }.groupBy { it.instance.providerType }
            groupedRows.forEach { (providerType, typeRows) ->
                SettingsSection(header = providerType.displayName) {
                    typeRows.forEachIndexed { index, row ->
                        ProviderInstanceRow(
                            instance = row.instance,
                            modelCount = row.modelCount,
                            apiKey = row.apiKey,
                            isConfigured = row.isConfigured,
                            pinned = row.instance.pinned,
                            onTogglePinned = remember(row.instance.id) {
                                { providerRepository.setInstancePinned(row.instance.id, !row.instance.pinned) }
                            },
                            onClick = remember(row.instance.id) { { onProviderClick(row.instance.id) } },
                        )
                        if (index < typeRows.size - 1) {
                            val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 38.dp, end = 14.dp)
                                    .height(0.5.dp)
                                    .background(divider),
                            )
                        }
                    }
                }
            }
        }

        // [voice-removed] The runtime "Voice Services" shadow section was
        // removed along with the rest of the in-app voice UI. The underlying
        // voice provider engine still exists for agent-facing tools; it just no
        // longer surfaces as its own provider-list section here.
        Spacer(Modifier.height(80.dp))
    }

    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMenu = false
                            onAddProvider()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.provider_list_add_provider), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                // [feat3-lan-discovery] Scan the LAN for Ollama / LM Studio /
                // vLLM / llama.cpp servers and offer them as ready-made
                // OpenAI-compatible providers.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMenu = false
                            lanScanOpen = true
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Radar, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.lan_scan_title), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMenu = false
                            importLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "application/zip",
                                    "application/x-zip-compressed",
                                ),
                            )
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.provider_list_import_provider), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    // [feat3-lan-discovery] LAN model-server scan sheet: NSD browse + /24
    // port sweep, results offered as ready-made OpenAI-compatible provider
    // base URLs (one tap → ProviderDetailScreen with the base pre-filled
    // via the shared draft mechanism).
    if (lanScanOpen) {
        LanScanSheet(
            onDismiss = { lanScanOpen = false },
            onPick = { candidate ->
                lanScanOpen = false
                // [feat3-lan-discovery] Hand the picked base URL to the
                // add-provider screen via the shared nav state; one-shot
                // consumption there (state initializer reads it once).
                lanPickedBaseUrl = candidate.baseUrl
                onAddProviderWithLanPick(candidate.baseUrl)
            },
        )
    }
}

/**
 * [feat3-lan-discovery] Bottom sheet running [LanModelDiscovery.discover]
 * once per open. Lists candidates with source (mDNS/port-scan) and lets the
 * user add one as a new OpenAI-compatible provider. Empty state explains
 * that servers must be on the same Wi-Fi and reachable.
 */
@Composable
private fun LanScanSheet(
    onDismiss: () -> Unit,
    onPick: (com.openminis.app.network.LanModelDiscovery.Candidate) -> Unit,
) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(true) }
    var candidates by remember {
        mutableStateOf<List<com.openminis.app.network.LanModelDiscovery.Candidate>>(emptyList())
    }

    LaunchedEffect(Unit) {
        scanning = true
        candidates = com.openminis.app.network.LanModelDiscovery.discover(context)
        scanning = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.lan_scan_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.lan_scan_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (scanning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.lan_scan_scanning))
                }
            } else if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.lan_scan_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                candidates.forEach { c ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(c) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${c.baseUrl}  ·  ${c.source}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.lan_scan_add),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProviderInstanceRow(
    instance: ProviderInstance,
    modelCount: Int,
    apiKey: String?,
    isConfigured: Boolean,
    pinned: Boolean,
    onTogglePinned: () -> Unit,
    onClick: () -> Unit,
) {
    val isActive = isConfigured && instance.isEnabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (isActive) ChatColors.success else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    shape = CircleShape,
                ),
        )

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = instance.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.provider_list_api_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Text(
                    text = if (!apiKey.isNullOrBlank()) maskKey(apiKey) else stringResource(R.string.provider_list_no_api_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (modelCount > 0) {
                Text(
                    text = stringResource(R.string.provider_list_models_count, modelCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        if (!instance.isEnabled) {
            Text(
                text = stringResource(R.string.provider_list_disabled),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(50),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
        }

        // [P0-pinned-providers] Inline star toggles favorite directly — no overflow menu needed.
        IconButton(
            onClick = onTogglePinned,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = if (pinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (pinned) R.string.provider_unset_favorite
                    else R.string.provider_set_favorite,
                ),
                tint = if (pinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                modifier = Modifier.size(20.dp),
            )
        }


    }
}

private fun maskKey(key: String): String {
    if (key.length <= 8) return "****"
    return key.take(6) + "..." + key.takeLast(4)
}

/** [perf-provider-list] Per-instance display data, pre-computed once per
 *  instances change so the per-row composition never re-runs
 *  EncryptedSharedPreferences reads (loadApiKey / OAuth isAuthenticated). */
private data class ProviderRowData(
    val instance: ProviderInstance,
    val modelCount: Int,
    val apiKey: String?,
    val isConfigured: Boolean,
)
