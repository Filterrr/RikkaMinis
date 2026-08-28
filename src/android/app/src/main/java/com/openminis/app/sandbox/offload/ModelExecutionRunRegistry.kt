package com.openminis.app.sandbox.offload

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [startup-barrier] In-process registry of run dirs THIS client process
 * currently owns, keyed by dir name (`run-<uuid>`).
 *
 * It closes the worker-startup race the orphan reaper's mtime heuristic
 * could not: a run dir is created, `startService()` is issued, but the
 * `:modelservice` worker has not yet been scheduled — no `worker.pid`, no
 * `liveness.beat`. Purely file-based evidence ("no beat + no pid + old
 * mtime") can NEVER prove the worker will not start; only the CLIENT can,
 * because the client is the actor that requested it. While this process
 * holds a run registered, the reaper must not touch the dir regardless of
 * what the files look like.
 *
 * Guarantees:
 *  - registered  → dir is in flight for a live client → reaper skips it;
 *  - unregistered (normal cleanup, client crash + process restart, or
 *    explicit release) → no live client owns the dir; the 10-minute age
 *    guard then amply covers any plausible worker startup/finish window.
 *
 * Registry lives in the SAME process that dispatches runs (the main app
 * process, where [ModelExecutionOrphanReaper] also runs), so it is an
 * accurate "live client" barrier — an app restart empties it, which is
 * exactly right: after a restart the previous instance's runs have no owner.
 */
object ModelExecutionRunRegistry {

    /** dir name (run-<uuid>) → creation time, for diagnostics. */
    private val active = ConcurrentHashMap<String, Long>()

    /** Call as soon as the run dir is created, BEFORE startService(). */
    fun register(dir: File) {
        active[dir.name] = System.currentTimeMillis()
    }

    /**
     * Call on EVERY exit path of the dispatch (normal completion, timeout,
     * failure, cancellation). The reaper regains authority over the dir.
     */
    fun unregister(dir: File) {
        active.remove(dir.name)
    }

    /** True while a live client in THIS process owns the dir. */
    fun isRegistered(dir: File): Boolean = active.containsKey(dir.name)

    /** Number of registered (in-flight) runs — diagnostics/tests. */
    fun size(): Int = active.size
}
