package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.R

private const val TAG = "ManageProviderModels"

/**
 * [T-provider-detail-visible-models] Full-catalog manager for ONE provider.
 *
 * The provider detail screen now lists only the visible (selected) models.
 * This screen is the escape hatch: it shows EVERY model the provider exposes
 * (the same `modelEntries` the repo pulled via refreshModels — nothing is
 * re-fetched here), with a search box to narrow the list and a per-row
 * Switch that toggles visibility (switch on = visible / off = isHidden).
 *
 * Toggling calls ProviderRepository.updateEntry(entry.copy(isHidden = ...)),
 * which persists to the state-backed config and propagates reactively to the
 * detail screen's visibleEntries list, the model picker, and everywhere else
 * that reads `!isHidden`. Mirrors rikkahub's "pull everything, display the
 * chosen few" — the network cost of loading the catalog is paid once at
 * refresh; the user only ever deals with the handful they actually want.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageProviderModelsScreen(
    instanceId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onModelEntryClick: (String) -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val instance = config.instances.find { it.id == instanceId }
    if (instance == null) { onBack(); return }

    // Full catalog for this provider, cached per (instanceId, config) exactly
    // like ProviderDetailScreen does — no full-list rescan per recomposition.
    val allEntries = remember(instanceId, config) {
        providerRepository.entriesFor(instanceId)
    }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(allEntries, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { entry ->
                entry.model.displayName.contains(q, ignoreCase = true) ||
                    entry.model.id.contains(q, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.provider_detail_models_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ─── Search ─────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SectionTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    placeholder = stringResource(R.string.provider_detail_manage_models_search_hint),
                )
            }

            // ─── Count header ───────────────────────────────────────
            Text(
                text = stringResource(R.string.provider_detail_manage_models_all_header, allEntries.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // ─── Model list ─────────────────────────────────────────
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.model_picker_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // LazyColumn in full height — this screen owns its scrollable,
                // no outer verticalScroll wrapper, so an unbounded LazyColumn is
                // legal (unlike inside ProviderDetailScreen's SettingsScaffold).
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(filtered, key = { _, entry -> entry.id }) { idx, entry ->
                        ManageModelRow(
                            entry = entry,
                            showDivider = idx != filtered.lastIndex,
                            onToggleVisible = { visible ->
                                providerRepository.updateEntry(entry.copy(isHidden = !visible))
                                AppLogger.info(
                                    TAG,
                                    "Set isHidden=${!visible} for ${entry.model.id} (${instance.id})",
                                )
                            },
                            onClick = { onModelEntryClick(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One row in the manage screen: same visual language as ProviderDetailScreen's
 * row (name + model id + input/output capability badges) but with a right-hand
 * Switch that toggles the model's visibility instead of a chevron. Tapping the
 * row body still opens the entry detail screen; the Switch is independent.
 */
@Composable
private fun ManageModelRow(
    entry: ModelEntry,
    showDivider: Boolean,
    onToggleVisible: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Dim hidden entries — consistent with ProviderDetailScreen, so a
            // hidden model reads as "off" at a glance (alpha is visual-only;
            // the row stays tappable to open its detail screen).
            .then(if (entry.isHidden) Modifier.alpha(0.45f) else Modifier),
    ) {
        val inputModalities = entry.model.inputModalities.orEmpty()
        val outputModalities = entry.model.outputModalities.orEmpty()
        val hasBadge = inputModalities.any { it in modalityIconKeys } ||
            outputModalities.any { it in modalityOutputIconKeys }
        SettingsRow(
            title = entry.model.displayName,
            subtitle = entry.model.id,
            onClick = onClick,
            showChevron = true,
            showDivider = showDivider,
            minHeight = 72.dp,
            trailing = {
                Row(
                    modifier = Modifier.width(110.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (hasBadge) {
                            ModalityIconsRow(inputModalities, outputModalities)
                        }
                    }
                    Switch(
                        checked = !entry.isHidden,
                        onCheckedChange = onToggleVisible,
                        colors = SwitchDefaults.colors(),
                    )
                }
            },
        )
    }
}
