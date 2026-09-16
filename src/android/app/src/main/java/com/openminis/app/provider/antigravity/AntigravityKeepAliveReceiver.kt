package com.openminis.app.provider.antigravity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [T-antigravity-keepalive] AlarmManager entry point for the Antigravity
 * authorization keep-alive. Two triggers:
 *
 *  - ACTION_TICK: the periodic alarm fired. Runs one keep-alive pass
 *    (goes through [AntigravityKeepAlive.runKeepAliveNow], which refreshes
 *    every stored credential once) and re-arms the next tick.
 *  - BOOT_COMPLETED: the device rebooted and the (non-repeating)
 *    setAndAllowWhileIdle alarm was lost. Re-arm if the user's setting is
 *    still on — no immediate pass (the network stack may not be up yet);
 *    the first tick is one interval away.
 *
 * goAsync(): the pass is a coroutine on a dedicated scope, so the broadcast
 * receiver registration is held until it completes — Doze-window wakes have
 * a ~10s grace and the whole pass is a handful of HTTPS round-trips.
 */
class AntigravityKeepAliveReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AntigravityKeepAlive"
        const val ACTION_TICK = "com.openminis.app.action.ANTIGRAVITY_KEEPALIVE_TICK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_TICK -> {
                if (!AntigravityKeepAlive.isEnabled(appContext)) {
                    AppLogger.info(TAG, "tick fired but feature disabled; not rescheduling")
                    return
                }
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        AntigravityKeepAlive.runKeepAliveNow(appContext)
                    } catch (e: Exception) {
                        AppLogger.warning(TAG, "keep-alive pass crashed: ${e.message}")
                    } finally {
                        // Arm the next tick BEFORE finishing the broadcast so
                        // a process death between pass and re-arm can't
                        // strand the chain until the next app start.
                        try {
                            AntigravityKeepAlive.scheduleNext(appContext)
                        } catch (e: Exception) {
                            AppLogger.warning(TAG, "re-arm failed: ${e.message}")
                        }
                        pending.finish()
                    }
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                // Reboot wiped the one-shot alarm; re-arm (and only re-arm)
                // while the user's setting is on. runKeepAliveNow is skipped:
                // at boot the network may still be coming up, and the whole
                // point is a multi-hour cadence, not immediacy.
                AntigravityKeepAlive.rearmIfEnabled(appContext)
            }
        }
    }
}
