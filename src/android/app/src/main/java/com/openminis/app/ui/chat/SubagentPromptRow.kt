package com.openminis.app.ui.chat

// [T-subagent-ui] In-chat prompt pills: one compact capsule per sub-agent
// run, ALL IN A SINGLE HORIZONTAL ROW (horizontalScroll, weight-equal
// widths). Rendered above the composer in ChatScreen; tapping a capsule
// opens the second-level SubagentDetailScreen for that run. Active runs
// pulse; finished ones show a terminal icon at reduced alpha.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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

/** Max capsules visible in the row (newest first); older runs drop off. */
private const val MAX_VISIBLE_SUBAGENT_PILLS = 4

/**
 * The prompt row. [runs] = full registry list (newest first);
 * [activeCount] = how many are RUNNING; [onOpenRun] routes to the
 * second-level detail page.
 *
 * [T-subagent-ui-row] All runs render as equal-weight capsules on ONE
 * horizontal line (no per-run rows): with N runs each capsule gets N-th
 * of the width, text ellipsizes, and the row scrolls horizontally when
 * the minimum readable width overflows.
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        runs.take(MAX_VISIBLE_SUBAGENT_PILLS).forEach { run ->
            SubagentPill(
                run = run,
                activeCount = activeCount,
                accent = accent,
                onOpenRun = onOpenRun,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun SubagentPill(
    run: SubagentRunRegistry.Run,
    activeCount: Int,
    accent: androidx.compose.ui.graphics.Color,
    onOpenRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = run.isActive
    Row(
        modifier = modifier
            .widthIn(min = 120.dp)
            .alpha(if (isActive) 1f else 0.65f)
            .background(ChatColors.inputBg, RoundedCornerShape(12.dp))
            .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(12.dp))
            .clickable { onOpenRun(run.id) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
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
            Spacer(modifier = Modifier.width(5.dp))
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
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = buildString {
                append(run.title.ifBlank { run.skillName })
                append(" · ")
                append(
                    when {
                        // [T-subagent-orchestration] Queued behind the
                        // scheduler (per-skill / global cap) — visible wait.
                        run.isQueued -> "queued · waiting for slot"
                        isActive -> "running · turn ${run.turn}/${run.maxTurns}"
                        run.status == SubagentRunRegistry.RunStatus.SUCCESS -> "done"
                        run.status == SubagentRunRegistry.RunStatus.FAILED -> "failed"
                        else -> "cancelled"
                    },
                )
            },
            fontSize = 12.sp,
            fontWeight = if (isActive || run.isQueued) FontWeight.SemiBold else FontWeight.Medium,
            color = ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (activeCount > 1 && isActive) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "+${activeCount - 1}",
                fontSize = 11.sp,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "Open sub-agent detail",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}
