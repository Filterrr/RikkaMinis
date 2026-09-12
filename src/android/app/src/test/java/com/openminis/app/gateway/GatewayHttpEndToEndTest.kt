package com.openminis.app.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * [T-local-llm-gateway] End-to-end coverage of the hand-rolled HTTP/1.1 layer:
 * a real socket against a real [GatewayAcceptor], so framing (Content-Length vs
 * chunked), keep-alive pipelining and the SSE terminator are exercised the way
 * a client SDK drives them rather than through a mocked transport.
 *
 * [MiniClient] below re-implements the framing rules independently of
 * [GatewayHttp] — reading by declared length and by chunk sizes — which is what
 * makes these tests meaningful: a server that mis-frames its own response fails
 * here even though its parser would happily accept its own writer.
 *
 * The dispatcher is stubbed; codec/routing behaviour is covered by
 * GatewayRequestCodecTest, GatewayResponseCodecTest and GatewayStreamWriterTest,
 * and the engine needs a live :modelservice worker a JVM test cannot start.
 */
class GatewayHttpEndToEndTest {

    // ── test-side client ──

    private class Response(val status: Int, val headers: Map<String, String>, val body: String)

    private class MiniClient(private val socket: Socket) {
        private val input = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()

        fun send(method: String, path: String, body: String? = null, headers: String = "") {
            val payload = (body ?: "").toByteArray(StandardCharsets.UTF_8)
            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            sb.append("Host: 127.0.0.1\r\n").append(headers)
            if (payload.isNotEmpty()) sb.append("Content-Length: ").append(payload.size).append("\r\n")
            sb.append("\r\n")
            output.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
            if (payload.isNotEmpty()) output.write(payload)
            output.flush()
        }

