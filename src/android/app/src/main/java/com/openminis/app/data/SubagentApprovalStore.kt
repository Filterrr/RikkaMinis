package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-subagent-approval] Durable "always allow" decisions for sub-agent
 * dispatch — one boolean per skill id.
 *
 * Why this exists: spawning a sub-agent hands a whole autonomous tool loop
 * (shell, files, browser) to a model-chosen task with no human in the middle
 * of its turns. The spawn gate asks the user ONCE per batch; this store
 * records the "always allow for '<skill>'" answer so trusted skills stop
 * prompting. Default is OFF — an upgrade never silently auto-approves spawns;
 * approval is granted explicitly and survives chat switches and restarts.
 *
 * Scope is GLOBAL (not per-chat) on purpose: trusting `read-only-researcher`
 * is a statement about the capability, not about one conversation, and the
 * per-skill granularity matches how the runtime already namespaces sub-agent
 * config (SKILL.md `subagent: true` frontmatter).
 *
 * Shape mirrors [MemoryGlobalPrefs]: a tiny object over SharedPreferences with
 * context-per-call, so there is no init lifecycle to get wrong.
 */
object SubagentApprovalStore {

    private const val PREFS = "minis_subagent_approval_prefs"
    private const val KEY_PREFIX = "subagent.always_allow__"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when the user granted "always allow" for this skill. */
    fun isAlwaysAllowed(context: Context, skillId: String): Boolean =
        prefs(context).getBoolean(KEY_PREFIX + skillId, false)

    /** Grant or revoke "always allow" for [skillId]. */
    fun setAlwaysAllowed(context: Context, skillId: String, allowed: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFIX + skillId, allowed).apply()
    }

    /**
     * Every skill currently auto-approved. Surfaces in the approval dialog
     * ("revocable here") so a grant is never a one-way door the user cannot
     * find again.
     */
    fun alwaysAllowedSkillIds(context: Context): List<String> =
        runCatching { prefs(context).all.keys }
            .getOrDefault(emptySet())
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
            .sorted()

    /** Revoke every grant — the "reset sub-agent trust" escape hatch. */
    fun revokeAll(context: Context) {
        val keys = alwaysAllowedSkillIds(context).map { KEY_PREFIX + it }
        if (keys.isEmpty()) return
        prefs(context).edit().apply { keys.forEach { remove(it) } }.apply()
    }
}
