package com.openminis.app.ui.trace

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * [T-android-trace-viewer] One run, in full.
 *
 * Layout mirrors how the question is actually asked:
 *
 *  1. **Verdict** — terminal state + reason, the one thing a failing run is
 *     opened for. Rendered from `trace_end`; a run that never closed says so.
 *  2. **Evidence** — the audit results the recorder already computes
 *     (`auditEvidenceGaps`, lease balance). These are what make a trace
 *     trustworthy, so a gap is shown rather than buried.
 *  3. **Timeline** — every event, in order, with its offset from run start.
 *     One line per event; long fields were already capped by the recorder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceDetailScreen(
    viewModel: TraceViewModel,
    traceId: String,
    onBack: () -> Unit,
) {
    var run by remember(traceId) { mutableStateOf<TraceRun?>(null) }
    var loading by remember(traceId) { mutableStateOf(true) }
    var failed by remember(traceId) { mutableStateOf(false) }

    LaunchedEffect(traceId) {
        val loaded = viewModel.loadRun(traceId)
        run = loaded
        failed = loaded == null
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trace_detail_title), fontWeight = FontWeight.SemiBold) },
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
            val current = run
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                current == null || failed -> Text(
                    text = stringResource(R.string.trace_load_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> TraceDetailBody(current)
            }
        }
    }
}

@Composable
private fun TraceDetailBody(run: TraceRun) {
    val timeline = remember(run.traceId) { TraceTimeline.lines(run) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { VerdictCard(run) }
        item { EvidenceCard(run) }
        item { MetaCard(run) }
        item {
            SectionHeader(stringResource(R.string.trace_section_timeline, timeline.size))
        }
        items(timeline, key = { it.index }) { line -> TimelineRow(line) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun VerdictCard(run: TraceRun) {
    val palette = MaterialTheme.colorScheme
    val tint = TraceStatus.color(run.terminalState, palette)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(tint.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = TraceStatus.label(run.terminalState),
            style = MaterialTheme.typography.titleMedium,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
        if (run.terminalReason.isNotBlank()) {
            Text(
                text = run.terminalReason,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onSurface,
            )
        }
        Text(
            text = buildList {
                if (run.turns >= 0) add(stringResource(R.string.trace_turns, run.turns))
                if (run.durationMs >= 0) add(formatDuration(run.durationMs))
                add(stringResource(R.string.trace_events, run.events.size))
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = palette.onSurfaceVariant,
        )
        if (run.errors.isNotEmpty()) {
            Text(
                text = stringResource(R.string.trace_errors_count, run.errors.size),
                style = MaterialTheme.typography.bodySmall,
                color = palette.error,
            )
        }
    }
}

/**
 * What the trace can and cannot prove. This is the recruiter's own evidence
 * audit, surfaced — a run with a missing terminal event or an unreleased lease
 * is a run that cannot be trusted to have ended cleanly, and that fact is more
 * useful up front than buried in the event list.
 */
@Composable
private fun EvidenceCard(run: TraceRun) {
    val palette = MaterialTheme.colorScheme
    val gaps = run.evidenceGaps
    val clean = gaps.isEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (clean) stringResource(R.string.trace_evidence_complete) else stringResource(R.string.trace_evidence_gaps, gaps.size),
                style = MaterialTheme.typography.labelLarge,
                color = if (clean) palette.primary else palette.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (run.leasesClean) stringResource(R.string.trace_leases_clean) else stringResource(R.string.trace_leases_leaked),
                style = MaterialTheme.typography.labelSmall,
                color = if (run.leasesClean) palette.onSurfaceVariant else palette.error,
            )
        }
        gaps.forEach { gap ->
            Row(modifier = Modifier.padding(start = 4.dp)) {
                Text("•", style = MaterialTheme.typography.bodySmall, color = palette.error)
                Spacer(Modifier.width(6.dp))
                Text(gap, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MetaCard(run: TraceRun) {
    val palette = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (run.runId.isNotBlank()) MetaLine(stringResource(R.string.trace_meta_run_id), run.runId.take(12))
        if (run.provider.isNotBlank()) MetaLine(stringResource(R.string.trace_meta_provider), run.provider)
        MetaLine(stringResource(R.string.trace_meta_schema), run.schemaVersion)
        MetaLine(stringResource(R.string.trace_meta_size), formatSize(run.sizeBytes))
        MetaLine(stringResource(R.string.trace_meta_file), run.traceId)
        if (run.promptPreview.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = run.promptPreview,
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceVariant,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.widthIn(min = 88.dp),
        )
        Text(
            text = value,
            style = TraceMonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun TimelineRow(line: TraceEventLine) {
    val palette = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Offset from run start — the axis a reader uses to find the moment
        // things went wrong.
        Text(
            text = if (line.offsetMs >= 0) "+${line.offsetMs}ms" else "—",
            style = TraceMonoStyle,
            color = palette.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.width(72.dp),
        )
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .background(TraceTimeline.color(line.kind, palette), RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = line.summary,
            style = MaterialTheme.typography.bodySmall,
            color = if (line.kind == TraceEventLine.Kind.ERROR) palette.error else palette.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Category → dot colour, so the eye can skim for the class of event. */
internal fun TraceTimeline.color(
    kind: TraceEventLine.Kind,
    palette: androidx.compose.material3.ColorScheme,
): Color = when (kind) {
    TraceEventLine.Kind.START -> palette.tertiary
    TraceEventLine.Kind.END -> palette.primary
    TraceEventLine.Kind.TURN -> palette.secondary
    TraceEventLine.Kind.TOOL -> palette.primary
    TraceEventLine.Kind.BUDGET -> palette.tertiary
    TraceEventLine.Kind.RESOURCE -> palette.secondary
    TraceEventLine.Kind.RETRY -> palette.tertiary
    TraceEventLine.Kind.PERSIST -> palette.secondary
    TraceEventLine.Kind.ERROR -> palette.error
    TraceEventLine.Kind.OTHER -> palette.onSurfaceVariant
}
