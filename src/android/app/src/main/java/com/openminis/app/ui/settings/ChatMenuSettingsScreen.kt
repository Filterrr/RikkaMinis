package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.config.ChatMenuPrefs

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Chat Menu customization — dedicated screen reached from
 * Settings → Appearance → Chat Menu.
 *
 * Lists every customizable entry of the chat "..." menu. Each row:
 *   - a drag handle (≡) to grab and reorder (long-press + drag, live
 *     swap as you cross the half-row boundary),
 *   - a visibility Switch (hiding an entry only removes it from the
 *     menu — the feature and its data stay intact; re-enable any time).
 *
 * Order and visibility are persisted to appearance_prefs via ChatMenuPrefs,
 * so they round-trip through minis-config and every local backup.
 */
@Composable
fun ChatMenuSettingsScreen(
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { getAppearancePrefs(context) }
    val rowHeight = 56.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }

    var order by remember { mutableStateOf(ChatMenuPrefs.resolveOrder(prefs)) }
    // Index currently being dragged, plus its live pixel offset.
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun commitOrder(newOrder: List<String>) {
        order = newOrder
        ChatMenuPrefs.writeOrder(prefs, newOrder)
    }

    SettingsScaffold(
        title = stringResource(R.string.appearance_section_chat_menu),
        onBack = onBack,
        navigation = {},
    ) {
        SettingsSection(
            header = stringResource(R.string.appearance_section_chat_menu),
            footer = stringResource(R.string.appearance_chat_menu_footer),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                order.forEachIndexed { index, entryKey ->
                    val isDragging = index == draggingIndex
                    val visible = ChatMenuPrefs.isVisible(prefs, entryKey)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .offset {
                                IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0)
                            }
                            .pointerInput(entryKey) {
                                // Long-press anywhere on the row to lift it, then
                                // drag vertically. While dragging, crossing the
                                // half-row boundary swaps the entry with its
                                // neighbour — live, with the offset corrected so
                                // the gesture stays 1:1 with the finger.
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingIndex = index
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val currentIndex = draggingIndex
                                        if (currentIndex < 0) return@detectDragGesturesAfterLongPress
                                        val delta = (dragOffset / rowHeightPx).roundToInt()
                                        if (delta != 0) {
                                            val targetIndex = (currentIndex + delta)
                                                .coerceIn(0, order.lastIndex)
                                            val next = order.toMutableList()
                                            val tmp = next[currentIndex]
                                            next[currentIndex] = next[targetIndex]
                                            next[targetIndex] = tmp
                                            draggingIndex = targetIndex
                                            dragOffset -= (targetIndex - currentIndex) * rowHeightPx
                                            commitOrder(next)
                                        }
                                    },
                                    onDragEnd = {
                                        draggingIndex = -1
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingIndex = -1
                                        dragOffset = 0f
                                    },
                                )
                            },
                    ) {
                        SettingsRow(
                            icon = chatMenuIcon(entryKey),
                            iconColor = MaterialTheme.colorScheme.primary,
                            title = stringResource(chatMenuTitleRes(entryKey)),
                            onClick = null,
                            showChevron = false,
                            showDivider = index != order.lastIndex,
                            trailing = {
                                androidx.compose.foundation.layout.Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Drag handle (grab affordance; the whole row
                                    // is draggable after a long press).
                                    Icon(
                                        Icons.Outlined.DragHandle,
                                        contentDescription = stringResource(R.string.chat_menu_drag_handle),
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    // Visibility switch.
                                    Switch(
                                        checked = visible,
                                        onCheckedChange = { newValue ->
                                            ChatMenuPrefs.setVisible(prefs, entryKey, newValue)
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// Shared mapping between a chat menu entry key and the icon shown in the
// Chat Menu customization list.
private fun chatMenuIcon(entryKey: String): ImageVector? = when (entryKey) {
    ChatMenuPrefs.TERMINAL -> Icons.Outlined.Terminal
    ChatMenuPrefs.BROWSER -> Icons.Outlined.Language
    ChatMenuPrefs.CHAT_FILES -> Icons.Outlined.Description
    ChatMenuPrefs.SLASH_COMMANDS -> Icons.Outlined.Keyboard
    ChatMenuPrefs.EXPORT -> Icons.Outlined.Share
    ChatMenuPrefs.SESSION_SKILLS -> Icons.Outlined.Build
    ChatMenuPrefs.SESSION_MCPS -> Icons.Outlined.Extension
    ChatMenuPrefs.SESSION_MEMORY -> Icons.Outlined.Psychology
    else -> null
}

// Shared mapping between a chat menu entry key and its display title string.
private fun chatMenuTitleRes(entryKey: String): Int = when (entryKey) {
    ChatMenuPrefs.TERMINAL -> R.string.chat_menu_open_terminal
    ChatMenuPrefs.BROWSER -> R.string.chat_menu_open_browser
    ChatMenuPrefs.CHAT_FILES -> R.string.chat_menu_browse_chat_files
    ChatMenuPrefs.SLASH_COMMANDS -> R.string.chat_menu_slash_commands
    ChatMenuPrefs.EXPORT -> R.string.sessionlist_export
    ChatMenuPrefs.SESSION_SKILLS -> R.string.session_skills_title
    ChatMenuPrefs.SESSION_MCPS -> R.string.session_mcps_title
    ChatMenuPrefs.SESSION_MEMORY -> R.string.session_memory_title
    else -> R.string.appearance_section_chat_menu
}