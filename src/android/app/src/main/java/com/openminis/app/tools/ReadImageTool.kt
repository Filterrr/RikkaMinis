package com.openminis.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object ReadImageTool {
    const val NAME = "read_image"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read an image file from the Linux filesystem and return it for visual analysis. Supports PNG, JPEG, GIF, WEBP, and other common image formats. Use this to inspect generated charts, downloaded images, screenshots, or any visual output. The image is returned directly for your analysis along with metadata (dimensions, file size).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'View generated bar chart', 'Inspect downloaded screenshot'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Linux path (e.g. /var/minis/attachments/chart.png) or minis:// URL (e.g. minis://attachments/chart.png)"),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path"),
    )

    /**
     * T178: when the caller knows the owning session, prefer
     * [PRootKernel.resolveSessionHostPath] so per-session subdirs
     * (`/var/minis/{attachments,workspace,offloads,browser}/...`) resolve
     * directly against this session's host dir instead of consulting the
     * global, last-writer-wins `bindMounts` map. Without this, an agent
     * loop in session A that calls `read_image` after session B booted
     * its PRoot reads from session B's host dir — confirmed leak per
     * docs/parity/cross-session-isolation-audit.md.
     *
     * The legacy single-arg overload is preserved for callers that don't
     * know the session id (and falls back to the global map). Mirror iOS
     * `ReadImageTool` which carries `sessionId` through its tool-call
     * pipeline.
     */
    fun execute(argsJson: String, sessionId: String? = null, context: Context? = null): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val rawPath = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)

            if (rawPath.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            val path = if (rawPath.startsWith("minis://")) {
                "/var/minis/" + java.net.URLDecoder.decode(rawPath.removePrefix("minis://"), "UTF-8")
            } else rawPath

            val file = (
                if (sessionId != null && context != null) {
                    PRootKernel.resolveSessionHostPath(sessionId, path, context)
                } else null
            ) ?: PRootKernel.resolveHostPath(path)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            // [P1-oom] Two-phase decode: read the bounds first, then decode
            // with inSampleSize. A single full-resolution decode allocates
            // width×height×4 bytes up front — a 12000×9000 photo is ~432 MB
            // and OOM-kills the app before the maxEdge downscale below ever
            // runs. Sampling to ~maxEdge bounds peak memory to roughly
            // maxEdge²×4 bytes regardless of source size.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return ToolExecutionResult("Error: Cannot decode image: $path", false, toolTitle = toolTitle)
            }
            val maxEdge = 2000
            var inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (inSampleSize * 2) >= maxEdge) {
                inSampleSize *= 2
            }
            val original = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { this.inSampleSize = inSampleSize },
            ) ?: return ToolExecutionResult("Error: Cannot decode image: $path", false, toolTitle = toolTitle)
            val scaled = if (original.width > maxEdge || original.height > maxEdge) {
                val scale = maxEdge.toFloat() / maxOf(original.width, original.height)
                val w = (original.width * scale).toInt()
                val h = (original.height * scale).toInt()
                Bitmap.createScaledBitmap(original, w, h, true)
            } else {
                original
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val imageBytes = out.toByteArray()

            if (scaled !== original) scaled.recycle()
            original.recycle()

            // Report the TRUE source dimensions (from the bounds pass), not
            // the sampled decode's.
            val metadata = "[$path | ${bounds.outWidth}x${bounds.outHeight} | ${file.length()} bytes]"
            ToolExecutionResult(
                output = metadata,
                success = true,
                imageData = imageBytes,
                imageMimeType = "image/jpeg",
                toolTitle = toolTitle,
                // Surface the source file for inline preview in the tool result UI
                // (mirrors iOS ToolLiveSheet.readImageTool case).
                imageFilePath = file.absolutePath,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error reading image: ${e.message}", false)
        }
    }
}
