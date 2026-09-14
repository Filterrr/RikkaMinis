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

        /**
         * [fix-antigravity-oauth-error-sentinel] Prefix used on the single
         * result slot when Google redirects back with an OAuth error
         * (?error=access_denied&…) instead of an authorization code. The
         * login manager MUST consume this sentinel — feeding it to
         * exchangeCodeForTokens used to surface a misleading "换取令牌失败"
         * and wasted a round-trip to the token endpoint.
         */
        const val OAUTH_ERROR_PREFIX = "oauth-error:"

        // ── [fix-antigravity-callback-hardening] resource limits ──
        // The catcher speaks to exactly one peer class (browsers following
        // Google's 302), so tiny limits are safe: request lines ≤ 16 KiB,
        // ≤ 100 header lines, 10 s read deadline. A local hostile process
        // could previously wedge the accept thread with an endless header
        // flood (unbounded readLine) — now every read is bounded.
        private const val MAX_REQUEST_LINE_BYTES = 16 * 1024
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_LINES = 100
        private const val READ_TIMEOUT_MS = 10_000
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

    /**
     * Test seam: the actually-bound port (meaningful when constructed with
     * an ephemeral port), or null when not listening.
     */
    internal fun boundPort(): Int? = serverSocket?.localPort

    private fun handleConnection(socket: Socket) {
        try {
            socket.use { s ->
                // [fix-antigravity-callback-hardening] Bound every read: a
                // slow/hostsile client can no longer hold the accept thread.
                s.soTimeout = READ_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))

                val requestLine = readBoundedLine(reader, MAX_REQUEST_LINE_BYTES)
                    ?: return  // EOF before any data — nothing to serve.
                if (requestLine.length >= MAX_REQUEST_LINE_BYTES) {
                    respond(s, "413 Payload Too Large", "<html><body><h1>Request too large</h1></body></html>")
                    return
                }

                // Drain request headers (Google sends a short GET; read to the blank line).
                var headerCount = 0
                while (true) {
                    val line = readBoundedLine(reader, MAX_HEADER_LINE_BYTES) ?: break
                    if (line.isEmpty()) break
                    headerCount++
                    if (headerCount > MAX_HEADER_LINES) {
                        respond(s, "431 Request Header Fields Too Large", "<html><body><h1>Too many headers</h1></body></html>")
                        return
                    }
                }

                var html = "<html><body><h1>Waiting for authorization…</h1></body></html>"
                var matched = false

                val parts = requestLine.split(" ")
                if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) {
                    // [fix-antigravity-callback-hardening] Malformed request
                    // line: answer 400 and never touch the result slot.
                    respond(s, "400 Bad Request", "<html><body><h1>Bad request</h1></body></html>")
                    return
                }
                if (parts[0] == "GET") {
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
                            sink(AntigravityCallbackServer.OAUTH_ERROR_PREFIX + error, state)
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

                respond(s, "200 OK", html)
                if (matched) stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection handling failed: ${e.message}")
        }
    }

    /**
     * [fix-antigravity-callback-hardening] readLine with a hard cap — the
     * stock BufferedReader.readLine buffers without limit, so a flood line
     * grows the heap and an unterminated one blocks forever. Returns the
     * line without its terminator, null on EOF-with-no-data, and a string
     * of length ≥ [maxBytes] when the cap was hit (caller decides).
     */
    private fun readBoundedLine(reader: BufferedReader, maxBytes: Int): String? {
        val sb = StringBuilder(128)
        while (true) {
            val c = reader.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length >= maxBytes) return sb.toString()
        }
    }

    private fun respond(socket: Socket, statusLine: String, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 $statusLine\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
        socket.getOutputStream().write(body)
        socket.getOutputStream().flush()
    }
}
