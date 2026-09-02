package com.openminis.app.ui.subagent

// [T-subagent-ui] Second-level page rendering a sub-agent run's live
// execution process: header (skill, task, status, timing), the step list
// (per tool call: icon, title, status, live output tail), and the
// sub-agent's streaming result text. Reached from the in-chat prompt pill
// or a spawn_agent tool pill. Mirrors ChatScreen's back-arrow pattern.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.tools.SubagentRunRegistry
import com.openminis.app.ui.chat.toolAccentColor
import com.openminis.app.ui.chat.ToolCheckColor
import com.openminis.app.ui.chat.ToolErrorColor
import com.openminis.app.ui.theme.ChatColors

private fun stepIcon(toolName: String): ImageVector = when (toolName) {
    "shell_execute" -> Icons.Default.Terminal
    "file_read" -> Icons.Default.Description
    "file_write" -> Icons.Default.Bolt
    "file_edit" -> Icons.Default.EditNote
    "read_image" -> Icons.Default.Image
    "memory_write", "memory_get", "memory_rollup" -> Icons.Default.Psychology
    else -> Icons.Default.Radar
}

private fun formatDuration(ms: Long): String = when {
    ms < 1000L -> "${ms}ms"
    ms < 60_000L -> String.format("%.1fs", ms / 1000.0)
    else -> String.format("%dm%02ds", ms / 60_000, (ms % 60_000) / 1000)
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
                    text = when {
                        run == null -> "Finished"
                        run.isActive -> "Running · turn ${run.turn}/${run.maxTurns}"
                        else -> when (run.status) {
                            SubagentRunRegistry.RunStatus.SUCCESS -> "Completed"
                            SubagentRunRegistry.RunStatus.FAILED -> "Failed"
                            SubagentRunRegistry.RunStatus.CANCELLED -> "Cancelled"
                            SubagentRunRegistry.RunStatus.RUNNING -> "Running"
                        } + " · " + formatDuration(run.durationMs)
                    },
                    fontSize = 12.sp,
                    color = ChatColors.secondaryText,
                )
            }
            if (run != null && run.isActive) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                    color = toolAccentColor("spawn_agent"),
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
 * Body for an existing run — task card, live step log, streaming result.
 * Split out so the null-run fallback above doesn't need a bare [return]
 * inside the composable body. Lives OUTSIDE the header Column scope, so
 * it fills the remaining space with [Modifier.fillMaxSize] (no ColumnScope
 * weight available here).
 */
@Composable
private fun SubagentRunDetailBody(run: SubagentRunRegistry.Run) {
        val listState = rememberLazyListState()
        val stepCount = run.steps.size
        // Follow the live log: keep the newest step visible while running,
        // unless the user has scrolled up to read (mirrors chat follow
        // semantics loosely — a simple "stick to bottom while active" is
        // enough for a progress page).
        LaunchedEffect(stepCount, run.isActive) {
            if (run.isActive && stepCount > 0) {
                runCatching {
                    listState.animateScrollToItem((stepCount - 1).coerceAtLeast(0))
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Task card
            item(key = "task") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ChatColors.toolBg, RoundedCornerShape(12.dp))
                        .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        "TASK",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatColors.tertiaryText,
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        run.query,
                        fontSize = 14.sp,
                        color = ChatColors.primaryText,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "skill: ${run.skillId}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.secondaryText,
                    )
                }
            }

            // Steps
            items(items = run.steps, key = { it.id }) { step ->
                SubagentStepCard(step)
            }

            // Streaming / final result
            if (run.resultText.isNotBlank()) {
                item(key = "result") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ChatColors.secondaryBg, RoundedCornerShape(12.dp))
                            .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
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
                            color = ChatColors.primaryText,
                        )
                    }
                }
            }

            // Error banner
            run.error?.let { err ->
                item(key = "error") {
                    Text(
                        err,
                        fontSize = 13.sp,
                        color = ToolErrorColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                ChatColors.warningBg,
                                RoundedCornerShape(10.dp),
                            )
                            .padding(12.dp),
                    )
                }
            }
        }
}

@Composable
private fun SubagentStepCard(step: SubagentRunRegistry.Step) {
    val accent = toolAccentColor(step.toolName)
    val statusColor = when (step.status) {
        SubagentRunRegistry.ToolStepStatus.RUNNING -> accent
        SubagentRunRegistry.ToolStepStatus.SUCCESS -> ToolCheckColor
        SubagentRunRegistry.ToolStepStatus.FAILED -> ToolErrorColor
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatColors.secondaryBg, RoundedCornerShape(10.dp))
            .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (step.status) {
                SubagentRunRegistry.ToolStepStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = statusColor,
                )
                SubagentRunRegistry.ToolStepStatus.SUCCESS -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(14.dp),
                )
                SubagentRunRegistry.ToolStepStatus.FAILED -> Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
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
                    formatDuration(step.durationMs),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ChatColors.secondaryText,
                )
            }
        }
        if (step.output.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                step.output,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.secondaryText,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChatColors.codeBlockBg, RoundedCornerShape(6.dp))
                    .padding(8.dp),
            )
        }
    }
}
