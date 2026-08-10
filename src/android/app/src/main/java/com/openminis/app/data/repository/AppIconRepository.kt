package com.openminis.app.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log

/**
 * T-android-dynamic-app-icon: Manage the launcher-icon alias the system
 * resolves for MAIN/LAUNCHER. Mirrors iOS
 * `UIApplication.setAlternateIconName` — the user picks an icon variant
 * in Settings → Appearance → App Icon, and we toggle the corresponding
 * `<activity-alias>` enabled/disabled via PackageManager.
 *
 * Wire:
 *   - All three aliases target MainActivity (declared in AndroidManifest).
 *   - At any time exactly ONE alias is enabled. Selecting a new variant
 *     enables that alias and disables the others.
 *   - DONT_KILL_APP keeps the current Activity alive across the toggle;
 *     the launcher may still take a few seconds to refresh its icon
 *     cache (this is a launcher-side caching detail we don't control).
 *
 * The current selection is also persisted to SharedPreferences so the
 * Appearance screen can render the correct checkmark before reading
 * back the PackageManager state (which can lag right after a toggle).
 *
 * Why we split enable/disable across foreground/background:
 *   Disabling the activity-alias under which the CURRENT task was launched
 *   makes ActivityTaskManager finish the whole task even with DONT_KILL_APP
 *   (DONT_KILL_APP only spares the *process*). Toggling the icon while the
 *   user is inside the app therefore crashed them back to the launcher.
 *   So an icon change is a two-phase op:
 *     1. foreground — enable the new alias only (safe, never kills a task)
 *     2. background — once the app is fully stopped (user left), disable
 *        the other aliases. The task is invisible by then, the launcher
 *        receives PACKAGE_CHANGED and re-renders the icon.
 *   [MinisApp.onActivityStopped] is the hook that drives phase 2.
 *
 * Auto-follow:
 *   - "Auto" alone does NOT reliably follow the system dark mode on
 *     MIUI/HyperOS: the launcher renders the adaptive-icon resource once
 *     and never re-resolves the `-night` qualifier when the system theme
 *     flips. The manual ClassicLight/ClassicDark variants DO update,
 *     because toggling the activity-alias forces the launcher to reload.
 *   - So under "Auto" we actively mirror the effective theme onto the
 *     ClassicLight/ClassicDark alias (syncWithSystemTheme). The persisted
 *     selection stays "auto" — the alias is just the launcher-visible
 *     projection of it.
 */
object AppIconRepository {
    private const val TAG = "AppIconRepository"
    private const val PREFS = "app_icon_prefs"
    private const val KEY_SELECTED_ID = "selected_icon_id"
    private const val PACKAGE_NAME = "com.openminis.app"

    enum class Variant(val id: String, val aliasClass: String) {
        Auto("auto", "$PACKAGE_NAME.MainActivityIconAuto"),
        ClassicLight("classic_light", "$PACKAGE_NAME.MainActivityIconLight"),
        ClassicDark("classic_dark", "$PACKAGE_NAME.MainActivityIconDark"),
        ;

        companion object {
            fun fromId(id: String?): Variant = entries.firstOrNull { it.id == id } ?: Auto
        }
    }

    /**
     * Set while an alias swap is pending background cleanup. Atomically
     * visible across the border between [apply]/[syncWithSystemTheme]
     * (any thread) and [flushPendingCleanup] (main thread via
     * ActivityLifecycleCallbacks).
     */
    @Volatile
    private var pendingCleanup: Boolean = false

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(context: Context): Variant =
        Variant.fromId(prefs(context).getString(KEY_SELECTED_ID, Variant.Auto.id))

    /**
     * What the launcher-visible alias *should* be right now, given the
     * persisted selection and the effective (system) night mode. For
     * [Variant.Auto] this resolves to the Classic variant matching the
     * current system uiMode — the projection that makes the icon actually
     * follow dark mode on launchers that never re-resolve `-night`.
     */
    private fun effectiveAlias(context: Context): Variant {
        val selected = current(context)
        if (selected != Variant.Auto) return selected
        val night = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Variant.ClassicDark else Variant.ClassicLight
    }

