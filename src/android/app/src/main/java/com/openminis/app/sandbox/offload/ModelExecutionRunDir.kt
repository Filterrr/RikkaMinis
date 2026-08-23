package com.openminis.app.sandbox.offload

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

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
     * PID can never be mistaken for THIS run's worker. The process identity
     * fields are deliberately persisted: `/proc/<pid>` existence alone is not
     * proof that the same worker is still alive.
     */
    data class WorkerProcessRef(
        val pid: Int,
        val runId: String,
        val nonce: String,
        val processName: String,
        val startedAtMs: Long,
        val procStartTicks: Long = 0L,
        val uid: Int = -1,
    )

    /** Minimal identity read from `/proc/<pid>` for liveness verification. */
    data class ProcIdentity(
        val processName: String,
        val uid: Int,
        val procStartTicks: Long,
    )

    enum class ProcReadStatus { PRESENT, MISSING, UNREADABLE }

    data class ProcReadResult(
        val status: ProcReadStatus,
        val identity: ProcIdentity? = null,
        val detail: String? = null,
    )

    /** File names used by the run-dir ownership protocol. */
    const val FILE_WORKER_PID = "worker.pid"
    const val FILE_READY = "worker.ready"
    const val FILE_TERMINAL = "terminal.json"

    /**
     * Persist the worker's process ref atomically. A partially-written ref is
     * indistinguishable from a stale/ref-reused pid to the client, so it must
     * never replace the previous complete file in place.
     */
    fun writeWorkerPid(dir: File, ref: WorkerProcessRef): Boolean = runCatching {
        val tmp = File(dir, "$FILE_WORKER_PID.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(encodeWorkerRef(ref).toByteArray(Charsets.UTF_8))
            fos.flush()
            try { fos.fd.sync() } catch (_: Throwable) {}
        }
        if (!tmp.renameTo(File(dir, FILE_WORKER_PID))) {
            tmp.delete()
            error("worker.pid rename failed")
        }
        fsyncDir(dir)
        true
    }.getOrElse {
        android.util.Log.w(TAG, "writeWorkerPid failed: ${it.message}")
        false
    }

    /** Worker writes a `worker.ready` marker once the request thread has
     * started and opened its stream file. The service must not publish ready
     * merely because onStartCommand ran. Idempotent and defensive. */
    fun writeReady(dir: File): Boolean = runCatching {
        File(dir, FILE_READY).writeText("ready")
        true
    }.getOrElse {
        android.util.Log.w(TAG, "writeReady failed: ${it.message}")
        false
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
     * Read a process identity from a proc root. Kept injectable for JVM tests;
     * Android production passes `/proc`. A missing pid is definitive death,
     * while permission/IO failures remain UNKNOWN to callers.
     */
    fun readProcIdentity(pid: Int, procRoot: File = File("/proc")): ProcReadResult = runCatching {
        val procDir = File(procRoot, pid.toString())
        if (!procDir.exists()) {
            return ProcReadResult(ProcReadStatus.MISSING, detail = "proc_missing")
        }
        val comm = File(procDir, "comm").readText().trim()
        val status = File(procDir, "status").readLines()
        val uid = status.firstOrNull { it.startsWith("Uid:") }
            ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
            ?: return ProcReadResult(ProcReadStatus.UNREADABLE, detail = "uid_missing")
        val stat = File(procDir, "stat").readText().trim()
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) {
            return ProcReadResult(ProcReadStatus.UNREADABLE, detail = "stat_malformed")
        }
        // `/proc/<pid>/stat` field 22 is process starttime; after comm, the
        // remaining fields begin at field 3, so index 19 is field 22.
        val fields = stat.substring(close + 2).trim().split(Regex("\\s+"))
        val startTicks = fields.getOrNull(19)?.toLongOrNull()
            ?: return ProcReadResult(ProcReadStatus.UNREADABLE, detail = "start_ticks_missing")
        // Prefer cmdline (full argv0) but tolerate its absence/emptiness —
        // some processes (kernel threads, zombies, SELinux-restricted) make
        // it unreadable while `comm` is always present.
        val cmdlineRaw = runCatching {
            File(procDir, "cmdline").readText().trim('\u0000').substringBefore('\u0000')
        }.getOrNull().orEmpty()
        val processName = cmdlineRaw.ifBlank { comm }.trim()
        if (processName.isEmpty()) {
            return ProcReadResult(ProcReadStatus.UNREADABLE, detail = "process_name_missing")
        }
        ProcReadResult(
            status = ProcReadStatus.PRESENT,
            identity = ProcIdentity(processName, uid, startTicks),
        )
    }.getOrElse { error ->
        ProcReadResult(ProcReadStatus.UNREADABLE, detail = error.javaClass.simpleName)
    }

    fun procIdentityMatches(ref: WorkerProcessRef, actual: ProcIdentity): Boolean {
        val processMatches = ref.processName.isBlank() ||
            actual.processName == ref.processName ||
            actual.processName.endsWith(ref.processName)
        val uidMatches = ref.uid < 0 || actual.uid == ref.uid
        val startMatches = ref.procStartTicks <= 0L || actual.procStartTicks == ref.procStartTicks
        return processMatches && uidMatches && startMatches
    }

    /**
     * Three-state liveness probe for THIS run dir only. Identity mismatch is
     * definitive evidence that the recorded worker is gone/replaced; unreadable
     * proc state is UNKNOWN and must never authorize retry or deletion.
     */
    fun probeLiveness(
        dir: File,
        expectedRunId: String?,
        procRoot: File = File("/proc"),
    ): WorkerLiveness {
        val ref = readWorkerRef(dir, expectedRunId) ?: return WorkerLiveness.UNKNOWN
        val result = readProcIdentity(ref.pid, procRoot)
        return when (result.status) {
            ProcReadStatus.MISSING -> WorkerLiveness.DEAD
            ProcReadStatus.UNREADABLE -> WorkerLiveness.UNKNOWN
            ProcReadStatus.PRESENT -> {
                val actual = result.identity
                if (actual != null && procIdentityMatches(ref, actual)) {
                    WorkerLiveness.ALIVE
                } else {
                    WorkerLiveness.DEAD
                }
            }
        }
    }

    /** True when this run has already reached a terminal marker. */
    fun terminalPresent(dir: File): Boolean = File(dir, FILE_TERMINAL).exists()

    /**
     * TF-I P0-B/P0-D: fine-grained death probe for the CLIENT death trigger.
     *
     * `probeLiveness` collapses "pid MISSING" and "identity mismatch" both into
     * DEAD. For the client's `worker died` decision that is too aggressive: an
     * identity mismatch (e.g. processName/uid/startTicks read-back drift on a
     * real device) can fire while the worker is actually alive but blocked
     * behind the execution mutex — killing the request 6-8s before it ever
     * reaches HTTP. TF-H's open question (a) real provider crash vs (b) probe
     * mismatch hinges on this distinction.
     *
     * This probe returns a struct so the client (and the run log, P0-D) can
     * tell the two apart. Only [DeathEvidence.MISSING] is a *confirmed* death;
     * [DeathEvidence.IDENTITY_MISMATCH] is suspicious but NOT proof the worker
     * died — the worker may still be alive with drift. The client should only
     * fire `worker died` on MISSING (optionally after a mould of identity-
     * mismatch evidence that has not reverted to ALIVE).
     */
    fun probeDeathEvidence(
        dir: File,
        expectedRunId: String?,
        procRoot: File = File("/proc"),
    ): DeathEvidence {
        val ref = readWorkerRef(dir, expectedRunId) ?: return DeathEvidence(DeathKind.NO_REF, detail = "no_pid_ref", pid = null)
        val result = readProcIdentity(ref.pid, procRoot)
        return when (result.status) {
            ProcReadStatus.MISSING -> DeathEvidence(DeathKind.MISSING, detail = "proc_missing", pid = ref.pid)
            ProcReadStatus.UNREADABLE -> DeathEvidence(DeathKind.UNKNOWN, detail = result.detail ?: "unreadable", pid = ref.pid)
            ProcReadStatus.PRESENT -> {
                val actual = result.identity
                if (actual != null && procIdentityMatches(ref, actual)) {
                    DeathEvidence(DeathKind.ALIVE, detail = "identity_matches", pid = ref.pid)
                } else {
                    // TF-I P0-D: surface exactly which identity field drifted
                    // so a false-DEAD can be attributed to probe mismatch.
                    val drift = when {
                        actual == null -> "(no identity)"
                        !procNameMatches(ref, actual) -> "name ref='${ref.processName}' actual='${actual.processName}'"
                        ref.uid >= 0 && actual.uid != ref.uid -> "uid ref=${ref.uid} actual=${actual.uid}"
                        else -> "startTicks ref=${ref.procStartTicks} actual=${actual.procStartTicks}"
                    }
                    DeathEvidence(DeathKind.IDENTITY_MISMATCH, detail = drift, pid = ref.pid)
                }
            }
        }
    }

    /** True when the two process names agree (blank ref = wildcard). */
    private fun procNameMatches(ref: WorkerProcessRef, actual: ProcIdentity): Boolean =
        ref.processName.isBlank() || actual.processName == ref.processName || actual.processName.endsWith(ref.processName)

    /** Fine-grained death evidence kinds. */
    enum class DeathKind { MISSING, IDENTITY_MISMATCH, ALIVE, UNKNOWN, NO_REF }

    data class DeathEvidence(
        val kind: DeathKind,
        val detail: String? = null,
        val pid: Int? = null,
    )

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
     * TF-H: stage-aware classification on top of the classic matrix. The
     * caller can pass a run-log phase tail; a worker that never reached the
     * request thread is NEVER_STARTED, one that reached the thread but never
     * got to HTTP/chunks is DIED_BEFORE_READY, and only then does the classic
     * ready/hadChunks matrix decide.
     */
    fun classifyWorkerDeathStaged(
        hasPidRef: Boolean,
        ready: Boolean,
        hadChunks: Boolean,
        reachedRequestThread: Boolean,
        reachedHttp: Boolean,
    ): WorkerDeathReason = when {
        hadChunks -> WorkerDeathReason.DIED_MID_STREAM
        hasPidRef && !reachedRequestThread -> WorkerDeathReason.NEVER_STARTED
        hasPidRef && reachedRequestThread && !reachedHttp -> WorkerDeathReason.DIED_BEFORE_READY
        else -> classifyWorkerDeath(hasPidRef, ready, hadChunks)
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
    fun safeToDelete(dir: File, expectedRunId: String?, procRoot: File = File("/proc")): Boolean {
        if (!terminalPresent(dir)) return false
        return when (probeLiveness(dir, expectedRunId, procRoot)) {
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
        put("procStartTicks", ref.procStartTicks)
        put("uid", ref.uid)
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
            procStartTicks = obj.optLong("procStartTicks", 0L),
            uid = obj.optInt("uid", -1),
        )
    }.getOrNull()
}