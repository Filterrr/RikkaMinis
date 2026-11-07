package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * [port/upstream-subagent-child-session] What a sub-agent run writes into its
 * persisted child session.
 *
 * The child session is a REAL row in `sessions` with `parent_session_id` set,
 * so it is read back by the ordinary message loader and parsed by
 * `tryParsePartsJson`. That parser is the contract, and it is not negotiable:
 * a hand-built string with the wrong key (`text` instead of `value`, or a
 * bare string instead of `{type, value}`) parses to an EMPTY part list, which
 * looks exactly like "the sub-agent said nothing" instead of a bug.
 *
 * So the writer lives in one place, next to the parser's schema, and the tests
 * round-trip through the real parser rather than asserting on the string.
 *
 * Scope, stated honestly: this persists the TASK, each turn's prose, and the
 * final report. It does NOT persist the tool calls and their outputs — those
 * live in the run journal (`/var/minis/workspace/.subagent/<runId>.md`), which
 * is already bounded, redacted and indexed. Duplicating a 200 KB transcript
 * into the messages table would double the storage for a screen that shows the
 * conversation, not the evidence.
 *
 * Pure: no Android, no clock, no I/O.
 */
object SubagentChildTranscript {

    /**
     * Per-message ceiling.
     *
     * A single turn can stream a very long answer; the child session is a
     * READABLE record, not an archive. The cut is announced in the text so a
     * reader knows to open the journal for the whole thing.
     */
    const val MAX_MESSAGE_CHARS = 8_000

    /** Session titles are shown in the (hidden) list and in search. */
    const val MAX_TITLE_CHARS = 120

    /**
     * "Agent · <what it was asked to do>".
     *
     * The `Agent ·` prefix is what makes an orphaned child row identifiable if
     * something ever lists it by mistake, and it matches the title the parent's
     * own delegation card shows, so the two are recognisably the same run.
     */
    fun title(runTitle: String, skillName: String): String {
        val base = runTitle.trim().ifEmpty { skillName.trim().ifEmpty { "sub-agent run" } }
        val text = "Agent · $base"
        return if (text.length <= MAX_TITLE_CHARS) text else text.take(MAX_TITLE_CHARS - 1).trimEnd() + "…"
    }

    /** `[{"type":"text","value":"…"}]` — the schema `tryParsePartsJson` reads. */
    fun textPartsJson(text: String): String {
        val clipped = clip(text)
        val part = JSONObject()
            .put("type", "text")
            .put("value", clipped)
        return JSONArray().put(part).toString()
    }

    /**
     * The task the run was given, as the child session's opening user turn.
     *
     * Labelled, because a bare task string in a saved conversation reads as if
     * the USER wrote it — and it was the parent model that did.
     */
    fun taskPartsJson(task: String, skillName: String): String =
        textPartsJson(
            buildString {
                if (skillName.isNotBlank()) append("[").append(skillName).append("]\n\n")
                append(task)
            },
        )

    /** The terminal report, labelled with the verdict so the row is unambiguous. */
    fun reportPartsJson(report: String, verdictLabel: String): String =
        textPartsJson(
            buildString {
                append("[").append(verdictLabel).append("]\n\n")
                append(report.ifBlank { "(the run produced no report text)" })
            },
        )

    /** True when [text] had to be cut — used to decide whether to say so. */
    fun isClipped(text: String): Boolean = text.length > MAX_MESSAGE_CHARS

    private fun clip(text: String): String =
        if (text.length <= MAX_MESSAGE_CHARS) text
        else text.take(MAX_MESSAGE_CHARS).trimEnd() +
            "\n\n…(truncated at $MAX_MESSAGE_CHARS characters — the full run journal is linked above)"
}