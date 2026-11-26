package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject

/**
 * [T-apply-patch] 多文件 unified-diff 应用。
 *
 * 与 [FileEditTool] 的分工：
 *  - 改一处、只想确认"这一行对不对" → file_edit（old_string 精确匹配）；
 *  - 一次改多处 / 跨文件重构 / 从别处拿来的标准 patch → apply_patch。
 *
 * 语义是**全有或全无**：所有文件先在内存里算完，任何一个 hunk 对不上就整体
 * 失败，一个字节都不落盘。这也是它比"连续调十次 file_edit"强的地方——后者
 * 中途失败会留下半套修改，而且模型很难自己回滚。
 */
object ApplyPatchTool {
    const val NAME = "apply_patch"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Apply a unified diff (the standard `--- a/x` / `+++ b/x` / `@@ ... @@` " +
            "format) to one or more files, atomically. Use this instead of many file_edit calls " +
            "when a change touches several places or several files: one patch, one validation, and " +
            "either everything applies or nothing does. Hunk line numbers may drift — matching is " +
            "done by the context lines in the hunk, not by the numbers. Deleting files is not " +
            "supported here (use shell_execute). Pass dry_run=true to validate without writing.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary (e.g. 'Patch retry policy across 3 files').",
            ),
            "patch" to AgentToolParam(
                "string",
                "The unified diff. Multiple files allowed (one `diff --git` / `---`+`+++` block " +
                    "each). Context lines must match the files exactly.",
            ),
            "dry_run" to AgentToolParam(
                "boolean",
                "Validate and report what would change without writing anything (default: false).",
            ),
        ),
        required = listOf("tool_title", "patch"),
        propertyOrdering = listOf("tool_title", "patch", "dry_run"),
    )

    private data class Planned(
        val path: String,
        val file: java.io.File,
        val applied: PatchEngine.AppliedFile,
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val patchText = args.optString("patch", "")
            val dryRun = args.optBoolean("dry_run", false)
            val toolTitle = args.optString("tool_title", NAME)

            if (patchText.isBlank()) {
                return ToolExecutionResult("Error: 'patch' is required", false, toolTitle = toolTitle)
            }

            val filePatches = try {
                PatchEngine.parse(patchText)
            } catch (e: PatchEngine.PatchException) {
                return ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
            }

            // ── Phase 1: resolve + compute every file, in memory, no writes ──
            val planned = mutableListOf<Planned>()
            for (fp in filePatches) {
                if (PRootKernel.isLinuxPathUnderReadOnlyMount(fp.path)) {
                    return ToolExecutionResult(
                        "Error: ${fp.path} is inside a read-only mounted folder; " +
                            "toggle writability in Settings → Mount External Folders. Nothing was written.",
                        false, toolTitle = toolTitle,
                    )
                }
                val file = PRootKernel.resolveSessionHostPath(sessionId, fp.path, context)
                    ?: return ToolExecutionResult(
                        "Error: Cannot resolve path: ${fp.path}. Nothing was written.",
                        false, toolTitle = toolTitle,
                    )
                val original = when {
                    file.isFile -> runCatching { file.readText() }.getOrElse { e ->
                        return ToolExecutionResult(
                            "Error: cannot read ${fp.path}: ${e.message}. Nothing was written.",
                            false, toolTitle = toolTitle,
                        )
                    }
                    fp.isNewFile -> null
                    else -> return ToolExecutionResult(
                        "Error: file not found: ${fp.path}. Nothing was written.",
                        false, toolTitle = toolTitle,
                    )
                }
                try {
                    planned += Planned(fp.path, file, PatchEngine.applyTo(original, fp))
                } catch (e: PatchEngine.PatchException) {
                    return ToolExecutionResult(
                        "Error: ${e.message}\nNothing was written (the patch is all-or-nothing).",
                        false, toolTitle = toolTitle,
                    )
                }
            }

            // ── Phase 2: write ──
            if (dryRun) {
                val summary = planned.joinToString("\n") {
                    "  ${it.path}: +${it.applied.added} -${it.applied.removed} (dry run, not written)"
                }
                return ToolExecutionResult("Patch applies cleanly:\n$summary", true, toolTitle = toolTitle)
            }

            for (p in planned) {
                p.file.parentFile?.mkdirs()
                p.file.writeText(p.applied.content)
            }

            val totalAdded = planned.sumOf { it.applied.added }
            val totalRemoved = planned.sumOf { it.applied.removed }
            val summary = planned.joinToString("\n") {
                "  ${it.path}: +${it.applied.added} -${it.applied.removed}"
            }
            ToolExecutionResult(
                "Applied patch to ${planned.size} file(s) (+$totalAdded -$totalRemoved):\n$summary",
                true, toolTitle = toolTitle,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error applying patch: ${e.message}", false)
        }
    }
}