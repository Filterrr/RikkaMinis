package com.openminis.app.provider.antigravity

import android.util.Log
import com.openminis.app.logging.AppLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

/**
 * Loopback OAuth callback catcher for the Antigravity flow.
 *
 * Google redirects to `http://localhost:51121/oauth-callback?code=…&state=…`
 * after the user consents. Any browser may be used (Chrome Custom Tab,
 * the in-app browser, or an external browser) — the server only needs the
 * HTTP request to reach this device's loopback interface.
 *
 * The success page mirrors CLIProxyAPI's management UI copy
 * ("Login successful — you can close this page").
 */
class AntigravityCallbackServer(private val port: Int) {

    companion object {
        private const val TAG = "AntigravityCallback"
    }

    /**
     * Invoked exactly once with the authorization code when Google (or the
     * consent page's error redirect) lands on the loopback URL. Set BEFORE
     * [start]; guarded against races via [sunk].
     */
    @Volatile var onResult: ((code: String, state: String?) -> Unit)? = null

    /** Reserved for future keepalive signaling; unused by the current flow. */
    @Volatile var onTimeoutTick: (() -> Unit)? = null

    private var serverSocket: ServerSocket? = null

    @Volatile private var running = false
    @Volatile private var sunk = false

    private val sink: (code: String, state: String?) -> Unit = { code, state ->
        if (!sunk) {
            sunk = true
            onResult?.invoke(code, state)
        }
    }

    /** Binds the loopback port and starts the accept loop. False when the port is taken. */
    fun start(): Boolean {
        running = true
        return try {
            serverSocket = ServerSocket(port, 0, InetAddress.getLoopbackAddress())
            AppLogger.info(TAG, "listening on localhost:$port")
            Thread {
                try {
                    while (running) {
                        val socket = serverSocket?.accept() ?: break
                        handleConnection(socket)
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "callback server error", e)
                }
            }.apply { isDaemon = true }.start()
            true
        } catch (e: Exception) {
            Log.w(TAG, "bind failed on port $port: ${e.message}")
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                // Drain request headers (Google sends a short GET; read to the blank line).
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                var html = "<html><body><h1>Waiting for authorization…</h1></body></html>"
                var matched = false

                val parts = requestLine.split(" ")
                if (parts.size >= 2 && parts[0] == "GET") {
                    val uri = URI("http://localhost${parts[1]}")
                    val params = uri.query?.split("&")?.associate {
                        val kv = it.split("=", limit = 2)
                        kv[0] to (if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else "")
                    } ?: emptyMap()

                    val error = params["error"]
                    val code = params["code"]
                    val state = params["state"]

                    when {
                        error != null -> {
                            AppLogger.warning(TAG, "OAuth error from Google: $error")
                            html = "<html><body><h1>Authorization failed</h1><p>$error</p>" +
                                "<p>You can close this page and retry in the app.</p></body></html>"
                            // Surface the failure through the same single slot so
                            // the waiter doesn't hang out the full 5 minutes.
                            sink("oauth-error:$error", state)
                        }
                        code != null -> {
                            matched = true
                            html = "<html><body><h1>Login successful</h1>" +
                                "<p>You can close this page and return to the app.</p></body></html>"
                            sink(code, state)
                        }
                        else -> {
                            html = "<html><body><h1>Missing authorization code</h1></body></html>"
                        }
                    }
                }

                val body = html.toByteArray(Charsets.UTF_8)
                val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                s.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                s.getOutputStream().write(body)
                s.getOutputStream().flush()
                if (matched) stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection handling failed: ${e.message}")
        }
    }
}
