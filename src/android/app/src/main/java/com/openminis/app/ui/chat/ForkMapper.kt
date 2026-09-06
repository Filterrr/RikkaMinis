package com.openminis.app.ui.chat

import com.openminis.app.data.model.MediaRef
import java.io.File

/**
 * [T-message-fork] Pure helpers for forking a conversation from a message.
 * Dependency-free (File only) so the path-remapping logic runs as plain JVM
 * unit tests in CI.
 */
internal object ForkMapper {

    /**
     * Media files live at `filesDir/media/<yyyy/MM/dd>/<sessionId>/<uuid>.<ext>`
     * (MediaStore.saveMedia). The session id is always a full path segment,
     * so a textual replace of "/<fromSid>/" → "/<toSid>/" on relativePath
     * strings is segment-safe: UUID session ids never contain "/" and the
     * date segments are fixed-width digits.
     */
    internal fun remapMediaRelativePath(relativePath: String, fromSid: String, toSid: String): String =
        relativePath.replace("/$fromSid/", "/$toSid/")

    /**
     * Rewrite every MediaRef inside a serialized parts_json payload so media
     * files copied into the forked session's directory resolve there instead
     * of the original session's (which the user may later delete — a fork
     * must own its media or it silently loses attachments).
     *
     * Operates on the raw JSON text via [remapMediaRelativePath] applied to
     * each `"relativePath":"…"` value. The parts payload shape is stable
     * (ContentPartSerializer), and a non-matching string simply contains no
     * "/<fromSid>/" segment, so the replace is a no-op for text-only parts.
     */
    internal fun remapPartsJson(partsJson: String, fromSid: String, toSid: String): String {
        if (fromSid !in partsJson) return partsJson
        return partsJson.replace("/$fromSid/", "/$toSid/")
    }

    /**
     * The fork anchor's effective cutoff sort_order. UI rows can merge
     * multiple consecutive DB rows into one bubble (sourceDbIds carries every
     * merged row id) — the fork must include ALL of the anchor's source rows,
     * so the cutoff is the MAX sort_order across them. Callers resolve
     * maxSortOrder → anchor entity up front and pass it here.
     */
    internal fun cutoffSortOrder(anchorSortOrder: Int, mergedTailSortOrders: List<Int> = emptyList()): Int =
        (mergedTailSortOrders + anchorSortOrder).maxOrNull() ?: anchorSortOrder

    /**
     * Forked session title. Mirrors git branch naming: the parent title with
     * a "分支 · " prefix. Null/blank parent titles fall back to "分支 · 未命名会话".
     */
    internal fun forkedTitle(parentTitle: String?): String =
        "分支 · ${parentTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: "未命名会话"}"

    /**
     * Source provenance marker stored in the forked session's free-form
     * `source` column (existing values: "shortcut", "share"). Encodes both
     * the origin session and the anchor message so lineage can be surfaced
     * later without a schema migration.
     */
    internal fun forkSource(fromSid: String, anchorMessageId: String): String =
        "fork:$fromSid:$anchorMessageId"

    /**
     * Copy the media directory of [fromSid] into [toSid] inside [mediaBaseDir]
     * (filesDir/media). Walks every date dir; a missing source is a no-op
     * (text-only sessions have no media dir). Returns the number of files
     * copied. Caller is responsible for running this AFTER the DB rows are
     * committed — a media copy failure must not roll back the fork (the rows
     * reference paths; a missing file degrades to a broken thumbnail, a
     * rolled-back fork would strand the user with nothing).
     */
    internal fun copySessionMedia(mediaBaseDir: File, fromSid: String, toSid: String): Int {
        if (!mediaBaseDir.isDirectory) return 0
        var copied = 0
        mediaBaseDir.walkTopDown()
            .filter { it.isDirectory && it.name == fromSid }
            .forEach { srcDir ->
                // Rebuild the same date-path under the new session id.
                val relativeToBase = srcDir.parentFile?.toRelativeString(mediaBaseDir) ?: return@forEach
                val dstDir = File(File(mediaBaseDir, relativeToBase), toSid).apply { mkdirs() }
                srcDir.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        val dst = File(dstDir, f.name)
                        if (!dst.exists() && runCatching { f.copyTo(dst, overwrite = false) }.isSuccess) {
                            copied++
                        }
                    }
                }
            }
        return copied
    }

    /**
     * Copy the sandbox resource dir (minis-sessions/<sid>: attachments /
     * offloads / workspace / browser) of [fromSid] into [toSid]. Mirrors
     * ChatViewModel.migrateDraftResources but COPY (not move): the original
     * session keeps working against its files.
     */
    internal fun copySessionResourceDir(sessionsRoot: File, fromSid: String, toSid: String): Int {
        val src = File(sessionsRoot, fromSid)
        if (!src.isDirectory) return 0
        var copied = 0
        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            val dir = File(src, subdir)
            if (!dir.isDirectory) return@forEach
            val dst = File(File(sessionsRoot, toSid), subdir).apply { mkdirs() }
            dir.listFiles()?.forEach { child ->
                if (runCatching {
                        if (child.isDirectory) copyDirRecursive(child, File(dst, child.name))
                        else child.copyTo(File(dst, child.name), overwrite = false).isSuccess
                    }.getOrDefault(false)
                ) copied++
            }
        }
        return copied
    }

    private fun copyDirRecursive(src: File, dst: File): Boolean {
        if (src.isFile) return src.copyTo(dst, overwrite = false).isSuccess
        dst.mkdirs()
        return src.listFiles()?.all { copyDirRecursive(it, File(dst, it.name)) } ?: true
    }
}
