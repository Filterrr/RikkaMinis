package com.openminis.app.ui.chat

// [T-subagent-ui] In-chat prompt row for sub-agent runs: ONE horizontal
// line that shows EVERY run — no cap, no "+N" overflow. Each run renders
// as a compact two-line capsule (status glyph + title / live timer +
// status label, slim turn-progress line along the bottom edge while
// executing); the row scrolls horizontally when the total width
// overflows the screen. Tap a capsule → second-level SubagentDetailScreen.
//
// NOTE: package stays com.openminis.app.ui.chat (as upstream) — ChatScreen
// references this composable unqualified from the same package, so moving
// the package would break resolution (caught by CI, fixed here). Shared
// status/timer helpers live in ui.subagent.SubagentUiCommon and are
// imported below.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Cancel
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
import com.openminis.app.ui.subagent.SubagentAccent
import com.openminis.app.ui.subagent.formatSubagentDuration
import com.openminis.app.ui.subagent.rememberRunElapsedMs
import com.openminis.app.ui.subagent.runStatusColor
import com.openminis.app.ui.subagent.runStatusLabel
import com.openminis.app.ui.subagent.turnProgressFraction
import com.openminis.app.ui.theme.ChatColors

/**
 * The prompt row. [runs] = full registry list (newest first);
 * [activeCount] = how many are RUNNING; [onOpenRun] routes to the
 * second-level detail page.
 *
 * [T-subagent-ui-row] Every run renders on ONE horizontal line — the
 * row scrolls horizontally, nothing is ever dropped or collapsed into
 * a counter. When several runs are alive at once, a compact "N running"
 * chip leads the line as a glanceable counter.
 */
@Composable
fun SubagentPromptRow(
    runs: List<SubagentRunRegistry.Run>,
    activeCount: Int,
    onOpenRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (activeCount > 1) {
            Text(
                text = "$activeCount running",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = SubagentAccent,
                modifier = Modifier
                    .background(
                        SubagentAccent.copy(alpha = if (ChatColors.isDark) 0.16f else 0.08f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
        runs.forEach { run ->
            SubagentCapsule(
                run = run,
                onOpenRun = onOpenRun,
            )
        }
    }
}

@Composable
private fun SubagentCapsule(
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
            .widthIn(min = 128.dp, max = 208.dp)
            .clip(shape)
            .background(bg, shape)
            .border(0.5.dp, ChatColors.toolBorder, shape)
            .clickable { onOpenRun(run.id) },
    ) {
        Column(
            modifier = Modifier
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
        ) {
            // Line 1: status glyph + run title.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubagentStatusGlyph(run = run, tint = status)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = run.title.ifBlank { run.skillName },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            // Line 2: live elapsed timer + status word.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (run.isQueued) {
                    Icon(
                        Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        tint = ChatColors.tertiaryText,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "waiting for slot",
                        fontSize = 10.sp,
                        color = ChatColors.tertiaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Text(
                        text = formatSubagentDuration(elapsedMs),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isActive) status else ChatColors.secondaryText,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(status, RoundedCornerShape(2.dp)),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = runStatusLabel(run),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = status,
                        maxLines = 1,
                    )
                }
            }
        }
        if (progress != null) {
            // Slim turn progress pinned to the capsule's bottom edge — flat,
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
                    .size(15.dp)
                    .alpha(pulse.value),
            )
        }
        run.isQueued -> CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
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
                    .size(14.dp)
                    .alpha(if (run.status == SubagentRunRegistry.RunStatus.SUCCESS) 0.85f else 1f),
            )
        }
    }
}
