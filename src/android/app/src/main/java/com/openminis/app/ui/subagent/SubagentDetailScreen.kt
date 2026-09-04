package com.openminis.app.ui.subagent

// [T-subagent-ui] Second-level page rendering a sub-agent run's live
// execution process. Rebuilt from a flat card list into a "mission log"
// layout: a task header (query + meta chips + live stats), a per-tool-call
// execution log with accent-tinted tool glyphs and expandable output
// panels, then the streaming/final result and any error banner. Status
// color / labels / live elapsed time come from SubagentUiCommon so this
// page can never disagree with the in-chat prompt row. Reached from the
// in-chat prompt row or a spawn_agent tool pill. Mirrors ChatScreen's
// back-arrow pattern.

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.tools.SubagentRunRegistry
import com.openminis.app.ui.chat.toolAccentColor
import com.openminis.app.ui.theme.ChatColors

private fun stepIcon(toolName: String): androidx.compose.ui.graphics.vector.ImageVector = when (toolName) {
    "shell_execute" -> Icons.Default.Terminal
    "file_read" -> Icons.Default.Description
    "file_write" -> Icons.Default.Bolt
    "file_edit" -> Icons.Default.EditNote
    "read_image" -> Icons.Default.Image
    "memory_write", "memory_get", "memory_rollup" -> Icons.Default.Psychology
    else -> Icons.Default.Radar
}

/**
 * Full-screen sub-agent run detail. [runs] is collected from the chat's
 * live registry flow (published via ChatSubagentRunsHolder) — the entry
 * matching [runId] renders its latest snapshot on every registry update.
 * When the run id disappears (registry cleared / pruned) the page shows a
 * compact "no longer available" fallback and [onBack] stays available.
 */
@Composable
fun SubagentDetailScreen(
    runId: String,
    onBack: () -> Unit,
) {
    val runsFlow = remember { ChatSubagentRunsHolder.currentRuns }
    val runs: List<SubagentRunRegistry.Run> = if (runsFlow != null) {
        runsFlow.collectAsState().value
    } else {
        emptyList()
    }
    val run = runs.firstOrNull { it.id == runId }
    // Hoisted: a @Composable call must not sit inside buildString's plain
    // lambda below. `let` is inline so the composable context is preserved.
    val elapsedMs = run?.let { rememberRunElapsedMs(it) } ?: 0L
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── Top bar ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ChatColors.sheetHeaderBg)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = ChatColors.primaryText,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = run?.title?.ifBlank { run.skillName } ?: "Sub-agent",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(runStatusLabel(run))
                        if (run != null) {
                            if (run.isExecuting && run.maxTurns > 0) {
                                append(" · turn ${run.turn}/${run.maxTurns}")
                            }
                            append(" · ")
                            append(formatSubagentDuration(elapsedMs))
                        }
                    },
                    fontSize = 12.sp,
                    color = runStatusColor(run),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (run != null && run.isExecuting) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                    color = SubagentAccent,
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = ChatColors.sheetHeaderBorder)

        if (run == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "This sub-agent run is no longer available.",
                    color = ChatColors.secondaryText,
                    fontSize = 14.sp,
                )
            }
        } else {
            SubagentRunDetailBody(run = run)
        }
    }
}

/**
 * Body for an existing run — task header, live step log, streaming result.
 * Split out so the null-run fallback above doesn't need a bare [return]
 * inside the composable body. Lives OUTSIDE the header Column scope, so
 * it fills the remaining space with [Modifier.fillMaxSize] (no ColumnScope
 * weight available here).
 */
