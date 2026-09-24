package com.openminis.app.ui.subagent

// [T-subagent-chat-stream] The second-level sub-agent page renders the run as
// a CHAT STREAM instead of a log table: the sub-agent's own narration, its
// reasoning and its tool calls, interleaved in the order they happened
// (text → tool pill → text → tool pill), with a floating tool-status bar
// pinned above the bottom edge for the currently running call.
//
// Why a separate file rather than more of SubagentDetailScreen.kt: that file
// owns the screen chrome (Scaffold / TopAppBar / back / stop). This one owns
// the stream rendering — projection from registry state to rows, then row to
// composable — so each stays readable and the diff stays reviewable.
//
// Reuse over reinvention — every visual primitive is the SAME one the main
// chat uses, so the two surfaces cannot drift apart:
//   • tool pill    → ToolCallPill (ui.chat: circle capsule, tool-accent icon,
//                    duration, shimmer while running)
//   • thinking row → ThinkingBlock (ui.chat: its own collapse + the
//                    tail-window rendering guards for huge reasoning blobs)
//   • floating bar → FloatingToolStatusBar (ui.chat: status glyph, title,
//                    ‹N/M› paging)
//   • body text    → StreamingMarkdownText (ui.chat)

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
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.tools.SubagentRunRegistry
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.FloatingToolStatusBar
import com.openminis.app.ui.chat.StreamingMarkdownText
import com.openminis.app.ui.chat.ThinkingBlock
import com.openminis.app.ui.chat.ToolBlockStatus
import com.openminis.app.ui.chat.ToolCallPill
import com.openminis.app.ui.theme.ChatColors

/**
 * One rendered row of the stream.
 *
 * Built from [SubagentRunRegistry.Run.segments] with the matching
 * [SubagentRunRegistry.Step] folded in for tool calls: the segment carries
 * POSITION (ordering, turn), the step carries live STATUS and output. Keeping
 * them separate means a pill's spinner/duration keeps updating from the step
 * while its place in the reading order stays fixed.
 */
internal sealed interface SubagentStreamItem {
    /** Stable LazyColumn key; also the identity passed to the output viewer. */
    val key: String

    data class Narrate(
        override val key: String,
        val text: String,
        val streaming: Boolean,
    ) : SubagentStreamItem

    data class Reason(
        override val key: String,
        val text: String,
        val streaming: Boolean,
    ) : SubagentStreamItem

    data class ToolUse(
        override val key: String,
        val toolName: String,
        val toolTitle: String,
        val status: ToolBlockStatus,
        val durationMs: Long,
        val output: String,
    ) : SubagentStreamItem
}

/**
 * Project a run's transcript into renderable rows.
 *
 * Fallback: when a run has NO transcript but does have report text (a run
 * restored from a journal written before transcripts existed, or a model that
 * answered without narrating), the report is rendered as a single narration
 * row — an empty page would read as a broken run rather than an old one.
 *
 * The trailing narration/reasoning row is marked `streaming` while the run is
 * live, which drives the same markdown incremental path (and thinking
 * auto-follow) the chat's tail block uses.
 */
