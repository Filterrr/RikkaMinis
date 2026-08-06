package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.EpisodeMemoryStore
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.components.DialogTextField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings-level memory file management.
 * Lists GLOBAL.md + daily logs in grouped card style.
 * Tapping a file navigates to a full-page editor.
 * GLOBAL.md cannot be deleted.
 * Mirrors iOS MemoryManagementView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagementScreen(
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
    onFileClick: (fileName: String, isGlobal: Boolean) -> Unit = { _, _ -> },
) {
    var files by remember { mutableStateOf<List<MemoryRepository.MemoryFileInfo>>(emptyList()) }
    var deleteFileName by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // [ExpMem] Experience memory (episodic): auto-records every finished
    // exchange (query, tools, outcome, reply) into a local plain-text JSONL
    // file and injects up to 3 similar past episodes before each reply.
    // Zero extra model calls; retrieval is keyword scoring; the verification
    // counter (+1 on success / -1 on failure) ranks proven episodes higher.
    // Declared here (before SettingsScaffold) so both the section and the
    // clear-confirm dialog below can reference them.
    var expEnabled by remember {
        mutableStateOf(com.openminis.app.data.ExperienceMemoryPrefs.isEnabled(context))
    }
    val expStore = remember(context) {
        com.openminis.app.data.EpisodeMemoryStore(
            java.io.File(context.filesDir, "minis-global/memory/episodes.jsonl")
        )
    }
    var expSize by remember { mutableStateOf(expStore.size()) }
    var expClearConfirm by remember { mutableStateOf(false) }
    // [ExpMem-viewer] Inline episode viewer: "View recorded episodes" row
    // expands in place to a compact list; tapping a row opens a detail dialog.
    var expExpanded by remember { mutableStateOf(false) }
    var expEpisodes by remember { mutableStateOf<List<EpisodeMemoryStore.Episode>>(emptyList()) }
    var expDetail by remember { mutableStateOf<EpisodeMemoryStore.Episode?>(null) }
    // [T-memory-global-toggle-settings-ui-android] Global default for
    // newly-created sessions. Stored separately from per-session
    // memoryEnabled (which lives in the sessions DB row) so toggling
    // here never retroactively rewrites existing chats. Read once on
    // entry; the Switch's onCheckedChange writes back synchronously
    // and updates the local state mirror.
    var globalMemoryOn by remember {
        mutableStateOf(com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context))
    }

    LaunchedEffect(Unit) {
        files = memoryRepository.listAllFiles()
    }

    // top-level page: rely on system back gesture / bottom nav (no back arrow)
    SettingsScaffold(title = stringResource(R.string.memory_title), onBack = null) {
        // Always-visible global toggle — sits above the file list so the
        // user finds it whether or not any memory files exist yet.
        SettingsSection(
            header = stringResource(R.string.settings_memory_global_header),
            footer = stringResource(R.string.settings_memory_global_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_memory_global_enabled_title),
                subtitle = stringResource(R.string.settings_memory_global_enabled_subtitle),
                checked = globalMemoryOn,
                onCheckedChange = { newValue ->
                    globalMemoryOn = newValue
                    com.openminis.app.data.MemoryGlobalPrefs.setGlobalEnabled(context, newValue)
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.settings_memory_experience_header),
            footer = stringResource(R.string.settings_memory_experience_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_memory_experience_enabled_title),
                subtitle = stringResource(R.string.settings_memory_experience_enabled_subtitle),
                checked = expEnabled,
                onCheckedChange = { newValue ->
                    expEnabled = newValue
                    com.openminis.app.data.ExperienceMemoryPrefs.setEnabled(context, newValue)
                },
                showDivider = true,
            )
            // View recorded episodes — expandable inline list (newest first)
            SettingsRow(
                title = stringResource(R.string.memory_experience_view),
                subtitle = if (expSize > 0) {
                    stringResource(R.string.memory_experience_entries, expSize)
                } else {
                    stringResource(R.string.memory_experience_view_empty)
                },
                onClick = {
                    expExpanded = !expExpanded
                    if (expExpanded) {
                        // [ExpMem-viewer] reload on every expand so episodes
                        // recorded while the list was collapsed show up.
                        expEpisodes = expStore.snapshot().reversed()
                    }
                },
                showDivider = true,
                showChevron = false,
                trailing = {
                    Icon(
                        imageVector = if (expExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            if (expExpanded) {
                if (expEpisodes.isEmpty()) {
                    Text(
                        stringResource(R.string.memory_experience_view_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                } else {
                    expEpisodes.forEachIndexed { index, episode ->
                        EpisodeRow(
                            episode = episode,
                            onClick = { expDetail = episode },
                            showDivider = index < expEpisodes.size - 1,
                        )
                    }
                }
            }
            // Clear experience memory — destructive, always last
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expClearConfirm = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.memory_experience_clear),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (files.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.memory_empty_title), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.memory_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SettingsSection(
                header = stringResource(R.string.memory_section_files),
                footer = stringResource(R.string.memory_section_footer),
            ) {
                files.forEachIndexed { index, file ->
                    MemoryFileRow(
                        file = file,
                        onClick = { onFileClick(file.name, file.isGlobal) },
                        onDelete = if (!file.isGlobal) { { deleteFileName = file.name } } else null,
                    )
                    if (index < files.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Delete confirmation
    if (deleteFileName != null) {
        AlertDialog(
            onDismissRequest = { deleteFileName = null },
            title = { Text("Delete ${deleteFileName}?") },
            text = { Text(stringResource(R.string.memory_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    deleteFileName?.let {
                        memoryRepository.deleteFile(it)
                        files = memoryRepository.listAllFiles()
                    }
                    deleteFileName = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteFileName = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // Experience memory clear confirmation
    if (expClearConfirm) {
        AlertDialog(
            onDismissRequest = { expClearConfirm = false },
            title = { Text(stringResource(R.string.memory_experience_clear_confirm_title)) },
            text = { Text(stringResource(R.string.memory_experience_clear_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    expStore.clear()
                    expSize = 0
                    expEpisodes = emptyList()
                    expClearConfirm = false
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { expClearConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // [ExpMem-viewer] Episode detail dialog — query, meta, tools, reply.
    // Built with plain Dialog + Surface (NOT AlertDialog): M3 AlertDialog
    // constrains the text slot and its fixed content area eats the vertical
    // drag, so a long reply ends up clipped with no way to scroll it.
    // A Dialog + Surface lets us own the height and scroll the Column freely.
    expDetail?.let { ep ->
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ep.t))
        val outcomeStr = stringResource(
            if (ep.ok) R.string.memory_experience_outcome_ok else R.string.memory_experience_outcome_fail
        )
        val toolsText = if (ep.tools.isEmpty()) {
            stringResource(R.string.memory_experience_detail_no_tools)
        } else {
            ep.tools.joinToString(", ") { t -> "${t.n}(${if (t.ok) "✓" else "✗"})" }
        }
        val radius = RoundedCornerShape(28.dp)
        Dialog(
            onDismissRequest = { expDetail = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f),
                shape = radius,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 16.dp,
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxHeight(),
                ) {
                    // Title area (fixed, not scrolled)
                Text(
                    ep.q.ifBlank { "—" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable body — owns its height, so a long reply can be
                // dragged to reveal everything below the fold.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(end = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.memory_experience_detail_meta,
                            dateStr, outcomeStr, ep.durMs / 1000, ep.v
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.memory_experience_detail_tools),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(toolsText, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.memory_experience_detail_reply),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        ep.reply.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Close button (fixed, not scrolled)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { expDetail = null }) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun MemoryFileRow(
    file: MemoryRepository.MemoryFileInfo,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (file.fileSize.isNotBlank()) {
                        Text(
                            file.fileSize,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
                Text(
                    file.modifiedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (file.preview.isNotBlank()) {
                Text(
                    file.preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Compact single-episode row inside the expanded Experience Memory list.
 * Green/red dot = outcome, title = query, subtitle = tool names,
 * trailing = reuse count + chevron (tap opens detail dialog).
 */
@Composable
private fun EpisodeRow(
    episode: EpisodeMemoryStore.Episode,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (episode.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    ),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    episode.q.ifBlank { "—" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    if (episode.tools.isEmpty()) "—"
                    else episode.tools.joinToString(", ") { it.n },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (episode.v > 0) {
                Text(
                    stringResource(R.string.memory_experience_reuse, episode.v),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 34.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

/**
 * Full-page memory file editor, matching iOS MemoryFileEditView.
 * Monospaced text. The Save button stays ALWAYS visible.
 *
 * [T-global-memory-save-always-visible] Previously the Save action was
 * gated on a `hasChanges` flag that only flipped true inside the field's
 * onValueChange. Programmatic content changes (paste, IME commit, state
 * restore) don't always route through onValueChange, so Save could fail
 * to appear after a paste until the user typed another key — the exact
 * symptom reported on iOS/macOS (XIN msg 41384). Keeping Save permanently
 * visible removes the dependency entirely; saveFile is idempotent so a
 * no-op Save on unchanged content is harmless.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryFileEditScreen(
    fileName: String,
    isGlobal: Boolean,
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
) {
    var content by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // [T-memory-save-toast-feedback] Confirm Save actually committed by
    // flashing a toast — previously the Save tap silently closed nothing,
    // showed no state change, and the user had no signal that the edit
    // landed (user-reported confusion). Reuses the existing
    // memory_save_toast string already wired for the per-chat memory
    // detail editor's SavedToast so the wording stays consistent.
    val savedToastText = stringResource(R.string.memory_save_toast)

    LaunchedEffect(fileName) {
        content = memoryRepository.readFile(fileName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // [T-global-memory-save-always-visible] Always render Save —
                    // no hasChanges gate (see KDoc above).
                    MinisTextButton(onClick = {
                        try {
                            memoryRepository.saveFile(fileName, content)
                            saveError = null
                            android.widget.Toast.makeText(
                                context,
                                savedToastText,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } catch (e: Exception) {
                            saveError = e.message
                        }
                    }) {
                        Text("Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Editor
            DialogTextField(
                value = content,
                onValueChange = {
                    content = it
                },
                singleLine = false,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                placeholder = stringResource(R.string.memory_editor_placeholder),
                modifier = Modifier.weight(1f),
            )

            // Footer text
            if (isGlobal) {
                Text(
                    stringResource(R.string.memory_global_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (saveError != null) {
                Text(
                    saveError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
