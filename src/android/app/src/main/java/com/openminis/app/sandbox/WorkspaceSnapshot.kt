package com.openminis.app.sandbox

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [feat-workspace-snapshot] Run-level workspace snapshots with rollback.
 *
 * An agent run can touch dozens of files under the session workspace
 * (`/var/minis/workspace/...`). The shell CAN copy files, but the agent
 * never remembers to snapshot before it starts mutating, and the user
 * has no "undo this run's file changes" affordance. This is a guarantee
 * only the app layer can provide: take a zip snapshot before the run's
 * mutations begin, let the user roll the workspace back afterwards.
 *
 * Design:
 *  - Snapshots live in app-private storage (`context.filesDir/workspace-snapshots/<sessionId>/`),
 *    NOT inside the sandbox — the agent must never be able to see or
 *    tamper with its own undo data.
 *  - Format: a zip of the workspace tree + a `META.txt` sidecar entry
 *    (runId, startedAt, fileCount, bytes). Zip deflate keeps text-heavy
 *    workspaces small; empty dirs are not preserved (acceptable: the
 *    workspace is a scratch area, not a layout-critical tree).
 *  - Budget guard: workspaces above [MAX_SNAPSHOT_BYTES] (default 256MB
 *    of raw content) are skipped and reported as `null` — the rollback
 *    entry simply won't exist for that run, and a log line explains why.
 *    Snapshotting must never block or slow the run's hot path.
 *  - Retention: keep at most [MAX_SNAPSHOTS_PER_SESSION] per session
 *    (oldest pruned) so a long-lived chat can't grow the private dir
 *    without bound.
 *  - Thread-safety: all entry points are called from the IO dispatcher;
 *    the in-memory "latest snapshot" index is a ConcurrentHashMap.
 *
 * Every failure is swallowed (returns null) — snapshotting must never
 * break the run, mirroring [SubagentRunJournal]/SubagentRunCheckpoint.
 */
object WorkspaceSnapshot {

    private const val TAG = "WorkspaceSnapshot"
    private const val ROOT_DIR = "workspace-snapshots"
    private const val META_NAME = "META.txt"

    /** Skip snapshotting workspaces whose content exceeds this (bytes). */
    private const val MAX_SNAPSHOT_BYTES = 256L * 1024L * 1024L

    /** Per-session retention cap (oldest snapshot file pruned first). */
    private const val MAX_SNAPSHOTS_PER_SESSION = 3

    /** sessionId → host-side zip file of the latest taken snapshot. */
    private val latestSnapshot = ConcurrentHashMap<String, SnapshotMeta>()

    data class SnapshotMeta(
        val sessionId: String,
        val runId: String,
        val file: File,
        val fileCount: Int,
        val bytes: Long,
        val takenAtMs: Long,
    )

    /** Host-side workspace root for a session (mirrors ExecutionCoordinator's layout). */
    private fun workspaceHostRoot(sessionId: String, context: Context): File {
        return PRootKernel.resolveSessionHostPath(
            sessionId, "/var/minis/workspace", context,
        ) ?: File(context.filesDir, "sessions/$sessionId/workspace")
    }

    private fun snapshotDir(sessionId: String, context: Context): File =
        File(context.filesDir, "$ROOT_DIR/$sessionId").apply { mkdirs() }

    /**
     * Snapshot the session workspace before a run's mutations. Returns
     * metadata, or null when the workspace is missing/empty/over budget —
     * callers treat null as "no rollback available for this run" (NOT as
     * an error).
     */
    fun snapshotBeforeRun(sessionId: String, runId: String, context: Context): SnapshotMeta? =
        runCatching {
            val root = workspaceHostRoot(sessionId, context)
            if (!root.isDirectory) return null

            // Budget check: total regular-file bytes (symlinks skipped —
            // proot layout may contain session symlinks we must not follow).
            var total = 0L
            var count = 0
            val files = root.walkTopDown()
                .onEnter { dir -> dir.name != ".subagent" } // journal/ckpt churn — exclude
                .filter { it.isFile && !it.isSymbolicLink }
                .onEach { total += it.length(); count++ }
                .toList()
            if (count == 0) return null
            if (total > MAX_SNAPSHOT_BYTES) {
                AppLogger.warning(
                    TAG, "workspace too large for snapshot: $total bytes (> $MAX_SNAPSHOT_BYTES) sid=$sessionId",
                )
                return null
            }

            val dir = snapshotDir(sessionId, context)
            val out = File(dir, "ws-$runId.zip")
            java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zos ->
                val rel = root.toPath()
                for (f in files) {
                    val entryName = rel.relativize(f.toPath()).toString().replace('\\', '/')
                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                    f.inputStream().use { it.copyTo(zos, 64 * 1024) }
                    zos.closeEntry()
                }
                zos.putNextEntry(java.util.zip.ZipEntry(META_NAME))
                zos.write(metaBody(sessionId, runId, count, total).toByteArray())
                zos.closeEntry()
            }

