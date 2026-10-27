package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.app.data.model.ThinkingLevel

/**
 * [feat/subagent-roster-routing] P0-A — the editor for the CONFIGURED
 * sub-agent roster.
 *
 * The roster had a complete data model, storage, CRUD and backup merge from
 * the start — and NO WAY TO EDIT IT. `SubAgentRoster.resolve` had zero call
 * sites and the `subagent_task` tool was never registered, so the roster was
 * a data structure no user could reach and no runtime consumed.
 *
 * This screen is the missing half: it makes the roster reachable, and the
 * runtime (SubagentDelegation) makes it meaningful.
 *
 * The three fields map to the three questions a delegation needs answered:
 *  - NAME: what the parent emits to pick this agent (the wire identifier;
 *    deliberately not localized);
 *  - DESCRIPTION: WHEN to delegate here (the only free text that reaches
 *    the parent's context, so it is bounded);
 *  - INSTRUCTIONS: WHAT this agent must do (child-session only, so it can
 *    be longer).
 */
@Composable
fun SubAgentManagementScreen(
    definitions: List<SubAgentDefinition>,
    onUpsert: (SubAgentDefinition) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    /**
     * [feat/subagent-roster-routing] The model groups the user can pin
     * this agent to.
     *
     * The roster entry's `modelGroupId` existed in the data model from the
     * start and was never reachable — and, until this branch, never read
     * by the runtime either. It is now both settable AND enforced.
     */
    modelGroups: List<ModelGroup> = emptyList(),
    /**
     * The reasoning levels the user can pin. Same story as the group: a
     * stored-but-unreachable field, now settable and honoured.
     */
    thinkingLevels: List<ThinkingLevel> = ThinkingLevel.entries,
) {
    var editing by remember { mutableStateOf<SubAgentDefinition?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.sub_agent_roster_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Text(
                text = stringResource(R.string.sub_agent_roster_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(definitions, key = { it.id }) { def ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = def.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = def.description.ifBlank {
                                    stringResource(R.string.sub_agent_roster_no_description)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { editing = def }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit))
                        }
                        if (def.id != SubAgentDefinition.BUILT_IN_ID) {
                            IconButton(onClick = { onDelete(def.id) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.common_delete),
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = { creating = true },
                enabled = definitions.size < SubAgentLimits.MAX_COUNT,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.sub_agent_roster_add),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (definitions.size >= SubAgentLimits.MAX_COUNT) {
            item {
                Text(
                    text = stringResource(R.string.sub_agent_roster_limit, SubAgentLimits.MAX_COUNT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (creating || editing != null) {
        SubAgentEditorDialog(
            initial = editing,
            modelGroups = modelGroups,
            thinkingLevels = thinkingLevels,
            onDismiss = { creating = false; editing = null },
            onSave = { def ->
                onUpsert(def)
                creating = false
                editing = null
            },
        )
    }
}

/**
 * The add/edit dialog. Validates the name and description against the same
 * [SubAgentLimits] the storage and the prompt injection use, so the editor
 * cannot create a definition the runtime would later clamp.
 */
@Composable
private fun SubAgentEditorDialog(
    initial: SubAgentDefinition?,
    onDismiss: () -> Unit,
    onSave: (SubAgentDefinition) -> Unit,
    modelGroups: List<ModelGroup>,
    thinkingLevels: List<ThinkingLevel>,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var instructions by remember { mutableStateOf(initial?.instructions.orEmpty()) }
    var modelGroupId by remember { mutableStateOf(initial?.modelGroupId) }
    var thinkingLevel by remember { mutableStateOf(initial?.thinkingLevelOverride) }
    var groupExpanded by remember { mutableStateOf(false) }
    var thinkingExpanded by remember { mutableStateOf(false) }

    val nameError = when {
        name.isBlank() -> stringResource(R.string.sub_agent_roster_name_required)
        !name.matches(Regex("^[A-Za-z][A-Za-z0-9_-]*$")) ->
            stringResource(R.string.sub_agent_roster_name_invalid)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.sub_agent_roster_add
                    else R.string.sub_agent_roster_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= SubAgentLimits.NAME_MAX_LENGTH) name = it },
                    label = { Text(stringResource(R.string.sub_agent_roster_name)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        if (it.length <= SubAgentLimits.DESCRIPTION_MAX_LENGTH) description = it
                    },
                    label = { Text(stringResource(R.string.sub_agent_roster_description)) },
                    supportingText = {
                        Text(stringResource(R.string.sub_agent_roster_description_count, description.length))
                    },
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = {
                        if (it.length <= SubAgentLimits.INSTRUCTIONS_MAX_LENGTH) instructions = it
                    },
                    label = { Text(stringResource(R.string.sub_agent_roster_instructions)) },
                    minLines = 3,
                )
                // [feat/subagent-roster-routing] The model group and the
                // reasoning level. Both were stored in the data model and
                // unreachable from the UI — and unread by the runtime.
                if (modelGroups.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = groupExpanded,
                        onExpandedChange = { groupExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = modelGroups.firstOrNull { it.id == modelGroupId }?.name
                                ?: stringResource(R.string.sub_agent_roster_any_model),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.sub_agent_roster_model_group)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = groupExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = groupExpanded,
                            onDismissRequest = { groupExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sub_agent_roster_any_model)) },
                                onClick = {
                                    modelGroupId = null
                                    groupExpanded = false
                                },
                            )
                            modelGroups.forEach { group ->
                                DropdownMenuItem(
                                    text = { Text(group.name) },
                                    onClick = {
                                        modelGroupId = group.id
                                        groupExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (thinkingLevels.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = thinkingExpanded,
                        onExpandedChange = { thinkingExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = thinkingLevel?.displayName
                                ?: stringResource(R.string.sub_agent_roster_inherit_thinking),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.sub_agent_roster_thinking)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = thinkingExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = thinkingExpanded,
                            onDismissRequest = { thinkingExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sub_agent_roster_inherit_thinking)) },
                                onClick = {
                                    thinkingLevel = null
                                    thinkingExpanded = false
                                },
                            )
                            thinkingLevels.forEach { level ->
                                DropdownMenuItem(
                                    text = { Text(level.displayName) },
                                    onClick = {
                                        thinkingLevel = level
                                        thinkingExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val base = initial ?: SubAgentDefinition(
                        name = name.trim(),
                        description = description.trim(),
                    )
                    onSave(
                        base.copy(
                            name = name.trim(),
                            description = description.trim(),
                            instructions = instructions,
                            modelGroupId = modelGroupId,
                            thinkingLevelOverride = thinkingLevel,
                        ),
                    )
                },
                enabled = nameError == null && name.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
