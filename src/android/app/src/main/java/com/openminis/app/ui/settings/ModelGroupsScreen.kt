package com.openminis.app.ui.settings

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.DEFAULT_GROUP_CONTEXT_LIMIT_TOKENS
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.R
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SectionCard
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionDivider
import com.openminis.app.ui.components.SectionFooter
import com.openminis.app.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGroupsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onGroupClick: (String) -> Unit,
    /** [model-groups-simplify] Navigate to the standalone Agent Loop
     *  Models screen (Routes.AGENT_LOOP_MODELS). The inline
     *  AgentLoopModelsSection (T182/T185/T186) moved out of this page
     *  so the model-group list stays compact. */
    onAgentLoopClick: () -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    val groups = config.modelGroups
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }

    val lazyListState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_groups_model_groups)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.model_group_detail_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showNewGroupDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.model_groups_new_group))
                    }
                },
            )
        },
    ) { padding ->
        // T313: page background uses SectionDesign.screenBackgroundColor()
        // (= surfaceContainerLow), so the section cards painted in `surface`
        // visibly stand out as iOS-style inset-grouped panels.
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SectionDesign.screenBackgroundColor()),
        ) {
            item("top_gap") { Spacer(modifier = Modifier.height(SectionDesign.FirstSectionTopGap)) }

            if (groups.isEmpty()) {
                item("empty_state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.model_groups_no_model_groups),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.model_groups_groups_let_you_combine_models_for_fallba),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // T313 — Section 1 (Groups). One LazyColumn item wraps the
                // whole card; SectionCard + SectionDivider compose normally
                // because no reorder happens inside this section.
                item("groups_section_header") {
                    SectionHeader(text = stringResource(R.string.agent_loop_models_groups))
                }
                item("groups_section_card") {
                    SectionCard {
                        groups.forEachIndexed { index, group ->
                            val dismissState = rememberSwipeToDismissBoxState()
                            LaunchedEffect(dismissState.currentValue) {
                                if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                    providerRepository.removeGroup(group.id)
                                }
                            }
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {},
                                enableDismissFromStartToEnd = false,
                            ) {
                                GroupRow(
                                    group = group,
                                    config = config,
                                    onClick = { onGroupClick(group.id) },
                                    onSetPrimary = { providerRepository.defaultPrimaryGroupId = group.id },
                                    onSetSub = { providerRepository.defaultSubGroupId = group.id },
                                    onClearPrimary = { providerRepository.defaultPrimaryGroupId = null },
                                    onClearSub = { providerRepository.defaultSubGroupId = null },
                                )
                            }
                            if (index != groups.lastIndex) SectionDivider()
                        }
                    }
                }

                // [model-groups-simplify] The old "Defaults" section (two
                // full-width dropdowns for Default Primary / Default Sub,
                // plus the voice bindings) is gone. Setting a default is now
                // a per-row ⋮ menu action on each group row below.
            }

            // [model-groups-simplify] Agent Loop section collapsed into a
            // single entry row that navigates to the standalone
            // AgentLoopModelsScreen (Routes.AGENT_LOOP_MODELS). Rendered
            // whether or not groups exist.
            item("agent_loop_entry_spacer") {
                Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap))
            }
            item("agent_loop_entry_header") {
                SectionHeader(text = stringResource(R.string.agent_loop_section_entry_title))
            }
            item("agent_loop_entry_row") {
                SectionCard {
                    AgentLoopEntryRow(onClick = onAgentLoopClick)
                }
            }
            item("agent_loop_entry_footer") {
                SectionFooter(text = stringResource(R.string.agent_loop_section_footer))
            }

            item("bottom_gap") { Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap)) }
        }
    }

    if (showNewGroupDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewGroupDialog = false
                newGroupName = ""
            },
            title = { Text(stringResource(R.string.model_groups_new_group)) },
            text = {
                Column {
                    Text(
                        "Enter a name for the new model group.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text(stringResource(R.string.model_groups_group_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        if (newGroupName.isNotBlank()) {
                            val newGroup = ModelGroup(
                                name = newGroupName.trim(),
                                // [T-context-new-group-default] Start user-created
                                // groups with a sane context limit (128K) instead of
                                // null/unlimited, so they get context-pressure
                                // signals like the default group does. Users can
                                // raise or clear it in the group detail screen.
                                contextLimitTokens = DEFAULT_GROUP_CONTEXT_LIMIT_TOKENS,
                            )
                            providerRepository.addGroup(newGroup)
                            // Auto-set as primary default if it's the first group
                            if (config.modelGroups.size == 1 && config.defaultPrimaryGroupId == null) {
                                providerRepository.defaultPrimaryGroupId = newGroup.id
                            }
                            newGroupName = ""
                            showNewGroupDialog = false
                        }
                    },
                    enabled = newGroupName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.model_groups_create))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = {
                    showNewGroupDialog = false
                    newGroupName = ""
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Small colored badge label (e.g. "Primary", "Sub"). */
@Composable
private fun BadgeLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentLoopEntryRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Article,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.agent_loop_section_entry_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.agent_loop_section_entry_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}


