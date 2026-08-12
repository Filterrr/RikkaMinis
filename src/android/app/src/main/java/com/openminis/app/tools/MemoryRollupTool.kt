package com.openminis.app.tools

import com.openminis.app.workspace.MemoryRollupRunner
import java.io.File

/**
 * Agent tool definition and execution for `memory_rollup` — on-demand daily
 * memory distillation (T6, invoked by the agent rather than a scheduler).
 *
 * Reads the previous day's completed daily log, classifies entries into
 * stable rules (conventions / decisions / lessons), and appends them to
 * MEMORY-ROLLUP.md. The source log is never modified.
 *
 * Idempotent: a date already rolled up is silently skipped.
 */
object MemoryRollupTool {

    private const val TOOL_NAME = "memory_rollup"

    fun agentToolDefinition(): com.openminis.app.data.model.AgentToolDefinition {
        return com.openminis.app.data.model.AgentToolDefinition(
            name = TOOL_NAME,
            description = "Distill stable rules from the previous day's daily log into MEMORY-ROLLUP.md. " +
                "Reads the last completed daily log (yesterday), classifies each entry as " +
                "convention/user-decision/lesson/transient, and appends distilled entries to " +
                "the rollup file. Idempotent: a date already rolled up is skipped. " +
                "Call this when daily logs are getting large and you want to surface " +
                "reusable knowledge without re-reading raw logs.",
            parameters = emptyMap(),
            required = emptyList(),
            propertyOrdering = emptyList(),
        )
    }

    fun openAIDefinition(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", TOOL_NAME)
                put("description", agentToolDefinition().description)
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject())
                    put("required", org.json.JSONArray())
                })
            })
        }
    }

    data class ToolResult(
        val output: String,
        val success: Boolean,
        val toolTitle: String = "memory_rollup",
    )

    fun execute(memoryDir: File): ToolResult {
        return try {
            val runner = MemoryRollupRunner(memoryDir)
            val outcome = runner.runOnce()
            val (message, success) = when (outcome) {
                MemoryRollupRunner.Outcome.ROLLED_UP ->
                    "Memory rollup completed: yesterday's daily log distilled into MEMORY-ROLLUP.md" to true
                MemoryRollupRunner.Outcome.SKIPPED_ALREADY ->
                    "Memory rollup skipped: yesterday's log was already distilled (idempotent)" to true
                MemoryRollupRunner.Outcome.NO_LOG_YESTERDAY ->
                    "No daily log found for yesterday — nothing to roll up" to true
                MemoryRollupRunner.Outcome.NOTHING_TO_DISTILL ->
                    "Yesterday's log had no distillable stable rules (all entries transient)" to true
                MemoryRollupRunner.Outcome.ERROR ->
                    "Memory rollup failed due to an I/O error" to false
            }
            ToolResult(message, success)
        } catch (t: Throwable) {
            ToolResult("Memory rollup error: ${t.message}", false)
        }
    }
}