        fun readResponse(): Response {
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.ISO_8859_1))
            val statusLine = reader.readLine() ?: error("connection closed before a status line")
            val status = statusLine.substringAfter(' ').substringBefore(' ').toIntOrNull()
                ?: error("unparsable status line: $statusLine")
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val body = if (headers["transfer-encoding"]?.contains("chunked") == true) {
                readChunked(reader)
            } else {
                val len = headers["content-length"]?.trim()?.toIntOrNull() ?: 0
                val chars = CharArray(len)
                var got = 0
                while (got < len) {
                    val n = reader.read(chars, got, len - got)
                    if (n < 0) break
                    got += n
                }
                String(chars, 0, got)
            }
            return Response(status, headers, body)
        }

        /** Read frames until the zero-length terminator. */
        fun readSseFrames(stopMarker: String): String {
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.ISO_8859_1))
            // Drain the status line + headers first.
            while (true) {
                val line = reader.readLine() ?: return ""
                if (line.isEmpty()) break
            }
            val acc = StringBuilder()
            while (true) {
                val sizeLine = reader.readLine() ?: break
                val size = sizeLine.trim().toIntOrNull(16) ?: break
                if (size == 0) {
                    reader.readLine() // trailing CRLF
                    break
                }
                val chars = CharArray(size)
                var got = 0
                while (got < size) {
                    val n = reader.read(chars, got, size - got)
                    if (n < 0) break
                    got += n
                }
                acc.append(chars, 0, got)
                reader.readLine() // CRLF after each chunk
                if (acc.indexOf(stopMarker) >= 0) break
            }
            return acc.toString()
        }

        private fun readChunked(reader: BufferedReader): String {
            val acc = StringBuilder()
            while (true) {
                val size = (reader.readLine() ?: break).trim().toIntOrNull(16) ?: break
                if (size == 0) break
                val chars = CharArray(size)
                var got = 0
                while (got < size) {
                    val n = reader.read(chars, got, size - got)
                    if (n < 0) break
                    got += n
                }
                acc.append(chars, 0, got)
                reader.readLine()
            }
            return acc.toString()
        }
    }

    // ── harness ──

    private fun withServer(dispatcher: (GatewayHttp.Request, Socket) -> Boolean, block: (Int) -> Unit) {
        val acceptor = GatewayAcceptor(port = 0, bindLoopbackOnly = true, dispatcher = dispatcher)
        // Port 0 lets the OS pick a free one; startBlocking() binds synchronously
        // and records it in boundPort, which the poll below waits for.
        val thread = Thread { runCatching { acceptor.startBlocking() } }.apply {
            isDaemon = true
            start()
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (acceptor.boundPort == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("listener failed to bind", acceptor.boundPort != 0)
        try {
            block(acceptor.boundPort)
        } finally {
            acceptor.stop()
            thread.join(2_000)
        }
    }

    private fun connect(port: Int): Socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }

    // ── tests ──

    @Test
    fun `buffered json response round-trips over a live socket`() {
        withServer({ req, socket ->
            GatewayHttp.writeResponse(
                socket.getOutputStream(), 200,
                """{"ok":true,"path":"${req.path}"}""".toByteArray(),
                keepAlive = false,
            )
            false
        }) { port ->
            connect(port).use { s ->
                val client = MiniClient(s)
                client.send("GET", "/v1/models")
                val resp = client.readResponse()
                assertEquals(200, resp.status)
                assertEquals("application/json", resp.headers["content-type"])
                assertTrue(resp.body, resp.body.contains("\"path\":\"/v1/models\""))
            }
        }
    }

    @Test
    fun `two requests pipeline over one keep-alive connection`() {
        var hits = 0
        withServer({ req, socket ->
            hits++
            GatewayHttp.writeResponse(
                socket.getOutputStream(), 200,
                """{"n":$hits,"p":"${req.path}"}""".toByteArray(),
                keepAlive = true,
            )
            false
        }) { port ->
            connect(port).use { s ->
                val client = MiniClient(s)
                client.send("GET", "/health")
                val first = client.readResponse()
                assertEquals(200, first.status)
                assertTrue(first.body, first.body.contains("\"p\":\"/health\""))

                client.send("GET", "/api/tags")
                val second = client.readResponse()
                assertTrue(second.body, second.body.contains("\"p\":\"/api/tags\""))
                assertTrue(second.body, second.body.contains("\"n\":2"))
            }
        }
        assertEquals("both pipelined requests reached the dispatcher", 2, hits)
    }

    @Test
    fun `json body with unicode arrives intact`() {
        var seen = ""
        withServer({ req, socket ->
            seen = String(req.body, StandardCharsets.UTF_8)
            GatewayHttp.writeResponse(socket.getOutputStream(), 200, ByteArray(0), keepAlive = false)
            false
        }) { port ->
            connect(port).use { s ->
                val client = MiniClient(s)
                client.send(
                    "POST", "/v1/chat/completions",
                    """{"model":"m","messages":[{"role":"user","content":"你好 — ok"}]}""",
                )
                // Reading the reply is what proves the server consumed the whole
                // body — closing right after send() would race the accept loop.
                assertEquals(200, client.readResponse().status)
            }
        }
        assertTrue(seen, seen.contains("你好"))
        assertTrue(seen, seen.endsWith("}"))
    }

    @Test
    fun `chunked upload reassembles through the live socket`() {
        var seen = ""
        withServer({ req, socket ->
            seen = String(req.body)
            GatewayHttp.writeResponse(socket.getOutputStream(), 200, ByteArray(0), keepAlive = false)
            false
        }) { port ->
            connect(port).use { s ->
                val out = s.getOutputStream()
                out.write(
                    "POST /api/chat HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        .toByteArray(StandardCharsets.ISO_8859_1),
                )
                out.write("4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()
                assertEquals(200, MiniClient(s).readResponse().status)
            }
        }
        assertEquals("Wikipedia", seen)
    }

    @Test
    fun `sse stream declares chunked framing and ends with a zero terminator`() {
        withServer({ _, socket ->
            val out = socket.getOutputStream()
            GatewayHttp.beginStream(out)
            GatewayHttp.writeSseData(out, """{"v":1}""")
            GatewayHttp.writeSseData(out, """{"v":2}""")
            GatewayHttp.writeSseData(out, "[DONE]")
            GatewayHttp.endStream(out)
            true
        }) { port ->
            connect(port).use { s ->
                val client = MiniClient(s)
                client.send("POST", "/v1/chat/completions", "{}")
                val frames = client.readSseFrames("[DONE]")
                assertTrue(frames, frames.contains("data: {\"v\":1}"))
                assertTrue(frames, frames.contains("data: {\"v\":2}"))
                assertTrue(frames, frames.contains("data: [DONE]"))
            }
        }
    }

    @Test
    fun `ndjson stream carries its own content type`() {
        withServer({ _, socket ->
            val out = socket.getOutputStream()
            GatewayHttp.beginStream(out, "application/x-ndjson")
            GatewayHttp.writeChunk(out, "{\"done\":false}\n")
            GatewayHttp.endStream(out)
            true
        }) { port ->
            connect(port).use { s ->
                val client = MiniClient(s)
                client.send("POST", "/api/chat", "{}")
                val frames = client.readSseFrames("true")
                assertTrue(frames, frames.contains("{\"done\":false}"))
            }
        }
    }

    @Test
    fun `oversized content length answers 413 instead of hanging`() {
        withServer({ _, _ -> false }) { port ->
            connect(port).use { s ->
                val out = s.getOutputStream()
                out.write(
                    ("POST /v1/chat/completions HTTP/1.1\r\nHost: x\r\nContent-Length: " +
                        (GatewayHttp.MAX_BODY_BYTES + 1024) + "\r\nConnection: close\r\n\r\n")
                        .toByteArray(StandardCharsets.ISO_8859_1),
                )
                out.flush()
                val resp = MiniClient(s).readResponse()
                assertEquals(413, resp.status)
                assertTrue(resp.body, resp.body.contains("too large"))
            }
        }
    }

    @Test
    fun `dispatcher gateway exception becomes the matching http status`() {
        withServer({ _, _ ->
            throw GatewayException(404, "not_found_error", "Unknown gateway route", "route_not_found")
        }) { port ->
            connect(port).use { s ->
                val client = MiniClient(s)
                client.send("GET", "/nope")
                val resp = client.readResponse()
                assertEquals(404, resp.status)
                assertTrue(resp.body, resp.body.contains("not_found_error"))
                assertTrue(resp.body, resp.body.contains("route_not_found"))
            }
        }
    }

    @Test
    fun `loopback bind keeps the listener on the loopback address`() {
        var localAddress: String? = null
        withServer({ _, socket ->
            localAddress = socket.localAddress.hostAddress
            GatewayHttp.writeResponse(socket.getOutputStream(), 200, ByteArray(0), keepAlive = false)
            false
        }) { port ->
            connect(port).use { s ->
                val client = MiniClient(s)
                client.send("GET", "/health")
                client.readResponse()
            }
        }
        assertEquals("127.0.0.1", localAddress)
    }
}
