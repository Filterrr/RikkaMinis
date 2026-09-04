package com.openminis.app.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.GzipSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OPT6-request-gzip] JVM tests for the request-body gzip interceptor.
 * Real HTTP round-trip through MockWebServer: the test server receives the
 * wire bytes and gunzips them to verify content integrity.
 */
class GzipRequestInterceptorTest {

    private val json = "application/json".toMediaType()
    private val bigPayload = """{"messages":[${(1..200).joinToString(",") { """{"role":"user","content":"tool output line $it with some padding text to make compression worthwhile"}""" }}]}"""
    private val smallPayload = """{"ping":1}"""

    private fun client(gate: (String) -> Boolean = { true }) = OkHttpClient.Builder()
        .addInterceptor(GzipRequestInterceptor(shouldCompress = { req -> gate(req.url.host) }))
        .build()

    @Test
    fun `large json body is gzipped on the wire and survives the round trip`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        val resp = client().newCall(
            Request.Builder().url(server.url("/v1/chat"))
                .post(bigPayload.toRequestBody(json)).build()
        ).execute()
        assertEquals(200, resp.code)
        resp.close()

        val recorded = server.takeRequest()
        assertEquals("gzip", recorded.getHeader("Content-Encoding"))
        val gunzipped = GzipSource(recorded.body.buffer().snapshot().clone()).buffer()
            .readUtf8()
        assertEquals(bigPayload, gunzipped)
        server.close()
    }

    @Test
    fun `small json body stays raw`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        client().newCall(
            Request.Builder().url(server.url("/"))
                .post(smallPayload.toRequestBody(json)).build()
        ).execute().close()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Content-Encoding"))
        assertEquals(smallPayload, recorded.body.readUtf8())
        server.close()
    }

    @Test
    fun `route gate opts out unknown hosts`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        client(gate = { it == "api.openai.com" }).newCall(
            Request.Builder().url(server.url("/"))
                .post(bigPayload.toRequestBody(json)).build()
        ).execute().close()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Content-Encoding"))
        assertEquals(bigPayload, recorded.body.readUtf8())
        server.close()
    }

    @Test
    fun `existing content-encoding is never double-encoded`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        client().newCall(
            Request.Builder().url(server.url("/"))
                .header("Content-Encoding", "identity")
                .post(bigPayload.toRequestBody(json)).build()
        ).execute().close()

        val recorded = server.takeRequest()
        assertEquals("identity", recorded.getHeader("Content-Encoding"))
        assertEquals(bigPayload, recorded.body.readUtf8())
        server.close()
    }

    @Test
    fun `non-json bodies are left alone`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        client().newCall(
            Request.Builder().url(server.url("/"))
                .post(bigPayload.toByteArray().toRequestBody("audio/mpeg".toMediaType()))
                .build()
        ).execute().close()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Content-Encoding"))
        server.close()
    }

    @Test
    fun `counter increments on compression`() {
        val before = GzipRequestInterceptor.compressibleRequests.get()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        client().newCall(
            Request.Builder().url(server.url("/"))
                .post(bigPayload.toRequestBody(json)).build()
        ).execute().close()
        assertTrue(GzipRequestInterceptor.compressibleRequests.get() > before)
        server.close()
    }
}
