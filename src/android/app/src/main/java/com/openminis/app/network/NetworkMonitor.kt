package com.openminis.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.openminis.app.sandbox.RootfsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * Monitors network connectivity changes using ConnectivityManager.NetworkCallback.
 * Evicts the OkHttp connection pool on network transitions to prevent stale connections.
 */
class NetworkMonitor {

    enum class NetworkStatus {
        CONNECTED,
        DISCONNECTED
    }

    companion object {
        private const val TAG = "NetworkMonitor"

        /**
         * [T-android-stale-conn-retry-hang] App-wide ConnectionPool shared by
         * every long-lived LLM provider OkHttpClient (OpenAI / Anthropic /
         * Gemini) — OkHttp explicitly supports sharing one pool across
         * clients. Routing them all through this instance is what lets
         * [evictConnectionPool] actually reach provider connections:
         * previously eviction only covered the single client registered via
         * [start], and MinisApp registers none, so eviction was a no-op.
         * Through a local VPN/proxy (e.g. clash at 127.0.0.1:7890) the TCP
         * socket to localhost survives network flaps, so the pool kept
         * handing the dead h2 tunnel to every retry — requests wrote into it
         * and hung forever waiting for response headers.
         *
         * [P2-9-pool-capacity] idle 5 → 10. Concurrent subagents + the main
         * session + provider fallback each open their own h2 connection; at
         * idle=5 the newest warm connection kept evicting the one the next
         * turn needed, re-paying handshakes. 10 keeps every plausible
         * concurrent route warm without meaningful memory cost (idle
         * sockets are cheap; keep-alive unchanged at 5 min).
         */
        val sharedLLMConnectionPool = okhttp3.ConnectionPool(
            10, 5, java.util.concurrent.TimeUnit.MINUTES,
        )

        /**
         * [OPT-restore-doh] Shared DoH Dns for every LLM client, or null when
         * the feature is off / the URL is malformed. Bootstrap lookups for
         * the DoH endpoint itself are PINNED to known IPs of the well-known
         * public resolvers via [DoHBootstrap.pinResolver] — no chicken-and-egg
         * wedge, and DoH now works even on networks where plaintext DNS is
         * hijacked/blocked (the exact networks DoH exists for). Unpinned
         * (custom) DoH URLs keep the system resolver for their bootstrap.
         * Restored from the TTFB pack (593d056), DoH-only slice.
         */
        @Volatile
        var sharedDohDns: Dns? = null
            private set

        /**
         * (Re)build the shared DoH resolver from current NetworkSettings.
         * No-op when DoH is disabled or the URL is unparsable — callers then
         * keep the system DNS. Building the object is cheap (network happens
         * lazily per lookup on client threads).
         *
         * [P1-5-doh-bootstrap] Known public endpoints get a pinned bootstrap
         * resolver built from [DoHBootstrap.pinnedIps] — their IPs are stable
         * and published by the operators, so resolving them over plaintext
         * DNS first defeats the purpose of DoH on hijacked networks.
         */
        fun refreshDoh() {
            val template = NetworkSettings.dohTemplateUrl()
            sharedDohDns = if (template == null) null else try {
                val builder = okhttp3.dnsoverhttps.DnsOverHttps.Builder()
                    .client(
                        OkHttpClient.Builder()
                            // Bootstrap + DoH queries are tiny; don't let a
                            // wedged DoH server outlive the call budget.
                            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                    )
                    .url(template)
                DoHBootstrap.pinnedIps(template)?.let { builder.bootstrapDnsHosts(*it) }
                builder.build()
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /**
         * [OPT-restore-doh] Combine the shared DoH resolver (when enabled)
         * with a loopback safety net: DoH must NEVER be used to resolve
         * loopback names — a local relay referenced by hostname would be
         * leaked to the remote DoH server, which cannot answer it.
         * Everything else rides DoH; a DoH lookup failure surfaces as a
         * retryable UnknownHostException exactly like a failed system lookup.
         *
         * Reads the CURRENT resolver at lookup time (not client-build time)
         * so toggling DoH in settings applies to already-built clients on
         * their next lookup. The loopback check is STRING-based — resolving
         * via InetAddress.getByName() first would perform a system DNS
         * lookup, the exact path DoH exists to bypass; "localhost" is
         * special-cased in getByName (no network I/O).
         */
        fun buildDns(): Dns {
            return object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    val doh = sharedDohDns
                    return if (hostname == "localhost" || hostname.endsWith(".localhost")) {
                        listOf(java.net.InetAddress.getByName(hostname))
                    } else {
                        doh?.lookup(hostname) ?: Dns.SYSTEM.lookup(hostname)
                    }
                }
            }
        }
    }

    private val _status = MutableStateFlow(NetworkStatus.DISCONNECTED)
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var okHttpClient: OkHttpClient? = null
    private var appContext: Context? = null

