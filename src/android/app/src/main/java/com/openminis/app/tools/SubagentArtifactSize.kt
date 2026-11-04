package com.openminis.app.tools

/**
 * [fix/subagent-artifact-size] The size of a deliverable, measured once at the
 * moment the write succeeded.
 *
 * WHY IT IS ITS OWN FILE
 * ----------------------
 * [SubagentArtifactTruth] is deliberately PURE — no registry, no clock, no
 * filesystem — and a wiring guard counts those words in its source. Sizing a
 * file needs a stat, so putting it there would have broken a real invariant to
 * solve an unrelated problem.
 *
 * [SubagentRunRegistry] is state, not IO, and stays that way too. This is the
 * one small seam where the runtime touches the disk to answer "how big is it" —
 * and it is allowed to fail, because a deliverable whose size could not be
 * determined is still a deliverable.
 *
 * THE RULE
 * --------
 * -1 means NOT MEASURED. The renderer prints nothing for such a path. A
 * fabricated `0 B` about a file nobody stat'ed would be the same class of lie as
 * printing "cost: 0" for a provider that never reported usage.
 */
object SubagentArtifactSize {

    /** Sandbox paths are visible to the app process at the same path. */
    fun of(path: String): Int = runCatching {
        val file = java.io.File(path)
        if (!file.isFile) {
            -1
        } else {
            file.length().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
    }.getOrDefault(-1)
}