internal fun buildSubagentStreamItems(run: SubagentRunRegistry.Run): List<SubagentStreamItem> {
    val stepsById = run.stepsById
    val items = mutableListOf<SubagentStreamItem>()
    run.segments.forEach { segment ->
        when (segment) {
            is SubagentRunRegistry.Segment.Text -> {
                if (segment.content.isEmpty()) return@forEach
                items += SubagentStreamItem.Narrate(
                    // seq is monotonic per registry and does NOT shift when
                    // MAX_SEGMENTS pruning rotates the head — the LazyColumn
                    // keeps item identity (scroll position, animations) while
                    // the transcript grows past its cap. index would shift on
                    // every prune and rebuild every row.
                    key = "seg-text-${segment.seq}",
                    text = segment.content,
                    streaming = false,
                )
            }
            is SubagentRunRegistry.Segment.Thinking -> {
                if (segment.content.isEmpty()) return@forEach
                items += SubagentStreamItem.Reason(
                    key = "seg-think-${segment.seq}",
                    text = segment.content,
                    streaming = false,
                )
            }
            is SubagentRunRegistry.Segment.ToolCall -> {
                val step = stepsById[segment.id]
                items += SubagentStreamItem.ToolUse(
                    // Tool rows key on the STEP id (not seq): the pill opens
                    // the output sheet through this id, and the step id is the
                    // identity the registry itself guarantees unique.
                    key = "seg-tool-${segment.id}",
                    toolName = segment.toolName,
                    toolTitle = segment.toolTitle.ifBlank { step?.toolTitle.orEmpty() },
                    status = step?.let(::stepStatusToToolBlockStatus) ?: ToolBlockStatus.RUNNING,
                    durationMs = step?.durationMs ?: 0L,
                    output = step?.output.orEmpty(),
                )
            }
        }
    }

    if (items.isEmpty() && run.resultText.isNotBlank()) {
        items += SubagentStreamItem.Narrate(
            key = "fallback-result",
            text = run.resultText,
            streaming = run.isActive,
        )
    }

    // Mark the live tail: the newest prose row is the one still being written.
    if (run.isActive && items.isNotEmpty()) {
        val idx = items.indexOfLast {
            it is SubagentStreamItem.Narrate || it is SubagentStreamItem.Reason
        }
        if (idx >= 0) {
            items[idx] = when (val row = items[idx]) {
                is SubagentStreamItem.Narrate -> row.copy(streaming = true)
                is SubagentStreamItem.Reason -> row.copy(streaming = true)
                else -> row
            }
        }
    }
    return items
}

/** Registry step status → the chat's tool-block status (same colour semantics). */
internal fun stepStatusToToolBlockStatus(
    step: SubagentRunRegistry.Step,
): ToolBlockStatus = when (step.status) {
    SubagentRunRegistry.ToolStepStatus.RUNNING -> ToolBlockStatus.RUNNING
    SubagentRunRegistry.ToolStepStatus.SUCCESS -> ToolBlockStatus.SUCCESS
    SubagentRunRegistry.ToolStepStatus.FAILED -> ToolBlockStatus.FAILED
}

/** [ToolCallPill] renders from an [AssistantBlock]; build one for a row. */
private fun SubagentStreamItem.ToolUse.toBlock(): AssistantBlock = AssistantBlock(
    id = key,
    kind = "tool_use",
    content = output,
    toolStatus = status,
    toolTitle = toolTitle.ifBlank { toolName },
    toolName = toolName,
    durationMs = durationMs,
)

/**
 * The stream body. Owns the LazyColumn, the chat-parity auto-follow behaviour
 * (paused while the user reads history, resumed when they return to the
 * bottom), and the floating status bar for the live tool call.
 */
