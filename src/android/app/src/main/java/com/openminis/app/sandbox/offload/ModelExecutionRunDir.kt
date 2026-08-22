package com.openminis.app.sandbox.offload

import org.json.JSONObject
import java.io.File

/**
 * TF-F: run-directory ownership + worker-liveness protocol utilities.
 *
 * The P0 bug this module fixes: the main process deleted a `run-*` dir while
 * the `:modelservice` worker was still in `finishRequest()` writing
 * `state.json`, causing an uncaught ENOENT FATAL in the worker process (crash
 * buffer: `FileNotFoundException .../state.json open failed ENOENT`), which
 * the client mis-classified as `worker died` and re-tried 3× until exhausted.
 *
 * The ownership rules implemented here:
 *   - A run dir belongs to exactly ONE worker process and exactly ONE request
 *     (identified by runId + nonce). No global "last stream dir" surrogate —
 *     each side passes its own run dir explicitly.
 *   - The main process deletes a run dir ONLY after it has confirmed both a
 *     terminal marker AND that the worker's PID is gone (or the worker never
 *     started and we hold the terminal marker + the pid ref is absent/stale).
 *   - Liveness is THREE-STATE: only an explicit "the pid referenced by THIS
 *     run's worker.pid is gone AND the ref matches our runId" is DEAD.
 *     Anything ambiguous is UNKNOWN, and UNKNOWN never authorizes deletion
 *     nor a `worker died` classification.
 */
object ModelExecutionRunDir {

    const val TAG = "ModelExecRunDir"

    /** Worker liveness as seen by a client, for THIS run dir only. */
    enum class WorkerLiveness {
        /** We hold a valid pid/run ref and /proc/<pid> exists → process alive. */
        ALIVE,

        /** We hold a valid pid/run ref, /proc/<pid> is gone, and the runId
         *  matches ours → the process that owned THIS run is confirmed dead. */
        DEAD,

        /** No pid ref, unreadable pid file, runId mismatch, or any ambiguity
         *  (e.g. a stale pid that might belong to a recycled unrelated proc) →
         *  we MUST NOT classify death nor delete the directory. */
        UNKNOWN,
    }

    /**
     * Verifiable worker process reference. Contents pin the pid to the runId
     * (and a nonce) so a leftover worker.pid from a previous run or a recycled
     * PID can never be mistaken for THIS run's worker.
     */
    data class WorkerProcessRef(
        val pid: Int,
        val runId: String,
        val nonce: String,
        val processName: String,
        val startedAtMs: Long,
    )

    /** File names used by the run-dir ownership protocol. */
    const val FILE_WORKER_PID = "worker.pid"
    const val FILE_READY = "worker.ready"
    const val FILE_TERMINAL = "terminal.json"

    /**
     * Persist the worker's process ref in a form the client can validate.
     * Returns true on success.
     */
    fun writeWorkerPid(dir: File, ref: WorkerProcessRef): Boolean = runCatching {
        File(dir, FILE_WORKER_PID).writeText(encodeWorkerRef(ref))
        true
    }.getOrElse {
        android.util.Log.w(TAG, "writeWorkerPid failed: ${it.message}")
        false
    }

    /** Worker writes a `worker.ready` marker once it has fully started (after
     *  the pid ref is written). The client uses it to distinguish "worker not
     *  yet started" from "worker started but silent" — a slow first chunk must
     *  NOT be misjudged as a dead worker. Idempotent. */
    fun writeReady(dir: File) {
        runCatching { File(dir, FILE_READY).writeText("ready") }
    }

    /** Client → worker: runId expected to be embedded in THIS run's pid ref. */
    fun readWorkerRef(dir: File, expectedRunId: String?): WorkerProcessRef? = runCatching {
        val raw = File(dir, FILE_WORKER_PID).readText().trim()
        if (raw.isEmpty()) return null
        decodeWorkerRef(raw)?.takeIf { ref ->
            expectedRunId == null || ref.runId == expectedRunId
        }
    }.getOrElse { null }

    /**
     * Three-state liveness probe for THIS run dir only. Never fabricates DEAD
     * from ambiguous evidence (see [WorkerLiveness]).
     *
     * Note: we deliberately do NOT call /proc "not exists → immediate DEAD"
     * when the ref was not validated against a client-supplied runId — an
     * unvalidated foreign/stale pid could be a recycled process that happens
     * to be gone right now, and a later delete could race a fresh worker that
     * reused the pid. The client path always passes its runId, so the DEAD
     * verdict there is bound to its own run.
     */
    fun probeLiveness(dir: File, expectedRunId: String?): WorkerLiveness {
        val ref = readWorkerRef(dir, expectedRunId) ?: return WorkerLiveness.UNKNOWN
        // Ref is valid and (when expectedRunId != null) belongs to OUR run.
        val proc = File("/proc/${ref.pid}")
        return if (!proc.exists()) WorkerLiveness.DEAD else WorkerLiveness.ALIVE
    }

    /** True when this run has already reached a terminal marker. */
    fun terminalPresent(dir: File): Boolean = File(dir, FILE_TERMINAL).exists()

