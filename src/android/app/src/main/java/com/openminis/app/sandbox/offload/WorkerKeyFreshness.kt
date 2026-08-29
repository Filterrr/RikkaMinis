package com.openminis.app.sandbox.offload

import java.io.File

/**
 * [T-stale-apikey-worker-cache] Cross-process staleness detection for the
 * `provider_secrets` EncryptedSharedPreferences file read by the
 * `:modelservice` worker.
 *
 * ## Why this exists
 * Android's `SharedPreferences` is a PER-PROCESS singleton cache keyed by
 * backing file (ContextImpl.sSharedPrefsCache). The main process writes a new
 * API key (`ProviderRepository.saveApiKey` → EncryptedSharedPreferences
 * editor `.commit()`); the worker process reads it via
 * `EncryptedPrefsFactory.safeCreate(this, "provider_secrets").getString(...)`.
 * Both wrap the same underlying `provider_secrets.xml`, but each process has
 * its OWN in-memory cache instance. With targetSdk >= 26 and no
 * MODE_MULTI_PROCESS, a cache hit on the worker NEVER re-reads the file
 * (ContextImpl only calls `startReloadIfChangedUnexpectedly()` for
 * MODE_MULTI_PROCESS / targetSdk < HONEYCOMB) — so a worker that already
 * loaded the prefs once keeps serving the OLD key until its process dies.
 *
 * The worker is designed to self-reap after each request, but it can legally
 * linger (ack timeout 15s + controlled drain 30s), and a request arriving
 * while it lingers REVIVES the same process. In that window a just-replaced
 * key is invisible: the user swaps a quota-exhausted key for a fresh one and
 * the very next message still fails with the OLD key — the exact
 * "must restart the app for a new key to take effect" report.
 *
 * ## Fix strategy (worker-death handoff)
 * When staleness is detected the worker does NOT try to decrypt the new key
 * itself — re-opening the prefs under a different file name would mint a
 * fresh Tink keyset that cannot decrypt the existing ciphertext (the keyset
 * blob is keyed by prefs file name), and re-using the cached
 * EncryptedSharedPreferences keeps returning the cached map. Instead the
 * worker ABORTS the request before any provider work and kills its own
 * process:
 *
 *   worker detects stale key → dies immediately (no result.json, no error
 *   line, no HTTP call) → client sees a 0-chunk worker death
 *   (DIED_BEFORE_READY family) → ChatViewModel classifies it as transient
 *   (`workerDiedZeroChunk` → isTransient) → auto-retry re-dispatches →
 *   startService spawns a NEW :modelservice process → the new process's
 *   first-ever prefs load sees the NEW key → request succeeds.
 *
 * This reuses the entire existing worker-death/retry machinery; the only new
 * behavior is "check freshness before reading the key, and die instead of
 * serving a stale key".
 *
 * ## Baseline semantics
 * [captureBaseline] records the secrets file's mtime once per process (the
 * first request this worker ever served). A rewrite by the main process
 * strictly AFTER that moment is the signal — it means the file changed during
 * this process's lifetime, so anything cached from before is suspect. The
 * baseline is NOT advanced after a stale-triggered death because the process
 * never serves another request (it dies); a revived process that somehow
 * survives (see call sites) re-captures on its next request only if the
 * baseline was never set — by design we err toward re-dying rather than
 * serving a key we know might be stale.
 *
 * Extracted as a pure object (File-based, no Android classes) so it is
 * trivially JVM-testable, following the FE-4 route-A pattern.
 */
object WorkerKeyFreshness {

    /** Run-log / diagnostics marker for a stale-key-triggered abort. */
    const val STALE_KEY_CACHE = "stale_key_cache"

    private const val SECRETS_PREFS_NAME = "provider_secrets"

    /** Captured once per worker process; 0 = not yet captured. */
    @Volatile
    private var baselineMtimeMs: Long = 0L

    /** Capture the prefs file mtime as the process-start baseline (idempotent). */
    fun captureBaseline(dataDir: File) {
        if (baselineMtimeMs == 0L) {
            baselineMtimeMs = secretsFile(dataDir).lastModified()
        }
    }

    /**
     * Pure decision: has the file been rewritten after [baselineMs]?
     *
     * A missing file (mtime == 0) is never stale: nothing was written yet, so
     * the in-memory default (no key) matches disk. A baseline of 0 (not
     * captured) is likewise never stale.
     */
    fun isStale(baselineMs: Long, currentMtimeMs: Long): Boolean =
        baselineMs > 0 && currentMtimeMs > baselineMs

    /** Staleness of the real secrets file for this dataDir. */
    fun isStaleNow(dataDir: File): Boolean =
        isStale(baselineMtimeMs, secretsFile(dataDir).lastModified())

    /** The canonical encrypted prefs XML file for the app's data dir. */
    fun secretsFile(dataDir: File): File =
        File(File(dataDir, "shared_prefs"), "$SECRETS_PREFS_NAME.xml")

    /** Test hook: reset the captured baseline. Production never calls this. */
    fun resetForTests() {
        baselineMtimeMs = 0L
    }
}
