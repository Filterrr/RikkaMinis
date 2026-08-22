package com.openminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import android.util.Log
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Direction A: offload chat streaming to the [ModelExecutionService] process.
 *
 * Writes a streaming request.json, starts [ModelExecutionService], then incrementally
 * polls the service's stream.jsonl (append-only JSON Lines) and re-emits each decoded
 * [LLMStreamChunk] downstream as it arrives.
 *
 * Hardening notes (Tier 1):
 *  - failure propagation: an `error` line in the stream throws so the caller falls
 *    back to in-process (never a fabricated Finished).
 *  - cancellation propagation: cancelling the flow writes the cancel marker file so the
 *    service aborts its stream collection promptly.
 *  - incremental reads: we keep a byte offset and re-open/seek, never re-read whole file.
 */
object ChatStreamOffloadHandler {
    private const val TAG = "ChatStreamOffload"
    private const val STAGING_ROOT = "model-exec"
    private const val POLL_INTERVAL_MS = 160L
    private const val STREAM_TIMEOUT_MS = 6 * 60 * 1000L
    private const val CANCEL_ACK_TIMEOUT_MS = 5_000L
    private const val CANCEL_ACK_POLL_MS = 100L
    /** How long the stream file may stay frozen before we check worker liveness. */
    private const val WORKER_DIED_GRACE_MS = 2_000L
    /** Pid file written by the worker (deliberately NOT an API — just a file). */
    private const val WORKER_PID_FILE = "worker.pid"

    /**
     * [direction-A / B2] Global count of in-flight streaming runs (this process).
     * Incremented at the start of [stream], decremented in its finally. Read by
     * [com.openminis.app.sandbox.ExecutionCoordinator.maybeReclaimModelService]
     * to skip stopService(:modelservice) while a stream is active — otherwise the
     * app process kill would sever the stream mid-answer (stream.jsonl left without
     * DONE/error, leaving the UI silently stalled until the poll timeout).
     * @Volatile because it is incremented/decremented from stream coroutines but
     * read from a different execution context (reclaim path).
     */
    @Volatile
    var activeStreams = 0
        private set

    /** Dir of the in-flight stream, for the worker-liveness probe. */
    @Volatile
    private var lastStreamDirRef: File? = null

