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

    /**
     * A stable fingerprint of one skill's tool permissions.
     *
     * [feat/subagent-roster-routing] "Always allow" used to be recorded
     * per skill ID alone. But the scope it grants is not a property of
     * the skill — it is a property of the skill's CURRENT `allowed_tools`
     * and `disallowed_tools`, which the user can change at any time by
     * editing or re-importing the SKILL.md.
     *
     * So a grant outlived the thing the user actually approved. Approve a
     * read-only auditor, then give it `shell_execute`, and the next spawn
     * ran with `shell_execute` — silently, forever, under a grant the user
     * had never been shown. That is a privilege escalation wearing the
     * costume of a convenience.
     *
     * The fingerprint makes the grant mean what the dialog said: the
     * grant is valid only while the permissions hash to the same value.
     * Change the scope and the grant no longer matches, so the user is
     * asked again — with the NEW scope in the dialog.
     *
     * The hash is over the sets themselves, not the rendered label, so a
     * change in wording or in how many names are displayed does not
     * silently invalidate a grant. An unrestricted skill (no allowlist)
     * is distinguishable from an allowlist that happens to list every
     * tool, because they are different capabilities.
     */
    fun fingerprint(
        allowedTools: Set<String>?,
        disallowedTools: Set<String> = emptySet(),
    ): String {
        val allowPart = if (allowedTools == null) {
            "*"
        } else {
            allowedTools.sorted().joinToString(",")
        }
        val denyPart = disallowedTools.sorted().joinToString(",")
        return "$allowPart|deny:$denyPart"
    }

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
