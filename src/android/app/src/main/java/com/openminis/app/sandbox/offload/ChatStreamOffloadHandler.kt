package com.openminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import android.util.Log
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /**
     * Execute a streaming request and expose decoded chunks as a [Flow].
     * The flow completes when the service writes the done marker; throws when it writes an error line.
     * Cancelling the flow writes the cancel marker so the service aborts promptly.
     */
    fun stream(
        context: Context,
        requestJson: String,
    ): Flow<LLMStreamChunk> = flow {
        val dir = try {
            val root = File(context.cacheDir, STAGING_ROOT)
            root.mkdirs()
            val d = File(root, "run-${UUID.randomUUID()}")
            if (!d.mkdir()) throw IllegalStateException("cannot create run dir")
            d
        } catch (e: Exception) {
            throw RuntimeException("stream staging failed", e)
        }

        try {
            val requestFile = File(dir, "request.json")
            val streamFile = File(dir, ModelExecutionService.STREAM_FILE)
            val resultFile = File(dir, ModelExecutionService.RESULT_FILE)
            val cancelFile = File(dir, ModelExecutionService.CANCEL_FILE)
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
            val completedNormally = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                while (true) {
                    ensureActive()
                    val newLen = streamFile.length()
                    if (newLen > lastRead) {
                        val chunks = readAppendedChunks(streamFile, lastRead, newLen)
                        lastRead = chunks.second
                        for (line in chunks.first) {
                            if (line.isBlank()) continue
                            if (ChatStreamJsonl.isDone(line)) return@withTimeoutOrNull true
                            if (ChatStreamJsonl.isError(line)) {
                                throw RuntimeException("stream error: ${ChatStreamJsonl.errorMessage(line)}")
                            }
                            ChatStreamJsonl.decode(line)?.let { emit(it) }
                        }
                    }
                    delay(POLL_INTERVAL_MS)
                }
            } ?: false
            if (!completedNormally) {
                throw RuntimeException("stream timed out after ${STREAM_TIMEOUT_MS}ms")
            }
        } finally {
            // On any termination (timeout / external cancel / normal close), signal the
            // service to stop streaming so it doesn't keep appending to a deleted file.
            try { cancelFile.createNewFile() } catch (_: Exception) {}
            try { dir.deleteRecursively() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

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