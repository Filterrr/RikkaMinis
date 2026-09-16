package com.openminis.app.provider.antigravity

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * [T-antigravity-keepalive] Authorization keep-alive — prevents Google from
 * expiring the stored refresh tokens of Antigravity OAuth credentials.
 *
 * Background: the access token lasts ~1h and rotates via the refresh token
 * on demand (validAccessToken / prewarm). But the REFRESH token itself dies
 * from disuse: Google revokes installed-app refresh tokens that go months
 * without a grant_type=refresh_token call (and Antigravity-side project
 * inactivity can compound it). A user who installs, logs in, and only opens
 * the app every few weeks would find "登录已过期" with no way back except a
 * full browser re-auth.
 *
 * This object owns three things:
 *  1. SETTINGS (encrypted prefs `antigravity_keepalive`): enabled flag +
 *     check interval in hours. User-toggled in Provider 连接页.
 *  2. SCHEDULING: one inexact repeating AlarmManager chain
 *     (setAndAllowWhileIdle — no SCHEDULE_EXACT_ALARM permission needed,
 *     survives reboot via the receiver's BOOT_COMPLETED re-arm). Alarms,
 *     unlike WorkManager, are NOT gated on the app being alive, which is
 *     exactly what a never-opened app needs.
 *  3. EXECUTION ([runKeepAliveNow]): walks every antigravity instance and
 *     every credential-pool slot and refreshes each stored token once —
 *     that single call is what marks the refresh token as "in use" for
 *     Google. Throttled per [MIN_INTERVAL_MS] so a flaky alarm cadence or
 *     a foreground re-arm can never hammer the token endpoint; transient
 *     network failures simply retry on the next tick, while a FATAL one
 *     (Google rejected the refresh token) marks that account re-login
 *     needed — surfaced in the settings UI, never auto-cleared.
 */
object AntigravityKeepAlive {

    private const val TAG = "AntigravityKeepAlive"

    const val PREFS_NAME = "antigravity_keepalive"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL_HOURS = "interval_hours"
    private const val KEY_LAST_RUN_AT = "last_run_at"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_FATAL_ACCOUNTS = "fatal_accounts" // comma-joined emails

    /** User-selectable cadences (hours). Default 6h: generous vs. Google's
     *  token-endpoint budget yet far below any plausible revocation window. */
    val INTERVAL_CHOICES_HOURS = listOf(1, 6, 12, 24, 72)
    const val DEFAULT_INTERVAL_HOURS = 6

    /** Hard throttle: a keep-alive pass may never run more than once per
     *  30 minutes regardless of how many alarm/foreground re-arms fire. */
    private const val MIN_INTERVAL_MS = 30 * 60 * 1000L

    private const val REQUEST_CODE = 0x5AB17A18 // stable PendingIntent request code

    // ── pure decision helpers (internal seams for JVM unit tests) ──────────

    /** [fix via tests] Clamp a persisted/user-supplied interval to the
     *  allow-list; anything unknown collapses to the default. */
    internal fun sanitizeInterval(hours: Int): Int =
        hours.takeIf { it in INTERVAL_CHOICES_HOURS } ?: DEFAULT_INTERVAL_HOURS

    /** Throttle predicate: true when a pass completed less than
     *  [MIN_INTERVAL_MS] before [now]. `lastRunAt == 0` (never run) is
     *  always unthrottled. */
    internal fun isThrottled(now: Long, lastRunAt: Long): Boolean =
        lastRunAt > 0 && now - lastRunAt < MIN_INTERVAL_MS

    /** Catch-up predicate for the foreground re-arm: true when the last
     *  completed pass is older than the cadence itself. Never-run (0) is
     *  NOT overdue — the first pass waits for the first scheduled tick. */
    internal fun isOverdue(now: Long, lastRunAt: Long, intervalHours: Int): Boolean =
        lastRunAt > 0 && now - lastRunAt > intervalHours * 60L * 60L * 1000L

    /** Parse the comma-joined fatal-account pref; blank entries dropped. */
    internal fun parseFatalAccounts(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** One-line pass summary for the settings UI. */
    internal fun formatSummary(ok: Int, fatal: Int, transientFail: Int): String =
        "$ok ok · $fatal fatal · $transientFail transient"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── settings ───────────────────────────────────────────────────────────

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun intervalHours(context: Context): Int =
        sanitizeInterval(prefs(context).getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS))

    /** Epoch ms of the last completed pass, or 0. */
    fun lastRunAt(context: Context): Long = prefs(context).getLong(KEY_LAST_RUN_AT, 0L)

    /** One-line outcome of the last pass ("3 ok · 0 fatal · 1 transient"), or null. */
    fun lastResult(context: Context): String? =
        prefs(context).getString(KEY_LAST_RESULT, null)?.takeIf { it.isNotEmpty() }

    /** Emails whose refresh token Google REJECTED (fatal) at the last pass. */
    fun fatalAccounts(context: Context): List<String> =
        parseFatalAccounts(prefs(context).getString(KEY_FATAL_ACCOUNTS, ""))

    // ── scheduling ─────────────────────────────────────────────────────────

    /**
     * Enable/disable from settings. Persisting + (re)scheduling are one
     * operation so callers cannot forget one half. Disabling cancels the
     * pending alarm; enabling schedules the next tick immediately.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) scheduleNext(context) else cancel(context)
        AppLogger.info(TAG, "keep-alive ${if (enabled) "enabled" else "disabled"}")
    }

    /** Change the cadence; re-arms so the new interval applies from now. */
    fun setIntervalHours(context: Context, hours: Int) {
        val h = sanitizeInterval(hours)
        prefs(context).edit().putInt(KEY_INTERVAL_HOURS, h).apply()
        if (isEnabled(context)) scheduleNext(context)
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, AntigravityKeepAliveReceiver::class.java)
            .setAction(AntigravityKeepAliveReceiver.ACTION_TICK)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Arm the next tick [intervalHours] ahead (inexact, idle-friendly). */
    fun scheduleNext(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, intervalHours(context))
        }.timeInMillis
        try {
            // setAndAllowWhileIdle: works without SCHEDULE_EXACT_ALARM; Doze
            // may delay it, which is irrelevant for a multi-hour keep-alive.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent(context))
            AppLogger.info(TAG, "next keep-alive tick in ${intervalHours(context)}h")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "scheduleNext failed: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            am.cancel(alarmIntent(context))
        } catch (e: Exception) {
            AppLogger.warning(TAG, "cancel failed: ${e.message}")
        }
    }

    // ── execution ──────────────────────────────────────────────────────────

    /**
     * One keep-alive pass: refresh every stored antigravity credential
     * (all instances × all pool slots) once. Safe from any thread; returns
     * false when throttled out (a pass completed < [MIN_INTERVAL_MS] ago)
     * or disabled. Called by the receiver and (optionally) a foreground
     * re-arm.
     */
    suspend fun runKeepAliveNow(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isEnabled(context)) return@withContext false
        val now = System.currentTimeMillis()
        val prev = lastRunAt(context)
        if (isThrottled(now, prev)) {
            AppLogger.info(TAG, "throttled (last pass ${(now - prev) / 1000}s ago)")
            return@withContext false
        }
        prefs(context).edit().putLong(KEY_LAST_RUN_AT, now).apply()

        // Reuse the app-process singleton when present (broadcast-triggered
        // cold starts have run Application.onCreate by now); the constructor
        // is cheap regardless (prefs + Room.getInstance).
        val repo = (context.applicationContext as? com.openminis.app.MinisApp)
            ?.takeIf { it.providerRepositoryReady }?.providerRepository
            ?: ProviderRepository(context)
        var ok = 0
        var transientFail = 0
        var fatal = 0
        val fatalEmails = mutableListOf<String>()

        for (instance in repo.antigravityInstances()) {
            val slots = instance.credentialCount
            for (slot in 0 until slots) {
                if (AntigravityCredentialStore.loadTokens(context, instance.id, slot) == null) {
                    continue // slot has no credential — nothing to keep alive
                }
                val result = try {
                    AntigravityCredentialStore.refreshAccessTokenOrThrow(context, instance.id, slot)
                    "ok"
                } catch (e: AntigravityCredentialStore.RefreshFailure.Fatal) {
                    fatal++
                    fatalEmails += AntigravityCredentialStore.loadEmail(context, instance.id, slot)
                        ?: "instance ${instance.id.take(8)}"
                    AppLogger.warning(TAG, "keep-alive FATAL for ${instance.id} slot $slot: ${e.message}")
                    "fatal"
                } catch (e: Exception) {
                    transientFail++
                    AppLogger.warning(TAG, "keep-alive transient for ${instance.id} slot $slot: ${e.message}")
                    "transient"
                }
                if (result == "ok") ok++
            }
        }

        val total = ok + transientFail + fatal
        if (total == 0) {
            AppLogger.info(TAG, "keep-alive: no antigravity credentials found")
            prefs(context).edit().putString(KEY_LAST_RESULT, "无凭证").apply()
        } else {
            val summary = formatSummary(ok, fatal, transientFail)
            AppLogger.info(TAG, "keep-alive pass complete: $summary")
            prefs(context).edit()
                .putString(KEY_LAST_RESULT, summary)
                .putString(KEY_FATAL_ACCOUNTS, fatalEmails.joinToString(","))
                .apply()
        }
        true
    }

    /**
     * App-start / foreground re-arm hook: when the feature is on, make sure
     * an alarm is pending (setAndAllowWhileIdle alarms do NOT survive every
     * OEM aggressive-kill, and BOOT_COMPLETED only fires on reboot). If the
     * previous pass is older than the interval — the alarm died while the
     * app was away and the due time passed unnoticed — run a catch-up pass
     * immediately ([runKeepAliveNow]'s 30-minute throttle still applies);
     * otherwise just re-arm. Cheap either way.
     */
    fun rearmIfEnabled(context: Context) {
        if (!isEnabled(context)) return
        scheduleNext(context)
        val last = lastRunAt(context)
        if (isOverdue(System.currentTimeMillis(), last, intervalHours(context))) {
            AppLogger.info(TAG, "keep-alive overdue (last pass ${(System.currentTimeMillis() - last) / 3_600_000}h ago) — catch-up pass")
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    runKeepAliveNow(context)
                } catch (e: Exception) {
                    AppLogger.warning(TAG, "catch-up pass failed: ${e.message}")
                }
            }
        }
    }
}
