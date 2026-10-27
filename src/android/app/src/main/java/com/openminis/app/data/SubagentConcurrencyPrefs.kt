package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [feat/subagent-roster-routing] P2-4 — the persisted chat-global cap on
 * concurrent sub-agent runs.
 *
 * The cap used to be a constant (4), so a user on a slow device — or one
 * paying per token — had no way to stop four sub-agents from running at
 * once. It can now be LOWERED. It can never be raised past the built-in
 * ceiling: that ceiling is the bound the scheduler (and the memory
 * machinery behind it) was sized for, and a preference that could exceed
 * it would be a foot-gun rather than a setting.
 *
 * Mirrors [ConcurrencyPrefs] exactly: [prime] captures the app context once
 * at startup and warms a cache; the context-free [globalLimit] is then safe
 * to call from any layer. The value is read at prime time and held until
 * the next process start — the scheduler's semaphores are sized at init and
 * are deliberately not resized at runtime (see the scheduler's own note).
 */
object SubagentConcurrencyPrefs {
    const val PREFS = "minis_subagent_concurrency_prefs"
    const val KEY_GLOBAL_LIMIT = "globalLimit"

    /** Floor: one run at a time is still a valid setting. */
    const val MIN = 1

    /** The hard ceiling — the scheduler's designed maximum. */
    const val MAX = 4

    /** Default matches the previous hard-coded value. */
    const val DEFAULT = 4

    @Volatile
    private var cachedGlobalLimit: Int = DEFAULT

    @Volatile
    private var primed = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the app context and warm the cache. Call from MinisApp.onCreate. */
    fun prime(context: Context) {
        cachedGlobalLimit = clamp(prefs(context).getInt(KEY_GLOBAL_LIMIT, DEFAULT))
        primed = true
    }

    /** Context-free read for the chat layer. */
    fun globalLimit(): Int = cachedGlobalLimit

    /**
     * Persist a new cap and warm the cache. Takes effect on the next
     * process start (the scheduler's semaphores are sized at init).
     */
    fun setGlobalLimit(context: Context, value: Int) {
        val v = clamp(value)
        cachedGlobalLimit = v
        prefs(context).edit().putInt(KEY_GLOBAL_LIMIT, v).apply()
    }

    /** Exposed for tests: whether [prime] has run in this process. */
    fun isPrimed(): Boolean = primed

    private fun clamp(v: Int): Int = v.coerceIn(MIN, MAX)
}
