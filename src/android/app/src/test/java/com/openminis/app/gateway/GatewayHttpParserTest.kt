package com.openminis.app.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * [T-local-llm-gateway] HTTP/1.1 front-end parsing contracts.
 *
 * The request parser is hand-rolled (no HTTP library in the dependency set), so
 * everything a real client can send has to be covered here: fixed-length,
 * chunked, gzip-encoded bodies, keep-alive leftovers, and the oversize guards
 * that stop a hostile peer from growing the buffer without bound.
 */
class GatewayHttpParserTest {

    private fun stream(text: String, extra: ByteArray = ByteArray(0)): ByteArrayInputStream {
        val head = text.toByteArray(Charsets.ISO_8859_1)
        return ByteArrayInputStream(head + extra)
    }

    private fun requestOf(raw: String, body: ByteArray = ByteArray(0)): GatewayHttp.Request? =
        GatewayHttp.readRequest(stream(raw, body))

    @Test
    fun `fixed length json body is read exactly`() {
        val payload = """{"model":"m","messages":[]}""".toByteArray()
        val raw = "POST /v1/chat/completions HTTP/1.1\r\n" +
            "Host: x\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\n\r\n"
        val req = requestOf(raw, payload)!!
        assertEquals("POST", req.method)
        assertEquals("/v1/chat/completions", req.path)
        assertEquals(String(payload), String(req.body, Charsets.UTF_8))
    }

    @Test
    fun `headers are lowercased and case insensitive to read`() {
        val raw = "GET /v1/models HTTP/1.1\r\nAuthorization: Bearer abc\r\nX-Api-Key: k1\r\n\r\n"
        val req = requestOf(raw)!!
        assertEquals("Bearer abc", req.header("authorization"))
        assertEquals("k1", req.header("X-API-KEY"))
        assertNull(req.header("missing"))
    }

    @Test
    fun `query parameters are percent decoded`() {
        val req = requestOf("GET /v1beta/models/gpt%2D4o:generateContent?alt=sse&key=a%20b HTTP/1.1\r\n\r\n")!!
        assertEquals("/v1beta/models/gpt-4o:generateContent", req.path)
        assertEquals("sse", req.query["alt"])
        assertEquals("a b", req.query["key"])
    }

    @Test
    fun `trailing slash is normalised but the root is preserved`() {
        assertEquals("/v1/messages", requestOf("POST /v1/messages/ HTTP/1.1\r\n\r\n")!!.path)
        assertEquals("/", requestOf("GET / HTTP/1.1\r\n\r\n")!!.path)
    }

    @Test
    fun `lf only line endings parse`() {
        val raw = "POST /api/chat HTTP/1.1\nContent-Length: 2\n\n{}"
        val req = requestOf(raw)!!
        assertEquals("/api/chat", req.path)
    }

    @Test
    fun `chunked body reassembles across extensions and trailers`() {
        val raw = "POST /v1/chat/completions HTTP/1.1\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n"
        val chunks = "5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\nX-Trail: yes\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val req = GatewayHttp.readRequest(stream(raw, chunks))!!
        assertEquals("hello world", String(req.body, Charsets.UTF_8))
    }

    @Test
    fun `gzip encoded body is inflated before handing it over`() {
        val plain = """{"model":"m"}"""
        val gz = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(plain.toByteArray()) }
        }.toByteArray()
        val raw = "POST /v1/messages HTTP/1.1\r\nContent-Encoding: gzip\r\nContent-Length: ${gz.size}\r\n\r\n"
        val req = requestOf(raw, gz)!!
        assertEquals(plain, String(req.body, Charsets.UTF_8))
    }

    @Test
    fun `keep-alive leftovers before a request line are skipped`() {
        val raw = "\r\nGET /health HTTP/1.1\r\n\r\n"
        assertEquals("/health", requestOf(raw)!!.path)
    }

    @Test
    fun `clean eof before any byte returns null for a connection close`() {
        assertNull(GatewayHttp.readRequest(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `body shorter than content length yields what arrived, not a hang`() {
        val raw = "POST /api/generate HTTP/1.1\r\nContent-Length: 50\r\n\r\nshort"
        val req = requestOf(raw)!!
        assertEquals("short", String(req.body))
    }

    @Test
    fun `oversized body is refused with 413 before allocation`() {
        val raw = "POST /v1/chat/completions HTTP/1.1\r\nContent-Length: ${GatewayHttp.MAX_BODY_BYTES + 1}\r\n\r\n"
        val ex = runCatching { requestOf(raw) }.exceptionOrNull()
        assertTrue(ex is GatewayException)
        assertEquals(413, (ex as GatewayException).status)
    }

    @Test
    fun `malformed request line answers 400`() {
        val ex = runCatching { requestOf("GARBAGE\r\n\r\n") }.exceptionOrNull()
        assertTrue(ex is GatewayException && ex.status == 400)
    }

    @Test
    fun `keep-alive is honoured unless the peer asks to close`() {
        val keep = requestOf("GET /health HTTP/1.1\r\nConnection: keep-alive\r\n\r\n")!!
        assertTrue(GatewayHttp.keepAliveWanted(keep))
        val close = requestOf("GET /health HTTP/1.1\r\nConnection: close\r\n\r\n")!!
        assertTrue(!GatewayHttp.keepAliveWanted(close))
    }

    @Test
    fun `error envelope nests type message and code`() {
        val json = JSONObject(
            GatewayErrors.body(GatewayException(404, "not_found_error", "gone", "model_not_found"), "r1"),
        )
        val err = json.getJSONObject("error")
        assertEquals("not_found_error", err.getString("type"))
        assertEquals("gone", err.getString("message"))
        assertEquals("model_not_found", err.getString("code"))
        assertEquals("r1", err.getString("request_id"))
    }

    @Test
    fun `worker code mapping never leaves a client without a status`() {
        listOf("audio_input_unsupported_provider", "dispatch_failed", "", "weird_code").forEach { code ->
            val ex = GatewayErrors.fromWorkerCode(code, "detail")
            assertNotNull(ex)
            assertTrue("status must be a client-visible 4xx/5xx: $code -> ${ex.status}", ex.status in 400..599)
        }
    }
}
