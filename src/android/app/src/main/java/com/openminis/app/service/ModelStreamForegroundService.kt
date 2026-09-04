package com.openminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R

/**
 * [OPT3-fgs-modelservice] Foreground wrapper that runs INSIDE the
 * `:modelservice` process while an LLM generation stream is in flight.
 *
 * Why: `:modelservice` is a regular (non-foreground) started service in its
 * own process. On a long streaming generation (tens of minutes of steady SSE
 * output) the OS may kill the cached background process under memory
 * pressure or OEM background limits — the worker dies mid-stream, the client
 * classifies death via the liveness beat, and the turn restarts from zero.
 * Promoting to foreground (FOREGROUND_SERVICE_TYPE_DATA_SYNC) while
 * `ChatStreamOffloadHandler.activeStreams > 0` keeps the process out of the
 * cached bucket for the duration of the stream; it is demoted as soon as the
 * last stream ends, so idle workers remain fully killable (the whole point
 * of the short-lived-worker containment model).
 *
 * Notification policy: LOW importance, no sound, ongoing — a silent
 * "Generating reply…" row. The channel is created on first start; the
 * notification is updated with the live stream count.
 *
 * Threading/lifecycle: startForeground is called synchronously in
 * [onStartCommand] (Android 12+ FGS launch restrictions do not apply — this
 * service is only ever started from its own app, which will be in the
 * foreground during a chat turn). stopForeground+stopSelf happens when the
 * count drains to zero.
 */
class ModelStreamForegroundService : Service() {

    companion object {
        private const val TAG = "ModelStreamFGS"
        private const val CHANNEL_ID = "model_stream_status"
        private const val NOTIFICATION_ID = 9003

        /** Mirrors [ChatStreamOffloadHandler.activeStreams] semantics. */
        private var streamCount = 0

        private var isRunning = false

        /**
         * Called by ChatStreamOffloadHandler around every offloaded stream.
         * Idempotent: the service is only started on the 0→1 edge, and
         * only stopped when the count drains back to 0.
         */
        @Synchronized
        fun onStreamStarted(context: Context) {
            streamCount += 1
            if (!isRunning) {
                isRunning = true
                val intent = Intent(context, ModelStreamForegroundService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, intent)
                    } else {
                        context.startService(intent)
                    }
                } catch (e: Exception) {
                    // FES start can legitimately throw on some OEMs (background
                    // start restrictions) — degrade to non-foreground worker
                    // rather than failing the stream.
                    isRunning = false
                    Log.w(TAG, "FGS start failed (degraded to background worker): ${e.message}")
                }
            }
        }

        /** @return true when the service was stopped by THIS call. */
        @Synchronized
        fun onStreamEnded(context: Context): Boolean {
            streamCount = (streamCount - 1).coerceAtLeast(0)
            if (streamCount == 0 && isRunning) {
                isRunning = false
                try {
                    context.stopService(Intent(context, ModelStreamForegroundService::class.java))
                } catch (_: Exception) {}
                return true
            }
            return false
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // Same rationale as AgentForegroundService: Doze can suspend worker
        // threads mid-stream after screen-off on aggressive OEM ROMs. Held for
        // the service lifetime only.
        wakeLock = (getSystemService(POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Minis:ModelStreamFgs")
            ?.apply { setReferenceCounted(false); acquire(30 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // NOTE: deliberately NO "count drained → stop immediately" check here.
        // streamCount lives in the CALLER's process (main); this service runs
        // in :modelservice, where the companion copy is always 0 — a drain
        // check here would stop the FGS the instant it started. The stop path
        // is owned by onStreamEnded() (main process, stopService). A very
        // short stream may briefly show the row then stop it — acceptable.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent().also { it.`package` = packageName },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.model_stream_fgs_generating))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.model_stream_fgs_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        nm.createNotificationChannel(channel)
    }
}