            val meta = SnapshotMeta(
                sessionId = sessionId,
                runId = runId,
                file = out,
                fileCount = count,
                bytes = total,
                takenAtMs = System.currentTimeMillis(),
            )
            latestSnapshot[sessionId] = meta
            prune(sessionId, context)
            meta
        }.onFailure {
            AppLogger.warning(TAG, "snapshotBeforeRun failed: ${it.message}")
        }.getOrNull()

    /** Latest available snapshot for the session, or null. */
    fun latest(sessionId: String): SnapshotMeta? = latestSnapshot[sessionId]

    /**
     * Roll the workspace back to the latest snapshot (i.e. undo every
     * file change made after the snapshot was taken). Returns a summary
     * string for the tool-result / toast, or null when there is nothing
     * to roll back to.
     */
    fun rollbackLatest(sessionId: String, context: Context): String? = runCatching {
        val meta = latestSnapshot[sessionId] ?: return null
        val root = workspaceHostRoot(sessionId, context)
        if (!root.isDirectory) return null
        if (!meta.file.isFile) {
            latestSnapshot.remove(sessionId)
            return null
        }

        // Delete current contents (except the excluded journal dir), then
        // unzip the snapshot back. Files created after the snapshot vanish
        // — that is the point of a rollback.
        val keep = root.resolve(".subagent")
        root.listFiles()?.forEach { child ->
            if (child == keep) return@forEach
            child.deleteRecursively()
        }
        var restored = 0
        java.util.zip.ZipInputStream(meta.file.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory || entry.name == META_NAME) { zis.closeEntry(); continue }
                val out = File(root, entry.name)
                // Zip-slip guard: entry must resolve inside the root.
                if (!out.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                    zis.closeEntry(); continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { zis.copyTo(it, 64 * 1024) }
                zis.closeEntry()
                restored++
            }
        }
        latestSnapshot.remove(sessionId)
        val msg = "rolled back ${meta.fileCount} snapshot files (restored $restored); " +
            "run ${meta.runId}'s file changes were undone"
        AppLogger.info(TAG, msg + " sid=$sessionId")
        msg
    }.onFailure {
        AppLogger.warning(TAG, "rollbackLatest failed: ${it.message}")
    }.getOrNull()

    /** Drop the session's snapshot files (session deleted / VM cleared). */
    fun clearSession(sessionId: String, context: Context) {
        latestSnapshot.remove(sessionId)
        runCatching { snapshotDir(sessionId, context).deleteRecursively() }
    }

    private fun prune(sessionId: String, context: Context) {
        runCatching {
            val dir = snapshotDir(sessionId, context)
            val zips = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
                ?.sortedBy { it.lastModified() } ?: return
            while (zips.size > MAX_SNAPSHOTS_PER_SESSION) {
                val victim = zips.removeAt(0)
                val staleRun = latestSnapshot[sessionId]?.runId
                if (victim == latestSnapshot[sessionId]?.file) {
                    // Never prune the live rollback target.
                    zips.add(victim); break
                }
                victim.delete()
                AppLogger.info(TAG, "pruned old snapshot ${victim.name} run=$staleRun")
            }
        }
    }

    private fun metaBody(sessionId: String, runId: String, fileCount: Int, bytes: Long): String =
        buildString {
            appendLine("sessionId=$sessionId")
            appendLine("runId=$runId")
            appendLine("fileCount=$fileCount")
            appendLine("bytes=$bytes")
            appendLine("takenAt=${java.time.Instant.now()}")
        }
}