    /**
     * Execute a streaming request and expose decoded chunks as a [Flow].
     * The flow completes when the service writes the done marker; throws when it writes an error line.
     * Cancelling the flow writes the cancel marker so the service aborts promptly.
     */
    fun stream(
        context: Context,
        requestJson: String,
    ): Flow<LLMStreamChunk> = flow {
        activeStreams++
        val dir = try {
            val root = File(context.cacheDir, STAGING_ROOT)
            root.mkdirs()
            val d = File(root, "run-${UUID.randomUUID()}")
            if (!d.mkdir()) throw IllegalStateException("cannot create run dir")
            d
        } catch (e: Exception) {
            throw RuntimeException("stream staging failed", e)
        }

        val cancelFile = File(dir, ModelExecutionService.CANCEL_FILE)
        try {
            val requestFile = File(dir, "request.json")
            val streamFile = File(dir, ModelExecutionService.STREAM_FILE)
            val resultFile = File(dir, ModelExecutionService.RESULT_FILE)
            try { streamFile.createNewFile() } catch (e: Exception) {
                throw RuntimeException("cannot create stream file", e)
            }

            try {
                requestFile.writeText(requestJson)
            } catch (e: Exception) {
                throw RuntimeException("write stream request failed", e)
            }

            try {
                val intent = Intent(context, ModelExecutionService::class.java).apply {
                    putExtra(ModelExecutionService.EXTRA_REQUEST_DIR, dir.absolutePath)
                }
                context.startService(intent)
            } catch (e: Exception) {
                throw RuntimeException("start model service failed", e)
            }

            var lastRead = 0L
            var emittedChunks = false
            var lastGrowAtMs = System.currentTimeMillis()
            lastStreamDirRef = dir
            val timedOut = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                while (true) {
                    ensureActive()
                    val newLen = streamFile.length()
                    if (newLen > lastRead) {
                        lastGrowAtMs = System.currentTimeMillis()
                        val chunks = readAppendedChunks(streamFile, lastRead, newLen)
                        lastRead = chunks.second
                        for (line in chunks.first) {
                            if (line.isBlank()) continue
                            if (ChatStreamJsonl.isDone(line)) return@withTimeoutOrNull true
                            if (ChatStreamJsonl.isError(line)) {
                                throw ModelStreamErrorException(
                                    ChatStreamJsonl.errorMessage(line),
                                    hadChunks = emittedChunks,
                                )
                            }
                            ChatStreamJsonl.decode(line)?.let {
                                emittedChunks = true
                                emit(it)
                            }
                        }
                    }
                    // [TF-B crash recovery] Detect a worker process death without
                    // waiting for the 6-minute stream timeout: if the stream file
                    // stopped growing AND the worker is gone (process no longer
                    // alive) we classify worker_died — 0-chunk → caller MAY
                    // fallback, has-chunk → caller MUST NOT re-send (duplicate
                    // answer), it surfaces the error. Guard: a terminal result
                    // already written (or a lifecycle state dump present) means
                    // the worker finished NORMALLY (it self-reaps right after
                    // DONE / result) — NOT a crash. Only a missing terminal +
                    // dead process is worker_died.
                    if (newLen == lastRead &&
                        System.currentTimeMillis() - lastGrowAtMs > WORKER_DIED_GRACE_MS
                    ) {
                        val workerAlive = isWorkerProcessAlive()
                        if (!workerAlive && !File(dir, ModelExecutionMailbox.FILE_RESULT).exists()) {
                            throw ModelWorkerDiedException(hadChunks = emittedChunks)
                        }
                    }
                    delay(POLL_INTERVAL_MS)
                }
            } == null
            if (timedOut) {
                throw RuntimeException("stream timed out after ${STREAM_TIMEOUT_MS}ms")
            }
        } finally {
            // [B2] A stream is no longer in flight regardless of how we exited
            // (timeout / external cancel / normal close).
            activeStreams--
            // [TF-B cancel contract] Signal the service to stop, then WAIT for
            // its cancel.ack (or the terminal result) BEFORE deleting the
            // request dir. The old code wrote cancel then immediately
            // deleteRecursively — a race: the worker could still be appending
            // to stream.jsonl / writing result.json while the client removed
            // the directory under it (lost final chunks / torn result).
            try {
                ModelExecutionMailbox.writeCancel(cancelFile.parentFile!!)
                val deadline = System.currentTimeMillis() + CANCEL_ACK_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    if (File(dir, ModelExecutionMailbox.FILE_CANCEL_ACK).exists()) break
                    // A clean terminal result (DONE/error) is also an ack — the
                    // worker finished before seeing the cancel.
                    if (File(dir, ModelExecutionMailbox.FILE_RESULT).exists()) break
                    kotlinx.coroutines.delay(CANCEL_ACK_POLL_MS)
                }
            } catch (_: Exception) {}
            try { dir.deleteRecursively() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Cheap liveness probe for the :modelservice worker. The worker writes its
     * pid into worker.pid at start; we check /proc/<pid> which exists only
     * while the process is alive. False "dead" for a torn/incomplete pid file
     * is handled by the grace period above (we only classify death after
     * WORKER_DIED_GRACE_MS of no growth AND the probe says dead). Unknown
     * liveness (no pid file) → assume alive (never fabricate worker_died).
     */
    private fun isWorkerProcessAlive(): Boolean {
        val dir = lastStreamDirRef ?: return true
        val pidFile = File(dir, WORKER_PID_FILE)
        if (!pidFile.exists()) return true // unknown liveness → assume alive
        val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull() ?: return true
        return java.io.File("/proc/$pid").exists()
    }

    /** Read only the bytes appended after [offset] up to the last newline; return (lines, newOffset). */
    private fun readAppendedChunks(file: File, offset: Long, newLen: Long): Pair<List<String>, Long> {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val n = (newLen - offset).toInt()
                if (n <= 0) return emptyList<String>() to offset
                val buf = ByteArray(n)
                val read = raf.read(buf, 0, n)
                if (read <= 0) return emptyList<String>() to offset
                val text = String(buf, 0, read, Charsets.UTF_8)
                // Only advance to the last complete newline so a partial line is retried next poll.
                val completeEnd = text.lastIndexOf('\n')
                if (completeEnd < 0) return emptyList<String>() to offset
                val lines = text.substring(0, completeEnd).split('\n')
                lines to (offset + completeEnd + 1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "readAppendedChunks failed: ${e.message}")
            emptyList<String>() to offset
        }
    }
}