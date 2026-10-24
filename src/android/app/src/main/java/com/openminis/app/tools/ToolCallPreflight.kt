package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import org.json.JSONObject

/**
 * [fix/subagent-tool-preflight] Shared tool-call preflight validation.
 *
 * The main agent has always validated a tool call BEFORE executing it
 * (`ChatViewModel.preflightValidateToolCallImpl`): empty args, missing
 * required fields, and `{"path": ""}` are rejected up front, and the model
 * gets a clear reason so it can self-correct on the next turn.
 *
 * The sub-agent runner had NO such check. A sub-agent that emitted
 * `file_read {}` got no error at all — the tool itself reported
 * "'path' is required" only AFTER the call was dispatched, and for tools
 * like `shell_execute` a malformed call meant a real (possibly partial)
 * execution before anything was noticed. That wastes a whole turn of a
 * scarce budget on a call that could never work.
 *
 * This object is the SINGLE implementation of the rule, shared by both
 * agents. It lives in the `tools` package (not ChatViewModel's companion)
 * because it is pure — no Android dependencies — so the sub-agent runner
 * can call it and so it can be unit-tested without constructing a
 * ViewModel.
 *
 * The behaviour is deliberately IDENTICAL to the main agent's: same
 * non-blocking field list, same empty-string allowances, same messages.
 */
object ToolCallPreflight {

    /**
     * Fields that must NOT block a call when absent: they carry no
     * execution semantics (they label the UI pill), so rejecting the whole
     * call over a missing one is pure downside.
     */
    private val NON_BLOCKING_FIELDS = setOf("tool_title")

    /**
     * (tool name → field names) where an EMPTY STRING is a semantically
     * valid value and must not be treated as "missing".
     *
     * The canonical case is `file_edit.new_string`, whose schema documents
     * "Use empty string to delete old_string". Blocking it broke a promised
     * deletion workflow and pushed the model into shell_execute + python
     * file-rewrite workarounds. Mirrors iOS
     * AIChatViewModel.preflightEmptyStringAllowedFields.
     */
    private val EMPTY_STRING_ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
        "file_edit" to setOf("new_string"),
    )

    /** True when "" is a legal value for this exact (tool, field) pair. */
    fun emptyStringAllowed(tool: String, field: String): Boolean =
        EMPTY_STRING_ALLOWED_FIELDS[tool]?.contains(field) == true

    /**
     * Reject tool calls that have empty args or are missing required fields
     * BEFORE execution. Returns null when the call is well-formed, or a
     * human-readable reason string when it should be blocked.
     *
     * Driven off the canonical [AgentToolDefinition.required] list so the
     * validator never drifts from the schema published to the model. We do
     * NOT validate type beyond string-emptiness here; richer schema checks
     * (enum, regex, integer range) belong in each tool's own helper because
     * they need tool-specific context.
     *
     * Unknown tool names return null: the dispatcher's own "Unknown tool"
     * branch already reports them, and preflight stays silent so the error
     * is not duplicated.
     */
    fun validate(
        name: String,
        args: JSONObject,
        tools: List<AgentToolDefinition>,
    ): String? {
        val toolDef = tools.firstOrNull { it.name == name } ?: return null
        val enforced = toolDef.required.filter { it !in NON_BLOCKING_FIELDS }
        // Empty args on a tool that requires anything → block. Gate on
        // `enforced` so a tool whose only required field is non-blocking
        // isn't rejected for empty args, and the message lists only real
        // blockers.
        if (args.length() == 0 && enforced.isNotEmpty()) {
            return "Tool '$name' was called with empty arguments {} but requires: ${enforced.joinToString(", ")}."
        }
        val missing = mutableListOf<String>()
        for (field in enforced) {
            // Absent — or present as an explicit JSON null. org.json reports
            // has() == true for `{"x": null}` and opt() hands back
            // JSONObject.NULL, which is not a String, so a null previously
            // slipped through BOTH checks and reached the tool as a non-String
            // value. Both spellings are genuinely missing.
            if (!args.has(field) || args.isNull(field)) {
                missing.add(field)
                continue
            }
            val raw = args.opt(field)
            // Only the truly-empty literal "" is rejected — NOT whitespace.
            // The earlier `.trim().isEmpty()` over-rejected legitimate
            // payloads, most notably file_edit with `new_string: "\n"`
            // (replace a block with a newline) or `old_string: "  "` (match
            // consecutive spaces). Both are valid edits, neither is stream
            // corruption.
            //
            // And even "" is legal for whitelisted (tool, field) pairs.
            if (raw is String && raw.isEmpty() && !emptyStringAllowed(name, field)) {
                missing.add(field)
            }
        }
        if (missing.isNotEmpty()) {
            return "Tool '$name' is missing required parameter(s): ${missing.joinToString(", ")}."
        }
        return null
    }
}
