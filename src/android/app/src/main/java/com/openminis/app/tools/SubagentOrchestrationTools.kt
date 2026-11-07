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

    /**
     * [port/upstream-subagent-status] Read-only snapshot of the conversation's
     * sub-agent runs. Third sibling of join / wait_any / cancel: the parent
     * could act on runs but could not SEE them without blocking on one.
     */
    const val STATUS_NAME = "subagent_status"

    /**
     * [port/upstream-subagent-steer] Course correction for a running run.
     * The one mid-run interaction RikkaMinis was missing: before this, the
     * only way to influence a run in flight was to cancel it and lose the
     * work.
     */
    const val STEER_NAME = "steer_subagent"

    private fun timeoutParam(defaultSec: Int, toolLabel: String): Pair<String, AgentToolParam> = "timeout_sec" to AgentToolParam(
        "integer",
        "Optional. Max seconds to wait before giving up (default $defaultSec for $toolLabel). " +
            "On timeout the runs KEEP running in the background — call the tool again later " +
            "to collect stragglers. Prefer several short waits over one long one: a large value " +
            "parks this turn (and the user's view of it) until the wait ends.",
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
            timeoutParam(defaultSec = 600, toolLabel = "join_subagents"),
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
            timeoutParam(defaultSec = 300, toolLabel = "wait_any"),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "run_ids", "group_id", "success_only", "timeout_sec"),
    )

    // ── cancel_subagents ─────────────────────────────────────────────────

    fun cancelDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = CANCEL_NAME,
        description = "Cancel one or more running DETACHED sub-agent runs (or a whole group) and " +
            "discard them. Parent → child teardown: their model streams and tool calls stop at " +
            "the next cancellation point, partial reports are journaled, and any join_subagents / " +
            "wait_any blocked on them wakes with a 'cancelled' outcome. Use after wait_any to " +
            "reap losing racers, or when a detached spawn turns out to be unnecessary. " +
            "INLINE runs (spawned without run_until='detach') cannot be cancelled this way — they " +
            "execute inside the turn that spawned them, so stopping the turn is what ends them " +
            "early; this tool reports those ids separately instead of pretending to kill them.",
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
                "Optional. Cancel every detached member of one spawn batch group.",
            ),
            "reason" to AgentToolParam(
                "string",
                "Optional. Short human-readable reason (recorded in the run journal).",
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "run_ids", "group_id", "reason"),
    )

    // ── subagent_status ──────────────────────────────────────────────────

    fun statusDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = STATUS_NAME,
        description = "Report what this conversation's sub-agents are doing — WITHOUT waiting. " +
            "Every run's state (running / queued / finished, with the runtime's verdict), turn, " +
            "elapsed time, the tool call in flight, the model that is paying for it, token usage, " +
            "and — for finished runs — a short preview of the result. " +
            "Read-only and non-blocking: it never waits for anything to finish and never changes a " +
            "run. Use it to decide what to do next: which run to join, which to cancel, whether a " +
            "run that looks active has actually already failed. " +
            "Do NOT use it as a poll loop while waiting for a result — a spawn with " +
            "run_until='done' returns its own result, and join_subagents / wait_any are the " +
            "correct way to wait; status is for a snapshot, not for spinning. " +
            "Do NOT assume a run is healthy because it is still listed: check its state line, and " +
            "remember that a SUCCESS-state run can carry a DEGRADED/PARTIAL verdict.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does. Use the same language as the user.",
            ),
            "run_ids" to AgentToolParam(
                "string",
                "Optional. Comma- or space-separated run ids to report on. Omit to report every " +
                    "run of this conversation (still-running first, then the newest finished ones).",
            ),
            "group_id" to AgentToolParam(
                "string",
                "Optional. Report only the members of one spawn batch group.",
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "run_ids", "group_id"),
    )

    // ── steer_subagent ───────────────────────────────────────────────────

    fun steerDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = STEER_NAME,
        description = "Send a course correction to a RUNNING sub-agent — without stopping it. " +
            "The message is queued and read at the sub-agent's next turn boundary: a tool call " +
            "already in progress is not interrupted, and all the work done so far is kept. " +
            "This is the alternative to cancel + re-spawn when the task is right but a detail is " +
            "wrong (a path, a constraint, a format, a scope to drop). " +
            "It does NOT restart the task and does NOT create a new run. " +
            "Only a run that is still RUNNING or QUEUED can be steered — a finished run's result " +
            "stands, and the call says so instead of pretending. Steer applies to DETACHED runs " +
            "(spawn_agent run_until='detach'): an inline run executes inside the call that " +
            "spawned it, so you cannot issue another tool call while it runs. " +
            "Check state with subagent_status first if you are unsure the run is still alive.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does. Use the same language as the user.",
            ),
            "run_id" to AgentToolParam(
                "string",
                "The run to correct. A unique prefix of the id is accepted (the ids shown by " +
                    "subagent_status are 8-character prefixes).",
            ),
            "message" to AgentToolParam(
                "string",
                "The correction, phrased as an instruction to the running sub-agent. Say what to " +
                    "CHANGE, not the whole task again: the sub-agent keeps everything it has done.",
            ),
        ),
        required = listOf("tool_title", "run_id", "message"),
        propertyOrdering = listOf("tool_title", "run_id", "message"),
    )

    /** Parse a run_ids arg ("a, b" / "a b" / JSON-ish) into a clean list. */
    fun parseRunIds(raw: String?): List<String> = raw
        ?.split(',', ' ', ';', '\n', '\t')
        ?.map { it.trim().trim('"', '\'') }
        ?.filter { it.isNotBlank() }
        .orEmpty()
}
