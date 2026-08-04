package com.openminis.app.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for the customizable chat "..." (overflow) menu.
 *
 * The user can hide entries and reorder them from Settings → Appearance →
 * "Chat Menu". Both the visibility booleans and the order string live in the
 * same SharedPreferences file the AppearanceScreen already uses
 * (`appearance_prefs`), so:
 *  - ConfigBuiltins registers each as a config field → they are visible to
 *    minis-config AND walked into every local backup automatically.
 *  - ChatScreen reads them to filter + order the rendered menu.
 *  - AppearanceScreen writes them from the settings UI.
 *
 * Only the eight "action / session" entries are customizable. The
 * model-conditional toggles (Enhanced Cache, Fast Mode) are intentionally NOT
 * here: they already appear only when the active model supports them and carry
 * their own inline Switch, so a second "hide from settings" layer would be
 * redundant. The DEBUG crash trigger is a developer tool, also excluded.
 */
object ChatMenuPrefs {
    // Same SharedPreferences file as AppearanceScreen (PREF_APPEARANCE).
    const val PREFS = "appearance_prefs"

    // -- Entry stable keys (never localized; used as pref keys and order ids) --
    const val TERMINAL = "menu_terminal"
    const val BROWSER = "menu_browser"
    const val CHAT_FILES = "menu_chat_files"
    const val EXPORT = "menu_export"
    const val SLASH_COMMANDS = "menu_slash_commands"
    const val SESSION_SKILLS = "menu_session_skills"
    const val SESSION_MCPS = "menu_session_mcps"
    const val SESSION_MEMORY = "menu_session_memory"

    /** Customizable entries, in their default display order. */
    val DEFAULT_ORDER: List<String> = listOf(
        TERMINAL,
        BROWSER,
        CHAT_FILES,
        SESSION_SKILLS,
        SESSION_MCPS,
        SESSION_MEMORY,
        SLASH_COMMANDS,
        EXPORT,
    )

    /** Every customizable entry defaults to visible: nothing is hidden until
     *  the user opts to declutter. */
    fun defaultVisible(entryKey: String): Boolean = true

    /** SharedPreferences key that stores the persisted visibility of an entry. */
    fun visibilityKey(entryKey: String): String = "chatMenu.$entryKey.visible"

    /** SharedPreferences key that stores the comma-separated display order. */
    const val ORDER_KEY = "chatMenu.order"

    /** Config-registry path for an entry's visibility field. */
    fun visibilityPath(entryKey: String): String = "appearance.chatMenu.$entryKey"

    /** Config-registry path for the order field. */
    const val ORDER_PATH = "appearance.chatMenuOrder"

    // -- Read helpers (used by ChatScreen and settings) --

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isVisible(prefs: SharedPreferences, entryKey: String): Boolean =
        prefs.getBoolean(visibilityKey(entryKey), defaultVisible(entryKey))

    /**
     * Resolve the persisted order into a clean list of known entry keys.
     * Unknown keys (e.g. removed in a future version) are dropped; entries
     * missing from the stored order (e.g. added in a future version) are
     * appended in their DEFAULT_ORDER position so a stale backup never hides
     * a brand-new entry.
     */
    fun resolveOrder(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString(ORDER_KEY, null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it in DEFAULT_ORDER }
            ?: emptyList()
        val seen = raw.toMutableSet()
        val ordered = raw.toMutableList()
        for (k in DEFAULT_ORDER) {
            if (k !in seen) {
                ordered.add(k)
                seen.add(k)
            }
        }
        return ordered
    }

    /** Persist a new order (comma-separated). */
    fun writeOrder(prefs: SharedPreferences, order: List<String>) {
        prefs.edit().putString(ORDER_KEY, order.joinToString(",")).apply()
    }

    fun setVisible(prefs: SharedPreferences, entryKey: String, visible: Boolean) {
        prefs.edit().putBoolean(visibilityKey(entryKey), visible).apply()
    }
}
