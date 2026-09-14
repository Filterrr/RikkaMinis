package com.openminis.app.ui.trace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-android-trace-viewer] The run list for one chat.
 *
 * Why this screen exists: every run writes a schema-2.0 trace to
 * `workspace/.traces/`, the failure matrix (F01–F14) is verified against those
 * files, and yet the app has never SHOWN one — answering "why did that run
 * fail?" meant asking the agent to cat the file. This is the read side of
 * evidence the runtime already produces.
 *
 * Rows lead with the terminal state, because that is the question the reader
 * arrives with; counters and duration follow. A trace whose run never closed
 * renders as "running" rather than pretending to a verdict.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceListScreen(
    viewModel: TraceViewModel,
    onBack: () -> Unit,
    onOpenTrace: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trace_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.traces.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.traces.isEmpty() -> {
                    TraceEmptyState(error = state.error, modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.traces, key = { it.traceId }) { summary ->
                            TraceRow(summary = summary, onClick = { onOpenTrace(summary.traceId) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceEmptyState(error: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            // A read failure and "no runs yet" are different facts — never
            // render one as the other.
            text = if (error != null) stringResource(R.string.trace_load_failed) else stringResource(R.string.trace_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (error != null) error else stringResource(R.string.trace_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TraceRow(summary: TraceViewModel.TraceSummary, onClick: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(TraceStatus.color(summary.terminalState, palette).copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TraceStatus.icon(summary.terminalState),
                    contentDescription = null,
                    tint = TraceStatus.color(summary.terminalState, palette),
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = TraceStatus.label(summary.terminalState),
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = buildList {
                        if (summary.turns >= 0) add("${summary.turns} turns")
                        if (summary.durationMs >= 0) add(formatDuration(summary.durationMs))
                        add("${summary.eventCount} events")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = formatTimestamp(summary.lastModifiedMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 60.dp, end = 16.dp)
                .height(0.5.dp)
                .background(palette.outlineVariant.copy(alpha = 0.5f)),
        )
    }
}

/** Terminal-state presentation, kept in one place so list and detail agree. */
internal object TraceStatus {

    @Composable
    fun label(terminalState: String): String = when {
        terminalState.isBlank() -> stringResource(R.string.trace_state_running)
        terminalState.equals("Succeeded", ignoreCase = true) -> stringResource(R.string.trace_state_succeeded)
        terminalState.equals("Interrupted", ignoreCase = true) -> stringResource(R.string.trace_state_interrupted)
        terminalState.equals("Cancelled", ignoreCase = true) -> stringResource(R.string.trace_state_cancelled)
        terminalState.equals("Failed", ignoreCase = true) -> stringResource(R.string.trace_state_failed)
        // An unknown value is shown verbatim rather than mapped onto the
        // nearest known bucket — a new terminal state must look new.
        else -> terminalState
    }

    fun icon(terminalState: String) = when {
        terminalState.isBlank() -> Icons.Default.HourglassEmpty
        terminalState.equals("Succeeded", ignoreCase = true) -> Icons.Default.CheckCircle
        else -> Icons.Default.Error
    }

    fun color(terminalState: String, palette: androidx.compose.material3.ColorScheme): Color = when {
        terminalState.isBlank() -> palette.tertiary
        terminalState.equals("Succeeded", ignoreCase = true) -> palette.primary
        else -> palette.error
    }
}

internal fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

internal fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** Shared monospace style for raw JSON fragments. */
internal val TraceMonoStyle = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)