@Composable
internal fun SubagentStreamBody(
    run: SubagentRunRegistry.Run,
    contentPadding: PaddingValues,
    onOpenToolOutput: (AssistantBlock) -> Unit,
    onStop: (() -> Unit)?,
) {
    val listState = rememberLazyListState()
    val items = buildSubagentStreamItems(run)

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
    // Follow the tail only while live AND only when the user has not scrolled
    // away — the same contract the main chat honours.
    val tailMarker = items.lastOrNull()?.let { item ->
        when (item) {
            is SubagentStreamItem.Narrate -> item.text.length
            is SubagentStreamItem.Reason -> item.text.length
            is SubagentStreamItem.ToolUse -> item.status
        }
    }
    LaunchedEffect(items.size, tailMarker, run.isActive) {
        if (run.isActive && items.isNotEmpty() && !followPaused) {
            runCatching { listState.animateScrollToItem(items.size) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                // Extra bottom room so the floating bar never traps the last
                // row underneath it.
                bottom = contentPadding.calculateBottomPadding() + 76.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "task") { SubagentTaskHeader(run = run) }

            if (items.isEmpty() && run.isActive) {
                item(key = "waiting") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
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
                            stringResource(R.string.subagent_stream_waiting),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = ChatColors.tertiaryText,
                        )
                    }
                }
            }

            items(items = items, key = { it.key }) { item ->
                when (item) {
                    is SubagentStreamItem.Narrate -> StreamingMarkdownText(
                        content = item.text,
                        isStreaming = item.streaming,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    is SubagentStreamItem.Reason -> ThinkingBlock(
                        block = AssistantBlock(
                            id = item.key,
                            kind = "thinking",
                            content = item.text,
                        ),
                        isStreaming = item.streaming,
                        isLast = true,
                    )
                    is SubagentStreamItem.ToolUse -> ToolCallPill(
                        block = item.toBlock(),
                        allToolBlocks = emptyList(),
                        // [T-subagent-user-cancel] A Stop button that silently
                        // does nothing is worse than none: cancelSubagentRun
                        // REFUSES inline runs (they execute inside the parent
                        // turn — the honest advice is "stop the turn"), so the
                        // per-pill stop is offered only when the run is a
                        // cancellable detached one. The parent screen passes
                        // onStop already gated on detached + active; null here
                        // renders the pill clean.
                        onStop = onStop,
                        onOpenDetail = { onOpenToolOutput(item.toBlock()) },
                    )
                }
            }

            run.error?.takeIf { it.isNotBlank() }?.let { err ->
                item(key = "error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ChatColors.warningBg, RoundedCornerShape(10.dp))
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

        // [T-subagent-chat-stream] Floating tool status bar — the chat's own
        // component, showing the running (or most recent) call with ‹N/M›
        // paging. It answers "what is it doing right now" without scrolling,
        // which is the whole reason the chat grew one.
        val toolItems = items.filterIsInstance<SubagentStreamItem.ToolUse>()
        if (toolItems.isNotEmpty() && run.isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
            ) {
                FloatingToolStatusBar(
                    toolBlocks = toolItems.map { it.toBlock() },
                    onStop = null,
                    onOpenDetail = { blockId ->
                        toolItems.firstOrNull { it.key == blockId }?.let { onOpenToolOutput(it.toBlock()) }
                    },
                )
            }
        }
    }
}

/**
 * Task header: what was asked, by which skill/model, and how it is going.
 * Kept from the log layout — the stream below it is unreadable without
 * knowing the task it belongs to.
 */
@Composable
private fun SubagentTaskHeader(run: SubagentRunRegistry.Run) {
    val state = runStatusColor(run)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatColors.secondaryBg, RoundedCornerShape(14.dp))
            .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (run.isActive) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = SubagentAccent,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(state, CircleShape),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = runStatusLabel(run),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = state,
                letterSpacing = 0.5.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatSubagentDuration(rememberRunElapsedMs(run)),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.secondaryText,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = run.query,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = ChatColors.primaryText,
        )
        Spacer(modifier = Modifier.height(10.dp))
        SubagentMetaChipRow(
            chips = buildList {
                add("skill" to run.skillId.ifBlank { run.skillName })
                if (run.modelLabel.isNotBlank()) add("model" to run.modelLabel)
                formatSubagentTokenCost(run)?.let { add("tokens" to it) }
                formatSubagentQueueWait(run)?.let { add("queue" to it) }
                // [T-subagent-thinking] Make the EFFECTIVE reasoning choice
                // auditable: a skill that asked for thinking on a model that
                // cannot reason must not look silently broken. Only shown
                // when reasoning is part of this run's story.
                if (run.reasoningEnabled || run.thinkingText.isNotBlank()) {
                    add("thinking" to if (run.reasoningEnabled) "on" else "off")
                }
            },
        )
        if (run.notices.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                run.notices.lines().firstOrNull { it.isNotBlank() }.orEmpty(),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.warningText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Wrapping chip row. A single Row would squeeze the monospace chips into
 * ellipses once skill + model + tokens + queue + thinking are all present
 * (each chip is content-sized and three of them overflow a phone width), so
 * the row wraps in groups of two.
 */
@Composable
private fun SubagentMetaChipRow(chips: List<Pair<String, String>>) {
    chips.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pair.forEach { (label, value) -> SubagentMetaChip(label, value) }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/** Compact "label: value" chip, monospace — the log layout's chip language. */
@Composable
private fun SubagentMetaChip(label: String, value: String) {
    Text(
        text = "$label: $value",
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = ChatColors.secondaryText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(ChatColors.toolCapsuleBg, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
