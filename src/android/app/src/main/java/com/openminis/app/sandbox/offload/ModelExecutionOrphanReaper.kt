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
 *   1. OWNERSHIP RELEASED: no live client in THIS process owns the dir
 *      ([ModelExecutionRunRegistry] — the [startup-barrier]; file evidence
 *      alone can never prove "the worker will not start", only the client
 *      that requested it can, so a registered dir is untouchable);
 *   2. EVIDENCE of a finished or dead run, one of:
 *      a. `terminal.json` exists (the worker's LAST write) AND
 *         [ModelExecutionRunDir.safeToDelete] passes; or
 *      b. [worker-crash-cleanup] the liveness beat went STALE and the
 *         worker pid is CONFIRMED dead via [ModelExecutionRunDir.probeDeathEvidence]
 *         ([DeathKind.MISSING] only — a stale beat alone proves the beat
 *         protocol stalled, not that the process died: scheduler starvation,
 *         I/O stall, GC/native stall, or process freeze can all stall the
 *         beat while the worker lives. ALIVE / UNKNOWN / IDENTITY_MISMATCH
 *         never authorize deletion); or
 *      c. [worker-crash-cleanup] no beat AND no worker.pid ref — the worker
 *         never registered. Safe ONLY because criterion 1 already proved no
 *         live client owns the dir; the 10-minute age covers any plausible
 *         startService delivery latency;
 *   3. the dir's mtime is older than [ORPHAN_AGE_MS] (never race an active run);
 *   4. the canonical path is confirmed under `[cacheDir]/model-exec/` (never
 *      delete outside the staging root — canonicalize + prefix guard);
 *   5. [toctou-recheck] ALL evidence is re-verified immediately before the
 *      delete itself (the dir may have completed/revived between the scan's
 *      first check and the delete).
 *
 * UNKNOWN liveness, an ALIVE worker, a registered dir, or anything outside
 * the staging root is NEVER deleted.
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
            // Path guard (criterion 4): canonicalize child and confirm it is a
            // direct child of the canonical staging root. A stray symlink or
            // `..` cannot escape the root this way.
            val canonicalChild = runCatching { child.canonicalFile }.getOrElse { child }
            if (canonicalChild.parentFile != canonicalRoot) {
                Log.w(TAG, "reaper skipped non-staging path: ${child.absolutePath}")
                continue
            }
            // Criterion 1: a dir owned by a live client in THIS process is
            // untouchable regardless of file evidence.
            if (ModelExecutionRunRegistry.isRegistered(child)) continue
            if (!hasDeadRunEvidence(child)) continue
            // Criterion 3: mtime older than ORPHAN_AGE_MS.
            val mtime = child.lastModified()
            if (System.currentTimeMillis() - mtime < ORPHAN_AGE_MS) continue
            // [toctou-recheck] Criterion 5: evidence may have changed since the
            // checks above (worker finished, beat resumed, client re-registered).
            // Re-verify ownership + evidence right before the destructive call.
            if (ModelExecutionRunRegistry.isRegistered(child)) continue
            if (!hasDeadRunEvidence(child)) continue
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
     * Criterion 2: evidence that this run can never produce more output.
     * One of:
     *  - terminal barrier + confirmed-safe (worker finished and gone);
     *  - stale beat + pid CONFIRMED dead ([DeathKind.MISSING]) — the
     *    [worker-crash-cleanup] DIED_MID_STREAM class;
     *  - worker never registered (no beat, no pid ref) — client already
     *    released ownership (criterion 1, checked by the caller).
     *
     * Deliberately NOT sufficient on their own: a stale beat without a
     * confirmed-dead pid (starvation / freeze / hidepid UNKNOWN), and an
     * IDENTITY_MISMATCH (drift suspicion, not proof — see the probe
     * semantics note in [ModelExecutionRunDir.probeDeathEvidence]).
     */
    private fun hasDeadRunEvidence(child: File): Boolean {
        val runId = ModelExecutionDispatcher.runIdOf(child)
        // Path (a): terminal barrier present + safe to delete.
        if (ModelExecutionRunDir.terminalPresent(child) &&
            ModelExecutionRunDir.safeToDelete(child, runId)
        ) {
            return true
        }
        val beatPresent = File(child, ModelExecutionRunDir.FILE_LIVENESS_BEAT).isFile
        // Path (b): beat went stale AND the pid is confirmed dead. The beat
        // alone is not proof of death (GC stall, scheduler starvation,
        // process freeze), so the fine-grained probe arbitrates: only
        // MISSING (the /proc entry is gone) authorizes deletion; ALIVE,
        // UNKNOWN (e.g. hidepid devices) and IDENTITY_MISMATCH keep the dir.
        if (beatPresent &&
            ModelExecutionRunDir.beatStale(child) &&
            ModelExecutionRunDir.probeDeathEvidence(child, runId).kind ==
                ModelExecutionRunDir.DeathKind.MISSING
        ) {
            return true
        }
        // Path (c): worker never registered at all: no beat and no worker.pid
        // ref (startService failed, or the process died before the request
        // thread ran). Safe only in combination with criterion 1 (no live
        // client owns the dir) + the 10-min age guard.
        if (!beatPresent && !File(child, ModelExecutionRunDir.FILE_WORKER_PID).isFile) return true
        return false
    }

    /** A run dir is a `run-<uuid>` directory (conservative shape check). */
    private fun looksLikeRunDir(f: File): Boolean {
        val name = f.name
        return name.startsWith("run-") && name.length > "run-".length + 8
    }
}
