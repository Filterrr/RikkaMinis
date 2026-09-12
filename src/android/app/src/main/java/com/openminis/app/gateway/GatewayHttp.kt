package com.openminis.app.gateway

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.GZIPInputStream


/**
 * Minimal HTTP/1.1 front-end for the local LLM gateway.
 *
 * Deliberately hand-rolled (a `ServerSocket` accept loop + a strict-enough
 * request parser) rather than pulling a framework: the app's dependency set is
 * frozen for APK-size and CI-reproducibility reasons, and the surface a local
 * gateway needs is tiny — POST with a JSON body, chunked or fixed-length, and
 * a streaming response. This mirrors what `DebugServer` already does for the
 * JSON-RPC surface; the difference is SSE body writes and keep-alive.
 *
 * SSE contract: responses are sent with `Transfer-Encoding: chunked` so
 * standard clients (openai-python, @anthropic-ai/sdk, ollama-go, litellm)
 * parse them with their stock readers.
 */
internal object GatewayHttp {

    /** Cap on a single request body. 64 MB covers a fat agentic history with base64 images. */
    const val MAX_BODY_BYTES = 64L * 1024 * 1024

    /** Hard ceiling on request-line + headers, so a chatty client cannot grow the buffer forever. */
    private const val MAX_HEADER_BYTES = 256 * 1024

    data class Request(
        val method: String,
        /** Path with the query string stripped. */
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /**
     * Parse one HTTP request from [input]. Returns null on a clean EOF before
     * any byte (keep-alive idle close). Throws [GatewayException] on malformed
     * or oversized input so the caller can answer 400/413.
     */
    fun readRequest(input: InputStream): Request? {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        // Request line, skipping any blank lines a keep-alive peer leaves behind.
        var requestLine: String? = null
        while (true) {
            val line = readAsciiLine(buffered) ?: return null
            if (line.isBlank()) continue
            requestLine = line
            break
        }
        val parts = requestLine!!.split(" ")
        if (parts.size < 2) {
            throw GatewayException(400, "invalid_request_error", "Malformed request line")
        }
        val method = parts[0].uppercase()
        val target = parts[1]
        val qPath = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))

        val headers = LinkedHashMap<String, String>(16)
        var headerBytes = requestLine.length
        while (true) {
            val line = readAsciiLine(buffered) ?: break
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) {
                throw GatewayException(431, "invalid_request_error", "Request headers too large")
            }
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val body = readBody(buffered, headers)
        val decoded = if (headers["content-encoding"]?.lowercase()?.contains("gzip") == true && body.isNotEmpty()) {
            try { gunzip(body) } catch (_: Exception) {
                throw GatewayException(400, "invalid_request_error", "Undecodable gzip body")
            }
        } else body

