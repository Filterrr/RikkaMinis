package com.openminis.app.sandbox.offload

import org.json.JSONObject
import java.io.File

/**
 * File-based mailbox protocol for the `:modelservice` worker (TF-B: reliable
 * worker lifecycle — replaces the old "stopSelf == reclamation proof" model).
 *
 * The main process and the worker communicate via marker files inside the
 * per-request staging directory; the worker owns ITS OWN lifecycle via
 * [ModelExecutionLifecycle] (state machine) and a `state.json` dump. The main
 * process can only REQUEST a shutdown; it can never kill the worker on its
 * own counters.
 *
 * Files per request dir:
 *   - request.json          client → worker: the serialized request
 *   - cancel                client → worker: cancellation requested
 *   - cancel.ack            worker → client: cancel acknowledged
 *   - result.tmp            worker: atomic-write staging (never seen by client)
 *   - result.json           worker → client: terminal result (atomic rename)
 *   - state.json            worker: lifecycle state dump (ACTIVE / STOPPING / DEAD …)
 *   - client.ack            client → worker: client consumed the result
 *   - worker.pid            worker: its own pid (worker_died classification)
 *
 * Atomicity: the worker writes result.tmp, flush + fsync, then renames to
 * result.json — the client NEVER observes a partial result. Cancel is
 * acknowledged with cancel.ack BEFORE the client deletes the directory (the
 * old code wrote cancel then immediately deleteRecursively, which raced the
 * worker's appends to stream.jsonl / result.json).
 *
 * Directory deletion only ever happens after a confirmed terminal state or an
 * ACK (cancel.ack / client.ack, or a result.json already present).
 */
object ModelExecutionMailbox {

    const val FILE_REQUEST = "request.json"
    const val FILE_CANCEL = "cancel"
    const val FILE_CANCEL_ACK = "cancel.ack"
    const val FILE_RESULT_TMP = "result.tmp"
    const val FILE_RESULT = "result.json"
    const val FILE_STATE = "state.json"
    const val FILE_CLIENT_ACK = "client.ack"
    const val FILE_SHUTDOWN = "shutdown"
    const val FILE_WORKER_PID = "worker.pid"

    /* ── Cancellation ─────────────────────────────────────────────── */

    /** Client → worker: request cancellation. Idempotent (createNewFile). */
    fun writeCancel(dir: File) {
        File(dir, FILE_CANCEL).createNewFile()
    }

    /** Worker → client: acknowledge that the worker saw the cancel. */
    fun writeCancelAck(dir: File) {
        File(dir, FILE_CANCEL_ACK).createNewFile()
    }

    /** Client → worker: result consumed. Lets the worker self-reap promptly. */
    fun writeClientAck(dir: File) {
        File(dir, FILE_CLIENT_ACK).createNewFile()
    }

    /* ── Shutdown (main process → worker) ─────────────────────────── */

    /**
     * Main process → worker: drain then die. Idempotent. The worker checks the
     * marker and only self-reaps once quiescent; never stopService as proof.
     */
    fun writeShutdownRequest(markerFile: File) {
        markerFile.createNewFile()
    }

    /** Worker: was the main process asking us to drain? */
    fun shutdownRequested(markerFile: File): Boolean =
        markerFile.exists()

    /* ── State dump (worker → main) ───────────────────────────────── */

    /**
     * Persist the worker's current lifecycle state + a quiescence snapshot.
     * Written before each state transition so diagnostics can answer "why is
     * :modelservice still alive?" without trusting main-process counters.
     */
    fun writeState(
        dir: File,
        state: ModelExecutionWorkerState,
        active: Int,
        unacked: Int = 0,
    ) {
        val obj = JSONObject().apply {
            put("state", state.name)
            put("active", active)
            put("unacked", unacked)
            put("at", System.currentTimeMillis())
        }
        File(dir, FILE_STATE).writeText(obj.toString())
    }

    /** Read the worker's persisted state JSON text (or null when absent). */
    fun readState(dir: File): String? =
        runCatching { File(dir, FILE_STATE).readText().trim().ifEmpty { null } }.getOrNull()

    /** Read the worker's persisted lifecycle-state NAME (ACTIVE / STOPPING / …), or null. */
    fun readStateName(dir: File): String? =
        readState(dir)?.let { runCatching { JSONObject(it).optString("state", null) }.getOrNull() }
}