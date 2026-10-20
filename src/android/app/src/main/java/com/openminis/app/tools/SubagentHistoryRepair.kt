package com.openminis.app.tools

import com.openminis.app.data.model.LLMMessage

/**
 * [fix/subagent-phantom-bridge] Wire-alternation repair for the sub-agent's
 * OWN history.
 *
 * The bug this closes: `SubagentRunner` ended every turn with a repair pass
 * whose only test was `history.lastOrNull()?.role == USER` → insert an
 * assistant "you were interrupted by a new user message" bridge. But in this
 * loop a tool result IS a USER-role message, so after ANY turn that executed a
 * tool the tail is USER and the pass fired unconditionally. Every turn
 * therefore appended a fabricated interruption notice. Observed effect: three
 * separate sub-agent runs looping over "the user sent a new message — should I
 * keep going?", burning turns reasoning about a message that never existed,
 * and in one case ending with a plan/prose answer instead of the task's
 * findings.
 *
 * The pass was ported from the MAIN loop's
 * `ensureRoleAlternationBeforeUserAppend`, where the guard is correct: there a
 * NEW user prompt is about to be appended, so a USER tail genuinely means two
 * USERs in a row. The sub-agent loop has no such append — the tail being USER
 * (a tool result) is the normal, valid state. Copying the predicate without
 * its precondition inverted its meaning.
 *
 * Correct repair condition: two consecutive USER messages. That state only
 * arises from a trim boundary or a queued-prompt splice, never from the
 * ordinary assistant(tool_use) → user(tool_result) cycle.
 *
 * Pure + JVM-testable: no Android, no registry, no coroutines.
 */
object SubagentHistoryRepair {

    /**
     * Indices at which an assistant bridge must be inserted so the roles
     * alternate: for every adjacent pair of USER messages, the bridge goes
     * immediately before the SECOND one.
     *
     * Returned in ascending order; callers insert in REVERSE so earlier
     * indices stay valid.
     */
    fun bridgeIndices(history: List<LLMMessage>): List<Int> {
        if (history.size < 2) return emptyList()
        val out = mutableListOf<Int>()
        for (i in 1 until history.size) {
            if (history[i].role == LLMMessage.Role.USER &&
                history[i - 1].role == LLMMessage.Role.USER
            ) {
                out.add(i)
            }
        }
        return out
    }

    /** True when this history actually violates alternation. */
    fun needsBridge(history: List<LLMMessage>): Boolean = bridgeIndices(history).isNotEmpty()

    /**
     * Apply the bridges in place. Idempotent: a repaired history has no
     * consecutive USER pair left, so a second call is a no-op — that is what
     * keeps the per-turn repair from accumulating messages.
     */
    fun applyBridges(history: MutableList<LLMMessage>, bridgeText: String): Int {
        val indices = bridgeIndices(history)
        if (indices.isEmpty()) return 0
        for (i in indices.asReversed()) {
            history.add(
                i,
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = "",
                    contentParts = listOf(
                        com.openminis.app.data.model.AgentContentPart.Text(bridgeText),
                    ),
                ),
            )
        }
        return indices.size
    }
}
