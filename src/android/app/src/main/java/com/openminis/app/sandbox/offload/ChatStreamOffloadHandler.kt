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
    /**
     * How long the stream file may stay frozen before we RE-EXAMINE worker
     * liveness. This is NOT a "no output → dead" verdict: the worker is only
     * classified [ModelExecutionRunDir.WorkerLiveness.DEAD] when BOTH this
     * grace has elapsed AND the three-state probe confirms the pid referenced
     * by THIS run dir is gone. A slow first chunk (> this grace, worker alive)
     * is NOT a death — the flow keeps polling.
     *
     * TF-G: raised from 2s to 5s. A streaming worker now holds an unacked
     * response + writes its terminal barrier before self-reaping; a 5s window
     * keeps us from classifying a perfectly-healthy-but-just-finished worker
     * as dead on the hair between DONE and pid-exit, while still surfacing a
     * genuinely crashed worker promptly.
     */
    private const val WORKER_DIED_GRACE_MS = 5_000L
    /** Bounded wait after terminal for the worker process to disappear before deleting. */
    private const val WORKER_EXIT_WAIT_MS = 6_000L
    private const val WORKER_EXIT_POLL_MS = 60L

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
        // [TF-F] Declared OUTSIDE the try so the finally block can read/write
        // them (a `finally` cannot reference locals declared inside the try's
        // nested scope). These drive the terminal-and-exit delete decision.
        var lastRead = 0L
        var emittedChunks = false
        var terminalSeen = false
        val runId = ModelExecutionDispatcher.runIdOf(dir)
        var lastGrowAtMs = System.currentTimeMillis()
        try {
            val requestFile = File(dir, "request.json")
            val streamFile = File(dir, ModelExecutionService.STREAM_FILE)
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
                            if (ChatStreamJsonl.isDone(line)) {
                                terminalSeen = true
                                return@withTimeoutOrNull true
                            }
                            if (ChatStreamJsonl.isError(line)) {
                                // [TF-F] an error LINE is a stream-terminal
                                // event (the worker will also write result +
                                // terminal marker in finishRequest). Mark it so
                                // the finally never blind-deletes a live worker.
                                terminalSeen = true
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
                    // [TF-F crash recovery] Detect worker death THREE-STATE:
                    // only a CONFIRMED dead pid (probe returns DEAD for THIS
                    // run's pid ref) after a no-growth grace is worker_died.
                    // UNKNOWN (no valid pid ref / ambiguous / recycle-race) is
                    // never classified as death — we keep polling. A slow first
                    // chunk (>WORKER_DIED_GRACE_MS, worker ALIVE) is NOT death.
                    // A terminal result/marker present means the worker finished
                    // NORMALLY (it self-reaps right after) — NOT a crash.
                    if (newLen == lastRead &&
                        System.currentTimeMillis() - lastGrowAtMs > WORKER_DIED_GRACE_MS
                    ) {
                        val liveness = ModelExecutionRunDir.probeLiveness(dir, runId)
                        if (liveness == ModelExecutionRunDir.WorkerLiveness.DEAD &&
                            !ModelExecutionRunDir.terminalPresent(dir) &&
                            !File(dir, ModelExecutionMailbox.FILE_RESULT).exists()
                        ) {
                            // TF-G P0-3: classify WHY the worker appears dead so
                            // the caller can weigh retry (0-chunk) vs fatal, and
                            // diagnostics get the run-log tail as evidence.
                            val reason = classifyWorkerDeath(dir, emittedChunks)
                            val phase = ModelExecutionRunLog.tailSummary(dir)
                            Log.w(
                                TAG,
                                "worker died (${reason.name}) runId=$runId emittedChunks=$emittedChunks phase=$phase dir=${dir.name}",
                            )
                            throw ModelWorkerDiedException(
                                hadChunks = emittedChunks,
                                reason = reason,
                                runId = runId,
                                phaseSummary = phase,
                            )
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
            // [TF-F] Unified terminal-and-exit protocol: never delete a run dir
            // while the worker might still be writing to it. Only when
            //   - a terminal marker exists (worker's LAST write), AND
            //   - the worker's pid is confirmed gone (or there is no valid ref)
            // do we delete. `result.json`/`cancel.ack` alone are NOT enough —
            // the worker may still be inside finishRequest() writing state.json
            // (the exact P0 race). Timeout → leave the dir as an orphan and
            // let the orphan reaper reclaim later.
            try {
                // Signal a cancel ONLY if we have not yet seen a terminal state
                // (normal DONE / error must NOT get a cancel shoved at it).
                if (!terminalSeen) {
                    ModelExecutionMailbox.writeCancel(cancelFile.parentFile!!)
                }
                awaitTerminalAndWorkerExitThenDelete(dir, runId)
            } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /**
     * [TF-F] Wait for (a) this run's terminal marker, then (b) the worker's
     * process to disappear, and only then delete the run dir. Any timeout or
     * ambiguity keeps the dir as an orphan (never delete under a live worker).
     */
    private suspend fun awaitTerminalAndWorkerExitThenDelete(dir: File, runId: String?) {
        // (a) terminal marker. A DONE/error line usually precedes it by a
        // hair (finishRequest writes state + terminal right after the result),
        // so give it a bounded window.
        val termDeadline = System.currentTimeMillis() + CANCEL_ACK_TIMEOUT_MS
        var terminalSeen = ModelExecutionRunDir.terminalPresent(dir)
        while (!terminalSeen && System.currentTimeMillis() < termDeadline) {
            terminalSeen = ModelExecutionRunDir.terminalPresent(dir)
                || File(dir, ModelExecutionMailbox.FILE_RESULT).exists()
                || File(dir, ModelExecutionMailbox.FILE_CANCEL_ACK).exists()
            if (!terminalSeen) kotlinx.coroutines.delay(CANCEL_ACK_POLL_MS)
        }
        // If the terminal marker itself never appeared but we DID see a result /
        // cancel ack, the worker is done with run-dir writes and will write
        // terminal (or already self-reaped). Treat result/ack as the terminal
        // barrier for deletion — the actual deletion still requires the pid gone.
        val writeBarrierSeen = terminalSeen ||
            File(dir, ModelExecutionMailbox.FILE_RESULT).exists() ||
            File(dir, ModelExecutionMailbox.FILE_CANCEL_ACK).exists()

        // TF-G: client-ACK — the other half of the self-reap barrier. Once the
        // client has seen the terminal barrier (it has read every emitted chunk
        // by this point, since the loop consumed stream.jsonl as it grew), it
        // MUST tell the worker "consumed" so the worker can stop holding an
        // unacked response and self-reap promptly. Without this the streaming
        // worker holds unacked>0 and (with the TF-G barrier) refuses to reap
        // until its ack timeout + controlled drain — pinning the process for up
        // to 45s and leaving the run dir as an orphan. The Dispatcher (non-
        // streaming) already acks; streaming now does too.
        if (writeBarrierSeen) {
            try { ModelExecutionMailbox.writeClientAck(dir) } catch (_: Exception) {}
        }

        // (b) worker process gone. Reuse the dispatcher's bounded wait logic.
        if (writeBarrierSeen && awaitWorkerExit(dir, runId, WORKER_EXIT_WAIT_MS)) {
            try { dir.deleteRecursively() } catch (_: Exception) {}
        } else {
            Log.w(
                TAG,
                "stream run dir kept as orphan (terminal=$writeBarrierSeen dir=${dir.name})",
            )
        }
    }

    /**
     * [TF-F] Bounded wait for this run's worker pid to disappear. Returns true
     * only on a confirmed DEAD for THIS run's pid ref; UNKNOWN/ALIVE returns
     * false (never delete).
     */
    private suspend fun awaitWorkerExit(dir: File, runId: String?, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (ModelExecutionRunDir.probeLiveness(dir, runId)) {
                ModelExecutionRunDir.WorkerLiveness.DEAD -> return true
                ModelExecutionRunDir.WorkerLiveness.ALIVE,
                ModelExecutionRunDir.WorkerLiveness.UNKNOWN,
                -> { /* keep waiting */ }
            }
            delay(WORKER_EXIT_POLL_MS)
        }
        return ModelExecutionRunDir.probeLiveness(dir, runId) ==
            ModelExecutionRunDir.WorkerLiveness.DEAD
    }

    /**
     * TF-G P0-3: classify WHY a worker appears dead, from THIS run dir's
     * evidence. Pure decision is delegated to
     * [ModelExecutionRunDir.classifyWorkerDeath] (JVM-testable); here we only
     * assemble the per-run facts and attach the run-log tail for diagnosis.
     */
    private fun classifyWorkerDeath(dir: File, emittedChunks: Boolean): WorkerDeathReason {
        val runId = ModelExecutionDispatcher.runIdOf(dir)
        val hasPidRef = ModelExecutionRunDir.readWorkerRef(dir, runId) != null
        val ready = File(dir, ModelExecutionRunDir.FILE_READY).exists()
        return ModelExecutionRunDir.classifyWorkerDeath(
            hasPidRef = hasPidRef,
            ready = ready,
            hadChunks = emittedChunks,
        )
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