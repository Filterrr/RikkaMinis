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

    /**
     * The tool scope a grant was made under.
     *
     * A grant without one was written by an older build, which did not
     * bind the scope. It is kept separate from [KEY_PREFIX] so the
     * "trusted skills" list — which enumerates [KEY_PREFIX] — does not
     * start showing scope keys as skills.
     */
    private const val SCOPE_PREFIX = "subagent.always_allow_scope__"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * True when the user granted "always allow" for this skill.
     *
     * [feat/subagent-roster-routing] The grant is now bound to the tool
     * scope that was on screen when it was made. A grant made for a
     * read-only skill does NOT survive the user adding `shell_execute` to
     * that skill's SKILL.md — the scope the user approved is not the scope
     * the agent would run with, and the whole point of showing the scope
     * in the dialog is that "always allow" means THAT.
     *
     * A grant with no recorded scope (written by an older build) is
     * treated as NOT matching: the user is asked once more, with the
     * scope on screen. Failing closed here costs one prompt; failing open
     * costs an unreviewed permission grant.
     */
    fun isAlwaysAllowed(
        context: Context,
        skillId: String,
        scopeFingerprint: String? = null,
    ): Boolean {
        if (!prefs(context).getBoolean(KEY_PREFIX + skillId, false)) return false
        if (scopeFingerprint == null) return true
        val recorded = prefs(context).getString(SCOPE_PREFIX + skillId, null)
        return recorded != null && recorded == scopeFingerprint
    }

    /**
     * Grant or revoke "always allow" for [skillId].
     *
     * [T-subagent-approval] A revoke REMOVES the key rather than writing
     * `false`. The trusted-skills list on the Skills screen enumerates keys
     * (there is no cheap per-key value read through `prefs.all`), so a
     * `false` slot would keep rendering a "Trusted" row whose runtime gate
     * actually denies — the user taps Revoke, the row stays, and the store
     * silently disagrees with the UI. Key removal makes list, runtime gate,
     * and revokeAll agree on one rule: a grant exists iff its key exists.
     */
    fun setAlwaysAllowed(
        context: Context,
        skillId: String,
        allowed: Boolean,
        scopeFingerprint: String? = null,
    ) {
        prefs(context).edit().apply {
            if (allowed) {
                putBoolean(KEY_PREFIX + skillId, true)
                if (scopeFingerprint != null) {
                    putString(SCOPE_PREFIX + skillId, scopeFingerprint)
                }
            } else {
                remove(KEY_PREFIX + skillId)
                remove(SCOPE_PREFIX + skillId)
            }
        }.apply()
    }

    /**
     * Every skill currently auto-approved. Surfaces in the approval dialog
     * ("revocable here") so a grant is never a one-way door the user cannot
     * find again.
     *
     * [T-subagent-approval] Only keys whose value is actually `true` count.
     * Legacy builds wrote `false` on revoke (leaving the key present), so a
     * key-existence filter would list grants the runtime gate already denies.
     * Filtering by value keeps the list honest with old data; with new data
     * (revocation removes the key) it is equivalent.
     */
    fun alwaysAllowedSkillIds(context: Context): List<String> =
        runCatching { prefs(context).all }
            .getOrDefault(emptyMap())
            .filterKeys { it.startsWith(KEY_PREFIX) }
            .filterValues { it == true }
            .keys
            .map { it.removePrefix(KEY_PREFIX) }
            .sorted()

    /**
     * The tool scope a grant was made under, or null if there is no
     * grant (or the grant predates scope binding).
     *
     * [feat/subagent-roster-routing] The Trusted Sub-agents screen uses
     * this to tell the truth: a grant whose scope no longer matches the
     * skill's current permissions is NOT the grant the user made, and
     * the runtime will ask again. Showing it as plainly "trusted" would
     * be a lie the settings screen tells on the runtime's behalf.
     */
    fun recordedScope(context: Context, skillId: String): String? =
        if (prefs(context).getBoolean(KEY_PREFIX + skillId, false)) {
            prefs(context).getString(SCOPE_PREFIX + skillId, null)
        } else {
            null
        }

    /** Revoke every grant — the "reset sub-agent trust" escape hatch. */
    fun revokeAll(context: Context) {
        val keys = alwaysAllowedSkillIds(context).map { KEY_PREFIX + it }
        if (keys.isEmpty()) return
        prefs(context).edit().apply {
            keys.forEach { remove(it) }
            keys.forEach { remove(SCOPE_PREFIX + it.removePrefix(KEY_PREFIX)) }
        }.apply()
    }
}
