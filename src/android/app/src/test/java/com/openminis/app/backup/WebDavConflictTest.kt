package com.openminis.app.backup

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [RC16-sync-if-match] JVM coverage for the optimistic-lock sync against a
 * MockWebServer: the conditional PUT (`If-Match` / `If-None-Match`) on
 * [WebDavClient.put], the ENTag capture in [WebDavSync.pullLatestSync], and
 * the canonical [WebDavSync.SYNC_STATE_FILE] push path.
 *
 * Pure JVM (OkHttp + XmlPullParser), same harness as [WebDavClientTest]. The
 * full [MultiDeviceSync.syncNow] cycle needs an Android Context, so the
 * conflict *contract* it relies on — put throws [WebDavException] with status
 * 412 when the precondition fails — is asserted here at the transport layer.
 */
class WebDavConflictTest {

    private lateinit var server: MockWebServer
    private lateinit var config: WebDavConfig

    private val client: OkHttpClient = WebDavClient.defaultClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = WebDavConfig(
            url = server.url("/dav/").toString().trimEnd('/'),
            username = "alice",
            password = "s3cret",
            path = "RikkaMinis_backups",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun dav() = WebDavClient(config, client)

    private fun enqueue207(body: String = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"/>""") {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(body)
        )
    }

    // ── Conditional PUT (If-Match / If-None-Match) ────────────────────────

    @Test
    fun `put with If-Match carries the etag and succeeds on 201`() {
        server.enqueue(MockResponse().setResponseCode(201))
        dav().put(
            WebDavSync.SYNC_SUBDIR + "/" + WebDavSync.SYNC_STATE_FILE,
            "{}".toByteArray(),
            ifMatchETag = "abc123",
        )
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("abc123", request.getHeader("If-Match"))
        assertNull(request.getHeader("If-None-Match"))
        // Path is the canonical single-file sync state.
        assertEquals(
            "/dav/RikkaMinis_backups/sync/" + WebDavSync.SYNC_STATE_FILE,
            request.path,
        )
    }

    @Test
    fun `put with If-Match refuses on 412 and does not overwrite`() {
        // [RC16] A sibling wrote between this device's pull and push → server
        // answers 412 Precondition Failed → the client must NOT clobber it.
        server.enqueue(MockResponse().setResponseCode(412))
        try {
            dav().put(
                WebDavSync.SYNC_SUBDIR + "/" + WebDavSync.SYNC_STATE_FILE,
                "{}".toByteArray(),
                ifMatchETag = "stale-etag",
            )
            fail("expected WebDavException conflict")
        } catch (e: WebDavException) {
            assertEquals(412, e.statusCode)
            assertEquals("Conflict: remote changed", e.message)
        }
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("stale-etag", request.getHeader("If-Match"))
    }

    @Test
    fun `put with If-None-Match stars refuses on 412 double first push`() {
        // Two devices doing a first sync at once: only one create succeeds,
        // the other gets 412 → conflict rather than clobber.
        server.enqueue(MockResponse().setResponseCode(412))
        try {
            dav().put(
                WebDavSync.SYNC_SUBDIR + "/" + WebDavSync.SYNC_STATE_FILE,
                "{}".toByteArray(),
                ifNoneMatch = true,
            )
            fail("expected WebDavException conflict")
        } catch (e: WebDavException) {
            assertEquals(412, e.statusCode)
        }
        val request = server.takeRequest()
        assertEquals("*", request.getHeader("If-None-Match"))
    }

    @Test
    fun `conditional put does not retry parent creation on 404`() {
        // A 404 on a preconditioned target means "the version we based this on
        // is gone" → surface as conflict, never re-run the parent-creation PUT
        // (which would unconditionally clobber).
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            dav().put(
                WebDavSync.SYNC_SUBDIR + "/" + WebDavSync.SYNC_STATE_FILE,
                "{}".toByteArray(),
                ifMatchETag = "gone",
            )
            fail("expected WebDavException conflict")
        } catch (e: WebDavException) {
            assertEquals(404, e.statusCode)
        }
        // Exactly one request — no automatic retry attempt.
        assertEquals(1, server.requestCount)
    }

    // ── pullLatestSync returns body + etag ─────────────────────────────────

    @Test
    fun `pullLatestSync returns body and etag from GET header`() {
        // listSyncFiles PROPFINDs depth 1 and returns the canonical state file.
        enqueue207(
            """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/RikkaMinis_backups/sync/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/RikkaMinis_backups/sync/${WebDavSync.SYNC_STATE_FILE}</d:href>
                <d:propstat><d:prop><d:getcontentlength>17</d:getcontentlength></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"etag-2\"")
                .setBody("{\"formatVersion\":1}")
        )
        val pulled = WebDavSync.pullLatestSync(config, client)
        assertEquals("{\"formatVersion\":1}", pulled?.json)
        assertEquals("\"etag-2\"", pulled?.etag)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals(
            "/dav/RikkaMinis_backups/sync/" + WebDavSync.SYNC_STATE_FILE,
            get.path,
        )
    }

    @Test
    fun `pullLatestSync returns null when no sync files exist`() {
        // listSyncFiles → empty multistatus → no item → null.
        enqueue207()
        assertNull(WebDavSync.pullLatestSync(config, client))
    }

    // ── pushSync pushes to the canonical state file ────────────────────────

    @Test
    fun `pushSync writes the canonical state file and forwards If-Match`() {
        enqueue207() // ensureCollectionExists
        enqueue207() // ensureCollectionExists(SYNC_SUBDIR)
        server.enqueue(MockResponse().setResponseCode(201))
        val name = WebDavSync.pushSync(config, "{}", client, pulledEtag = "etag-1")
        assertEquals(WebDavSync.SYNC_STATE_FILE, name)

        // Two PROPFINDs (root + sync subdir), then one conditional PUT.
        assertEquals("PROPFIND", server.takeRequest().method)
        assertEquals("PROPFIND", server.takeRequest().method)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("etag-1", put.getHeader("If-Match"))
        assertEquals("{}", put.body.readUtf8())
        assertEquals("/dav/RikkaMinis_backups/sync/" + WebDavSync.SYNC_STATE_FILE, put.path)
    }
}
