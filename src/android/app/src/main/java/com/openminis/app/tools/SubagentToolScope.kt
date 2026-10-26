package com.openminis.app.tools

/**
 * [feat/subagent-roster-routing] How a skill's tool permissions are shown
 * to the user BEFORE they grant them.
 *
 * The approval dialog used to print one fixed sentence for every skill —
 * "The sub-agent runs its own tool loop (shell, files, browser)" — while
 * "Always allow" granted that skill's real capability set, silently and
 * forever. A read-only auditor and an unrestricted agent produced the SAME
 * dialog.
 *
 * This renders the ACTUAL set. Three rules:
 *  - the allowlist names what IS permitted;
 *  - the blacklist names what is DENIED (a deny is the part that must be
 *    visible, because it is the part the model will hit and the user
 *    cannot otherwise predict);
 *  - no allowlist means unrestricted, and that is said EXPLICITLY — an
 *    empty line must never read as "nothing".
 */
internal object SubagentToolScope {

    /** Render the tool scope of one skill for the approval dialog. */
    fun label(
        allowedTools: Set<String>?,
        disallowedTools: Set<String> = emptySet(),
        maxTools: Int = 8,
    ): String {
        val parts = mutableListOf<String>()
        if (allowedTools != null) {
            parts += if (allowedTools.isEmpty()) {
                "no tools (this skill may not act on its own)"
            } else {
                "allowed: " + allowedTools.sorted().take(maxTools).joinToString(", ") +
                    if (allowedTools.size > maxTools) ", +${allowedTools.size - maxTools} more" else ""
            }
        } else {
            parts += "allowed: all tools"
        }
        if (disallowedTools.isNotEmpty()) {
            parts += "denied: " + disallowedTools.sorted().take(maxTools).joinToString(", ") +
                if (disallowedTools.size > maxTools) ", +${disallowedTools.size - maxTools} more" else ""
        }
        return parts.joinToString(" · ")
    }
}