/**
 * [T-android-modelgroup-modality-icons] One modality marker shown after a Model
 * Group title. `kind` is the modality string ("image"/"audio"/"video"/"pdf"/
 * "text") and `isOutput` distinguishes a generation output from an accepted
 * input. Ports iOS ModelGroupsView.GroupRow.topModalities /
 * modalityIcon (commits 7caa580a + e0a7cd5f).
 */
private data class GroupModalityMarker(val kind: String, val isOutput: Boolean)

/**
 * Distinctiveness ranking, most distinctive first — mirrors iOS
 * `modalityPriority`. `text` input is intentionally absent (universal noise);
 * `text` output ranks last (implied for every model, only surfaces when a group
 * has nothing more distinctive). Transcription (audio-in) outranks other inputs
 * so Whisper-style groups get flagged even though their output is plain text.
 */
private val GROUP_MODALITY_PRIORITY: List<GroupModalityMarker> = listOf(
    GroupModalityMarker("video", isOutput = true),
    GroupModalityMarker("image", isOutput = true),
    GroupModalityMarker("audio", isOutput = true),
    GroupModalityMarker("audio", isOutput = false),
    GroupModalityMarker("video", isOutput = false),
    GroupModalityMarker("image", isOutput = false),
    GroupModalityMarker("pdf", isOutput = false),
    GroupModalityMarker("text", isOutput = true),
)

/**
 * The group's top-2 most distinctive modalities across every member model's
 * inputs AND outputs, in priority order. Aggregates raw model modality lists
 * (Android convention — no capability inference); a null/empty outputModalities
 * counts as text output (iOS treats text-out as universal). Mirrors iOS
 * `GroupRow.topModalities`.
 */
private fun groupTopModalities(
    group: ModelGroup,
    config: com.openminis.app.data.model.ProviderConfig,
): List<GroupModalityMarker> {
    val inputs = mutableSetOf<String>()
    val outputs = mutableSetOf<String>()
    for (entryId in group.memberEntryIds) {
        val model = config.modelEntries.find { it.id == entryId }?.model ?: continue
        model.inputModalities.orEmpty().forEach { inputs.add(it.lowercase()) }
        val out = model.outputModalities.orEmpty()
        if (out.isEmpty()) outputs.add("text") else out.forEach { outputs.add(it.lowercase()) }
    }
    return GROUP_MODALITY_PRIORITY.filter { marker ->
        if (marker.isOutput) marker.kind in outputs else marker.kind in inputs
    }.take(2)
}

/**
 * Icon + tint + a11y label for one [GroupModalityMarker], reusing the glyph
 * convention from ProviderDetailScreen.ModalityIconsRow: output/generation
 * modalities use the primary tint + "generate"-style glyph; input modalities
 * use the muted onSurfaceVariant tint. Renders nothing for unknown combos.
 */
@Composable
private fun GroupModalityIcon(marker: GroupModalityMarker) {
    val outputTint = MaterialTheme.colorScheme.primary
    val inputTint = MaterialTheme.colorScheme.onSurfaceVariant
    val size = Modifier.size(14.dp)
    val (vector, labelRes, tint) = when {
        marker.isOutput && marker.kind == "video" -> Triple(Icons.Default.MovieCreation, R.string.modeldetail_video_output, outputTint)
        marker.isOutput && marker.kind == "image" -> Triple(Icons.Default.AddPhotoAlternate, R.string.modeldetail_image_output, outputTint)
        marker.isOutput && marker.kind == "audio" -> Triple(Icons.Default.VolumeUp, R.string.modelgroup_speech_output, outputTint)
        marker.isOutput && marker.kind == "text" -> Triple(Icons.AutoMirrored.Filled.Article, R.string.modelgroup_text_generation, outputTint)
        marker.kind == "audio" -> Triple(Icons.Default.Mic, R.string.modelgroup_speech_transcription, inputTint)
        marker.kind == "video" -> Triple(Icons.Default.Videocam, R.string.modeldetail_video_input, inputTint)
        marker.kind == "image" -> Triple(Icons.Default.Image, R.string.modeldetail_image_input, inputTint)
        marker.kind == "pdf" -> Triple(Icons.AutoMirrored.Filled.InsertDriveFile, R.string.modeldetail_pdf_input, inputTint)
        else -> return
    }
    Icon(imageVector = vector, contentDescription = stringResource(labelRes), modifier = size, tint = tint)
}

