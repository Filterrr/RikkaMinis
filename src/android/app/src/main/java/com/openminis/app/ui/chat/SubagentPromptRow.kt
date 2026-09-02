package com.openminis.app.ui.chat

// [T-subagent-ui] In-chat prompt pill row: "N sub-agent(s) running — tap to
// view". Rendered above the composer in ChatScreen while any sub-agent run
// is active; tapping opens the second-level SubagentDetailScreen for the
// newest active run. Compact history rows (finished runs) are listed here
// too so the user can review past runs without leaving the chat.

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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.tools.SubagentRunRegistry
import com.openminis.app.ui.theme.ChatColors

/**
 * The prompt row. [runs] = full registry list (newest first);
 * [activeCount] = how many are RUNNING; [onOpenRun] routes to the
 * second-level detail page.
 */
@Composable
fun SubagentPromptRow(
    runs: List<SubagentRunRegistry.Run>,
    activeCount: Int,
    onOpenRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) return
    val accent = toolAccentColor("spawn_agent")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        runs.take(MAX_VISIBLE_SUBAGENT_ROWS).forEach { run ->
            val isActive = run.isActive
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (isActive) 1f else 0.65f)
                    .background(ChatColors.inputBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(12.dp))
                    .clickable { onOpenRun(run.id) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isActive) {
                    // Pulsing radar icon + spinner — "a sub-agent is alive".
                    val pulse = rememberInfiniteTransition(label = "subagentPulse")
                        .animateFloat(
                            initialValue = 0.55f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(900, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "subagentPulseAlpha",
                        )
                    Icon(
                        Icons.Default.Radar,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier
                            .size(16.dp)
                            .alpha(pulse.value),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.5.dp,
                        color = accent,
                    )
                } else {
                    Icon(
                        Icons.Default.Radar,
                        contentDescription = null,
                        tint = when (run.status) {
                            SubagentRunRegistry.RunStatus.SUCCESS -> ToolCheckColor
                            SubagentRunRegistry.RunStatus.FAILED -> ToolErrorColor
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = buildString {
                        append(run.title.ifBlank { run.skillName })
                        append(" · ")
                        append(
                            when {
                                isActive -> "running · turn ${run.turn}/${run.maxTurns}"
                                run.status == SubagentRunRegistry.RunStatus.SUCCESS -> "done"
                                run.status == SubagentRunRegistry.RunStatus.FAILED -> "failed"
                                else -> "cancelled"
                            },
                        )
                    },
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (activeCount > 1 && isActive) {
                    Text(
                        text = "$activeCount running",
                        fontSize = 11.sp,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Open sub-agent detail",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** How many history rows the prompt area shows (newest active first). */
private const val MAX_VISIBLE_SUBAGENT_ROWS = 3
