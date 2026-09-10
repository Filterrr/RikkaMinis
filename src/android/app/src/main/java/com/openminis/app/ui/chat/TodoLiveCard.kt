package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.tools.TodoStore
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

/**
 * [T-todo-tool] Live task card mounted as an inline banner between the
 * message list and the composer (NOT an overlay — see mount site in
 * ChatScreen: it must never fight FloatingToolStatusBar's bottom-reserve
 * math, so it shifts the composer up instead of covering messages).
 *
 * "完成一项划一项" display: every completed item renders with a
 * strikethrough the moment the agent marks it done, visible in the chat
 * itself — no sheet detour. Tap toggles expanded (full list) / collapsed
 * (one-line counter) so a long plan never eats the screen. Same session
 * lifecycle as the top-bar badge: hidden when the list is empty; when
 * every item is done and streaming has ended, the card lingers briefly,
 * then folds away.
 */
@Composable
fun TodoLiveCard(
    todo: TodoStore.TodoList,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val done = todo.items.count { it.status == TodoStore.Status.completed }
    val hasContent = todo.items.isNotEmpty()
    val allDone = hasContent && done == todo.items.size
    var showCard by remember { mutableStateOf(false) }

    // Visibility driver: show while the session has an unfinished list or
    // an active turn; once everything is done, hold ~8s for the user to
    // register the final "all struck through" state, then dismiss.
    // Keyed on updatedAtMs too so a fresh all-done rewrite restarts the
    // linger timer instead of keeping the previous one.
    LaunchedEffect(hasContent, isStreaming, allDone, todo.updatedAtMs) {
        when {
            !hasContent -> showCard = false
            isStreaming || !allDone -> showCard = true
            else -> {
                delay(8000)
                showCard = false
            }
        }
    }

    AnimatedVisibility(
        visible = showCard,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        modifier = modifier,
    ) {
        // Plain remember (no list key): the user's expand/collapse choice
        // survives todo rewrites within the same visible period.
        var expanded by remember { mutableStateOf(true) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .clip(RoundedCornerShape(12.dp))
                .background(ChatColors.inputBg)
                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // Header row: title + done/total — always visible, even collapsed.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (allDone) Icons.Outlined.CheckCircle else Icons.Outlined.TaskAlt,
                    contentDescription = null,
                    tint = if (allDone) ChatColors.success else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.todo_sheet_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.secondaryText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$done/${todo.totalCount}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (allDone) ChatColors.success else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = ChatColors.secondaryText,
                    modifier = Modifier.size(16.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    todo.items.forEach { item ->
                        val isDone = item.status == TodoStore.Status.completed
                        val isInProgress = item.status == TodoStore.Status.in_progress
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = when {
                                    isDone -> Icons.Outlined.CheckCircle
                                    isInProgress -> Icons.Outlined.TaskAlt
                                    else -> Icons.Outlined.RadioButtonUnchecked
                                },
                                contentDescription = item.status.name,
                                tint = when {
                                    isDone -> ChatColors.success
                                    isInProgress -> MaterialTheme.colorScheme.primary
                                    else -> ChatColors.secondaryText
                                },
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = item.content,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                // The "划线": LineThrough spans every rendered
                                // line (ellipsis included), so a completed item
                                // reads as crossed out no matter its length.
                                textDecoration = if (isDone) TextDecoration.LineThrough else null,
                                color = if (isDone) ChatColors.secondaryText else ChatColors.primaryText,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .alpha(if (isDone) 0.75f else 1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
