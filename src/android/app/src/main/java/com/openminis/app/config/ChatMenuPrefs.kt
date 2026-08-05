package com.openminis.app.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for the customizable chat actions: the top-right
 * "..." (overflow) menu AND the chat-history drawer footer.
 *
 * The user can hide / reorder the menu entries and pin / reorder the footer
 * actions from Settings → Appearance → "Chat Menu". All booleans and both
 * order strings live in the same SharedPreferences file AppearanceScreen
 * already uses (`appearance_prefs`), so:
 *  - ConfigBuiltins registers each as a config field → they are visible to
 *    minis-config AND walked into every local backup automatically.
 *  - ChatScreen reads them to filter + order the rendered menu and footer.
 *  - ChatMenuSettingsScreen writes them from the settings UI.
 *
 * The action pool has ten stable keys: the original eight "action / session"
 * menu entries plus two footer-only actions (Token Usage, Settings). Each key
 * carries TWO independent flags:
 *  - `visible` — appears in the top-right "..." menu;
 *  - `pinned`  — appears in the history-drawer footer.
 * Neither flag is derived from the other, and the two order strings
 * (`chatMenu.order` / `chatMenu.pinOrder`) are resolved independently.
 *
 * The model-conditional toggles (Enhanced Cache, Fast Mode) are intentionally
 * NOT in the pool: they already appear only when the active model supports
 * them and carry their own inline Switch, so a second "hide from settings"
 * layer would be redundant. The DEBUG crash trigger is a developer tool, also
 * excluded.
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
    const val TOKEN_USAGE = "footer_token_usage"
    const val SETTINGS = "footer_settings"

    /** Original customizable entries, in their default menu order. */
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

    /** The full action pool: menu entries first (default order), then the two
     *  footer-only actions. Both order resolvers work over this list. */
    val ALL_ENTRIES: List<String> = DEFAULT_ORDER + listOf(TOKEN_USAGE, SETTINGS)

    /** SharedPreferences key for the "..." menu display order. */
    const val ORDER_KEY = "chatMenu.order"

    /** SharedPreferences key for the footer pin order. */
    const val PIN_ORDER_KEY = "chatMenu.pinOrder"

    /** SharedPreferences key storing the persisted visibility of an entry. */
    fun visibilityKey(entryKey: String): String = "chatMenu.$entryKey.visible"

    /** SharedPreferences key storing the persisted footer-pin of an entry. */
    fun pinKey(entryKey: String): String = "chatMenu.$entryKey.pinned"

    /** Config-registry path for an entry's visibility field. */
    fun visibilityPath(entryKey: String): String = "appearance.chatMenu.$entryKey"

    /** Config-registry path for an entry's pinned field. */
    fun pinPath(entryKey: String): String = "appearance.chatMenuPin.$entryKey"

    /** Config-registry path for the menu order field. */
    const val ORDER_PATH = "appearance.chatMenuOrder"

    /** Config-registry path for the footer pin order field. */
    const val PIN_ORDER_PATH = "appearance.chatMenuPinOrder"

    /**
     * Default visibility: every original menu entry is visible; the two
     * footer-only actions are hidden from the "..." menu.
     */
    fun defaultVisible(entryKey: String): Boolean =
        entryKey != TOKEN_USAGE && entryKey != SETTINGS

    /**
     * Default pinning: Token Usage and Settings start in the footer; the
     * eight menu entries do not.
     */
    fun defaultPinned(entryKey: String): Boolean =
        entryKey == TOKEN_USAGE || entryKey == SETTINGS

    // -- Read helpers (used by ChatScreen, drawer and settings) --

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isVisible(prefs: SharedPreferences, entryKey: String): Boolean =
        prefs.getBoolean(visibilityKey(entryKey), defaultVisible(entryKey))

    fun isPinned(prefs: SharedPreferences, entryKey: String): Boolean =
        prefs.getBoolean(pinKey(entryKey), defaultPinned(entryKey))

    fun setVisible(prefs: SharedPreferences, entryKey: String, visible: Boolean) {
        prefs.edit().putBoolean(visibilityKey(entryKey), visible).apply()
    }

    fun setPinned(prefs: SharedPreferences, entryKey: String, pinned: Boolean) {
        prefs.edit().putBoolean(pinKey(entryKey), pinned).apply()
    }

    // -- Order normalization (pure; unit-testable without Android) --

    /**
     * Normalize a persisted comma-separated order against [known] entries:
     * split + trim, drop empty parts and unknown keys, keep the FIRST
     * occurrence of each key (dedupe, preserving order), then append every
     * missing known key in [known] order so a stale backup never hides a
     * brand-new entry.
     */
    fun normalizeOrder(raw: String?, known: List<String>): List<String> {
        val ordered = ArrayList<String>()
        val seen = HashSet<String>()
        raw?.split(",")?.forEach { part ->
            val key = part.trim()
            if (key.isNotEmpty() && key in known && seen.add(key)) ordered.add(key)
        }
        for (key in known) {
            if (seen.add(key)) ordered.add(key)
        }
        return ordered
    }

    /**
     * Sanitize a UI-supplied order before persisting: drop unknown keys,
     * dedupe (keep first), then append missing known keys in [known] order so
     * a write can never produce an empty / damaged order. Accepts a list that
     * does NOT contain SETTINGS (an unpinned footer is valid).
     */
    fun sanitizeForWrite(order: List<String>, known: List<String>): List<String> {
        val ordered = ArrayList<String>()
        val seen = HashSet<String>()
        for (key in order) {
            if (key in known && seen.add(key)) ordered.add(key)
        }
        for (key in known) {
            if (seen.add(key)) ordered.add(key)
        }
        return ordered
    }

    /** Resolve the persisted "..." menu order (all ten entries). */
    fun resolveOrder(prefs: SharedPreferences): List<String> =
        normalizeOrder(prefs.getString(ORDER_KEY, null), ALL_ENTRIES)

    /**
     * Resolve the persisted footer pin order over all ten entries — unpinned
     * entries are INCLUDED so the settings UI can show the full editable
     * list. Render-time filtering happens in [resolvePinnedOrder].
     */
    fun resolvePinOrder(prefs: SharedPreferences): List<String> =
        normalizeOrder(prefs.getString(PIN_ORDER_KEY, null), ALL_ENTRIES)

    /**
     * Move SETTINGS to the very end whenever it is pinned; when unpinned it is
     * filtered out and never re-injected (an unpinned footer may contain no
     * settings button at all).
     */
    fun anchorSettingsLast(order: List<String>, settingsPinned: Boolean): List<String> {
        val without = order.filterNot { it == SETTINGS }
        return if (settingsPinned) without + SETTINGS else without
    }

    /**
     * The order shown in the Chat Menu settings screen's footer section: all
     * ten entries with SETTINGS anchored last regardless of its pin state.
     * Unlike [anchorSettingsLast] (render semantics — an unpinned SETTINGS is
     * dropped), the settings list must always keep the SETTINGS row visible so
     * an unpinned settings button can always be re-pinned from the UI; a row
     * that vanishes on unpin would leave no way back.
     */
    fun settingsPinOrder(prefs: SharedPreferences): List<String> =
        resolvePinOrder(prefs).filterNot { it == SETTINGS } + SETTINGS

    /**
     * The order actually rendered in the footer: pinned entries in resolved
     * order, SETTINGS anchored last when pinned. Empty list = nothing pinned
     * (the caller then hides the footer bar and its divider entirely).
     */
    fun resolvePinnedOrder(prefs: SharedPreferences): List<String> =
        anchorSettingsLast(
            resolvePinOrder(prefs).filter { isPinned(prefs, it) },
            isPinned(prefs, SETTINGS),
        )

    // -- Persist helpers --

    /** Persist a new "..." menu order (comma-separated, sanitized). */
    fun writeOrder(prefs: SharedPreferences, order: List<String>) {
        prefs.edit()
            .putString(ORDER_KEY, sanitizeForWrite(order, ALL_ENTRIES).joinToString(","))
            .apply()
    }

    /** Persist a new footer pin order (comma-separated, sanitized). */
    fun writePinOrder(prefs: SharedPreferences, order: List<String>) {
        prefs.edit()
            .putString(PIN_ORDER_KEY, sanitizeForWrite(order, ALL_ENTRIES).joinToString(","))
            .apply()
    }
}
