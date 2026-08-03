package com.openminis.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.sessions.DatePeriod
import com.openminis.app.ui.sessions.categoryStyle
import com.openminis.app.ui.sessions.groupSessionsByDate
import com.openminis.app.ui.sessions.relativeDate
import kotlinx.coroutines.launch

/**
 * RikkaHub-style chat-history drawer that slides out from the left of the
 * chat screen. Mirrors [com.openminis.app.ui.sessions.SessionListScreen] but
 * in a slimmer, always-available form so the user can switch conversations
 * without leaving the current chat (the session list remains reachable as the
 * navigation start destination).
 *
 * Data comes straight off [ChatRepository.observeSessions] — the same Room
 * flow the full list uses — so pins, deletions, titles and last-message
 * previews stay live and consistent with the standalone list. Section
 * grouping, category icons and relative timestamps reuse the (now `internal`)
 * helpers exported by SessionListScreen so there is a single source of truth.
 *
 * @param currentSessionId the chat currently displayed, highlighted in the list.
 * @param onSessionClick open another conversation (caller closes the drawer).
 * @param onNewChat start a fresh draft chat.
 * @param onSettings open Settings.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatHistoryDrawer(
    chatRepository: ChatRepository,
    currentSessionId: String,
    onSessionClick: (String) -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
) {
    val sessions by chatRepository.observeSessions()
        .collectAsState(initial = emptyList())
    // Never surface message-less draft rows (e.g. the current unsent chat) in
    // the history — they carry no title/preview and would read as noise. The
    // standalone list applies the same filter via lastMessage presence.
    val visibleSessions = remember(sessions) {
        // Draft rows (no title, no preview, not pinned) would read as noise;
        // the standalone list applies the same filter via lastMessage presence.
        sessions.filter { !it.title.isNullOrBlank() || !it.lastMessage.isNullOrBlank() || it.pinnedAt != null }
    }
    val grouped = remember(visibleSessions) { groupSessionsByDate(visibleSessions) }

    var deleteTarget by remember { mutableStateOf<ChatSessionEntity?>(null) }

    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: title + New Chat + Settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNewChat) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.chat_menu_new_chat),
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.settings),
                    )
                }
            }

            if (visibleSessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_sessions),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // weight(1f) is required: a bare LazyColumn inside the Column
                // would measure against the sheet's full maxHeight and push
                // the footer off-screen.
                LazyColumn(modifier = Modifier.weight(1f)) {
                    grouped.forEach { (period, group) ->
                        item(key = "header-${period.name}") {
                            DrawerSectionHeader(period)
                        }
                        items(group, key = { it.id }) { session ->
                            DrawerSessionRow(
                                session = session,
                                selected = session.id == currentSessionId,
                                onClick = { onSessionClick(session.id) },
                                onLongClick = { deleteTarget = session },
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        MinisAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.sessionlist_delete_one_title),
            text = stringResource(R.string.sessionlist_delete_message),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                val id = target.id
                deleteTarget = null
                // deletion is a fire-and-forget DB write mirroring
                // SessionListViewModel.deleteSession (row + messages gone,
                // VM store released, badges cleared).
                deleteSessionAndCleanup(chatRepository, id)
                // If the user just deleted the chat they're viewing, drop back
                // to a fresh draft so the screen isn't showing a dead session.
                if (id == currentSessionId) onNewChat()
            },
        )
    }
}

/**
 * Delete a session and release its resources. Mirrors
 * SessionListViewModel.deleteSession so drawer deletions behave identically to
 * list deletions (row + messages gone, VM store released, badges cleared).
 */
private val drawerIoScope =
    kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

private fun deleteSessionAndCleanup(chatRepository: ChatRepository, id: String) {
    drawerIoScope.launch {
        chatRepository.deleteSession(id)
        ChatViewModelStore.release(id)
        com.openminis.app.service.SessionBadgeStore.clear(id)
    }
}

@Composable
private fun DrawerSectionHeader(period: DatePeriod) {
    val title = when (period) {
        DatePeriod.PINNED -> stringResource(R.string.sessionlist_section_pinned)
        DatePeriod.TODAY -> stringResource(R.string.sessionlist_section_today)
        DatePeriod.YESTERDAY -> stringResource(R.string.sessionlist_section_yesterday)
        DatePeriod.THIS_WEEK -> stringResource(R.string.sessionlist_section_this_week)
        DatePeriod.THIS_MONTH -> stringResource(R.string.sessionlist_section_this_month)
        DatePeriod.EARLIER -> stringResource(R.string.sessionlist_section_earlier)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .padding(top = 4.dp),
    ) {
        if (period == DatePeriod.PINNED) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSessionRow(
    session: ChatSessionEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val style = remember(session.category) { categoryStyle(session.category) }
    val ctx = LocalContext.current
    val timeText = remember(session.updatedAt, ctx) { relativeDate(ctx, session.updatedAt) }
    val activeSessions by SessionActivityTracker.activeSessions.collectAsState()
    val isActive = session.id in activeSessions

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(color = style.color.copy(alpha = 0.18f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = style.color,
                modifier = Modifier.size(17.dp),
            )
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(style.color, CircleShape)
                        .align(Alignment.BottomEnd),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title ?: stringResource(R.string.chat_menu_new_chat),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            session.lastMessage?.takeIf { it.isNotBlank() }?.let { preview ->
                Text(
                    text = preview,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = timeText,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
