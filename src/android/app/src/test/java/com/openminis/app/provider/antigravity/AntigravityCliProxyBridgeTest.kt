package com.openminis.app.provider.antigravity

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-antigravity-cli-bridge] Contract tests for the CLIProxyAPI management
 * bridge — the wire format EasyCLIProxyAPI speaks to its core
 * (src-tauri/src/management_api.rs) and the core's management handlers
 * answer with (Go: internal/api/handlers/management):
 *
 *  - GET  /v0/management/antigravity-auth-url?is_webui=true  (Bearer auth)
 *  - GET  /v0/management/get-auth-status?state=...           (Bearer auth)
 *  - POST /v0/management/oauth-callback {"provider","redirect_url"} (no auth)
 *  - GET  /v0/management/auth-files                          (Bearer auth)
 *  - GET  /v0/management/auth-files/download?name=...        (Bearer auth)
 *
 * Every test speaks real HTTP against a MockWebServer, so regressions here
 * are wire-format regressions, not mock-contract drift.
 */
class AntigravityCliProxyBridgeTest {

    private lateinit var server: MockWebServer

    private fun bridgeConfig(secret: String = "test-secret") = AntigravityCliProxyBridge.Config(
        host = server.hostName,
        port = server.port,
        tls = false,
        secret = secret,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Step 1: antigravity-auth-url ──

    @Test
    fun `fetchAuthUrl requests webui callback mode with bearer auth`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"url":"https://accounts.google.com/o/oauth2/v2/auth?state=abc","state":"abc"}""",
            ),
        )

        val result = AntigravityCliProxyBridge.fetchAuthUrl(bridgeConfig())

        val recorded = server.takeRequest()
        assertEquals("/v0/management/antigravity-auth-url", recorded.path?.substringBefore('?'))
        assertTrue(recorded.path!!.contains("is_webui=true"))
        assertEquals("Bearer test-secret", recorded.getHeader("Authorization"))
        assertEquals("abc", result.state)
        assertTrue(result.url.startsWith("https://accounts.google.com/"))
    }

    @Test
    fun `fetchAuthUrl surfaces core error message and omits auth when no secret`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"error":"management key required"}"""))
        try {
            AntigravityCliProxyBridge.fetchAuthUrl(bridgeConfig(secret = ""))
            throw AssertionError("expected BridgeException")
        } catch (e: AntigravityCliProxyBridge.BridgeException) {
            assertTrue(e.message!!.contains("management key required"))
        }
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    // ── Step 2: get-auth-status ──

    @Test
    fun `pollAuthStatus parses wait ok and error`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"wait"}"""))
        val waiting = AntigravityCliProxyBridge.pollAuthStatus(bridgeConfig(), "st1")
        assertEquals("wait", waiting.status)
        server.takeRequest() // drain

        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        val done = AntigravityCliProxyBridge.pollAuthStatus(bridgeConfig(), "st2")
        assertEquals("ok", done.status)
        server.takeRequest()

        server.enqueue(MockResponse().setBody("""{"status":"error","error":"Failed to exchange token"}"""))
        val failed = AntigravityCliProxyBridge.pollAuthStatus(bridgeConfig(), "st3")
        assertEquals("error", failed.status)
        assertEquals("Failed to exchange token", failed.error)
        // The state must travel as a query parameter.
        val last = server.takeRequest()
        assertTrue(last.path!!.startsWith("/v0/management/get-auth-status?state=st3"))
    }

    // ── Step 3: oauth-callback paste path ──

    @Test
    fun `submitCallback posts provider and redirect_url without auth header`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))

        val error = AntigravityBridgeLoginManager.submitPastedCallback(
            bridgeConfig(),
            "http://localhost:51121/oauth-callback?code=xyz&state=abc",
        )

        assertNull(error)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v0/management/oauth-callback", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("antigravity", body.getString("provider"))
        assertTrue(body.getString("redirect_url").contains("code=xyz"))
    }

    @Test
    fun `submitCallback surfaces core rejection`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"error","error":"unknown or expired state"}"""))
        val error = AntigravityBridgeLoginManager.submitPastedCallback(
            bridgeConfig(),
            "http://localhost:51121/oauth-callback?code=xyz&state=stale",
        )
        assertNotNull(error)
        assertTrue(error!!.contains("unknown or expired state"))
    }

    // ── Step 4: auth-files list + download ──

    @Test
    fun `credential sync picks the newest antigravity file and downloads it`() = runBlocking {
        val fileList = """
            {"files":[
              {"name":"gemini-cli-user@gmail.com.json","type":"gemini-cli"},
              {"name":"old@gmail.com.json","type":"antigravity","timestamp":1700000000},
              {"name":"new@gmail.com.json","provider":"antigravity","timestamp":1700005000}
            ]}
        """.trimIndent()
        val downloaded = """
            {"type":"antigravity","access_token":"at-new","refresh_token":"rt-new",
             "expires_in":3599,"timestamp":1700005000000,"email":"new@gmail.com",
             "project_id":"proj-1","expired":"2023-11-15T01:23:20Z"}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(fileList))
        server.enqueue(MockResponse().setBody(downloaded))

        val credential = AntigravityCliProxyBridge.fetchLatestAntigravityCredential(bridgeConfig())

        // List request first (Bearer), download second targeting the newest name.
        val listRequest = server.takeRequest()
        assertEquals("/v0/management/auth-files", listRequest.path)
        assertEquals("Bearer test-secret", listRequest.getHeader("Authorization"))
        val downloadRequest = server.takeRequest()
        assertEquals("/v0/management/auth-files/download", downloadRequest.path?.substringBefore('?'))
        assertTrue(downloadRequest.path!!.contains("name=new%40gmail.com.json"))

        assertNotNull(credential)
        assertEquals("at-new", credential!!.accessToken)
        assertEquals("rt-new", credential.refreshToken)
        assertEquals(3599L, credential.expiresIn)
        assertEquals("new@gmail.com", credential.email)
        assertEquals("proj-1", credential.projectId)
    }

    @Test
    fun `credential sync returns null when core has no antigravity files`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"files":[{"name":"a.json","type":"codex"}]}"""))
        val credential = AntigravityCliProxyBridge.fetchLatestAntigravityCredential(bridgeConfig())
        assertNull(credential)
        // Drain the list request (explicit Unit: takeRequest() itself returns
        // RecordedRequest, and a non-void @Test method fails class validation).
        val drained = server.takeRequest()
        assertNotNull(drained)
    }

    @Test
    fun `metadata parsing maps upstream auth-file fields 1-1`() {
        val json = JSONObject(
            """
            {"type":"antigravity","access_token":"at","refresh_token":"rt",
             "expires_in":3600,"email":"e@x.com","project_id":"p","timestamp":1700000000}
            """.trimIndent(),
        )
        val credential = AntigravityCliProxyBridge.credentialFromMetadata(json)!!
        // Second-granularity timestamps normalize to millis.
        assertEquals(1700000000000L, credential.timestamp)
        assertEquals("at", credential.accessToken)
        assertEquals("e@x.com", credential.email)
        assertEquals("p", credential.projectId)
    }

    @Test
    fun `metadata without access token parses to null`() {
        val json = JSONObject("""{"type":"antigravity","refresh_token":"rt"}""")
        assertNull(AntigravityCliProxyBridge.credentialFromMetadata(json))
    }

    // ── Connectivity probe ──

    @Test
    fun `testConnection passes when core answers and key is accepted`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))   // liveness ping
        server.enqueue(MockResponse().setBody("""{"config":"..."}"""))  // authed probe

        val result = AntigravityCliProxyBridge.testConnection(bridgeConfig())

        assertTrue(result.ok)
        assertEquals("/v0/management/get-auth-status", server.takeRequest().path)
        val authed = server.takeRequest()
        assertEquals("/v0/management/config", authed.path)
        assertEquals("Bearer test-secret", authed.getHeader("Authorization"))
    }

    @Test
    fun `testConnection reports key rejection without throwing`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        val result = AntigravityCliProxyBridge.testConnection(bridgeConfig())

        assertFalse(result.ok)
        assertTrue(result.message.contains("401"))
    }

    // ── Config parsing ──

    @Test
    fun `hash-shaped secrets are rejected as unusable`() {
        val hashed = listOf(
            "\$2a\$10\$abcdefghijklmnopqrstuv",
            "\$argon2id\$v=19\$m=65536,t=2,p=1\$c29tZXNhbHQ",
            "bcrypt:YWJj",
            "sha256:deadbeef",
        )
        hashed.forEach { raw ->
            val config = AntigravityCliProxyBridge.Config("h", 8317, tls = false, secret = raw)
            assertFalse("expected hashed: $raw", config.hasUsableSecret)
        }
        val plain = AntigravityCliProxyBridge.Config("h", 8317, tls = false, secret = "831227")
        assertTrue(plain.hasUsableSecret)
        assertTrue(plain.origin == "http://h:8317")
    }

    @Test
    fun `sanitize validates host and port ranges`() {
        val ok = AntigravityCliProxyBridge.Config.sanitize(" 192.168.1.10 ", "8317", tls = true, secret = " k ")
        assertNotNull(ok)
        assertEquals("192.168.1.10", ok!!.host)
        assertEquals(8317, ok.port)
        assertTrue(ok.tls)
        assertEquals("k", ok.secret)

        assertNull(AntigravityCliProxyBridge.Config.sanitize("  ", "8317", false, ""))
        assertNull(AntigravityCliProxyBridge.Config.sanitize("h", "0", false, ""))
        assertNull(AntigravityCliProxyBridge.Config.sanitize("h", "99999", false, ""))
        assertNull(AntigravityCliProxyBridge.Config.sanitize("h", "not-a-port", false, ""))
    }
}