        return Request(
            method = method,
            path = normalizePath(qPath),
            query = query,
            headers = headers,
            body = decoded,
        )
    }

    /** Strip a trailing slash (except root) so route matching stays exact. */
    private fun normalizePath(p: String): String {
        val unescaped = try {
            java.net.URLDecoder.decode(p, "UTF-8")
        } catch (_: Exception) { p }
        return if (unendedWithSlash(unescaped)) unescaped.dropLast(1).ifEmpty { "/" } else unescaped
    }

    private fun unendedWithSlash(s: String): Boolean = s.length > 1 && s.endsWith("/")

    private fun parseQuery(qs: String): Map<String, String> {
        if (qs.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        qs.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val k = pair.substringBefore('=', pair)
            val v = if (pair.contains('=')) pair.substringAfter('=') else ""
            out[percentDecode(k)] = percentDecode(v)
        }
        return out
    }

    private fun percentDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) { s }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val transferEncoding = headers["transfer-encoding"]?.lowercase().orEmpty()
        if (transferEncoding.contains("chunked")) return readChunked(input)
        val len = headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        if (len <= 0) return ByteArray(0)
        if (len > MAX_BODY_BYTES) {
            throw GatewayException(413, "invalid_request_error", "Request body too large")
        }
        val out = ByteArrayOutputStream(len.coerceAtMost(1L shl 20).toInt())
        val buf = ByteArray(8192)
        var remaining = len
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        val out = ByteArrayOutputStream(8192)
        var total = 0L
        while (true) {
            val sizeLine = readAsciiLine(buffered) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // Trailing headers until the blank line.
                while (true) {
                    val l = readAsciiLine(buffered) ?: break
                    if (l.isEmpty()) break
                }
                break
            }
            total += size
            if (total > MAX_BODY_BYTES) {
                throw GatewayException(413, "invalid_request_error", "Request body too large")
            }
            var remaining = size
            val buf = ByteArray(minOf(size, 8192))
            while (remaining > 0) {
                val n = buffered.read(buf, 0, minOf(remaining, buf.size))
                if (n < 0) return out.toByteArray()
                out.write(buf, 0, n)
                remaining -= n
            }
            readAsciiLine(buffered) // consume the terminating CRLF
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder(64)
        var c = input.read()
        if (c < 0) return null
        while (c >= 0) {
            if (c == 10) return sb.toString()      // '\n'
            if (c != 13) sb.append(c.toChar())     // skip '\r'
            c = input.read()
        }
        return sb.toString().ifEmpty { null }
    }

    private fun gunzip(data: ByteArray): ByteArray {
        GZIPInputStream(data.inputStream()).use { gz ->
            val out = ByteArrayOutputStream(data.size * 2)
            val buf = ByteArray(8192)
            while (true) {
                val n = gz.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    // ── Response writers ──

    /**
     * Write a complete buffered response and leave the socket open for
     * keep-alive when the client asked for it.
     */
    fun writeResponse(
        output: OutputStream,
        status: Int,
        body: ByteArray,
        contentType: String = "application/json",
        keepAlive: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        head.append("Cache-Control: no-cache\r\n")
        head.append("Access-Control-Allow-Origin: *\r\n")
        extraHeaders.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("\r\n")
        synchronized(output) {
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (body.isNotEmpty()) output.write(body)
            output.flush()
        }
    }

    /**
     * Open a chunked streaming response. [contentType] is `text/event-stream`
     * for the SSE dialects and `application/x-ndjson` for Ollama — a client
     * that gets the wrong one refuses to parse the body.
     */
    fun beginStream(output: OutputStream, contentType: String = "text/event-stream; charset=utf-8") {
        // Transfer-Encoding must be declared: frames are written with
        // writeChunk(), and without this header a client keeps reading past the
        // terminator (or hangs waiting for a body that never ends).
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n" +
            "X-Accel-Buffering: no\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n"
        synchronized(output) {
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
    }

    /** One `data:` SSE frame (multi-line `data` supported via embedded newlines). */
    fun writeSseData(output: OutputStream, data: String, event: String? = null) {
        val sb = StringBuilder(data.length + 32)
        if (event != null) sb.append("event: ").append(event).append("\r\n")
        // SSE forbids a bare newline inside a data field; each line becomes its own data:.
        for (line in data.split('\n')) sb.append("data: ").append(line).append("\n")
        sb.append("\n")
        writeRaw(output, sb.toString())
    }

    /** Write one `Transfer-Encoding: chunked` frame (used for both SSE and raw NDJSON). */
    fun writeChunk(output: OutputStream, payload: ByteArray) {
        if (payload.isEmpty()) return
        synchronized(output) {
            output.write(Integer.toHexString(payload.size).toByteArray(Charsets.ISO_8859_1))
            output.write(CRLF)
            output.write(payload)
            output.write(CRLF)
            output.flush()
        }
    }

    fun writeChunk(output: OutputStream, payload: String) =
        writeChunk(output, payload.toByteArray(Charsets.UTF_8))

    private fun writeRaw(output: OutputStream, s: String) {
        // SSE frames ride inside chunked framing so content-length-less clients
        // (and proxies) can tell where each flush ends.
        writeChunk(output, s.toByteArray(Charsets.UTF_8))
    }

    fun endStream(output: OutputStream) {
        synchronized(output) {
            output.write(ZERO_CHUNK_CRLF)
            output.write(CRLF)
            output.flush()
        }
    }

    private val CRLF = byteArrayOf(13, 10)
    private val ZERO_CHUNK_CRLF = "0".toByteArray(Charsets.ISO_8859_1)

    fun writeJsonError(
        output: OutputStream,
        ex: GatewayException,
        requestId: String? = null,
        protocol: GatewayProtocol? = null,
    ) {
        val body = GatewayErrors.body(ex, requestId, protocol)
        writeResponse(output, ex.status, body.toByteArray(Charsets.UTF_8))
    }

    /** Handle OPTIONS preflight for browser-based clients pointed at the phone. */
    fun writeCorsPreflight(output: OutputStream) {
        val body =
            "HTTP/1.1 204 No Content\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Authorization, Content-Type, X-API-Key, X-Goog-Api-Key, X-Goog-User-Project, HTTP-Referer, X-Title\r\n" +
            "Access-Control-Max-Age: 86400\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        synchronized(output) {
            output.write(body.toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "OK"
    }

    /** True when the peer should get a keep-alive connection. */
    fun keepAliveWanted(req: Request): Boolean {
        val conn = req.header("connection").orEmpty().lowercase()
        return !conn.contains("close")
    }
}

/**
 * Server socket wrapper owning accept + dispatch. One instance per enabled
 * gateway; started/stopped from [MinisGatewayServer].
 */
internal class GatewayAcceptor(
    private val port: Int,
    private val bindLoopbackOnly: Boolean,
    private val dispatcher: (GatewayHttp.Request, Socket) -> Boolean,
) {
    @Volatile private var socket: ServerSocket? = null

    @Volatile private var running = false

    /** Actual bound port (differs from [port] when 0 was requested). */
    @Volatile var boundPort: Int = port
        private set

    /**
     * Bind synchronously so a caller learns "port already in use" on the calling
     * thread instead of discovering a dead listener thread later. Android apps
     * cannot show a stack trace from a background thread usefully.
     */
    fun bind() {
        if (socket != null) return
        val srv = if (bindLoopbackOnly) {
            ServerSocket(port, 16, java.net.InetAddress.getByName("127.0.0.1"))
        } else {
            ServerSocket(port, 16)
        }
        socket = srv
        boundPort = srv.localPort
        running = true
    }

    fun startBlocking() {
        val srv = socket ?: run {
            bind()
            socket!!
        }
        while (running) {
            val client = try {
                srv.accept()
            } catch (_: Exception) {
                break
            }
            Thread { serve(client) }.apply { isDaemon = true; name = "minis-gw-conn" }.start()
        }
        runCatching { srv.close() }
        socket = null
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    /**
     * Drive one connection. [dispatcher] returns true when it wrote a
     * streaming response and the connection must close (chunked streams end
     * at socket close so SSE clients see EOF promptly).
     */
    private fun serve(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = 0 // generation streams can be slow to first token; per-op timeouts are the worker's job
                val input = s.getInputStream()
                val output = s.getOutputStream()
                while (running && !s.isClosed) {
                    val req = try {
                        GatewayHttp.readRequest(input)
                    } catch (ex: GatewayException) {
                        GatewayHttp.writeJsonError(output, ex)
                        break
                    } ?: break
                    val streamed = try {
                        dispatcher(req, s)
                    } catch (ex: GatewayException) {
                        GatewayHttp.writeJsonError(output, ex)
                        false
                    } catch (t: Throwable) {
                        GatewayHttp.writeJsonError(
                            output,
                            GatewayException(500, "api_error", t.message ?: "gateway failure"),
                        )
                        false
                    }
                    if (streamed) break
                    if (!GatewayHttp.keepAliveWanted(req)) break
                }
            } catch (_: Exception) {
                // Peer reset mid-request: drop quietly, the next request is unaffected.
            }
        }
    }
}