    /**
     * Atomic creation of the terminal marker — the LAST marker a worker may
     * write into a run dir. Returns true on success. Never throws.
     *
     * TF-G: this is the quiescence data barrier: the worker writes
     * `terminal.tmp` → flush + fsync → rename to `terminal.json` so a reader
     * never observes a partial marker. After this returns the run dir is
     * "write-complete" and the worker must NOT self-reap before the client
     * has consumed the stream (see the stream-ACK barrier in
     * [ModelExecutionService]).
     */
    fun writeTerminal(dir: File): Boolean = runCatching {
        val tmp = File(dir, "$FILE_TERMINAL.tmp")
        val target = File(dir, FILE_TERMINAL)
        java.io.FileOutputStream(tmp).use { fos ->
            fos.write(JSONObject().put("at", System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8))
            fos.flush()
            try { fos.fd.sync() } catch (_: Throwable) {}
        }
        if (!tmp.renameTo(target)) {
            // Fallback: if rename is rejected, write in place (still better
            // than never marking terminal — blocks safeToDelete forever).
            File(dir, FILE_TERMINAL).writeText(
                JSONObject().put("at", System.currentTimeMillis()).toString(),
            )
        }
        fsyncDir(dir)
        true
    }.getOrElse {
        android.util.Log.w(TAG, "writeTerminal failed: ${it.message}")
        false
    }

    /** Best-effort fsync of a directory so a rename is durable. Never throws. */
    fun fsyncDir(dir: File) {
        runCatching {
            java.io.FileOutputStream(dir).use { fos -> fos.fd.sync() }
        }
    }

    /** True when the client has acknowledged it consumed this run's output
     *  (stream chunks / result) — the second half of the self-reap barrier. */
    fun clientAckPresent(dir: File): Boolean = File(dir, ModelExecutionMailbox.FILE_CLIENT_ACK).exists()

    /**
     * TF-G P0-3: pure classification of WHY a worker appears dead, from the
     * per-run evidence the client holds. Pure so it is JVM-testable across the
     * full ready/pid/terminal/result/hadChunks matrix. The caller only invokes
     * this AFTER [probeLiveness] already returned DEAD AND no terminal/result
     * is present (data genuinely did not complete) — so neither UNKNOWN nor an
     * alive worker is classified here.
     *
     * @param hasPidRef  a valid worker.pid ref matching our runId was read
     * @param ready      worker.ready marker present
     * @param hadChunks  the client already emitted ≥1 stream chunk
     * @return a concrete [WorkerDeathReason]; never the ambiguous one.
     */
    fun classifyWorkerDeath(
        hasPidRef: Boolean,
        ready: Boolean,
        hadChunks: Boolean,
    ): WorkerDeathReason = when {
        hadChunks -> WorkerDeathReason.DIED_MID_STREAM
        hasPidRef && !ready -> WorkerDeathReason.DIED_BEFORE_READY
        hasPidRef -> WorkerDeathReason.DIED_AFTER_READY_NO_OUTPUT
        !hasPidRef -> WorkerDeathReason.NEVER_STARTED
        else -> WorkerDeathReason.DIED_AFTER_READY_NO_OUTPUT
    }

    /**
     * True iff the client may safely delete this run dir.
     *
     * SAFE only when BOTH hold:
     *   - a terminal marker is present (the worker finished writing run dir
     *     files and marked itself terminal), and
     *   - the worker's process is confirmed gone (DEAD) OR the worker never
     *     provided a valid pid ref (absent/stale → nothing of ours alive). An
     *     UNKNOWN liveness means the worker might still be running — NOT safe.
     *
     * The old code deleted on `result.json` OR `cancel.ack` existence alone,
     * which raced the worker's `finishRequest()` → `writeState()` → ENOENT.
     */
    fun safeToDelete(dir: File, expectedRunId: String?): Boolean {
        if (!terminalPresent(dir)) return false
        return when (probeLiveness(dir, expectedRunId)) {
            WorkerLiveness.DEAD -> true
            // No valid ref → we cannot point at a live process that owns this
            // run (it either never started writing pid, or the pid is stale/
            // foreign). Combined with a terminal marker this is safe.
            WorkerLiveness.UNKNOWN -> readWorkerRef(dir, expectedRunId) == null
            WorkerLiveness.ALIVE -> false
        }
    }

    fun encodeWorkerRef(ref: WorkerProcessRef): String = JSONObject().apply {
        put("pid", ref.pid)
        put("runId", ref.runId)
        put("nonce", ref.nonce)
        put("processName", ref.processName)
        put("startedAt", ref.startedAtMs)
    }.toString()

    fun decodeWorkerRef(raw: String): WorkerProcessRef? = runCatching {
        val obj = JSONObject(raw)
        val pid = obj.optInt("pid", -1)
        if (pid <= 0) return null
        WorkerProcessRef(
            pid = pid,
            runId = obj.optString("runId", ""),
            nonce = obj.optString("nonce", ""),
            processName = obj.optString("processName", ""),
            startedAtMs = obj.optLong("startedAt", 0L),
        )
    }.getOrNull()
}