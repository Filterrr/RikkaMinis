package com.openminis.app.sandbox.offload

import android.content.Context
import android.util.Log
import java.io.File

/**
 * TF-G (P1-2): orphan run-directory reaper for [ModelExecutionService] staging.
 *
 * A run dir can be left behind when:
 *   - a streaming worker timed out its client-ack and control got lost;
 *   - the client crashed / force-stopped mid-stream without deleting;
 *   - the worker self-reaped but the client never came back to delete;
 *   - [worker-crash-cleanup] the worker process DIED mid-run (LMK kill /
 *     native crash — the DIED_MID_STREAM class): such a dir never gets a
 *     `terminal.json` (only a live worker writes it), so the original
 *     terminal-only criteria leaked it FOREVER.
 *
 * Reaping is CONSERVATIVE — it only deletes a dir that satisfies ALL of:
 *   1. EVIDENCE of a finished or dead run, one of:
 *      a. `terminal.json` exists (the worker's LAST write: stream flushed +
 *         result committed + final state written) AND
 *         [ModelExecutionRunDir.safeToDelete] passes (worker pid confirmed
 *         DEAD for THIS run, or it never wrote a valid ref); or
 *      b. [worker-crash-cleanup] the liveness beat file exists but has gone
 *         STALE — the beat's ONLY writer is the worker's heartbeat thread,
 *         and the protocol stops the beat only AFTER the terminal marker is
 *         written, so a stale beat with no terminal means the worker died
 *         mid-run and the dir can never complete; or
 *      c. [worker-crash-cleanup] no beat AND no worker.pid ref — the worker
 *         never registered (startService failed / process died before the
 *         request thread ran) and the client is long gone.
 *   2. the dir's mtime is older than [ORPHAN_AGE_MS] (no worker/client touch
 *      for a while — never race an active run);
 *   3. the canonical path is confirmed under `[cacheDir]/model-exec/` (never
 *      delete outside the staging root — canonicalize + prefix guard).
 *
 * UNKNOWN liveness WITHOUT stale-beat/never-started evidence, an ALIVE
 * worker, or anything outside the staging root is NEVER deleted.
 */
object ModelExecutionOrphanReaper {

    private const val TAG = "ModelExecOrphanReaper"
    private const val STAGING_ROOT = "model-exec"
    /** A run dir must be untouched this long before it's deemed an orphan. */
    private const val ORPHAN_AGE_MS = 10 * 60 * 1000L // 10 minutes

    /**
     * Scan the staging root and delete any eligible orphan run dir. Returns
     * the number deleted (for logging). Never throws.
     */
    fun reapOrphans(context: Context): Int =
        reapOrphans(File(context.cacheDir, STAGING_ROOT))

    /** File-root overload so the JVM tests can drive the full scan. */
    fun reapOrphans(root: File): Int {
        if (!root.isDirectory) return 0
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { root }
        var deleted = 0
        val children = runCatching { root.listFiles() ?: emptyArray() }.getOrDefault(emptyArray())
        for (child in children) {
            if (!child.isDirectory) continue
            if (!looksLikeRunDir(child)) continue
            // Path guard (criterion 3): canonicalize child and confirm it is a
            // direct child of the canonical staging root. A stray symlink or
            // `..` cannot escape the root this way.
            val canonicalChild = runCatching { child.canonicalFile }.getOrElse { child }
            if (canonicalChild.parentFile != canonicalRoot) {
                Log.w(TAG, "reaper skipped non-staging path: ${child.absolutePath}")
                continue
            }
            if (!hasDeadRunEvidence(child)) continue
            // Criterion 2: mtime older than ORPHAN_AGE_MS.
            val mtime = child.lastModified()
            if (System.currentTimeMillis() - mtime < ORPHAN_AGE_MS) continue
            val ok = runCatching { child.deleteRecursively(); true }.getOrDefault(false)
            if (ok) {
                Log.i(TAG, "reaped orphan run dir: ${child.name} (mtime ${mtime}ms)")
                deleted++
            } else {
                Log.w(TAG, "reaper failed to delete orphan: ${child.name}")
            }
        }
        return deleted
    }

    /**
     * Criterion 1: evidence that this run can never produce more output —
     * either the terminal barrier + confirmed-dead worker (the original TF-G
     * path), or the [worker-crash-cleanup] evidence (stale beat / never
     * registered worker) that covers dirs a crashed worker left behind
     * without a terminal marker.
     */
    private fun hasDeadRunEvidence(child: File): Boolean {
        // Original path: terminal barrier present + safe to delete.
        if (ModelExecutionRunDir.terminalPresent(child) &&
            ModelExecutionRunDir.safeToDelete(child, ModelExecutionDispatcher.runIdOf(child))
        ) {
            return true
        }
        // [worker-crash-cleanup] The beat file's ONLY writer is the worker. A
        // stale beat with no terminal means the worker started, stopped
        // beating (crashed / killed / reaped), and never finished the run —
        // the DIED_MID_STREAM class from the client's death classifier.
        val beatPresent = File(child, ModelExecutionRunDir.FILE_LIVENESS_BEAT).isFile
        if (beatPresent && ModelExecutionRunDir.beatStale(child)) return true
        // [worker-crash-cleanup] Worker never registered at all: no beat and
        // no worker.pid ref (startService failed, or the process died before
        // the request thread ran). Guarded by the caller's 10-min age check
        // so a slow service start is never raced.
        if (!beatPresent && !File(child, ModelExecutionRunDir.FILE_WORKER_PID).isFile) return true
        return false
    }

    /** A run dir is a `run-<uuid>` directory (conservative shape check). */
    private fun looksLikeRunDir(f: File): Boolean {
        val name = f.name
        return name.startsWith("run-") && name.length > "run-".length + 8
    }
}