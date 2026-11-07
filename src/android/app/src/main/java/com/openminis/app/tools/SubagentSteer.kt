package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [port/upstream-subagent-steer] How a course correction reaches a running
 * sub-agent without breaking the wire.
 *
 * Upstream (OpenMinis `subagent_task` action=`steer`) delivers corrections at
 * the child's next TURN BOUNDARY, never mid-tool-call, so the work already
 * done is kept — that is the whole difference between steering and
 * cancel-then-re-delegate. RikkaMinis had only cancel, which throws that work
 * away.
 *
 * The hard part is not the queue, it is the TRANSCRIPT. In this loop a tool
 * result is itself a USER-role message, so at a turn boundary the tail is
 * normally USER. Blindly appending a new USER message therefore puts two
 * USERs in a row, which some providers reject and which this codebase has
 * already been burned by:
 *
 *  - [fix/subagent-phantom-bridge] a repair pass whose only test was
 *    "tail is USER" fired on EVERY tool-executing turn and injected a
 *    fabricated "you were interrupted by a new user message" turn — three
 *    recorded runs looped on a message that never existed;
 *  - `SubagentHistoryRepair` now bridges only a genuine USER/USER pair, which
 *    is the right repair for a TRIM boundary but the wrong thing to rely on
 *    at a turn boundary, because it inserts an ASSISTANT turn that says the
 *    model was interrupted.
 *
 * So a correction is MERGED into the existing tail when the tail is a user
 * turn (tool results + "[Course correction ...]" in one message — which is
 * also semantically what it is: one user turn containing results and a note),
 * and only appended as its own message when the tail is an assistant turn.
 * Alternation is preserved by construction, and no repair pass has to run.
 *
 * Pure + JVM-testable: no Android, no registry, no coroutines.
 */
object SubagentSteer {

    /**
     * The marker a child sees.
     *
     * Deliberately explicit that this is the DELEGATING AGENT, not the human:
     * a sub-agent that mistakes a correction for a new user request starts
     * answering the user instead of continuing the task.
     */
    const val PREFIX = "[Course correction from the delegating agent]"

    /**
     * Merge several corrections into one message.
     *
     * One message rather than N: they were all queued before the same turn
     * boundary, and sending them separately would make the model treat an
     * amendment as a second, later instruction.
     */
    fun promptText(messages: List<String>): String {
        val clean = messages.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return ""
        return buildString {
            when (clean.size) {
                1 -> append(PREFIX).append(' ').append(clean[0])
                else -> {
                    append(PREFIX).append(" (").append(clean.size)
                        .append(" corrections, in the order they were sent):")
                    clean.forEachIndexed { i, m -> append("\n").append(i + 1).append(") ").append(m) }
                }
            }
            // Tell the child what the correction does NOT change. Without this
            // a model routinely re-reads everything it has already read.
            append("\n\nKeep the work you have already done — this refines the task, ")
            append("it does not restart it.")
        }
    }

    /**
     * Deliver [messages] into [history] in place. Returns how many were
     * delivered (0 when there was nothing to say).
     *
     * The three cases below are exhaustive over the tail's role; each keeps
     * the transcript alternating.
     */
    fun deliver(history: MutableList<LLMMessage>, messages: List<String>): Int {
        val clean = messages.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return 0
        val text = promptText(clean)

        val last = history.lastOrNull()
        when {
            // Nothing said yet (a resumed/odd run): this is the first user turn.
            last == null -> history.add(
                LLMMessage(role = LLMMessage.Role.USER, content = text),
            )

            // The normal case at a turn boundary: the tail is the tool result.
            // Merge into it rather than appending a second USER message.
            last.role == LLMMessage.Role.USER -> {
                val idx = history.lastIndex
                history[idx] = if (last.contentParts.isEmpty()) {
                    val joined = listOf(last.content, text).filter { it.isNotBlank() }.joinToString("\n\n")
                    last.copy(content = joined)
                } else {
                    last.copy(contentParts = last.contentParts + AgentContentPart.Text(text))
                }
            }

            // Assistant tail (e.g. a plan-only turn): its own user message is
            // correct and keeps alternation.
            else -> history.add(
                LLMMessage(role = LLMMessage.Role.USER, content = text),
            )
        }
        return clean.size
    }
}