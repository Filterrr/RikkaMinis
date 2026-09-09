package com.openminis.app.ui.chat

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
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.tools.TodoStore
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-todo-tool] Floating top-bar badge for the session task list.
 *
 * Hidden when the list is empty (drafts / fresh sessions). Shows
 * "done/total" with a live spinner-tint when an item is in_progress;
 * tap opens [TodoSheet].
 */
@Composable
fun TodoBadge(
    todo: TodoStore.TodoList,
    onClick: () -> Unit,
) {
    if (todo.items.isEmpty()) return
    val done = todo.items.count { it.status == TodoStore.Status.completed }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (todo.hasActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.TaskAlt,
            contentDescription = stringResource(R.string.todo_badge_label),
            tint = if (todo.hasActive) MaterialTheme.colorScheme.primary else ChatColors.secondaryText,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "$done/${todo.totalCount}",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (todo.hasActive) MaterialTheme.colorScheme.primary else ChatColors.primaryText,
        )
    }
}

/**
 * [T-todo-tool] Bottom sheet with the full task list: status icon,
 * content, strikethrough on completed items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoSheet(
    todo: TodoStore.TodoList,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.todo_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (todo.items.isNotEmpty()) {
                    Text(
                        text = "${todo.items.count { it.status == TodoStore.Status.completed }}/${todo.totalCount}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (todo.items.isEmpty()) {
                Text(
                    stringResource(R.string.todo_sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ChatColors.secondaryText,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    todo.items.forEach { item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = when (item.status) {
                                    TodoStore.Status.completed -> Icons.Outlined.CheckCircle
                                    TodoStore.Status.in_progress -> Icons.Outlined.TaskAlt
                                    TodoStore.Status.pending -> Icons.Outlined.RadioButtonUnchecked
                                },
                                contentDescription = item.status.name,
                                tint = when (item.status) {
                                    TodoStore.Status.completed -> ChatColors.success
                                    TodoStore.Status.in_progress -> MaterialTheme.colorScheme.primary
                                    TodoStore.Status.pending -> ChatColors.secondaryText
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = item.content,
                                fontSize = 14.sp,
                                lineHeight = 19.sp,
                                textDecoration = if (item.status == TodoStore.Status.completed)
                                    TextDecoration.LineThrough else null,
                                color = if (item.status == TodoStore.Status.completed)
                                    ChatColors.secondaryText else ChatColors.primaryText,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
