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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.openminis.app.tools.SubagentAnsiText
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

// [T-subagent-ui-collapse] Shared collapse mechanics for the detail page.
// Collapsed boxes keep the LAST lines (streaming output and results grow at
// the tail — the head is the least informative part). The result panel uses
// a larger preview so the agent's answer stays readable while collapsed.
private const val COLLAPSED_PREVIEW_LINES = 3
private const val RESULT_PREVIEW_LINES = 10

/** Pseudo key under which the RESULT card's collapse flag is stored. */
private const val RESULT_STEP_KEY = "subagent-result-card"

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
    // [T-subagent-ui-collapse] Collapse/expand state lives HERE, in a
    // keyed snapshot map, instead of rememberSaveable(step.id) inside each
    // card: one source of truth enables the bulk "expand all / collapse
    // all" control in the log header, and keyed map entries survive
    // LazyColumn item recreation on scroll. Absence of a key = default
    // collapsed. A user gesture marks the state "manual" so auto-reveal
    // (on stream completion) never fights the reader's choice.
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val manualOverride = remember { mutableStateMapOf<String, Boolean>() }
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
                    Spacer(modifier = Modifier.weight(1f))
                    // [T-subagent-ui-collapse] Bulk control: expand/collapse
                    // every step's output at once (streaming finals included
                    // via the STEP_RESULT pseudo-key). Collapsing also clears
                    // manual overrides so auto-reveal works again afterwards.
                    SubagentCollapseToggle(
                        label = "expand all",
                        onClick = {
                            run.steps.forEach { collapsed[it.id] = false }
                            collapsed[RESULT_STEP_KEY] = false
                            run.steps.forEach { manualOverride[it.id] = true }
                            manualOverride[RESULT_STEP_KEY] = true
                        },
                    )
                    Text(
                        "·",
                        fontSize = 11.sp,
                        color = ChatColors.tertiaryText,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    SubagentCollapseToggle(
                        label = "collapse all",
                        onClick = {
                            run.steps.forEach { collapsed[it.id] = true }
                            collapsed[RESULT_STEP_KEY] = true
                            run.steps.forEach { manualOverride.remove(it.id) }
                            manualOverride.remove(RESULT_STEP_KEY)
                        },
                    )
                }
            }
        }

        items(items = run.steps, key = { it.id }) { step ->
            SubagentStepCard(
                step = step,
                collapsed = collapsed,
                manualOverride = manualOverride,
            )
        }

        // ── Streaming / final result ────────────────────────────────────
        if (run.resultText.isNotBlank()) {
            item(key = "result") {
                SubagentResultCard(
                    run = run,
                    collapsed = collapsed,
                    manualOverride = manualOverride,
                )
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
 * [T-subagent-ui-collapse] Streaming / final result card. Collapsible like
 * the step output boxes; while the run streams, the box stays COLLAPSED to
 * a 10-line tail so a chatty sub-agent doesn't push the log out of view.
 * The moment the run finishes, the card auto-expands once — unless the user
 * already touched it (manual override wins over automation, in both
 * directions). Retains the accent spine + vertical fade of the old layout.
 */
@Composable
private fun SubagentResultCard(
    run: SubagentRunRegistry.Run,
    collapsed: SnapshotStateMap<String, Boolean>,
    manualOverride: SnapshotStateMap<String, Boolean>,
) {
    // First composition: a run that already FINISHED before this page was
    // opened shows its result expanded (the report is the content) — the
    // stream-time auto-collapse below never fired for late visitors.
    LaunchedEffect(Unit) {
        if (RESULT_STEP_KEY !in collapsed && !run.isActive) {
            collapsed[RESULT_STEP_KEY] = false
        }
    }
    // Auto-reveal once on stream completion; every set is guarded so only
    // the transition running→finished writes, and never over a manual pick.
    val wasActive = remember { mutableStateOf(run.isActive) }
    LaunchedEffect(run.isActive) {
        if (wasActive.value && !run.isActive && manualOverride[RESULT_STEP_KEY] != true) {
            collapsed[RESULT_STEP_KEY] = false
        }
        wasActive.value = run.isActive
    }
    // While streaming, if the user never touched the card, keep following
    // the tail collapsed instead of re-expanding on every new chunk.
    LaunchedEffect(run.isActive) {
        if (run.isActive && manualOverride[RESULT_STEP_KEY] != true) {
            collapsed[RESULT_STEP_KEY] = true
        }
    }

    val expanded = collapsed[RESULT_STEP_KEY] == false
    val lineCount = run.resultText.count { it == '\n' } + 1
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(ChatColors.secondaryBg, shape)
            .border(0.5.dp, ChatColors.toolBorder, shape)
            .animateContentSize(),
    ) {
        // Accent spine so the agent's own voice reads as distinct from tool
        // output above it — vertical fade keeps it subtle at the bottom of
        // long text blocks.
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
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (run.isActive) "OUTPUT (streaming)" else "RESULT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.tertiaryText,
                    letterSpacing = 1.sp,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (!expanded && lineCount > RESULT_PREVIEW_LINES) {
                    Text(
                        "⋯ $lineCount lines",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.tertiaryText,
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 0f else -90f,
                    animationSpec = tween(durationMillis = 150),
                    label = "resultChevron",
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = ChatColors.tertiaryText,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            // [T-subagent-ui-ansi] Result text is model prose — ANSI-free in
            // practice, so the parser short-circuits to one plain span and
            // rendering cost is a single Text. Collapsed keeps the styled
            // tail 10 lines; hint lives in the header row above.
            SubagentColoredOutput(
                rawOutput = run.resultText,
                expanded = expanded,
                previewLines = RESULT_PREVIEW_LINES,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                baseOverride = ChatColors.primaryText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChatColors.codeBlockBg, RoundedCornerShape(8.dp))
                    .clickable {
                        collapsed[RESULT_STEP_KEY] = expanded
                        manualOverride[RESULT_STEP_KEY] = true
                    }
                    .padding(10.dp)
                    .animateContentSize(),
                showLineHint = false,
            )
        }
    }
}

/** Tiny text toggle used by the "expand all / collapse all" log header. */
@Composable
private fun SubagentCollapseToggle(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = SubagentAccent,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * [T-subagent-ui-ansi] Colored monospace output text for the sub-agent
 * detail page. Parses ANSI SGR sequences ([SubagentAnsiText]) and renders
 * them as span styles on top of the chat palette:
 *  - Bold → FontWeight.Bold; italic / underline via Compose span styles.
 *  - Default 8/16-colors get a luminance check against the box background:
 *    dark palette passes through, light palette darkens near-black/near-
 *    white "default" entries so colored output stays readable on both.
 *  - Truecolor / 256-cube values pass through as-is (alpha forced opaque).
 * The line-count hint sits inside the box so the tap target stays whole.
 */
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
) {
    val baseColor = baseOverride ?: ChatColors.secondaryText
    val isDark = ChatColors.isDark
    // Parse once per output change — NOT per style lookup — and reuse the
    // spans for both the expanded and collapsed (tail-sliced) rendering.
    val spans = remember(rawOutput) { SubagentAnsiText.parse(rawOutput) }
    val shownSpans = remember(spans, expanded, previewLines) {
        if (expanded) spans else SubagentAnsiText.tailLines(spans, previewLines)
    }

    val annotated = remember(shownSpans, baseColor, isDark) {
        buildAnnotatedString {
            for (span in shownSpans) {
                val colorInt = span.color
                val spanColor = when {
                    colorInt == null -> null
                    // xterm defaults (black/white entries) need contrast
                    // fixes on light backgrounds; colored entries pass.
                    !isDark && colorInt == 0xFF000000.toInt() -> 0xFF3A3A3A.toInt()
                    !isDark && colorInt == 0xFFFFFFFF.toInt() -> 0xFFE5E5E5.toInt()
                    else -> colorInt
                }
                withStyle(SpanStyle(
                    color = spanColor?.let { Color(it) } ?: baseColor,
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
                color = ChatColors.tertiaryText,
            )
        }
    }
}

/**
 * One tool call in the execution log. A fixed-height left rail carries the
 * tool glyph (accent-tinted chip, status icon while running); the card body
 * holds the tool title, duration, and the output box.
 *
 * [T-subagent-ui-collapse] The output box is COLLAPSIBLE FOR EVERY step,
 * not just running ones (a 60-line tail from a finished shell call used to
 * render fully expanded and bury the log). Collapsed shows the last 3
 * lines — the tail is where streaming output and results live — plus a
 * line-count hint; tapping the box or the chevron toggles. Whole-card
 * state comes from the parent's shared [collapsed]/[manualOverride] maps
 * so bulk toggles and scroll survival work. [animateContentSize] keeps
 * expand/collapse smooth while a step is still streaming.
 */
@Composable
private fun SubagentStepCard(
    step: SubagentRunRegistry.Step,
    collapsed: SnapshotStateMap<String, Boolean>,
    manualOverride: SnapshotStateMap<String, Boolean>,
) {
    val accent = toolAccentColor(step.toolName)
    val hasOutput = step.output.isNotBlank()
    // Absent key = collapsed (the default). Manual gestures set the flag
    // AND mark the override so auto-reveal on stream completion stays away.
    val expanded = collapsed[step.id] == false
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
            if (hasOutput) {
                Spacer(modifier = Modifier.height(6.dp))
                val lineCount = step.output.count { it == '\n' } + 1
                val toggleable = lineCount > COLLAPSED_PREVIEW_LINES || expanded
                // [T-subagent-ui-ansi] Colored rendering: ANSI SGR spans →
                // styled text; collapsed keeps the styled tail 3 lines.
                SubagentColoredOutput(
                    rawOutput = step.output,
                    expanded = expanded,
                    previewLines = COLLAPSED_PREVIEW_LINES,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ChatColors.codeBlockBg, RoundedCornerShape(8.dp))
                        .clickable(enabled = toggleable) {
                            collapsed[step.id] = expanded
                            manualOverride[step.id] = true
                        }
                        .padding(8.dp)
                        .animateContentSize(),
                )
                // Toggle affordance only when there is something to toggle —
                // short outputs (≤ preview lines) never grow a chevron row.
                if (toggleable) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                            .clickable {
                                collapsed[step.id] = expanded
                                manualOverride[step.id] = true
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        val rotation by animateFloatAsState(
                            targetValue = if (expanded) 0f else -90f,
                            animationSpec = tween(durationMillis = 150),
                            label = "chevron",
                        )
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = ChatColors.tertiaryText,
                            modifier = Modifier
                                .size(13.dp)
                                .graphicsLayer { rotationZ = rotation },
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
