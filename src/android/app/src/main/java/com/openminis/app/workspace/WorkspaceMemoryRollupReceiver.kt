package com.openminis.app.workspace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Broadcast receiver for the daily memory rollup.
 *
 * Two roles:
 * - [Intent.ACTION_BOOT_COMPLETED] / [Intent.ACTION_MY_PACKAGE_REPLACED]:
 *   the OS clears alarms on reboot; this re-establishes the daily schedule.
 * - [WorkspaceMemoryRollupScheduler.ACTION_TRIGGER]: run the rollup for
 *   yesterday's log, then chain-schedule the next day.
 *
 * The rollup itself is fast local file I/O + regex (milliseconds), well
 * inside the goAsync() 10s window.
 */
class WorkspaceMemoryRollupReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MemoryRollupReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                // Re-establish the daily alarm after reboot / update.
                runCatching {
                    WorkspaceMemoryRollupScheduler(context).scheduleDaily()
                }.onFailure {
                    AppLogger.warning(TAG, "reschedule after ${intent.action} failed: ${it.message}")
                }
            }

            WorkspaceMemoryRollupScheduler.ACTION_TRIGGER -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val scheduler = WorkspaceMemoryRollupScheduler(context)
                        val memoryDir =
                            File(context.filesDir, "minis-global/memory")
                        val outcome = MemoryRollupRunner(memoryDir).runOnce()
                        AppLogger.info(TAG, "rollup outcome: $outcome")
                        // Chain the next day's alarm (also re-checks exact
                        // permission, so granting it later upgrades silently).
                        scheduler.scheduleDaily()
                    } catch (t: Throwable) {
                        AppLogger.error(TAG, "rollup trigger failed: ${t.message}")
                        // Even on failure, keep the chain alive so the next
                        // day still gets a chance.
                        runCatching { WorkspaceMemoryRollupScheduler(context).scheduleDaily() }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}