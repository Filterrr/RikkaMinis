package com.openminis.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [feat3-lan-discovery] Discover LAN-hosted model servers (Ollama / LM
 * Studio / llama.cpp / vLLM / …) so "add provider" can fill the base URL
 * from a tap instead of the user reading an IP off a terminal.
 *
 * Two discovery channels, merged:
 *   1. NSD/mDNS browse for `_ollama._tcp`, `_http._tcp`, `_lmstudio._tcp`,
 *      `_vllm._tcp` — the advertised path. Ollama ≥0.1.29 registers
 *      `_ollama._tcp`; LM Studio and vLLM register variants; generic HTTP
 *      servers show up under `_http._tcp` (noisy, so flagged lower-confidence
 *      and matched against the well-known ports below).
 *   2. Port sweep of the current /24 for the well-known ports
 *      (11434 Ollama, 1234 LM Studio, 8080 llama.cpp/vLLM default, 8000
 *      vLLM alt) — the fallback that still finds silent servers. Only TCP
 *      connect probes (50ms budget each); no payloads are sent. The sweep
 *      runs AFTER the NSD window so advertised answers are already in and
 *      the sweep result never overwrites a confident mDNS hit.
 *
 * `.local` names never resolve through normal DNS (also not through the
 * sandbox resolv.conf) — the discovery result therefore ALWAYS carries a
 * resolved numeric IP, never a hostname, so the discovered base URL works
 * verbatim in both the app layer and the sandbox layer.
 */
object LanModelDiscovery {

    private const val TAG = "LanDiscovery"

    private val NSD_TYPES = listOf(
        "_ollama._tcp.",
        "_lmstudio._tcp.",
        "_vllm._tcp.",
        "_http._tcp.",
    )

    private val COMMON_PORTS = listOf(11434, 1234, 8080, 8000)

    /** One discovered candidate. [source] is "mdns" or "port-scan". */
    data class Candidate(
        val baseUrl: String,
        val name: String,
        val port: Int,
        val source: String,
    )

    /**
     * Browse + sweep with a hard [timeoutMs] budget. Never throws; an
     * NSD-less device (rare) degrades to port-scan only.
     */
    suspend fun discover(context: Context, timeoutMs: Long = 4_000L): List<Candidate> =
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, Candidate>() // key = ip:port
            val latch = CountDownLatch(1)

            // ── Channel 1: NSD browse ────────────────────────────────
            val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            var listeners = listOf<Pair<NsdManager, NsdManager.DiscoveryListener>>()
            if (nsd != null) {
                listeners = NSD_TYPES.mapNotNull { type -> registerBrowse(nsd, type, found) }
            }

            // Give mDNS answers a short head start, then sweep; NSD keeps
            // resolving in parallel during the sweep.
            if (listeners.isNotEmpty()) latch.await(1_200, TimeUnit.MILLISECONDS)

            // ── Channel 2: /24 port sweep on the current network ─────
            val localAddr = currentWifiAddress()
            if (localAddr != null) {
                sweepSubnet(localAddr, found)
            }

            if (listeners.isNotEmpty()) {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                listeners.forEach { (mgr, l) ->
                    try { mgr.stopServiceDiscovery(l) } catch (_: Exception) {}
                }
            }

            found.values.sortedWith(
                compareBy({ it.source != "mdns" }, { it.port }, { it.baseUrl }),
            )
        }

    /** Register one browse; returns null when registration fails. */
    private fun registerBrowse(
        nsd: NsdManager,
        type: String,
        found: LinkedHashMap<String, Candidate>,
    ): Pair<NsdManager, NsdManager.DiscoveryListener>? {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            val port = info.port
                            if (port <= 0 || port > 65535) return
                            val key = "$host:$port"
                            synchronized(found) {
                                found[key] = Candidate(
                                    baseUrl = "http://$host:$port",
                                    name = serviceInfo.serviceName ?: key,
                                    port = port,
                                    source = "mdns",
                                )
                            }
                        }
                    })
                } catch (t: Throwable) {
                    Log.d(TAG, "resolve failed: ${t.message}")
                }
            }
        }
        return try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            nsd to listener
        } catch (t: Throwable) {
            Log.d(TAG, "discoverServices($type) failed: ${t.message}")
            null
        }
    }

    /** Best-effort local IPv4 on the active network (null when absent). */
    private fun currentWifiAddress(): InetAddress? = try {
        val socket = Socket()
        socket.use {
            it.connect(InetSocketAddress("8.8.8.8", 53), 1_000)
            it.localAddress
        }
    } catch (_: Throwable) {
        null
    }

    /** TCP connect sweep of the /24 the given address lives on. */
    private fun sweepSubnet(local: InetAddress, found: LinkedHashMap<String, Candidate>) {
        val ip = local.hostAddress ?: return
        val prefix = ip.substringBeforeLast('.')
        val me = ip.substringAfterLast('.').toIntOrNull() ?: return
        for (last in 1..254) {
            if (last == me) continue
            val host = "$prefix.$last"
            for (port in COMMON_PORTS) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), 50)
                        val key = "$host:$port"
                        synchronized(found) {
                            if (!found.containsKey(key)) {
                                found[key] = Candidate(
                                    baseUrl = "http://$host:$port",
                                    name = guessName(port),
                                    port = port,
                                    source = "port-scan",
                                )
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // closed port / filtered — the expected case, skip
                }
            }
        }
    }

    private fun guessName(port: Int): String = when (port) {
        11434 -> "Ollama?"
        1234 -> "LM Studio?"
        8080 -> "OpenAI-compatible server?"
        8000 -> "vLLM?"
        else -> "HTTP server"
    }
}