    /**
     * Apply [target] as the active launcher icon. Persists the selection
     * and enables [target] immediately; the other aliases are disabled
     * later, once the app is fully in the background (see class KDoc for
     * why — disabling the current task's alias tears the task down).
     * No-op when [target] is already the persisted current.
     */
    fun apply(context: Context, target: Variant): Boolean {
        val ctx = context.applicationContext
        val current = current(ctx)
        if (current == target) return false
        val pm = ctx.packageManager
        try {
            setEnabled(pm, ctx, target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            prefs(ctx).edit().putString(KEY_SELECTED_ID, target.id).apply()
            pendingCleanup = true
            Log.i(TAG, "icon switched ${current.id} → ${target.id}")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "icon switch failed: ${t.message}", t)
            return false
        }
    }

    /**
     * Auto-mode projection: when the persisted selection is [Variant.Auto],
     * mirror the effective theme (dark/light) onto the Classic aliases so
     * the launcher icon actually follows the system theme even on launchers
     * that never re-resolve `-night` icons. Does NOT touch the persisted
     * selection. [effectiveDark] is the theme the caller believes is active
     * (in-app forced theme or system uiMode — the caller decides which is
     * authoritative). No-op when the user pinned a Classic variant.
     *
     * [appForeground] splits the behaviour: while an Activity is visible we
     * only enable the new alias (disabling the running task's alias would
     * tear the task down = the "app exits when I switch the icon" bug);
     * once the app has fully left the foreground the caller should invoke
     * [flushPendingCleanup] (or pass appForeground=false here, which does
     * the full cleanup inline — safe because no task is visible).
     */
    fun syncWithSystemTheme(context: Context, effectiveDark: Boolean, appForeground: Boolean = true) {
        val ctx = context.applicationContext
        if (current(ctx) != Variant.Auto) return
        val target = if (effectiveDark) Variant.ClassicDark else Variant.ClassicLight
        val pm = ctx.packageManager
        try {
            setEnabled(pm, ctx, target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            if (appForeground) {
                pendingCleanup = true
            } else {
                completeIconState(pm, ctx, target)
            }
            Log.i(TAG, "auto icon synced → ${target.id} (effectiveDark=$effectiveDark, fg=$appForeground)")
        } catch (t: Throwable) {
            Log.w(TAG, "auto icon sync failed: ${t.message}", t)
        }
    }

    /**
     * Phase 2 of an icon change. Called when the app has fully left the
     * foreground ([MinisApp] ActivityLifecycleCallbacks, stopped count → 0).
     * At this point the current task is invisible, so disabling the other
     * aliases cannot bounce the user out of anything. Resolves the target
     * from the persisted selection (Auto → current system uiMode) and
     * disables every alias except that one. The launcher refreshes on the
     * resulting PACKAGE_CHANGED.
     */
    fun flushPendingCleanup(context: Context) {
        val ctx = context.applicationContext
        if (!pendingCleanup) return
        pendingCleanup = false
        val target = effectiveAlias(ctx)
        val pm = ctx.packageManager
        try {
            completeIconState(pm, ctx, target)
            Log.i(TAG, "icon cleanup flushed → only ${target.id} enabled")
        } catch (t: Throwable) {
            Log.w(TAG, "icon cleanup failed: ${t.message}", t)
        }
    }

    private fun setEnabled(
        pm: PackageManager,
        ctx: Context,
        variant: Variant,
        state: Int,
    ) {
        val component = ComponentName(ctx, variant.aliasClass)
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }

    /**
     * One-pass full icon state: enable [target], disable every other
     * alias. Only safe when no Activity is visible — disabling the alias
     * the current task was launched under finishes the task even with
     * DONT_KILL_APP. Used by [flushPendingCleanup] (background) and by
     * [syncWithSystemTheme] when the app is confirmed backgrounded.
     */
    private fun completeIconState(pm: PackageManager, ctx: Context, target: Variant) {
        setEnabled(pm, ctx, target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        for (variant in Variant.entries) {
            if (variant != target) {
                setEnabled(pm, ctx, variant, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            }
        }
    }
}