    /**
     * Background scope for DNS-refresh side effects. Kept off the callback
     * thread so ConnectivityManager isn't held up by file I/O.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Registers a network callback to observe connectivity changes.
     * Optionally accepts a shared OkHttpClient whose connection pool will be
     * evicted on network transitions.
     *
     * @param context Application or activity context.
     * @param client Optional shared OkHttpClient for connection pool eviction.
     */
    fun start(context: Context, client: OkHttpClient? = null) {
        okHttpClient = client
        appContext = context.applicationContext
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager

        val cm = connectivityManager ?: run {
            Log.e(TAG, "ConnectivityManager not available")
            return
        }

        // Set initial state
        val activeNetwork = cm.activeNetwork
        val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        _status.value = if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            NetworkStatus.CONNECTED
        } else {
            NetworkStatus.DISCONNECTED
        }
        Log.d(TAG, "Initial network status: ${_status.value}")

        // Mirror iOS NetworkMonitor.swift:23-26 — do an immediate DNS write so
        // resolv.conf is populated before the first NetworkCallback fires. The
        // callback is async and can lag by hundreds of ms on cold start.
        refreshSandboxDns("initial")

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                val previousStatus = _status.value
                _status.value = NetworkStatus.CONNECTED
                if (previousStatus == NetworkStatus.DISCONNECTED) {
                    Log.d(TAG, "Network transition: DISCONNECTED -> CONNECTED")
                    evictConnectionPool()
                    // [OPT7-warm-reconnect] The pool was just emptied — ask
                    // ConnectionWarmer to re-arm the most recently used
                    // provider origins so the user's next send skips the
                    // cold DNS+TCP+TLS path. Fire-and-forget.
                    ConnectionWarmer.onNetworkChanged()
                }
                // Always refresh sandbox DNS on availability — an interface
                // swap (Wi-Fi → cellular) can fire onAvailable without a
                // prior onLost, and the new interface carries new DNS servers.
                refreshSandboxDns("onAvailable")
            }

            override fun onLost(network: Network) {
                _status.value = NetworkStatus.DISCONNECTED
                Log.d(TAG, "Network transition: CONNECTED -> DISCONNECTED")
                evictConnectionPool()
                // Rewrite resolv.conf even when disconnected so it falls back
                // to 8.8.8.8 / 8.8.4.4 instead of sitting stale with a DNS
                // server that's no longer reachable.
                refreshSandboxDns("onLost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                val newStatus = if (hasInternet) NetworkStatus.CONNECTED else NetworkStatus.DISCONNECTED
                if (newStatus != _status.value) {
                    Log.d(TAG, "Network capabilities changed: ${_status.value} -> $newStatus")
                    _status.value = newStatus
                    evictConnectionPool()
                    refreshSandboxDns("onCapabilitiesChanged")
                }
            }
        }

        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
        Log.d(TAG, "Network monitoring started")
    }

    /**
     * Unregisters the network callback. Should be called during cleanup.
     */
    fun stop() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
                Log.d(TAG, "Network monitoring stopped")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Callback was not registered: ${e.message}")
            }
        }
        networkCallback = null
        connectivityManager = null
        okHttpClient = null
        appContext = null
    }

    /**
     * Evicts all idle connections from the OkHttp connection pools
     * to prevent stale connection reuse after a network change.
     * Always evicts [sharedLLMConnectionPool] (all LLM provider clients),
     * plus the optional client registered via [start].
     */
    private fun evictConnectionPool() {
        sharedLLMConnectionPool.evictAll()
        okHttpClient?.connectionPool?.evictAll()
        Log.d(TAG, "OkHttp connection pools evicted (shared LLM pool + registered client)")
    }

    /**
     * Refresh the sandbox rootfs' /etc/resolv.conf from the current system
     * DNS configuration. Runs on IO so we don't block the ConnectivityManager
     * callback thread with file I/O. Safe to call before the rootfs has been
     * extracted — [RootfsManager.refreshDns] no-ops when the rootfs is missing.
     *
     * Mirrors iOS NetworkMonitor.swift:26,60 which calls refreshDns() on every
     * NWPath update so already-running shells pick up the new nameservers the
     * next time they resolve a hostname (glibc's getaddrinfo re-reads
     * resolv.conf on each lookup — no cache to invalidate).
     */
    private fun refreshSandboxDns(reason: String) {
        val ctx = appContext ?: return
        scope.launch {
            try {
                RootfsManager.getInstance(ctx).refreshDns()
                Log.d(TAG, "[DNS] sandbox resolv.conf refreshed ($reason)")
            } catch (t: Throwable) {
                Log.w(TAG, "[DNS] refresh failed ($reason): ${t.message}")
            }
        }
    }
}
