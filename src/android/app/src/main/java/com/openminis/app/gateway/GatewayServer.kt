package com.openminis.app.gateway

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


/**
 * [T-local-llm-gateway] Process-wide lifecycle owner for the local LLM gateway.
 *
 * Singleton because the listener is a port, not a per-screen resource: Settings
 * toggles it, the app may restart, and anything that needs the bound port (the
 * Settings status row, the sandbox env injection) reads it back from here.
 * [com.openminis.app.debug.DebugServer] follows the same pattern.
 *
 * Start is idempotent and stop is safe to call when not running. A bind failure
 * (port taken by another app) is reported, never thrown: the app must not crash
 * because a stale ollama listener squatting the port is still alive on the host
 * side.
 */
object GatewayServer {

    private const val TAG = "MinisGateway"

    @Volatile private var acceptor: GatewayAcceptor? = null

    @Volatile private var router: GatewayRouter? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Last start failure, for the Settings UI to surface. Null while healthy. */
    @Volatile var lastError: String? = null
        private set

    val running: Boolean get() = acceptor != null

    /** Actual bound port, or the configured one when stopped. */
    val boundPort: Int get() = acceptor?.boundPort ?: GatewaySettings.port

    /**
     * Start the listener if [GatewaySettings.enabled] says so, applying the
     * current port / bind scope. No-op when already running on the same config.
     */
    @Synchronized
    fun syncFromSettings(context: Context, repository: ProviderRepository) {
        val wanted = GatewaySettings.enabled
        val port = GatewaySettings.port
        val lan = GatewaySettings.bindLan
        if (!wanted) {
            stop()
            return
        }
        val current = acceptor
        if (current != null && current.boundPort == port && GatewaySettings.bindLan == lan) {
            return // already serving the requested config
        }
        stop()
        startInternal(context, repository, port, lan)
    }

    @Synchronized
    private fun startInternal(context: Context, repository: ProviderRepository, port: Int, lan: Boolean) {
        val newRouter = GatewayRouter(context.applicationContext, repository)
        val newAcceptor = GatewayAcceptor(
            port = port,
            // Loopback-only by default: with bindLan off, a random app on the
            // same wifi cannot reach the phone's model budget.
            bindLoopbackOnly = !lan,
            dispatcher = { req, socket -> newRouter.dispatch(req, socket) },
        )
        // Bind on this thread: a port conflict must be visible to the caller
        // (the Settings UI shows lastError), not buried in a dead daemon thread.
        // The probe-then-rebind window is negligible here — nothing else on the
        // device races for the port except itself.
        try {
            newAcceptor.bind()
        } catch (t: Throwable) {
            lastError = t.message ?: "cannot listen on port $port"
            Log.w(TAG, "bind failed on :$port - $lastError")
            newRouter.shutdown()
            return
        }
        acceptor = newAcceptor
        router = newRouter
        lastError = null
        scope.launch {
            try {
                newAcceptor.startBlocking()
            } catch (t: Throwable) {
                Log.w(TAG, "gateway accept loop died: ${t.message}")
                lastError = t.message
            } finally {
                if (acceptor === newAcceptor) {
                    acceptor = null
                    router?.shutdown()
                    router = null
                }
            }
        }
        Log.i(TAG, "gateway listening on ${if (lan) "0.0.0.0" else "127.0.0.1"}:$port (auth=${if (GatewaySettings.currentToken().isBlank()) "off" else "on"})")
    }

    @Synchronized
    fun stop() {
        val a = acceptor ?: return
        a.stop()
        acceptor = null
        router?.shutdown()
        router = null
        Log.i(TAG, "gateway stopped")
    }

    /**
     * LAN address to advertise, from the active wifi interface. Falls back to
     * the loopback host: a device on mobile data has no peer-reachable address,
     * and showing a fake one would advertise a link that cannot connect.
     */
    fun preferredHost(context: Context): String {
        if (!GatewaySettings.bindLan) return "127.0.0.1"
        return lanAddress() ?: "127.0.0.1"
    }

    /** Best-effort station IP (192.168.x.x / 10.x) from the network interfaces. */
    fun lanAddress(): String? = try {
        val candidates = java.net.NetworkInterface.getNetworkInterfaces()
            .asSequence().flatMap { it.inetAddresses.asSequence() }.toList()
            .filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        // Prefer the private ranges a phone actually gets on wifi.
        fun pick(prefix: String) = candidates.firstOrNull { it.hostAddress?.startsWith(prefix) == true }
        (pick("192.168.") ?: pick("10.") ?: pick("172.") ?: candidates.firstOrNull())?.hostAddress
    } catch (_: Throwable) {
        null
    }


    /** Status snapshot for the Settings UI. */
    data class Status(
        val running: Boolean,
        val port: Int,
        val host: String,
        val lanReachable: Boolean,
        val error: String?,
    )

    fun status(context: Context): Status = Status(
        running = running,
        port = boundPort,
        host = preferredHost(context),
        lanReachable = GatewaySettings.bindLan && lanAddress() != null,
        error = if (running) null else lastError,
    )
}
