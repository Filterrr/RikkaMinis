package com.openminis.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadBridge
import com.openminis.app.sandbox.NativeOffloadServer
import com.openminis.app.sandbox.OffloadHandlerCatalog
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.offload.AlarmOffloadHandler
import com.openminis.app.sandbox.offload.CalendarOffloadHandler
import com.openminis.app.sandbox.offload.ClipboardOffloadHandler
import com.openminis.app.sandbox.offload.ContactsOffloadHandler
import com.openminis.app.sandbox.offload.DeviceOffloadHandler
import com.openminis.app.sandbox.offload.LocationOffloadHandler
import com.openminis.app.sandbox.offload.NotificationOffloadHandler
import com.openminis.app.sandbox.offload.OpenOffloadHandler
import com.openminis.app.sandbox.offload.PhotosOffloadHandler
import com.openminis.app.sandbox.offload.PlayerOffloadHandler
import com.openminis.app.sandbox.offload.SpeakOffloadHandler
import com.openminis.app.sandbox.offload.SpeechOffloadHandler
import com.openminis.app.sandbox.offload.WeatherOffloadHandler

/**
 * Tool-execution process: the long-lived home of the native_offload socket
 * server and its handler registry ([native-oom construction plan, 做法 B]).
 *
 * This process owns:
 *   - the PRoot `native_offload` abstract socket ([NativeOffloadServer]), and
 *   - the 13 "light" android-* handlers that only need `applicationContext`
 *     (alarm / calendar / clipboard / contacts / device / location /
 *     notification / open / photos / player / speak / speech / weather) and
 *     the DEBUG-only `minis-debug` handler.
 *
 * The 6 "heavy" handlers whose dependency graph (Room / BrowserTabPool /
 * ProviderRepository / AccessibilityService / Shizuku binder) must remain in
 * the main process are registered here as bridge facades:
 * [NativeOffloadBridge.Client.forwardingHandler] — their requests are
 * forwarded to the main process over the `native-offload-bridge` abstract
 * socket, where [NativeOffloadBridge.Server] runs the real handler.
 *
 * Process role (`android:process=":toolservice"`): like `:modelservice`,
 * this process runs `MinisApp.onCreate` too, but [MinisApp.isToolService-
 * Process] skips the full app init (Room / UI / PRoot boot / offload).
 * THIS service builds the tool-specific dependency graph instead.
 *
 * Permission negotiation: ASK_ONCE checks in this process delegate to the
 * main process (whose Compose UI shows the dialog) via
 * [OffloadPermissionManager.remoteCheck], which [ToolExecutionService.onCreate]
 * wires to [NativeOffloadBridge.Client.requestPermission].
 *
 * Resilience: the main process starts this service eagerly at app onCreate
 * and restarts it if the socket is found dead, so the PRoot guest always has
 * a reachable `native_offload` socket (START_STICKY + re-arm on create).
 */
class ToolExecutionService : Service() {

    companion object {
        private const val TAG = "ToolExecutionService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ToolExecutionService onCreate pid=${android.os.Process.myPid()}")
        val context = applicationContext

        // SharedPreferences-backed permission levels live in the same uid's
        // "offload_permissions" file — the main process and this process both
        // read the same persisted BYPASS / ASK_ONCE / NOT_ALLOWED levels.
        // ASK_ONCE interaction is delegated to the main process via the
        // bridge (Compose owns the dialog).
        OffloadPermissionManager.init(context)
        OffloadPermissionManager.remoteCheck = { toolName, toolTitle, sessionId ->
            NativeOffloadBridge.Client.requestPermission(
                NativeOffloadBridge.Client.nextRequestId(),
                toolName,
                toolTitle,
                sessionId,
            )
        }

        // PRootKernel's global bind-mount table (needed by handlers that
        // resolve host paths, e.g. SpeechOffloadHandler via resolveHostPath).
        // registerGlobalBindMounts is idempotent and uses filesDir, which is
        // identical across processes of the same app/uid.
        runCatching { PRootKernel.registerGlobalBindMounts(context) }

        // Build the light-handler graph — only applicationContext, no Room /
        // BrowserTabPool / ProviderRepository / Shizuku / Accessibility.
        NativeOffloadServer.register("android-alarm", AlarmOffloadHandler(context))
        NativeOffloadServer.register("android-calendar", CalendarOffloadHandler(context))
        NativeOffloadServer.register("android-clipboard", ClipboardOffloadHandler(context))
        NativeOffloadServer.register("android-contacts", ContactsOffloadHandler(context))
        NativeOffloadServer.register("android-device", DeviceOffloadHandler(context))
        NativeOffloadServer.register("android-location", LocationOffloadHandler(context))
        NativeOffloadServer.register("android-notification", NotificationOffloadHandler(context))
        NativeOffloadServer.register("android-open", OpenOffloadHandler(context))
        NativeOffloadServer.register("android-photos", PhotosOffloadHandler(context))
        NativeOffloadServer.register("android-player", PlayerOffloadHandler())
        NativeOffloadServer.register("android-speak", SpeakOffloadHandler(context))
        NativeOffloadServer.register("android-speech", SpeechOffloadHandler(context))
        NativeOffloadServer.register("android-weather", WeatherOffloadHandler(context))

        // Heavy handlers: register bridge facades so the PRoot guest sees a
        // reachable handler for every catalog name, even though execution
        // happens in the main process.
        NativeOffloadBridge.heavyHandlerNames.forEach { name ->
            NativeOffloadServer.register(name, NativeOffloadBridge.Client.forwardingHandler(name))
        }

        // DEBUG-only handler stays in this process too (it wraps the debug
        // RPC endpoint, no main-process dependency).
        if (com.openminis.app.BuildConfig.DEBUG) {
            NativeOffloadServer.register(
                "minis-debug",
                com.openminis.app.sandbox.offload.DebugOffloadHandler(context),
            )
        }

        // Own the socket. RootfsManager.getInstance uses filesDir which is
        // shared across processes of the same uid, so rootfsDir matches what
        // the main process's PRoot shell will bind.
        NativeOffloadServer.start(RootfsManager.getInstance(context).rootfsDir)

        // [toolservice-ready] Signal the main process that the offload socket
        // is now bound: PRootKernel.boot waits on this marker before booting
        // a shell, so the first guest execve never races a not-yet-bound
        // socket.
        runCatching {
            val marker = java.io.File(context.filesDir, "toolservice_ready")
            marker.writeText(android.os.Process.myPid().toString())
        }

        Log.i(TAG, "ToolExecutionService ready: light=${NativeOffloadBridge.lightHandlerNames.size} " +
            "heavy-bridge=${NativeOffloadBridge.heavyHandlerNames.size} " +
            "handlers=${NativeOffloadServer.registeredHandlers.sorted()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Long-lived: stay running while the app needs tool execution.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "ToolExecutionService onDestroy")
        OffloadPermissionManager.remoteCheck = null
        runCatching { NativeOffloadServer.stop() }
        // Remove the readiness marker so a later re-create re-signals it.
        runCatching { java.io.File(applicationContext.filesDir, "toolservice_ready").delete() }
        super.onDestroy()
    }
}