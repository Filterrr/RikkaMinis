package com.openminis.app.sandbox

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Manages per-session persistent shell processes.
 *
 * Architecture:
 * - PRootKernel (rootfs + proot binary): global singleton, booted once
 * - PersistentShell: one per sessionId, owns its bind mounts and /bin/sh process
 * - Per-session Mutex: different sessions can run commands concurrently
 * - ConcurrentHashMap: thread-safe shell/mutex registry
 *
 * Concurrency guarantees:
 * - Same session: commands are serialized by the per-session Mutex
 * - Different sessions: run concurrently (each has its own Mutex)
 * - Shell creation: protected by globalLock to prevent duplicate shells
 * - Shell death: detected on next command, shell is recreated with same bind mounts
 */
object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"

    // [P2-proot-native-leak]
    // High-water mark (MB) for PRoot *child process* RSS. Normal operation is
    // ~35-55MB. A long-lived PRoot tracer leaks native memory monotonically
    // (measured 6.2-6.9GB on 2026-08-07, enough to OOM the device). We read
    // the child process RSS via PersistentShell.nativeRssMB — NOT
    // Debug.getNativeHeapAllocatedSize(), which reports the *app-process*
    // heap and never sees the leaked memory held by the forked PRoot tracer.
    // When a command returns (or while it runs) and the child RSS exceeds
    // this, we recycle the session's shell so the next getOrCreateShell
    // spawns a fresh PRoot at baseline.
    private const val NATIVE_HEAP_HIGH_WATER_MARK_MB = 256L

    // [P2-app-native-oom] High-water mark for app-process native heap
    // (Debug.getNativeHeapAllocatedSize). This catches the actual OOM path
    // that nativeRssMB() misses: the PRoot tracer stays at 3MB while the
    // app process's own native heap (talloc inside PRoot's in-process
    // components, DirectByteBuffers, LOS objects) balloons past Scudo's
    // limit. Normal is ~50-100MB; lowered from 200MB to 120MB after
    // 2026-08-12 crash (native heap 542MB in 12s, recycle too late).
    private const val APP_NATIVE_HEAP_HIGH_WATER_MARK_MB = 120L

    // [P2-global-concurrency] Maximum number of persistent shells that can
    // run commands concurrently across all sessions. 3-4 sessions each
    // running dense tool-call sequences produce ~542MB app native heap in
    // 12s (2026-08-12 crash). Limiting to 2 ensures the combined memory
    // trajectory stays within the 512MB Java heap limit + 120MB native
    // headroom. Excess sessions queue on the Semaphore and get a resource-
    // busy error after 30s.
    private const val MAX_CONCURRENT_SHELLS = 2

    // [P2-app-native-hardcap] Hard cap for app-process native heap. When
    // this is exceeded, new commands are rejected immediately (before
    // acquiring the global concurrency slot) to prevent the process from
    // reaching Scudo OOM. The crash case hit 542MB before the recycling
    // mechanism could react — this is a last-line defence.
    private const val APP_NATIVE_HEAP_HARD_CAP_MB = 350L

    // [P2-app-native-oom] Java heap utilization threshold. When the agent
    // runs a dense tool-call sequence, Java heap climbs (crash case:
    // 395MB/512MB = 77%). Recycle the shell at 70% to prevent the
    // NativeAlloc GC storms that precede Scudo OOM.
    private const val JAVA_HEAP_PRESSURE_THRESHOLD = 0.70

    // [P2-app-native-oom] Maximum commands on a single shell before
    // forced recycle. The crash case was ~20 git/shell commands in 2.5
    // minutes. 30 is a safe upper bound — well above normal usage but
    // well below the accumulation that triggers Scudo OOM.
    private const val MAX_COMMANDS_PER_SHELL = 30
    // A shell idle this long is terminated to release its PRoot native
    // footprint. Generous above any agent transition gap (model thinking).
    private const val SHELL_IDLE_TIMEOUT_MS = 10 * 60 * 1000L  // 10 min
    // Sweep cadence for idle shell recycling (public for MinisApp sweeper).
    const val IDLE_SWEEP_INTERVAL_MS = 60 * 1000L              // 1 min

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        val truncated: Boolean = false,
    )

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    /** Thread-safe per-session shell registry. */
    private val shells = ConcurrentHashMap<String, PersistentShell>()

    /** Thread-safe per-session mutex registry. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Per-session snapshot of the env-var keys injected on the previous
     * `applyEnvironment` call. Used to issue `unset` for keys the user has
     * since deleted from EnvVarRepository. Long-lived shells would otherwise
     * keep the stale value around indefinitely.
     */
    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()
    // [P2-proot-native-leak] elapsedRealtime of last command completion
    // per session; drives recycleIdleShells.
    private val lastActiveMs = ConcurrentHashMap<String, Long>()

    /**
     * Global lock used only for shell creation to prevent duplicate shells
     * when the same session's first command arrives concurrently.
     */
    private val globalLock = Mutex()

    // [P2-global-concurrency] Global semaphore that limits the number of
    // sessions that can execute shell commands concurrently. Acquired before
    // the per-session mutex so that a session waiting for the global slot
    // does not block another session's per-session mutex. Fair ordering
    // prevents starvation of any single session.
    private val globalConcurrency = Semaphore(MAX_CONCURRENT_SHELLS, true)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Execute a command in the session's persistent shell.
     *
     * Flow:
     * 0. Pre-execution hard cap check (reject if native heap too high)
     * 1. Acquire global concurrency Semaphore (limit cross-session parallelism)
     * 2. Get or create per-session Mutex (serializes commands within same session)
     * 3. Get or create PersistentShell (protected by globalLock on creation)
     * 4. Execute command
     * 5. Release global concurrency Semaphore in finally
     * 6. Post-execution memory pressure check + GC recovery
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null
    ): CommandResult {
        // [P2-app-native-hardcap] Pre-execution hard cap check. Reject the
        // command immediately if the app-process native heap has already
        // exceeded the safe ceiling — the Scudo OOM is minutes away and
        // running another command will only accelerate it. Run GC before
        // returning so the caller's next retry has a better chance.
        val preExecNativeMB = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
        if (preExecNativeMB > APP_NATIVE_HEAP_HARD_CAP_MB) {
            Log.w(TAG, "[$sessionId] Native heap ${preExecNativeMB}MB exceeds hard cap ${APP_NATIVE_HEAP_HARD_CAP_MB}MB — rejecting command")
            postRecycleMemoryRecovery()
            return CommandResult(
                "[System memory pressure: native heap ${preExecNativeMB}MB exceeds safe limit. " +
                    "Please reduce concurrent sessions or wait for memory to recover.]",
                -1, 0, true
            )
        }

        // [P2-global-concurrency] Acquire the global shell slot. If all
        // slots are occupied by other sessions, wait up to 60s before
        // giving up — this prevents a third concurrent session from
        // pushing the combined memory footprint past Scudo's limit.
        val acquired = globalConcurrency.tryAcquire(60, TimeUnit.SECONDS)
        if (!acquired) {
            Log.w(TAG, "[$sessionId] Global concurrency slot timeout after 60s — rejecting command")
            postRecycleMemoryRecovery()
            return CommandResult(
                "[System busy: too many concurrent sessions (limit $MAX_CONCURRENT_SHELLS). " +
                    "Please wait for other sessions to complete and retry.]",
                -1, 0, true
            )
        }
        try {
            // ConcurrentHashMap.getOrPut is not atomic, use putIfAbsent pattern
            val mutex = mutexes.getOrPut(sessionId) { Mutex() }

            return mutex.withLock {
                val startTime = System.currentTimeMillis()

                // Auto-boot PRoot if not already booted
                if (!PRootKernel.isBooted) {
                    Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
                    PRootKernel.boot(appContext)
                }

            // [P3-shell-auto-retry] Execute with one automatic retry: if the
            // PRoot shell process itself died mid-command (HyperOS silent_kill,
            // tracer OOM, idle-recycle race), rebuild the shell and re-run the
            // command. At most 2 attempts total — guards against infinite retry
            // loops. The agent/user sees a single successful result for
            // transient infra failures.
            val (result, shell) = executeWithShellRetry(
                sessionId = sessionId,
                command = command,
                timeout = timeout,
                lineCallback = lineCallback,
            )

            val durationMs = System.currentTimeMillis() - startTime
            val sanitized = TerminalSanitizer.sanitize(result.output)
            val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
            // Combine host-side and shell-side truncation flags
            val outputTruncated = result.truncated || truncated != sanitized
            val output = if (result.exitCode != 0 && result.exitCode != 124) {
                "$truncated\n(exit code: ${result.exitCode})"
            } else {
                truncated
            }

            // [P2-proot-native-leak] mark active; recycle if the PRoot *child
            // process* RSS ballooned past the safe ceiling (tracer leak). This
            // reads the real child (PersistentShell.nativeRssMB) — NOT app
            // Debug.getNativeHeapAllocatedSize(), which misses the leak.
            lastActiveMs[sessionId] = SystemClock.elapsedRealtime()
            val prootRssMB = shell?.nativeRssMB() ?: 0L
            if (prootRssMB > NATIVE_HEAP_HIGH_WATER_MARK_MB) {
                Log.w(TAG, "[$sessionId] PRoot child RSS > ${NATIVE_HEAP_HIGH_WATER_MARK_MB}MB after command — recycling PRoot shell")
                sessionDidTerminate(sessionId)
            } else {
                Log.d(TAG, "[$sessionId] PRoot child RSS ${prootRssMB}MB — within mark")
            }

            // [P2-app-native-oom] Post-command pressure check. The PRoot child
            // RSS monitor above is blind to app-process native heap growth
            // (crash case 2026-08-09: child RSS constant 3MB while app native
            // heap + Java heap climbed to Scudo OOM in 12s of NativeAlloc GC
            // storms). Recycle on any of: app native heap > 200MB, Java heap
            // utilization > 70%, or > MAX_COMMANDS_PER_SHELL commands on one
            // shell. Recycling tears down the PRoot tracer so its in-process
            // talloc/mmap reservations are returned to the OS, restoring
            // memory to baseline for the next command.
            val nativeHeapMB = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
            val runtime = Runtime.getRuntime()
            val javaHeapUsed = runtime.totalMemory() - runtime.freeMemory()
            val javaHeapFrac = if (runtime.maxMemory() > 0L) javaHeapUsed.toDouble() / runtime.maxMemory().toDouble() else 0.0

            val nativeOversized = nativeHeapMB > APP_NATIVE_HEAP_HIGH_WATER_MARK_MB
            val javaPressured = javaHeapFrac > JAVA_HEAP_PRESSURE_THRESHOLD
            val cmdOverLimit = (shell?.commandCount ?: 0) >= MAX_COMMANDS_PER_SHELL

            when {
                nativeOversized -> Log.w(
                    TAG, "[$sessionId] App native heap ${nativeHeapMB}MB > ${APP_NATIVE_HEAP_HIGH_WATER_MARK_MB}MB — recycling shell"
                )
                javaPressured -> Log.w(
                    TAG, "[$sessionId] Java heap ${(javaHeapFrac * 100).toInt()}% > ${(JAVA_HEAP_PRESSURE_THRESHOLD * 100).toInt()}% — recycling shell"
                )
                cmdOverLimit -> Log.w(
                    TAG, "[$sessionId] Shell command count ${shell?.commandCount ?: 0} >= $MAX_COMMANDS_PER_SHELL — recycling shell"
                )
            }
            if (nativeOversized || javaPressured || cmdOverLimit) {
                sessionDidTerminate(sessionId)
            }

            CommandResult(output = output, exitCode = result.exitCode, durationMs = durationMs, truncated = outputTruncated)
        }
    } finally {
        // [P2-global-concurrency] Release the global concurrency slot.
        // This runs after the per-session mutex is released (the mutex is
        // inside the try block), so the next session waiting on the
        // Semaphore does not contend with the just-finished session's
        // mutex cleanup.
        globalConcurrency.release()
    }
    }

    /**
     * [P3-shell-auto-retry] Execute a command in the session's persistent shell
     * with ONE automatic retry when the shell process itself died mid-command
     * (exitCode == -1 from PersistentShell.readLoop, or the process is no longer
     * alive). The crash cases: HyperOS silent_kill, PRoot tracer OOM,
     * idle-recycle race. Rebuilding the shell and re-running the command
     * transparently heals all of these; the agent sees a single successful result.
     *
     * At most 2 attempts total — the loop immediately exits on success (exitCode
     * != -1 with alive shell) or after the second attempt, so infinite retry
     * loops are impossible.
     */
    private suspend fun executeWithShellRetry(
        sessionId: String,
        command: String,
        timeout: Long,
        lineCallback: ((String) -> Unit)?,
    ): Pair<CommandResult, PersistentShell?> {
        var attempt = 0
        var lastShell: PersistentShell? = null
        while (true) {
            attempt++
            val shell = getOrCreateShell(sessionId)
            lastShell = shell

            // Inject environment variables as a full snapshot (T124a).
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val previousKeys = lastInjectedKeys[sessionId] ?: emptySet()
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                lastInjectedKeys[sessionId] = envVars.keys.toSet()
            }

            val result = shell.executeCommand(
                command = command,
                timeout = timeout,
                lineCallback = lineCallback,
                memoryMonitor = { rssMB ->
                    midCommandRecycleIfOversized(shell, sessionId, rssMB)
                    val appNativeMB = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
                    if (appNativeMB > APP_NATIVE_HEAP_HIGH_WATER_MARK_MB) {
                        Log.w(TAG, "[$sessionId] App native heap ${appNativeMB}MB crossed mark in-flight — recycling shell")
                        sessionDidTerminate(sessionId)
                    }
                },
            )

            // Shell died or timed out — rebuild once and retry.
            // Timeout (124) can leave zombie processes in the PRoot tracer;
            // rebuilding the shell is safer than leaving a dead shell around.
            val shellDied = result.exitCode == -1 || result.exitCode == 124 || !shell.isAlive
            if (!shellDied || attempt >= 2) {
                if (shellDied && attempt >= 2) {
                    lastShell = null // shell is dead and we're out of retries
                }
                return CommandResult(output = result.output, exitCode = result.exitCode, durationMs = 0L, truncated = result.truncated) to lastShell
            }

            Log.w(
                TAG,
                "[$sessionId] Shell died mid-command (exit=${result.exitCode}, alive=${shell.isAlive}) " +
                    "— rebuilding and retrying (attempt $attempt/2)"
            )
            sessionDidTerminate(sessionId)
        }
    }

    /** [P2-proot-native-leak] If the PRoot child is already dead mid-command
     * (it OOM'd), immediately recycle the session so the shell isn't held as
     * a zombie and the next command spawns fresh. Called from the executeCommand
     * in-flight monitor; rssMB of 0 means the child already died. */
    private fun midCommandRecycleIfOversized(shell: PersistentShell, sessionId: String, rssMB: Long) {
        if (rssMB > NATIVE_HEAP_HIGH_WATER_MARK_MB) {
            Log.w(TAG, "[$sessionId] PRoot child RSS ${rssMB}MB crossed mark mid-command — recycling")
            sessionDidTerminate(sessionId)
        }
    }

    /**
     * Get the existing shell for this session, or create a new one.
     * Uses globalLock to prevent two coroutines from simultaneously creating
     * a shell for the same session (e.g. if the old shell just died).
     */
    private suspend fun getOrCreateShell(sessionId: String): PersistentShell {
        // Fast path: existing alive shell
        val existing = shells[sessionId]
        if (existing != null && existing.isAlive) {
            Log.d(TAG, "[$sessionId] Reusing existing shell")
            return existing
        }

        // Slow path: need to create (or recreate after crash)
        return globalLock.withLock {
            // Double-check after acquiring lock
            val recheck = shells[sessionId]
            if (recheck != null && recheck.isAlive) {
                Log.d(TAG, "[$sessionId] Reusing existing shell (post-lock)")
                return@withLock recheck
            }

            // Shell is dead or missing — clean up and create fresh
            if (recheck != null) {
                Log.w(TAG, "[$sessionId] Shell died unexpectedly, recreating")
                recheck.stop()
            }

            val bindMounts = buildSessionBindMounts(sessionId)
            val shell = PersistentShell(appContext, sessionId, bindMounts)
            shells[sessionId] = shell
            shell.ensureStarted()
            Log.i(TAG, "[$sessionId] Shell created with ${bindMounts.size} bind mounts")
            shell
        }
    }

    /**
     * Build bind mounts for a session:
     * - Session-level: workspace, attachments, offloads, browser → per-session dirs
     * - Global: memory, skills, shared → shared dirs across all sessions
     */
    private fun buildSessionBindMounts(sessionId: String): Map<String, String> {
        val filesDir = appContext.filesDir
        val mounts = linkedMapOf<String, String>()

        // Session-specific directories — written ONLY into this shell's local
        // bind-mount map, never the global PRootKernel.bindMounts. The global
        // map is shared across all sessions; per-session host paths would
        // otherwise overwrite each other (last session to boot wins), and the
        // interactive terminal (which reads the global map) would point at the
        // wrong session's dirs. Callers that need a session's per-session dir
        // must go through PRootKernel.resolveSessionHostPath(sessionId, ...).
        val sessionBase = File(filesDir, "minis-sessions/$sessionId")
        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            val hostDir = File(sessionBase, subdir).also { it.mkdirs() }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
        }

        // Global shared directories.
        // [T-android-mcp-bind-mount] mcp-servers MUST be here, not only in
        // PRootKernel.registerGlobalBindMounts: PersistentShell builds PRoot's
        // `-b` argv from THIS map, so a subdir missing here is invisible to the
        // shell that runs minis-mcp-cli — /var/minis/mcp-servers/servers.json
        // then resolves to the empty rootfs placeholder and `minis-mcp-cli list`
        // returns {"servers": [], "count": 0} even though the UI wrote the
        // server (the UI / debug.ls read via resolveHostPath, a separate map,
        // which is why they disagreed). Same trap as the external-mounts note
        // below.
        val globalBase = File(filesDir, "minis-global")
        listOf("memory", "skills", "shared", "mcp-servers").forEach { subdir ->
            val hostDir = File(globalBase, subdir).also { it.mkdirs() }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        // [T-logs-bind-android] AppLogger writes daily logs to files/logs/
        // (minis-YYYY-MM-DD.log). Bind them into /var/minis/logs so the agent's
        // shell can read the app's own runtime logs directly instead of
        // requiring a manual share from LogManagementScreen. Host dir is
        // guaranteed to exist: AppLogger.init() mkdirs it in Application.onCreate.
        val logsDir = File(filesDir, "logs").also { it.mkdirs() }
        val logsLinuxPath = "/var/minis/logs"
        mounts[logsLinuxPath] = logsDir.absolutePath
        PRootKernel.addBindMount(logsLinuxPath, logsDir.absolutePath)

        // T277: user-mounted external folders (SAF-picked trees). PersistentShell
        // uses this map verbatim as PRoot's `-b` argv, so any mount missing here
        // is invisible to the shell — `ls /var/minis/mounts/<name>/` then shows
        // only the empty rootfs placeholder. PRootKernel.bindMounts is kept in
        // sync separately by applyMountedFoldersSnapshot for the resolveHostPath
        // path (debug.ls, file_read, …) but does NOT feed the live PRoot argv.
        // Skip entries whose SAF tree URI didn't decode to a POSIX path
        // (cloud providers, unmounted removable storage).
        PRootKernel.mountedFoldersStore?.entries?.value?.forEach { entry ->
            val host = entry.resolvedHostPath ?: return@forEach
            val linuxPath = "/var/minis/mounts/${entry.name}"
            mounts[linuxPath] = host
        }

        return mounts
    }

    /**
     * Called when a session is closed. Stops and removes the shell, then
     * triggers post-recycle memory recovery to release app-process native
     * heap that the shell recycling didn't free.
     */
    fun sessionDidTerminate(sessionId: String) {
        val shell = shells.remove(sessionId)
        mutexes.remove(sessionId)
        // T124a: drop the snapshot too — a future shell for the same id
        // restarts from a clean baseline, so the next applyEnvironment
        // shouldn't try to `unset` keys that don't exist in the new shell.
        lastInjectedKeys.remove(sessionId)
        lastActiveMs.remove(sessionId)
        shell?.stop()
        if (shell != null) Log.i(TAG, "[$sessionId] Shell terminated")
        // [P2-app-native-oom] Recycle-driven GC: the shell is dead but app
        // process native heap (DirectByteBuffers, LOS objects, talloc) is
        // still held. Trigger GC + wait for it to settle so the next command
        // starts with a lower baseline.
        postRecycleMemoryRecovery()
    }

    /**
     * [P2-app-native-oom] Trigger garbage collection and log the memory
     * released. The 2026-08-12 crash showed that recycling the PRoot shell
     * alone does NOT release the app process's own native heap (the actual
     * OOM source). This forces the JVM GC to run and reports how much was
     * freed, so the next command starts from a lower baseline.
     *
     * System.gc() + Runtime.getRuntime().gc() are both hints; on Android
     * they trigger a concurrent GC. We also read Debug.getNativeHeap-
     * AllocatedSize() before/after to log the effect. This is cheap enough
     * to call on recycle and on pre-execution hard-cap rejection.
     */
    private fun postRecycleMemoryRecovery() {
        if (!::appContext.isInitialized) return
        try {
            val beforeNative = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
            val runtime = Runtime.getRuntime()
            val beforeJava = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
            System.gc()
            Runtime.getRuntime().gc()
            // Give the concurrent GC a moment to actually run before logging.
            Thread.sleep(50)
            val afterNative = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
            val afterJava = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
            Log.w(
                TAG,
                "Post-recycle GC: native ${beforeNative}MB→${afterNative}MB (freed ${beforeNative - afterNative}MB), " +
                    "java ${beforeJava}MB→${afterJava}MB (freed ${beforeJava - afterJava}MB)"
            )
        } catch (_: Throwable) {
            // Never let memory recovery break the calling flow.
        }
    }

    /**
     * [P2-proot-native-leak] Recycle shells idle past SHELL_IDLE_TIMEOUT_MS.
     * Long-lived PRoot shells leak native memory monotonically; terminating
     * idle ones releases their footprint. Next command re-spawns fresh.
     */
    fun recycleIdleShells() {
        val now = SystemClock.elapsedRealtime()
        for (sessionId in shells.keys) {
            val last = lastActiveMs[sessionId] ?: 0L
            if (last != 0L && (now - last) > SHELL_IDLE_TIMEOUT_MS) {
                Log.w(TAG, "[$sessionId] shell idle ${(now - last) / 1000}s — recycling")
                sessionDidTerminate(sessionId)
            }
        }
    }

    /**
     * [P2-proot-resource-hygiene] Clear accumulated junk from the PROOT_TMP_DIR
     * cache and the guest rootfs temp dirs. PRoot writes transient files
     * (loader cache, temp scratch) under app cache; apk/busybox and command
     * output also accumulate in rootfs /tmp and /var/tmp. Over weeks these grow
     * and push disk pressure / IO overhead (the "use it a long time → flash
     * crash" class of report). Mirrors RikkaHub's cleanupAllTempDirs, which runs
     * after each command — here we sweep on a low cadence instead so we never
     * delete a file a *running* command still needs.
     *
     * Only runs when NO shell is mid-command, to avoid racing an in-flight
     * command's temp files (they get reaped on the next sweep).
     */
    fun cleanupProotTmp() {
        // Skip entirely if any shell is alive and possibly executing — safest
        // and sufficient given the sweeper runs every minute.
        if (shells.values.any { it.isAlive }) return
        if (!::appContext.isInitialized) return
        val ctx = appContext
        val tmpDir = PRootKernel.getProotTmpDir(ctx)
        try {
            var removed = 0L
            tmpDir.listFiles()?.forEach { f ->
                if (f.deleteRecursively()) removed++
            }
            if (removed > 0) {
                Log.i(TAG, "cleanupProotTmp: cleared $removed entries from ${tmpDir.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cleanupProotTmp failed: ${t.message}")
        }
    }

    /**
     * Stop the shell for a specific session (e.g. user tapped cancel).
     * The shell process is killed; next command will recreate it.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) {
            val shell = shells.remove(sessionId)
            // T124a: snapshot belongs to the now-dead shell.
            lastInjectedKeys.remove(sessionId)
            lastActiveMs.remove(sessionId)
            shell?.stop()
            Log.i(TAG, "[$sessionId] Shell stopped by user")
        } else {
            // Stop all sessions (legacy/fallback)
            shells.values.forEach { it.stop() }
            shells.clear()
            lastInjectedKeys.clear()
            lastActiveMs.clear()
            @Suppress("DEPRECATION")
            ShellExecutor.destroyCurrent()
        }
    }

    /** Legacy overload for callers without sessionId. */
    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    /**
     * Propagate a system-timezone change to every live shell.
     *
     * - Updates [PRootKernel.customEnvironment]["TZ"] so future shells inherit
     *   the new value at spawn time.
     * - Exports the new TZ into every already-running [PersistentShell] via
     *   `export TZ=...` on stdin.
     * - Asks [TerminalSession] to do the same for every live interactive PTY.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        val tz = PRootKernel.updateTimezone()
        val tzMap = mapOf("TZ" to tz)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(tzMap)
        }
        TerminalSession.broadcastTimezone(tz)
    }

    /**
     * Propagate a system-proxy change to every live shell. Exports all six
     * proxy keys as a block — empty strings when no proxy is configured, so
     * a disable transition clears the old values in-place without needing
     * a separate `unset`.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastProxyChange() {
        if (!PRootKernel.isBooted) return
        val env = PRootKernel.updateProxy(appContext)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(env)
        }
        TerminalSession.broadcastProxy(env)
    }
}
