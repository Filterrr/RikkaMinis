package com.openminis.app.ui.trace

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-android-trace-viewer] Lists and loads the agent traces a chat produced.
 *
 * Reads the same files the recorder writes:
 * `<session workspace>/workspace/.traces/agent-<stamp>.jsonl`
 * (allocated in `ChatViewModel.newTraceFile`, capped at
 * `MAX_TRACE_FILES_PER_SESSION` with oldest-first pruning). Resolution goes
 * through [PRootKernel.resolveSessionHostPath] rather than joining paths by
 * hand, so the per-session bind-mount rules (`/var/minis/workspace` →
 * `filesDir/minis-sessions/<sid>/workspace`) are honoured and this screen shows
 * the SAME files the agent sees — including when another session was the last
 * to boot the sandbox.
 *
 * All file I/O runs on [Dispatchers.IO]; a missing directory is an empty list,
 * not an error (a session that never ran the agent has no traces).
 */
class TraceViewModel(
    private val context: Context,
    private val sessionId: String,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val traces: List<TraceSummary> = emptyList(),
        val error: String? = null,
    )

    /**
     * Row model for the list. Deliberately lighter than [TraceRun]: listing a
     * session with 20 traces should not parse ~20 full event streams to render
     * a list. The header fields come from a bounded read of each file.
     */
    data class TraceSummary(
        val traceId: String,
        val filePath: String,
        val lastModifiedMs: Long,
        val sizeBytes: Long,
        val terminalState: String,
        val turns: Int,
        val durationMs: Long,
        val eventCount: Int,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = runCatching { withContext(Dispatchers.IO) { loadSummaries() } }
            _state.value = result.fold(
                onSuccess = { UiState(loading = false, traces = it) },
                onFailure = {
                    AppLogger.warning(TAG, "trace list failed sid=$sessionId: ${it.message}")
                    UiState(loading = false, error = it.message ?: "failed to read traces")
                },
            )
        }
    }

    /** Load one trace in full, for the drill-down screen. */
    suspend fun loadRun(traceId: String): TraceRun? = withContext(Dispatchers.IO) {
        runCatching {
            val file = traceFile(traceId) ?: return@runCatching null
            if (!file.isFile) return@runCatching null
            TraceRun.fromRaw(
                traceId = traceId,
                filePath = file.absolutePath,
                lastModifiedMs = file.lastModified(),
                sizeBytes = file.length(),
                raw = file.readText(),
            )
        }.onFailure {
            AppLogger.warning(TAG, "trace load failed id=$traceId: ${it.message}")
        }.getOrNull()
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun tracesDir(): File? =
        PRootKernel.resolveSessionHostPath(sessionId, TRACES_LINUX_DIR, context)

    /** Newest first — the run the user just watched is the one they want. */
    private fun loadSummaries(): List<TraceSummary> {
        val dir = tracesDir() ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(TraceRun.TRACE_EXTENSION) }
            ?: return emptyList()

        return files
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val traceId = file.name.removeSuffix(TraceRun.TRACE_EXTENSION)
                // Parse the whole file: trace_end lives at the END, so a
                // partial read would report every run as still-running. The
                // files are small (truncation caps are per-field: 300/500/
                // 1500 chars) and there are at most 20 of them.
                val run = runCatching {
                    TraceRun.fromRaw(
                        traceId = traceId,
                        filePath = file.absolutePath,
                        lastModifiedMs = file.lastModified(),
                        sizeBytes = file.length(),
                        raw = file.readText(),
                    )
                }.getOrNull()
                TraceSummary(
                    traceId = traceId,
                    filePath = file.absolutePath,
                    lastModifiedMs = file.lastModified(),
                    sizeBytes = file.length(),
                    terminalState = run?.terminalState.orEmpty(),
                    turns = run?.turns ?: -1,
                    durationMs = run?.durationMs ?: -1L,
                    eventCount = run?.events?.size ?: 0,
                )
            }
    }

    private fun traceFile(traceId: String): File? {
        // Reject separators / traversal: the id arrives from a nav argument and
        // must only ever name a file directly inside the traces dir.
        if (traceId.isBlank() || traceId.contains('/') || traceId.contains('\\') || traceId.contains("..")) {
            return null
        }
        return tracesDir()?.resolve("$traceId${TraceRun.TRACE_EXTENSION}")
    }

    companion object {
        private const val TAG = "TraceViewModel"

        /** Where `ChatViewModel.newTraceFile()` allocates trace files. */
        const val TRACES_LINUX_DIR = "/var/minis/workspace/.traces"
    }
}
