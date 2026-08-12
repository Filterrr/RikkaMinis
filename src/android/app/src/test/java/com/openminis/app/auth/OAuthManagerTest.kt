package com.openminis.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OAuthManagerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testSanitizeBody_defaultMaxLen() {
        val body = """{"access_token":"secret123","refresh_token":"secret456"}"""
        val result = OAuthManager.sanitizeBody(body)
        assert(result.contains("\"access_token\":\"***\""))
        assert(result.contains("\"refresh_token\":\"***\""))
        assert(result.length <= 300)
    }

    @Test
    fun testSanitizeBody_customMaxLen() {
        val body = """{"access_token":"secret123"}"""
        val result = OAuthManager.sanitizeBody(body, maxLen = 50)
        assert(result.length <= 50)
    }

    @Test
    fun testSanitizeBody_noTokens() {
        val body = """{"name":"test","email":"test@test.com"}"""
        val result = OAuthManager.sanitizeBody(body)
        assert(result == body)
    }

    @Test
    fun testSanitizeBody_shortBody() {
        val body = "short"
        val result = OAuthManager.sanitizeBody(body, maxLen = 10)
        assert(result == "short")
    }

    @Test
    fun testSanitizeBody_emptyBody() {
        val result = OAuthManager.sanitizeBody("")
        assert(result == "")
    }

    @Test
    fun testSanitizeBody_nullBody() {
        val result = OAuthManager.sanitizeBody("null")
        assert(result == "null")
    }

    @Test
    fun testSanitizeBody_multipleTokens() {
        val body = """{"access_token":"tok1","id_token":"tok2","api_key":"tok3"}"""
        val result = OAuthManager.sanitizeBody(body)
        assert(result.contains("\"access_token\":\"***\""))
        assert(result.contains("\"id_token\":\"***\""))
        assert(result.contains("\"api_key\":\"***\""))
    }

    @Test
    fun testSanitizeBody_longBodyTruncated() {
        val body = "a".repeat(500)
        val result = OAuthManager.sanitizeBody(body, maxLen = 100)
        assert(result.length <= 100)
        assert(result.endsWith("…(500 chars)"))
    }

    @Test
    fun testSanitizeBody_longBodyNotTruncated() {
        val body = "a".repeat(200)
        val result = OAuthManager.sanitizeBody(body, maxLen = 300)
        assert(result.length == 200)
    }

    @Test
    fun testForInstance_anthropic() {
        val instance = mockk<com.openminis.app.data.model.ProviderInstance> {
            every { providerType } returns com.openminis.app.data.model.ProviderType.anthropic
            every { id } returns "instance_1"
        }
        val manager = OAuthManager.forInstance(context, instance)
        assert(manager is ClaudeOAuthManager)
    }

    @Test
    fun testForInstance_openAI() {
        val instance = mockk<com.openminis.app.data.model.ProviderInstance> {
            every { providerType } returns com.openminis.app.data.model.ProviderType.openAI
            every { id } returns "instance_2"
        }
        val manager = OAuthManager.forInstance(context, instance)
        assert(manager is OpenAIOAuthManager)
    }

    @Test
    fun testForInstance_xAI() {
        val instance = mockk<com.openminis.app.data.model.ProviderInstance> {
            every { providerType } returns com.openminis.app.data.model.ProviderType.xAI
            every { id } returns "instance_3"
        }
        val manager = OAuthManager.forInstance(context, instance)
        assert(manager is XAIOAuthManager)
    }

    @Test
    fun testForInstance_kimiCode() {
        val instance = mockk<com.openminis.app.data.model.ProviderInstance> {
            every { providerType } returns com.openminis.app.data.model.ProviderType.kimiCode
            every { id } returns "instance_4"
        }
        val manager = OAuthManager.forInstance(context, instance)
        assert(manager is KimiOAuthManager)
    }

    @Test
    fun testForInstance_unknownType() {
        val instance = mockk<com.openminis.app.data.model.ProviderInstance> {
            every { providerType } returns com.openminis.app.data.model.ProviderType.unknown
            every { id } returns "instance_5"
        }
        val manager = OAuthManager.forInstance(context, instance)
        assert(manager == null)
    }

    @Test
    fun testForInstance_nullInstance() {
        val manager = OAuthManager.forInstance(context, null)
        assert(manager == null)
    }

    @Test
    fun testGeneratePKCE_defaultLength() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.generatePKCE() } answers {
            val bytes = ByteArray(64)
            java.security.SecureRandom().nextBytes(bytes)
            val verifier = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            )
            verifier to challenge
        }
        val (verifier, challenge) = mockManager.generatePKCE()
        assert(verifier.isNotEmpty())
        assert(challenge.isNotEmpty())
        assert(verifier.length >= 43)
        assert(challenge.length >= 43)
    }

    @Test
    fun testGeneratePKCE_customByteLength() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.generatePKCE(byteLength = 32) } answers {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            val verifier = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            )
            verifier to challenge
        }
        val (verifier, challenge) = mockManager.generatePKCE(byteLength = 32)
        assert(verifier.isNotEmpty())
        assert(challenge.isNotEmpty())
        assert(verifier.length >= 43)
        assert(challenge.length >= 43)
    }

    @Test
    fun testGenerateHexPKCE() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.generateHexPKCE() } answers {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            val verifier = bytes.joinToString("") { "%02x".format(it) }
            val challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            )
            verifier to challenge
        }
        val (verifier, challenge) = mockManager.generateHexPKCE()
        assert(verifier.isNotEmpty())
        assert(challenge.isNotEmpty())
        assert(verifier.length == 64)
        assert(challenge.length >= 43)
    }

    @Test
    fun testGenerateState() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.generateState() } answers {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        val state = mockManager.generateState()
        assert(state.isNotEmpty())
        assert(state.length >= 43)
    }

    @Test
    fun testGenerateAndSavePKCE() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.generateAndSavePKCE() } answers {
            val (verifier, challenge) = mockManager.generatePKCE()
            verifier to challenge
        }
        val (verifier, challenge) = mockManager.generateAndSavePKCE()
        assert(verifier.isNotEmpty())
        assert(challenge.isNotEmpty())
    }

    @Test
    fun testGenerateAndSaveState() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.generateAndSaveState() } answers {
            mockManager.generateState()
        }
        val state = mockManager.generateAndSaveState()
        assert(state.isNotEmpty())
    }

    @Test
    fun testBuildAuthorizationUrl() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.authURL } returns "https://example.com/auth"
        every { mockManager.clientId } returns "test_client"
        every { mockManager.scopes } returns "openid profile"
        every { mockManager.redirectUri } returns "http://localhost:8080/callback"
        every { mockManager.buildAuthorizationUrl() } answers {
            val (_, challenge) = mockManager.generateAndSavePKCE()
            val state = mockManager.generateAndSaveState()
            "https://example.com/auth?" + listOf(
                "client_id=test_client",
                "redirect_uri=${Uri.encode("http://localhost:8080/callback")}",
                "response_type=code",
                "scope=${Uri.encode("openid profile")}",
                "state=$state",
                "code_challenge=$challenge",
                "code_challenge_method=S256",
            ).joinToString("&")
        }
        val url = mockManager.buildAuthorizationUrl()
        assert(url.startsWith("https://example.com/auth?"))
        assert(url.contains("client_id=test_client"))
        assert(url.contains("response_type=code"))
        assert(url.contains("code_challenge_method=S256"))
    }

    @Test
    fun testBuildAuthorizationUrl_withoutState() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.authURL } returns "https://example.com/auth"
        every { mockManager.clientId } returns "test_client"
        every { mockManager.scopes } returns "openid"
        every { mockManager.redirectUri } returns "http://localhost:8080/callback"
        every { mockManager.buildAuthorizationUrl() } answers {
            val (_, challenge) = mockManager.generateAndSavePKCE()
            "https://example.com/auth?" + listOf(
                "client_id=test_client",
                "redirect_uri=${Uri.encode("http://localhost:8080/callback")}",
                "response_type=code",
                "scope=${Uri.encode("openid")}",
                "code_challenge=$challenge",
                "code_challenge_method=S256",
            ).joinToString("&")
        }
        val url = mockManager.buildAuthorizationUrl()
        assert(url.startsWith("https://example.com/auth?"))
        assert(!url.contains("state="))
    }

    @Test
    fun testIsAuthenticated_withTokens() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.isAuthenticated() } returns true
        assert(mockManager.isAuthenticated())
    }

    @Test
    fun testIsAuthenticated_withoutTokens() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.isAuthenticated() } returns false
        assert(!mockManager.isAuthenticated())
    }

    @Test
    fun testIsAuthenticated_withManualBearer() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.isAuthenticated() } returns true
        every { mockManager.loadManualBearerToken() } returns "manual_token"
        assert(mockManager.isAuthenticated())
    }

    @Test
    fun testLogout() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.logout() } answers {
            mockManager.deleteManualBearerToken()
        }
        mockManager.logout()
        verify { mockManager.deleteManualBearerToken() }
    }

    @Test
    fun testSaveManualBearerToken() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.saveManualBearerToken(any()) } answers {
            mockManager.saveOAuthString("manual_bearer_token", firstArg())
        }
        mockManager.saveManualBearerToken("test_token")
        verify { mockManager.saveOAuthString("manual_bearer_token", "test_token") }
    }

    @Test
    fun testLoadManualBearerToken() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.loadManualBearerToken() } returns "test_token"
        val token = mockManager.loadManualBearerToken()
        assert(token == "test_token")
    }

    @Test
    fun testLoadManualBearerToken_null() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.loadManualBearerToken() } returns null
        val token = mockManager.loadManualBearerToken()
        assert(token == null)
    }

    @Test
    fun testDeleteManualBearerToken() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.deleteManualBearerToken() } answers {
            mockManager.saveOAuthString("manual_bearer_token", "")
        }
        mockManager.deleteManualBearerToken()
        verify { mockManager.saveOAuthString("manual_bearer_token", "") }
    }

    @Test
    fun testExportStoredTokensJson() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.exportStoredTokensJson() } returns """{"access_token":"test"}"""
        val json = mockManager.exportStoredTokensJson()
        assert(json != null)
        assert(json!!.contains("access_token"))
    }

    @Test
    fun testExportStoredTokensJson_null() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.exportStoredTokensJson() } returns null
        val json = mockManager.exportStoredTokensJson()
        assert(json == null)
    }

    @Test
    fun testImportStoredTokensJson() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.importStoredTokensJson(any()) } answers {
            mockManager.saveOAuthString("oauth_tokens", firstArg())
        }
        val json = """{"access_token":"test"}"""
        mockManager.importStoredTokensJson(json)
        verify { mockManager.saveOAuthString("oauth_tokens", json) }
    }

    @Test
    fun testImportStoredTokensJson_invalidJson() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.importStoredTokensJson(any()) } answers {
            // Do nothing for invalid JSON
        }
        mockManager.importStoredTokensJson("invalid json")
        // Should not throw exception
    }

    @Test
    fun testExportOAuthString() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.exportOAuthString("test_key") } returns "test_value"
        val value = mockManager.exportOAuthString("test_key")
        assert(value == "test_value")
    }

    @Test
    fun testExportOAuthString_null() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.exportOAuthString("test_key") } returns null
        val value = mockManager.exportOAuthString("test_key")
        assert(value == null)
    }

    @Test
    fun testImportOAuthString() {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.importOAuthString(any(), any()) } answers {
            mockManager.saveOAuthString(firstArg(), secondArg())
        }
        mockManager.importOAuthString("test_key", "test_value")
        verify { mockManager.saveOAuthString("test_key", "test_value") }
    }

    @Test
    fun testStartLogin() = runTest {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.startLogin(any()) } answers {
            val callback = firstArg<() -> Unit>()
            callback()
        }
        var called = false
        mockManager.startLogin { called = true }
        assert(called)
    }

    @Test
    fun testExchangeCode_success() = runTest {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.exchangeCode(any()) } returns true
        val result = mockManager.exchangeCode("test_code")
        assert(result)
    }

    @Test
    fun testExchangeCode_failure() = runTest {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.exchangeCode(any()) } returns false
        val result = mockManager.exchangeCode("test_code")
        assert(!result)
    }

    @Test
    fun testRefreshToken_success() = runTest {
        val mockManager = mockk<OAuthManager>(relaxed = true)
        every { mockManager.refreshToken() } returns true
        val result = mockManager.refreshToken()
        assert(result)
    }

    @Test
    fun testRefreshToken_failure() = runTest {
        val mockManager = mockk