package com.openminis.app.tools

/**
 * [feat/subagent-roster-routing] Which model does a sub-agent run on?
 *
 * Three sources can name one, and the order matters:
 *
 *   1. the PARENT's explicit `model` argument;
 *   2. the roster entry's pinned model GROUP;
 *   3. the skill's own `model:` default.
 *
 * The parent's argument wins over everything because it is the most
 * specific instruction in play. The roster's group outranks the skill's
 * default because the roster entry is the USER's configuration for THIS
 * agent, while `model:` is the skill AUTHOR's general default — when
 * the user has said otherwise for this agent, the user's word is the
 * more specific one.
 *
 * The rule lives here, as a pure function, because it was previously
 * inline in the runner — an Android-dependent file the local JVM script
 * cannot compile. A precedence rule that can only be tested on CI is a
 * precedence rule nobody tests.
 */
internal object SubagentModelChoice {

    /**
     * The model argument the spawn should resolve.
     *
     * @param requestedModelArg the parent's explicit `model` argument.
     * @param rosterGroupId the roster entry's pinned model group, if any.
     * @param skillDefaultModel the skill's own `model:` default, if any.
     */
    fun resolve(
        requestedModelArg: String,
        rosterGroupId: String?,
        skillDefaultModel: String?,
    ): String {
        val requested = requestedModelArg.trim()
        if (requested.isNotEmpty()) return requested
        val group = rosterGroupId?.trim()
        if (!group.isNullOrEmpty()) return "group:$group"
        return skillDefaultModel?.trim().orEmpty()
    }

    /**
     * True when the parent's explicit model is OUTSIDE the pinned group.
     *
     * The group is a CONSTRAINT, not a demand: the user pinned the
     * group precisely to keep this agent on it, so a model named by the
     * parent that is not in the group is refused rather than silently
     * honoured.
     *
     * A blank request is never a violation — the group then simply
     * chooses.
     */
    fun isOutsidePinnedGroup(
        requestedModelArg: String,
        pinnedGroupId: String?,
        groupMemberIds: Collection<String>,
        groupMemberDisplayNames: Collection<String> = emptyList(),
        groupMemberEntryIds: Collection<String> = emptyList(),
    ): Boolean {
        val requested = requestedModelArg.trim()
        if (requested.isEmpty()) return false
        val group = pinnedGroupId?.trim()
        if (group.isNullOrEmpty()) return false
        if (groupMemberIds.contains(requested)) return false
        if (groupMemberEntryIds.contains(requested)) return false
        return groupMemberDisplayNames.none { it.equals(requested, ignoreCase = true) }
    }
}
