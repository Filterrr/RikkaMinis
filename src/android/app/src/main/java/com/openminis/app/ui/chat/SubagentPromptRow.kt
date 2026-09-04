package com.openminis.app.ui.subagent

// [T-subagent-ui] In-chat prompt row for sub-agent runs, rebuilt as a
// vertical "mission control" list. The old design packed every run into
// one horizontally-scrolling capsule line, which in real sessions (2-3
// parallel runs, long Chinese titles) collapsed into ellipsised slivers
// with no visible status. Now: one compact timeline row per run — status
// glyph, title + status, live turn chip, live elapsed timer, slim turn
// progress line — newest first, capped at MAX_VISIBLE rows with a "+N"
// overflow counter. Tap any row → second-level SubagentDetailScreen.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.tools.SubagentRunRegistry
import com.openminis.app.ui.theme.ChatColors

/** Max timeline rows visible (newest first); older runs collapse into a counter. */
private const val MAX_VISIBLE_SUBAGENT_ROWS = 3

/**
 * The prompt row. [runs] = full registry list (newest first);
 * [activeCount] = how many are RUNNING; [onOpenRun] routes to the
 * second-level detail page. Rendered directly above the composer while
 * any run exists (active or recently finished).
 */
@Composable
fun SubagentPromptRow(
    runs: List<SubagentRunRegistry.Run>,
    activeCount: Int,
    onOpenRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (activeCount > 1) {
            Text(
                text = "$activeCount sub-agents running",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = SubagentAccent,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        runs.take(MAX_VISIBLE_SUBAGENT_ROWS).forEach { run ->
            SubagentRunRow(
                run = run,
                onOpenRun = onOpenRun,
            )
        }
        val hidden = runs.size - MAX_VISIBLE_SUBAGENT_ROWS
        if (hidden > 0) {
            Text(
                text = "+$hidden earlier run${if (hidden == 1) "" else "s"}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.tertiaryText,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun SubagentRunRow(
    run: SubagentRunRegistry.Run,
    onOpenRun: (String) -> Unit,
) {
    val isActive = run.isActive
    val status = runStatusColor(run)
    val accent = SubagentAccent
    val elapsedMs = rememberRunElapsedMs(run)
    val progress = turnProgressFraction(run)
    val shape = RoundedCornerShape(14.dp)
    // A whisper of the violet family tint behind live runs so "something is
    // working" is readable at a glance, even before the glyphs register.
    val bg = if (isActive) {
        val wash = if (ChatColors.isDark) 0.10f else 0.06f
        accent.copy(alpha = wash)
    } else {
        ChatColors.inputBg
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg, shape)
            .border(0.5.dp, ChatColors.toolBorder, shape)
            .clickable { onOpenRun(run.id) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SubagentStatusGlyph(run = run, tint = status)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = run.title.ifBlank { run.skillName },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatColors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    TurnChip(run = run)
                }
                if (run.query.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = run.query,
                        fontSize = 11.sp,
                        color = ChatColors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatSubagentDuration(elapsedMs),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isActive) status else ChatColors.secondaryText,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(status, RoundedCornerShape(3.dp)),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = runStatusLabel(run),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = status,
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open sub-agent detail",
                tint = ChatColors.tertiaryText,
                modifier = Modifier.size(14.dp),
            )
        }
        if (progress != null) {
            // Slim turn progress pinned to the row's bottom edge — flat,
            // unobtrusive, and only present while the run is executing.
            // Hand-drawn (not LinearProgressIndicator) so the 2dp line
            // renders exactly as specified on every API level.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(accent.copy(alpha = if (ChatColors.isDark) 0.18f else 0.12f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(2.dp)
                        .background(accent),
                )
            }
        }
    }
}

/** Leading glyph per state: pulsing radar (running), spinner (queued), terminal icons. */
@Composable
private fun SubagentStatusGlyph(run: SubagentRunRegistry.Run, tint: Color) {
    when {
        run.isExecuting -> {
            val pulse = rememberInfiniteTransition(label = "subagentRowPulse")
                .animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "subagentRowPulseAlpha",
                )
            Icon(
                Icons.Default.Radar,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(18.dp)
                    .alpha(pulse.value),
            )
        }
        run.isQueued -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 1.5.dp,
            color = tint,
        )
        else -> {
            val icon: ImageVector = when (run.status) {
                SubagentRunRegistry.RunStatus.SUCCESS -> Icons.Default.CheckCircle
                SubagentRunRegistry.RunStatus.FAILED -> Icons.Default.Error
                else -> Icons.Default.Cancel
            }
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(16.dp)
                    .alpha(if (run.status == SubagentRunRegistry.RunStatus.SUCCESS) 0.85f else 1f),
            )
        }
    }
}

/**
 * Inline status chip after the title: queued runs get an hourglass glyph +
 * "waiting for slot" (the visible-wait contract from orchestration), running
 * runs get live turn progress "turn 2/12".
 */
@Composable
private fun TurnChip(run: SubagentRunRegistry.Run) {
    val label = when {
        run.isQueued -> null // rendered as glyph + text below
        run.isExecuting && run.maxTurns > 0 -> "turn ${run.turn}/${run.maxTurns}"
        else -> null
    }
    if (run.isQueued) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.HourglassEmpty,
                contentDescription = null,
                tint = ChatColors.tertiaryText,
                modifier = Modifier.size(10.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "waiting for slot",
                fontSize = 10.sp,
                color = ChatColors.tertiaryText,
            )
        }
    } else if (label != null) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = ChatColors.secondaryText,
            modifier = Modifier
                .background(ChatColors.toolCapsuleBg, RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
