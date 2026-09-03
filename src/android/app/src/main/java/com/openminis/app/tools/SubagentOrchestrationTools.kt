package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * [T-subagent-orchestration] Tool definitions for sub-agent orchestration.
 * These tools belong to the MAIN agent only — [SubagentSkill.FORBIDDEN_TOOLS]
 * and [AgentCapabilities] keep them away from sub-agents (no recursion via
 * join; a sub-agent's lifecycle is managed by its spawner).
 */
object SubagentOrchestrationTools {

    const val JOIN_NAME = "join_subagents"
    const val WAIT_ANY_NAME = "wait_any"
    const val CANCEL_NAME = "cancel_subagents"

    private fun timeoutParam(): Pair<String, AgentToolParam> = "timeout_sec" to AgentToolParam(
        "integer",
        "Optional. Max seconds to wait before giving up (default 1800 for join, 600 for wait_any). " +
            "On timeout the runs KEEP running in the background — call the tool again later.",
    )

    // ── join_subagents ───────────────────────────────────────────────────

    fun joinDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = JOIN_NAME,
        description = "Wait for detached sub-agent runs to complete and collect their results. " +
            "Used with spawn_agent run_until='detach' (spawns that return immediately and keep " +
            "running in the background). Target either specific runs via run_ids, a batch via " +
            "group_id (every spawn batch forms a group), or omit both to join the MOST RECENT " +
            "batch. Returns each run's terminal report (or a timeout note for still-running " +
            "members — safe to call again). Do NOT join runs you spawned with the default " +
            "run_until='done' — those results already arrived in the spawn tool result.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does. Use the same language as the user.",
            ),
            "run_ids" to AgentToolParam(
                "string",
                "Optional. Comma- or space-separated sub-agent run ids to join (the ids were " +
                    "returned by the detached spawn results). Takes priority over group_id.",
            ),
            "group_id" to AgentToolParam(
                "string",
                "Optional. Join every member of one spawn batch group " +
                    "(e.g. the id returned by a detached spawn).",
            ),
            timeoutParam(),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "run_ids", "group_id", "timeout_sec"),
    )

    // ── wait_any ─────────────────────────────────────────────────────────

    fun waitAnyDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = WAIT_ANY_NAME,
        description = "Race detached sub-agent runs and return as soon as the FIRST one completes " +
            "(with success_only=true: the first one that SUCCEEDS — failures are skipped and " +
            "the race keeps going). Use for competitive tasks: N agents search alternative " +
            "solutions, you take the first useful answer and cancel the losers with " +
            "cancel_subagents. Target selection identical to join_subagents (run_ids > " +
            "group_id > most recent batch). Other runs keep running while you proceed.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does. Use the same language as the user.",
            ),
            "run_ids" to AgentToolParam(
                "string",
                "Optional. Comma- or space-separated sub-agent run ids to race.",
            ),
            "group_id" to AgentToolParam(
                "string",
                "Optional. Race every member of one spawn batch group.",
            ),
            "success_only" to AgentToolParam(
                "boolean",
                "Optional. Default true — keep waiting for the first SUCCESSFUL completion and " +
                    "skip failed/cancelled members. false = resolve on the first terminal state of any kind.",
            ),
            timeoutParam(),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "run_ids", "group_id", "success_only", "timeout_sec"),
    )

    // ── cancel_subagents ─────────────────────────────────────────────────

    fun cancelDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = CANCEL_NAME,
        description = "Cancel one or more running sub-agent runs (or a whole group) and discard " +
            "them. Parent → child teardown: their model streams and tool calls stop at the next " +
            "cancellation point, partial reports are journaled, and any join_subagents / " +
            "wait_any blocked on them wakes with a 'cancelled' outcome. Use after wait_any to " +
            "reap losing racers, or when a detached spawn turns out to be unnecessary.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does. Use the same language as the user.",
            ),
            "run_ids" to AgentToolParam(
                "string",
                "Optional. Comma- or space-separated sub-agent run ids to cancel.",
            ),
            "group_id" to AgentToolParam(
                "string",
                "Optional. Cancel every member of one spawn batch group.",
            ),
            "reason" to AgentToolParam(
                "string",
                "Optional. Short human-readable reason (recorded in the run journal).",
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "run_ids", "group_id", "reason"),
    )

    /** Parse a run_ids arg ("a, b" / "a b" / JSON-ish) into a clean list. */
    fun parseRunIds(raw: String?): List<String> = raw
        ?.split(',', ' ', ';', '\n', '\t')
        ?.map { it.trim().trim('"', '\'') }
        ?.filter { it.isNotBlank() }
        .orEmpty()
}
