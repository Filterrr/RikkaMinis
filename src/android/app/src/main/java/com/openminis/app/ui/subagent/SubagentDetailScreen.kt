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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.fillMaxHeight
import com.openminis.app.ui.chat.ToolBlockStatus
import com.openminis.app.R
import com.openminis.app.tools.SubagentAnsiText
import com.openminis.app.tools.SubagentRunRegistry
import com.openminis.app.ui.chat.ToolCancelColor
import com.openminis.app.ui.chat.toolAccentColor
import com.openminis.app.ui.theme.ChatColors

/**
 * Full-screen sub-agent run detail. [runs] is collected from the chat's
 * live registry flow (published via ChatSubagentRunsHolder) — the entry
 * matching [runId] renders its latest snapshot on every registry update.
 * When the run id disappears (registry cleared / pruned) the page shows a
 * compact "no longer available" fallback and [onBack] stays available.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentDetailScreen(
    runId: String,
    onBack: () -> Unit,
    /**
     * [T-subagent-user-cancel] Stop this run. Null when the hosting nav graph
     * has no cancel channel (e.g. a preview) — the Stop affordance then simply
     * does not render rather than offering a dead button.
     */
    onStop: ((String) -> Unit)? = null,
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
    // [T-subagent-ui-chat-consistent] Scaffold + M3 TopAppBar — the SAME
    // chrome the main chat uses: container color = ChatColors.background
    // at 0.92 alpha (content scrolls under the bar), status-bar insets
    // handled by the TopAppBar itself (edge-to-edge), no hard divider.
    // The old hand-rolled Row painted sheetHeaderBg (a sheet color, not a
    // screen color) and left a status-bar hole on edge-to-edge builds.
    Scaffold(
        containerColor = ChatColors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.Start) {
                        // Line 1: run title — mirrors the chat title's
                        // 16sp/19lh SemiBold rhythm.
                        Text(
                            text = run?.title?.ifBlank { run.skillName } ?: "Sub-agent",
                            fontSize = 16.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ChatColors.primaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Line 2: status dot + label + turn + elapsed — the
                        // same info the old bar carried, now formatted like
                        // the chat's model row (dot + 11sp tertiary text).
                        // The live elapsed value comes from the hoisted
                        // ticker above; status color/label from the shared
                        // SubagentUiCommon helpers so pill and page agree.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(runStatusColor(run), CircleShape),
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
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                color = ChatColors.tertiaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = ChatColors.primaryText,
                        )
                    }
                },
                actions = {
                    // [T-subagent-chat-stream] Tool progress chip — the
                    // "0/8"-style counter from the chat's top bar, retargeted
                    // at THIS run's own work: completed / total tool calls.
                    //
                    // Deliberately NOT the chat's TodoBadge: `todo_write` is
                    // not in the sub-agent capability catalog (AgentCapabilities
                    // has no mapping for it, so the fail-closed filter drops it
                    // from every sub-agent's tool set) AND TodoStore is keyed by
                    // parent session id — a sub-agent writing there would
                    // overwrite the PARENT chat's task list. A counter derived
                    // from the run's own steps is both truthful and scoped.
                    val stepTotal = run?.steps?.size ?: 0
                    if (stepTotal > 0) {
                        val stepDone = run!!.steps.count {
                            it.status != SubagentRunRegistry.ToolStepStatus.RUNNING
                        }
                        val chipColor = if (run.isActive) SubagentAccent else ChatColors.secondaryText
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (run.isActive) {
                                        SubagentAccent.copy(alpha = if (ChatColors.isDark) 0.16f else 0.08f)
                                    } else {
                                        ChatColors.toolCapsuleBg
                                    },
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = stringResource(R.string.subagent_tools_label),
                                tint = chipColor,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "$stepDone/$stepTotal",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                color = chipColor,
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
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
                    // [T-subagent-user-cancel] Stop, for DETACHED runs only.
                    //
                    // An inline run cannot be stopped from here (it executes
                    // inside the parent turn), so showing a button that silently
                    // does nothing would be worse than showing none — the
                    // navigation-level holder already returns an explanatory
                    // message for that case, and this branch keeps the pill
                    // honest about which kind of run the user is looking at.
                    if (run != null && run.isActive && onStop != null) {
                        val detached = remember(run.id, run.status) {
                            ChatSubagentRunsHolder.isDetached(run.id)
                        }
                        if (detached) {
                            IconButton(onClick = { onStop(run.id) }) {
                                Icon(
                                    Icons.Default.StopCircle,
                                    contentDescription = "Stop this sub-agent",
                                    tint = ToolCancelColor,
                                )
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ChatColors.background.copy(alpha = 0.92f),
                    scrolledContainerColor = ChatColors.background.copy(alpha = 0.92f),
                ),
                expandedHeight = 68.dp,
            )
        },
    ) { innerPadding ->
        if (run == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "This sub-agent run is no longer available.",
                    color = ChatColors.secondaryText,
                    fontSize = 14.sp,
                )
            }
        } else {
            SubagentRunDetailBody(
                run = run,
                contentPadding = innerPadding,
                onStop = if (run.isActive && onStop != null) {
                    { onStop(run.id) }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * Body for an existing run — task header, live step log, streaming result.
 * [contentPadding] comes from the Scaffold's inner padding (status bar +
 * TopAppBar height), so the LazyColumn scrolls under the translucent top
 * bar exactly like the chat message list does. Lives OUTSIDE the Scaffold
 * scope's Column — fills the padded area with [Modifier.fillMaxSize].
 */

/**
 * Body for a run whose transcript renders as a CHAT STREAM.
 *
 * [SubagentRunRegistry.Run.segments] carries narration, reasoning and tool
 * calls in arrival order; [SubagentStreamBody] turns that into rows using the
 * chat's own pill / thinking / markdown components. This function is the thin
 * adapter: it feeds the stream and hosts the tool-output sheet opened by
 * tapping a pill (or the floating status bar).
 *
 * [contentPadding] is the Scaffold inner padding (status bar + TopAppBar), so
 * the list scrolls under the translucent bar exactly like the chat does.
 */
@Composable
private fun SubagentRunDetailBody(
    run: SubagentRunRegistry.Run,
    contentPadding: PaddingValues,
    onStop: (() -> Unit)?,
) {
    // Which tool's output the user opened (null = closed). The row carries a
    // project of the step; the sheet re-resolves the live one from the run so
    // a still-running command keeps streaming into the open sheet.
    var openToolKey by remember(run.id) { mutableStateOf<String?>(null) }
    val rows = buildSubagentStreamItems(run)
    val openRow = rows
        .filterIsInstance<SubagentStreamItem.ToolUse>()
        .firstOrNull { it.key == openToolKey }

    SubagentStreamBody(
        run = run,
        contentPadding = contentPadding,
        onOpenToolOutput = { block -> openToolKey = block.id },
        onStop = onStop,
    )

    if (openRow != null) {
        SubagentToolOutputSheet(row = openRow, onDismiss = { openToolKey = null })
    }
}

/**
 * [T-subagent-chat-stream] Full output of one tool call, opened from its pill
 * or from the floating status bar.
 *
 * A sheet rather than inline expansion: the stream stays scannable (pills
 * remain one line) while the complete output — including the ANSI colours a
 * shell command emitted — is one tap away. Reuses [SubagentColoredOutput]
 * (the black terminal card), fully expanded since showing it all is the
 * sheet's entire purpose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubagentToolOutputSheet(
    row: SubagentStreamItem.ToolUse,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accent = toolAccentColor(row.toolName)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .background(ChatColors.secondaryBg),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChatColors.sheetHeaderBg)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(accent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        stepIcon(row.toolName),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        row.toolTitle.ifBlank { row.toolName },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatColors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(row.toolName)
                            if (row.durationMs > 0) {
                                append(" · ")
                                append(formatSubagentDuration(row.durationMs))
                            }
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.tertiaryText,
                    )
                }
                Text(
                    stringResource(R.string.subagent_close),
                    fontSize = 13.sp,
                    color = ChatColors.secondaryText,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            if (row.output.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (row.status == ToolBlockStatus.RUNNING) {
                            stringResource(R.string.subagent_output_running)
                        } else {
                            stringResource(R.string.subagent_output_empty)
                        },
                        fontSize = 13.sp,
                        color = ChatColors.tertiaryText,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    SubagentColoredOutput(
                        rawOutput = row.output,
                        expanded = true,
                        previewLines = Int.MAX_VALUE,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black, RoundedCornerShape(10.dp))
                            .border(0.5.dp, Color(0xFF404040), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        showLineHint = false,
                    )
                }
            }
        }
    }
}

/** Leading glyph per tool — shared by the stream sheet and the pill icons. */
private fun stepIcon(toolName: String): androidx.compose.ui.graphics.vector.ImageVector = when (toolName) {
    "shell_execute" -> Icons.Default.Terminal
    "file_read" -> Icons.Default.Description
    "file_write" -> Icons.Default.Bolt
    "file_edit" -> Icons.Default.EditNote
    "read_image" -> Icons.Default.Image
    "memory_write", "memory_get", "memory_rollup" -> Icons.Default.Psychology
    else -> Icons.Default.Radar
}

@Composable
private fun SubagentColoredOutput(
    rawOutput: String,
    expanded: Boolean,
    previewLines: Int,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
    lineHeight: TextUnit = 15.sp,
    baseOverride: Color? = null,
    showLineHint: Boolean = true,
    terminalCard: Boolean = true,
) {
    // Main chat ToolDetailSheet shell-card palette: black bg + success
    // green base + light-blue links. terminalCard=false (the RESULT card
    // — model prose, not tool output) keeps the theme-colored rendering.
    val cardBg = if (terminalCard) Color.Black else ChatColors.codeBlockBg
    val baseColor = baseOverride
        ?: if (terminalCard) ChatColors.success else ChatColors.secondaryText
    // Parse once per output change — NOT per style lookup — and reuse the
    // spans for both the expanded and collapsed (tail-sliced) rendering.
    val spans = remember(rawOutput) { SubagentAnsiText.parse(rawOutput) }
    val shownSpans = remember(spans, expanded, previewLines) {
        if (expanded) spans else SubagentAnsiText.tailLines(spans, previewLines)
    }

    val annotated = remember(shownSpans, baseColor) {
        buildAnnotatedString {
            for (span in shownSpans) {
                withStyle(SpanStyle(
                    color = span.color?.let { Color(it) } ?: baseColor,
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = if (span.underline) TextDecoration.Underline else null,
                )) {
                    append(span.text)
                }
            }
        }
    }
    val lineCount = remember(rawOutput) { rawOutput.count { it == '\n' } + 1 }

    Column(modifier = modifier) {
        Text(
            annotated,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamily = FontFamily.Monospace,
            // Color comes per-span now; the base style stays colorless.
            modifier = Modifier.fillMaxWidth(),
        )
        if (!expanded && showLineHint && lineCount > previewLines) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "⋯ $lineCount lines — tap to expand",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                // Fixed gray tuned for the black card (both themes) — the
                // chat terminal card's own secondary text is theme-fixed too.
                color = Color(0xFF98989D),
            )
        }
    }
}
