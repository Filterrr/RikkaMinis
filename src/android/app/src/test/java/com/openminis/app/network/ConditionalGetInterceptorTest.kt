package com.openminis.app.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** [P2-9-conditional-etag] Interceptor behaviour over a real HTTP loop. */
class ConditionalGetInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    /** Records the last ETag written per URL (behaves like UpdateEtagStore). */
    private class MemStore : EtagStore {
        val map = HashMap<String, String>()
        override fun get(urlKey: String): String? = map[urlKey]
        override fun put(urlKey: String, etag: String) {
            if (etag.isEmpty()) map.remove(urlKey) else map[urlKey] = etag
        }
    }

    private val store = MemStore()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(ConditionalGetInterceptor(store))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.url("/releases").toString()

    @Test
    fun `first GET stores etag, second GET revalidates and gets synthesized 200`() {
        server.enqueue(
            MockResponse().setBody("""[{"tag":"v1"}]""").setHeader("ETag", "\"v1\""),
        )
        server.enqueue(MockResponse().setResponseCode(304))

        // 1st: full 200 → body visible, ETag stored.
        val r1 = client.newCall(Request.Builder().url(url()).build()).execute()
        assertEquals(200, r1.code)
        assertEquals("""[{"tag":"v1"}]""", r1.body!!.string())
        assertEquals("\"v1\"", store.get(server.url("/releases").toString()))
        assertNull(r1.header("X-Minis-Not-Modified"))

        // 2nd: If-None-Match sent, 304 → synthesized 200 + marker + empty body.
        val r2 = client.newCall(Request.Builder().url(url()).build()).execute()
        assertEquals(200, r2.code)
        assertEquals("1", r2.header("X-Minis-Not-Modified"))
        assertEquals("", r2.body!!.string())

        // takeRequest() returns requests in arrival order: the first one
        // carried no If-None-Match (cold store); the SECOND one must.
        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("\"v1\"", second.getHeader("If-None-Match"))
    }

    @Test
    fun `server without etag clears stored tag`() {
        server.enqueue(MockResponse().setBody("data").setHeader("ETag", "\"old\""))
        server.enqueue(MockResponse().setBody("data")) // no ETag this time

        client.newCall(Request.Builder().url(url()).build()).execute().body!!.string()
        assertEquals("\"old\"", store.get(server.url("/releases").toString()))

        client.newCall(Request.Builder().url(url()).build()).execute().body!!.string()
        assertNull(store.get(server.url("/releases").toString()))
    }

    @Test
    fun `non-2xx responses never touch the store`() {
        server.enqueue(MockResponse().setResponseCode(500))
        client.newCall(Request.Builder().url(url()).build()).execute()
        assertNull(store.get(server.url("/releases").toString()))
    }
}
