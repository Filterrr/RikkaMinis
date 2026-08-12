package com.openminis.app.macro

import android.content.Context
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File-based storage for macro recordings.
 *
 * Macros are stored as individual JSON files under
 * `<context.filesDir>/macros/<name>.json` for easy export/backup.
 */
class MacroStorage(private val context: Context) {

    companion object {
        private const val TAG = "MacroStorage"
        private const val MACRO_DIR = "macros"
        private const val INDEX_FILE = "macros/index.json"
    }

    private val macrosDir: File get() = File(context.filesDir, MACRO_DIR).also { it.mkdirs() }
    private val indexFile: File get() = File(context.filesDir, INDEX_FILE)

    /** Save a macro to disk. Overwrites if name already exists. */
    fun save(macro: MacroRecording): Boolean {
        return try {
            val file = macroFile(macro.name)
            file.writeText(macro.toJson().toString(2))
            updateIndex(macro.name)
            AppLogger.info(TAG, "saved macro '${macro.name}' (${macro.actions.size} actions)")
            true
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "failed to save macro '${macro.name}': ${t.message}")
            false
        }
    }

    /** Load a macro by name. Returns null if not found. */
    fun load(name: String): MacroRecording? {
        return try {
            val file = macroFile(name)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            MacroRecording.fromJson(json)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "failed to load macro '$name': ${t.message}")
            null
        }
    }

    /** List all saved macro names (from index, or fallback to directory scan). */
    fun listNames(): List<String> {
        val index = readIndex()
        if (index.isNotEmpty()) return index
        // Fallback: scan directory
        return macrosDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted() ?: emptyList()
    }

    /** List all macros with metadata (no full action list for performance). */
    fun list(): List<MacroInfo> {
        return listNames().mapNotNull { name ->
            load(name)?.let { macro ->
                MacroInfo(
                    name = macro.name,
                    createdAt = macro.createdAt,
                    actionCount = macro.actions.size,
                    replayCount = macro.replayCount,
                    lastReplayedAt = macro.lastReplayedAt,
                    description = macro.description,
                    packageName = macro.packageName,
                )
            }
        }.sortedByDescending { it.createdAt }
    }

    /** Delete a macro. Returns true if existed and deleted. */
    fun delete(name: String): Boolean {
        val file = macroFile(name)
        val existed = file.exists()
        if (file.delete()) {
            removeFromIndex(name)
            AppLogger.info(TAG, "deleted macro '$name'")
        }
        return existed
    }

    /** Export macro as a formatted JSON string. */
    fun export(name: String): String? {
        return load(name)?.let { it.toJson().toString(2) }
    }

    data class MacroInfo(
        val name: String,
        val createdAt: Long,
        val actionCount: Int,
        val replayCount: Int,
        val lastReplayedAt: Long?,
        val description: String?,
        val packageName: String?,
    )

    // ── internals ────────────────────────────────────────────────────────

    private fun macroFile(name: String): File {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .take(100)
        return File(macrosDir, "$safeName.json")
    }

    private fun readIndex(): List<String> {
        return try {
            if (!indexFile.exists()) return emptyList()
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Throwable) { emptyList() }
    }

    private fun updateIndex(name: String) {
        val names = readIndex().toMutableList()
        if (name !in names) names.add(name)
        writeIndex(names)
    }

    private fun removeFromIndex(name: String) {
        val names = readIndex().toMutableList()
        names.remove(name)
        writeIndex(names)
    }

    private fun writeIndex(names: List<String>) {
        try {
            indexFile.parentFile?.mkdirs()
            indexFile.writeText(JSONArray(names).toString())
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "failed to write index: ${t.message}")
        }
    }
}