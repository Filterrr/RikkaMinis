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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
    // The entry currently being dragged (tracked by its stable key, NOT a list
    // index: a pointerInput coroutine freezes its captured locals until the
    // modifier's key changes, so an index captured at launch would go stale as
    // soon as the first swap reorders the list — the next drag would grab the
    // wrong row).
    var draggingEntry by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // SharedPreferences is not observable, so a visibility Switch can't re-read
    // its `checked` value on its own — without this counter the toggle would
    // persist the pref but never repaint until the screen is re-entered. Each
    // bump forces a recomposition that re-reads the persisted value (same
    // mechanism the old inline AppearanceScreen list used). It also picks up
    // external changes (backup restore / minis-config) on the next toggle.
    var chatMenuTick by remember { mutableStateOf(0) }

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
                    // key(entryKey) makes Compose reconcile children by entry
                    // identity instead of list position. Without it, the first
                    // swap reorders the list, the Box at this slot gets a NEW
                    // pointerInput key (the entry that used to be here is gone)
                    // and the in-flight drag coroutine is cancelled — the row
                    // would only ever move ONE position per long-press drag.
                    key(entryKey) {
                        val isDragging = draggingEntry == entryKey
                        // The tick read keeps the Switch in sync after a toggle
                        // and after external prefs changes.
                        @Suppress("UNUSED_EXPRESSION")
                        chatMenuTick
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
                                            draggingEntry = entryKey
                                            dragOffset = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            // `order` and `draggingEntry` are backed by
                                            // State, so reading them here always yields
                                            // the CURRENT value — the dragged entry's
                                            // position is re-derived after every swap.
                                            val currentIndex = order.indexOf(draggingEntry)
                                            if (currentIndex < 0) return@detectDragGesturesAfterLongPress
                                            val delta = (dragOffset / rowHeightPx).roundToInt()
                                            if (delta != 0) {
                                                val targetIndex = (currentIndex + delta)
                                                    .coerceIn(0, order.lastIndex)
                                                if (targetIndex != currentIndex) {
                                                    val next = order.toMutableList()
                                                    val tmp = next[currentIndex]
                                                    next[currentIndex] = next[targetIndex]
                                                    next[targetIndex] = tmp
                                                    dragOffset -= (targetIndex - currentIndex) * rowHeightPx
                                                    commitOrder(next)
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggingEntry = null
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            draggingEntry = null
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
                                                chatMenuTick++
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