@Composable
private fun SubagentRunDetailBody(run: SubagentRunRegistry.Run) {
    val listState = rememberLazyListState()
    val stepCount = run.steps.size
    // Pause auto-follow when the USER drags away from the bottom; resume
    // when they return (derivedStateOf so only actual position flips
    // recompose, not every scroll pixel). Programmatic animateScrollToItem
    // does NOT emit drag interactions — user intent only.
    var followPaused by remember { mutableStateOf(false) }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followPaused = !atBottom
        }
    }
    LaunchedEffect(atBottom) {
        if (atBottom) followPaused = false
    }
    // Follow the live log: keep the newest step visible while running,
    // unless the user is reading history.
    LaunchedEffect(stepCount, run.isActive) {
        if (run.isActive && stepCount > 0 && !followPaused) {
            val target = 1 + stepCount - 1  // "task" header + newest step
            runCatching {
                listState.animateScrollToItem(target.coerceAtLeast(0))
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = 16.dp, vertical = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Task header ─────────────────────────────────────────────────
        item(key = "task") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChatColors.toolBg, RoundedCornerShape(14.dp))
                    .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        runStatusLabel(run).uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = runStatusColor(run),
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        formatSubagentDuration(rememberRunElapsedMs(run)),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.secondaryText,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    run.query,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = ChatColors.primaryText,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetaChip(label = "skill", value = run.skillId.ifBlank { run.skillName })
                    if (run.groupId.isNotEmpty()) {
                        // [T-subagent-orchestration] Batch provenance — the
                        // group the pill belongs to (join/wait/cancel target).
                        MetaChip(label = "group", value = run.groupId.takeLast(6))
                    }
                }
                if (run.isExecuting && run.maxTurns > 0) {
                    // Turn progress mirrors the in-chat capsule's slim line
                    // (shared fraction logic, same 4–96% clamp) so the two
                    // surfaces tell one consistent story.
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(
                                SubagentAccent.copy(alpha = if (ChatColors.isDark) 0.18f else 0.12f),
                                RoundedCornerShape(2.dp),
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(turnProgressFraction(run) ?: 0f)
                                .height(3.dp)
                                .background(SubagentAccent, RoundedCornerShape(2.dp)),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "turn ${run.turn} of ${run.maxTurns}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.tertiaryText,
                    )
                }
            }
        }

        // ── Execution log ───────────────────────────────────────────────
        if (run.steps.isEmpty()) {
            item(key = "log-empty") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(0.65f)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = SubagentAccent,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "waiting for first tool call…",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.tertiaryText,
                    )
                }
            }
        } else {
            item(key = "log-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "EXECUTION LOG",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatColors.tertiaryText,
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "${run.steps.count { it.status != SubagentRunRegistry.ToolStepStatus.RUNNING }}/${run.steps.size}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.secondaryText,
                    )
                }
            }
        }

        items(items = run.steps, key = { it.id }) { step ->
            SubagentStepCard(step)
        }

        // ── Streaming / final result ────────────────────────────────────
        // ── Streaming / final result ────────────────────────────────────
        if (run.resultText.isNotBlank()) {
            item(key = "result") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .background(ChatColors.secondaryBg, RoundedCornerShape(12.dp))
                        .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(12.dp)),
                ) {
                    // Accent spine so the agent's own voice reads as
                    // distinct from tool output above it — vertical fade
                    // keeps it subtle at the bottom of long text blocks.
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        SubagentAccent.copy(alpha = 0.7f),
                                        SubagentAccent.copy(alpha = 0.2f),
                                    ),
                                ),
                            ),
                    )
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (run.isActive) "OUTPUT (streaming)" else "RESULT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ChatColors.tertiaryText,
                            letterSpacing = 1.sp,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            run.resultText,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = ChatColors.primaryText,
                        )
                    }
                }
            }
        }

        // ── Error banner ────────────────────────────────────────────────
        run.error?.let { err ->
            item(key = "error") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            ChatColors.warningBg,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(12.dp),
                ) {
                    Text(
                        "ERROR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatColors.error,
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        err,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = ChatColors.error,
                    )
                }
            }
        }
    }
}

/** Small "label value" chip used for skill / group provenance in the header. */
@Composable
private fun MetaChip(label: String, value: String) {
    Text(
        text = "$label: $value",
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = ChatColors.secondaryText,
        modifier = Modifier
            .background(ChatColors.toolCapsuleBg, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * One tool call in the execution log. A fixed-height left rail carries the
 * tool glyph (accent-tinted chip, status icon while running); the card body
 * holds the tool title, duration, and — collapsed by default — the live
 * output tail. Running steps cap their preview and grow an expander
 * affordance; finished steps render their full tail. [animateContentSize]
 * keeps the expand/collapse smooth.
 */
@Composable
private fun SubagentStepCard(step: SubagentRunRegistry.Step) {
    val accent = toolAccentColor(step.toolName)
    val isRunning = step.status == SubagentRunRegistry.ToolStepStatus.RUNNING
    val isFailed = step.status == SubagentRunRegistry.ToolStepStatus.FAILED
    // Expandable state survives the page's scroll-driven recreation.
    var expanded by rememberSaveable(step.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ChatColors.secondaryBg, shape)
            .border(0.5.dp, ChatColors.toolBorder, shape)
            .animateContentSize(),
    ) {
        // Left rail: the tool's own accent, always present, so the log
        // reads as a colored spine of activity even when titles truncate.
        Column(
            modifier = Modifier
                .width(36.dp)
                .padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(accent.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    stepIcon(step.toolName),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (step.status) {
                    SubagentRunRegistry.ToolStepStatus.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = accent,
                    )
                    SubagentRunRegistry.ToolStepStatus.SUCCESS -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = ChatColors.success,
                        modifier = Modifier
                            .size(13.dp)
                            .alpha(0.9f),
                    )
                    SubagentRunRegistry.ToolStepStatus.FAILED -> Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = ChatColors.error,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = step.toolTitle.ifBlank { step.toolName },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (step.durationMs > 0) {
                    Text(
                        formatSubagentDuration(step.durationMs),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.secondaryText,
                    )
                }
            }
            if (step.output.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                val previewLines = if (isRunning && !expanded) 3 else Int.MAX_VALUE
                Text(
                    step.output,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ChatColors.secondaryText,
                    maxLines = previewLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ChatColors.codeBlockBg, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )
                if (isRunning) {
                    // Live steps can grow unbounded between polls — collapse
                    // to the tail until the user asks for the full stream.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clickable { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowDown
                            else Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                            tint = ChatColors.tertiaryText,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            if (expanded) "collapse" else "full output",
                            fontSize = 11.sp,
                            color = ChatColors.tertiaryText,
                        )
                    }
                }
            }
        }
    }
}
