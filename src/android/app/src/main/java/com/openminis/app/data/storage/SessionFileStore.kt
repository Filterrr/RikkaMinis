package com.openminis.app.data.storage

import android.content.Context
import java.io.File

/**
 * Singular owner of the on-disk footprint attached to a chat session.
 *
 * A session's real existence is THREE things, and they must live and die
 * together:
 *  1. the DB row(s) in ChatDao          → chatRepository/dao ownership
 *  2. its bind-mounted dir under filesDir/minis-sessions/<sid>/
 *     (workspace / attachments / offloads / browser)               → this class
 *  3. its media under filesDir/media/<date>/<sid>/                 → MediaStore
 *
 * Before this class existed, deleting a session only dropped the DB rows —
 * the files under minis-sessions/<sid>/ (and media) were orphaned forever:
 * invisible to the DB-backed session list yet still burning disk. This is the
 * same lineage as the backup-OOM: chat session dirs accumulate large tool
 * artifacts (workspace downloads, browser data, offload dumps, attachments)
 * with no reclamation path.
 *
 * Centralising session-file ownership here (instead of scattering
 * deleteRecursively/directorySize inline in UI) gives deleteSession-> and the
 * storage page a single, converged notion of "empty this session / reclaim
 * orphans".
 */
class SessionFileStore(context: Context) {

    /** Root that holds one subdir per live session: filesDir/minis-sessions/<sid>/. */
    val sessionsRoot: File = File(context.filesDir, "minis-sessions")

    /** Media root: filesDir/media/<date>/<sid>/. */
    private val mediaRoot: File = File(context.filesDir, "media")

    /** Per-session directory under [sessionsRoot]. */
    fun sessionDir(sessionId: String): File = File(sessionsRoot, sessionId)

    /** Recursive on-disk size of [dir] in bytes (regular files only). */
    fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) total += f.length() }
        return total
    }

    /** Media bytes belonging to [sessionId] (files anywhere under media/<…>/<sid>/). */
    fun mediaSize(sessionId: String): Long {
        if (!mediaRoot.exists()) return 0L
        var total = 0L
        mediaRoot.walkTopDown().forEach { f ->
            if (f.isFile && f.parentFile?.name == sessionId) total += f.length()
        }
        return total
    }

    /**
     * Per-subdir breakdown of one session's bind-mounted dir under
     * minis-sessions/<sid>/ (workspace / attachments / offloads / browser).
     * [C: storage-page breakdown] — lets the UI surface which component of a
     * session's footprint is actually huge (usually workspace), instead of one
     * opaque "total" number.
     */
    fun sessionSubdirSizes(sessionId: String): Map<String, Long> {
        val dir = sessionDir(sessionId)
        if (!dir.exists()) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) out[child.name] = sizeOf(child)
        }
        return out
    }

    /** Recursively delete a session's bind-mounted dir + its media. No-op if absent. */
    fun deleteSessionFiles(sessionId: String) {
        sessionDir(sessionId).takeIf { it.exists() }?.deleteRecursively()
        deleteMedia(sessionId)
    }

    /** Delete media dirs holding [sessionId]. Matches MediaStore layout media/<date>/<sid>/. */
    private fun deleteMedia(sessionId: String) {
        if (!mediaRoot.exists()) return
        mediaRoot.walkTopDown().forEach { dir ->
            if (dir.isDirectory && dir.name == sessionId) dir.deleteRecursively()
        }
    }

    /**
     * B: orphan reclamation. Any dir under [sessionsRoot] or any media subdir
     * whose id is NOT in [liveSessionIds] is a leftover from a deleted (or
     * never-committed) session. Returns the reclaimable bytes and reclaims them.
     */
    fun reclaimOrphans(liveSessionIds: Set<String>): ReclaimReport {
        val report = scanOrphans(liveSessionIds)
        with(report) {
            // Delete the scanned session dirs (ids in the report).
            if (sessionIds.isNotEmpty()) {
                sessionIds.forEach { sessionDir(it).takeIf { d -> d.exists() }?.deleteRecursively() }
            }
            // Re-delete media leaves (walk again; ids may overlap session dirs).
            deleteOrphanMediaLeaves(liveSessionIds)
        }
        return report
    }

    /**
     * True if [name] looks like a session directory id. Session ids are always
     * `UUID.randomUUID().toString()` (36 chars, 4 hyphens). Guarding on this
     * shape means orphan reclamation can never mistake an unrelated folder or
     * stray file under minis-sessions/ (or a media date path) for a session and
     * delete it — fail-safe: unknown-looking names are simply never reclaimed.
     */
    private fun looksLikeSessionId(name: String): Boolean {
        return name.length == 36 && name.count { it == '-' } == 4
    }

    /**
     * B: scan-only variant — measures reclaimable space WITHOUT deleting.
     * The storage page calls this on entry to surface a "X MB of orphan data"
     * banner that the user explicitly confirms before anything is removed.
     */
    fun scanOrphans(liveSessionIds: Set<String>): ReclaimReport {
        val sessionIds = mutableListOf<String>()
        var sessionBytes = 0L
        var mediaBytes = 0L
        if (sessionsRoot.exists()) {
            sessionsRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory && looksLikeSessionId(dir.name) && dir.name !in liveSessionIds) {
                    sessionIds += dir.name
                    sessionBytes += sizeOf(dir)
                }
            }
        }
        if (mediaRoot.exists()) {
            mediaRoot.walkTopDown().forEach { dir ->
                if (dir.isDirectory &&
                    dir.listFiles()?.none { it.isDirectory } == true &&
                    looksLikeSessionId(dir.name) &&
                    dir.name !in liveSessionIds
                ) {
                    mediaBytes += sizeOf(dir)
                }
            }
        }
        return ReclaimReport(
            sessionIds = sessionIds,
            sessionDirs = sessionIds.size,
            sessionBytes = sessionBytes,
            mediaDirs = 0,
            mediaBytes = mediaBytes,
        )
    }

    private fun deleteOrphanMediaLeaves(liveSessionIds: Set<String>) {
        if (!mediaRoot.exists()) return
        mediaRoot.walkTopDown().forEach { dir ->
            if (dir.isDirectory &&
                dir.listFiles()?.none { it.isDirectory } == true &&
                looksLikeSessionId(dir.name) &&
                dir.name !in liveSessionIds
            ) {
                dir.deleteRecursively()
            }
        }
    }

    /**
     * Batch media-size lookup for many live sessions (avoids re-walking the
     * whole media tree once per session).
     */
    fun mediaSizesBySessionBrief(sessionIds: Set<String>): Map<String, Long> {
        if (!mediaRoot.exists()) return emptyMap()
        val sizes = mutableMapOf<String, Long>()
        mediaRoot.walkTopDown().forEach { f ->
            if (f.isFile) {
                val sid = f.parentFile?.name ?: return@forEach
                if (sid in sessionIds) sizes[sid] = (sizes[sid] ?: 0L) + f.length()
            }
        }
        return sizes
    }

    data class ReclaimReport(
        val sessionIds: List<String> = emptyList(),
        val sessionDirs: Int = 0,
        val sessionBytes: Long = 0L,
        val mediaDirs: Int = 0,
        val mediaBytes: Long = 0L,
    ) {
        val totalBytes: Long get() = sessionBytes + mediaBytes
        val totalDirs: Int get() = sessionDirs + mediaDirs
    }
}