/** A single group row inside the Groups section card. Built as a plain
 *  Composable (not a ListItem) so it inherits the card's surface color
 *  cleanly without ListItem's container override. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupRow(
    group: ModelGroup,
    config: com.openminis.app.data.model.ProviderConfig,
    onClick: () -> Unit,
    onSetPrimary: () -> Unit,
    onSetSub: () -> Unit,
    onClearPrimary: () -> Unit,
    onClearSub: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val memberNames = group.memberEntryIds.mapNotNull { entryId ->
        config.modelEntries.find { it.id == entryId }?.model?.displayName
    }
    val preview = if (memberNames.size <= 3) {
        memberNames.joinToString(", ")
    } else {
        memberNames.take(3).joinToString(", ") + " +${memberNames.size - 3}"
    }
    val isPrimary = config.defaultPrimaryGroupId == group.id
    val isSub = config.defaultSubGroupId == group.id
    val strategyLabel = when (group.strategy) {
        RoutingStrategy.fallback -> stringResource(R.string.model_group_detail_fallback)
        RoutingStrategy.loadBalance -> stringResource(R.string.model_group_detail_load_balance)
    }
    // [T-disabled-provider-via-group-android] Count members whose provider
    // instance is currently enabled — that's what the runtime resolver
    // will actually consider. When every member sits behind a disabled
    // provider, surface a warning so the user understands why the group
    // appears empty in chat.
    val enabledInstanceIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    val totalMembers = group.memberEntryIds.size
    val enabledMembers = group.memberEntryIds.count { entryId ->
        config.modelEntries.find { it.id == entryId }?.providerInstanceId in enabledInstanceIds
    }
    val allDisabled = totalMembers > 0 && enabledMembers == 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = SectionDesign.RowHorizontalPadding,
                vertical = SectionDesign.RowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // [T-android-modelgroup-modality-icons] Mark the group's top-2
                // most distinctive modalities (inputs + outputs) right after the
                // title, in priority order, for at-a-glance "what is this group
                // for". Ports iOS ModelGroupsView (7caa580a + e0a7cd5f).
                groupTopModalities(group, config).forEach { marker ->
                    GroupModalityIcon(marker)
                }
                if (isPrimary) BadgeLabel(stringResource(R.string.model_groups_primary_badge), MaterialTheme.colorScheme.primary)
                if (isSub) BadgeLabel(stringResource(R.string.model_groups_sub_badge), MaterialTheme.colorScheme.tertiary)
                if (allDisabled) {
                    BadgeLabel(
                        stringResource(R.string.model_group_no_usable_models_badge),
                        MaterialTheme.colorScheme.error,
                    )
                }
            }
            // [T-disabled-provider-via-group-android] Surface "M of N
            // disabled" when any member's provider is off so the user
            // doesn't have to drill into the detail page to learn that
            // the group isn't fully usable. Total count stays prominent
            // because it's still the source of truth for what's in the
            // group; the parenthetical reports disabled count.
            val disabledCount = totalMembers - enabledMembers
            val subtitleText = if (disabledCount > 0) {
                "$strategyLabel · $totalMembers models ($disabledCount disabled)"
            } else {
                "$strategyLabel · $totalMembers models"
            }
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = if (allDisabled) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (preview.isNotEmpty()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.model_groups_row_menu),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                if (isPrimary) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_groups_clear_default_primary)) },
                        onClick = { onClearPrimary(); menuExpanded = false },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_groups_set_default_primary)) },
                        onClick = { onSetPrimary(); menuExpanded = false },
                    )
                }
                if (isSub) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_groups_clear_default_sub)) },
                        onClick = { onClearSub(); menuExpanded = false },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_groups_set_default_sub)) },
                        onClick = { onSetSub(); menuExpanded = false },
                    )
                }
            }
        }
    }
}
