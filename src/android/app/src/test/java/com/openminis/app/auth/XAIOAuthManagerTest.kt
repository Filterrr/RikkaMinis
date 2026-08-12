package com.openminis.app.auth

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties

class XAIOAuthManagerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: android.content.Context
    private lateinit var manager: XAIOAuthManager
    private val testInstanceId = "test-instance-123"

    @BeforeEach
    fun setUp(@TempDir tempDir: File) {
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        manager = XAIOAuthManager(context, testInstanceId)
    }

    @Test
    fun testCompanionObjectConstants() {
        assertEquals("b1a00492-073a-47ea-816f-4c329264a828", XAIOAuthManager.OAUTH_CLIENT_ID)
        assertEquals("https://auth.x.ai/.well-known/openid-configuration", XAIOAuthManager.DISCOVERY_URL)
        assertEquals(56121, XAIOAuthManager.CALLBACK_PORT)
        assertEquals("/callback", XAIOAuthManager.CALLBACK_PATH)
    }

    @Test
    fun testDefaultPropertyValues() {
        assertNull(manager.accountId)
        assertNull(manager.email)
        assertNull(manager.displayName)
    }

    @Test
    fun testAuthURL() {
        assertEquals("https://auth.x.ai/authorize", manager.authURL)
    }

    @Test
    fun testTokenURL() {
        assertEquals("https://auth.x.ai/token", manager.tokenURL)
    }

    @Test
    fun testClientId() {
        assertEquals(XAIOAuthManager.OAUTH_CLIENT_ID, manager.clientId)
    }

    @Test
    fun testClientSecret() {
        assertNull(manager.clientSecret)
    }

    @Test
    fun testCallbackPort() {
        assertEquals(XAIOAuthManager.CALLBACK_PORT, manager.callbackPort)
    }

    @Test
    fun testRedirectPath() {
        assertEquals(XAIOAuthManager.CALLBACK_PATH, manager.redirectPath)
    }

    @Test
    fun testScopes() {
        assertEquals("openid profile email offline_access grok-cli:access api:access", manager.scopes)
    }

    @Test
    fun testRedirectUri() {
        val expected = "http://localhost:${XAIOAuthManager.CALLBACK_PORT}${XAIOAuthManager.CALLBACK_PATH}"
        assertEquals(expected, manager.redirectUri)
    }

    @Test
    fun testGenerateAndSavePKCE() {
        val (verifier, challenge) = manager.generateAndSavePKCE()
        assertNotNull(verifier)
        assertNotNull(challenge)
        assertTrue(verifier.isNotEmpty())
        assertTrue(challenge.isNotEmpty())
        assertFalse(verifier.contains("+"))
        assertFalse(verifier.contains("/"))
        assertFalse(challenge.contains("+"))
        assertFalse(challenge.contains("/"))
    }

    @Test
    fun testGenerateNonce() {
        val nonce1 = manager.generateNonce()
        val nonce2 = manager.generateNonce()
        assertNotNull(nonce1)
        assertNotNull(nonce2)
        assertTrue(nonce1.isNotEmpty())
        assertTrue(nonce2.isNotEmpty())
        assertFalse(nonce1.contains("+"))
        assertFalse(nonce1.contains("/"))
        assertFalse(nonce2.contains("+"))
        assertFalse(nonce2.contains("/"))
    }

    @Test
    fun testBuildTokenParams() {
        val testCode = "test-authorization-code-123"
        val params = manager.buildTokenParams(testCode)
        assertEquals("authorization_code", params["grant_type"])
        assertEquals(testCode, params["code"])
        assertEquals(manager.redirectUri, params["redirect_uri"])
        assertEquals(manager.clientId, params["client_id"])
        assertEquals("S256", params["code_challenge_method"])
        assertNotNull(params["code_verifier"])
        assertNotNull(params["code_challenge"])
    }

    @Test
    fun testLoginCompanionFunction() {
        val providerRepository = com.openminis.app.data.repository.ProviderRepository(context)
        assertThrows(Exception::class.java) {
            XAIOAuthManager.login(context, testInstanceId, providerRepository)
        }
    }

    @Test
    fun testAccountIdSetter() {
        manager.accountId = "test-account-id"
        assertEquals("test-account-id", manager.accountId)
    }

    @Test
    fun testEmailSetter() {
        manager.email = "test@example.com"
        assertEquals("test@example.com", manager.email)
    }

    @Test
    fun testDisplayNameSetter() {
        manager.displayName = "Test User"
        assertEquals("Test User", manager.displayName)
    }

    @Test
    fun testMultipleAccountIdAssignments() {
        manager.accountId = "account-1"
        assertEquals("account-1", manager.accountId)
        manager.accountId = "account-2"
        assertEquals("account-2", manager.accountId)
    }

    @Test
    fun testMultipleEmailAssignments() {
        manager.email = "first@example.com"
        assertEquals("first@example.com", manager.email)
        manager.email = "second@example.com"
        assertEquals("second@example.com", manager.email)
    }

    @Test
    fun testMultipleDisplayNameAssignments() {
        manager.displayName = "User One"
        assertEquals("User One", manager.displayName)
        manager.displayName = "User Two"
        assertEquals("User Two", manager.displayName)
    }

    @Test
    fun testAccountIdNullAssignment() {
        manager.accountId = "test-account"
        assertEquals("test-account", manager.accountId)
        manager.accountId = null
        assertEquals("test-account", manager.accountId)
    }

    @Test
    fun testEmailNullAssignment() {
        manager.email = "test@example.com"
        assertEquals("test@example.com", manager.email)
        manager.email = null
        assertEquals("test@example.com", manager.email)
    }

    @Test
    fun testDisplayNameNullAssignment() {
        manager.displayName = "Test User"
        assertEquals("Test User", manager.displayName)
        manager.displayName = null
        assertEquals("Test User", manager.displayName)
    }

    @Test
    fun testParseIdToken() {
        manager.parseIdToken("invalid.token")
        assertNull(manager.email)
        assertNull(manager.displayName)
        assertNull(manager.accountId)
    }

    @Test
    fun testParseIdTokenWithValidPayload() {
        val payload = "{\"email\":\"user@example.com\",\"name\":\"John Doe\",\"sub\":\"user123\"}"
        val encodedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val idToken = "header.$encodedPayload.signature"
        manager.parseIdToken(idToken)
        assertEquals("user@example.com", manager.email)
        assertEquals("John Doe", manager.displayName)
        assertEquals("user123", manager.accountId)
    }

    @Test
    fun testParseIdTokenWithAccountId() {
        val payload = "{\"email\":\"test@test.com\",\"name\":\"Test\",\"account_id\":\"acc-456\"}"
        val encodedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val idToken = "header.$encodedPayload.signature"
        manager.parseIdToken(idToken)
        assertEquals("test@test.com", manager.email)
        assertEquals("Test", manager.displayName)
        assertEquals("acc-456", manager.accountId)
    }

    @Test
    fun testParseIdTokenWithEmptyFields() {
        val payload = "{\"email\":\"\",\"name\":\"\",\"sub\":\"\"}"
        val encodedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val idToken = "header.$encodedPayload.signature"
        manager.parseIdToken(idToken)
        assertNull(manager.email)
        assertNull(manager.displayName)
        assertNull(manager.accountId)
    }

    @Test
    fun testParseIdTokenWithMalformedJson() {
        val payload = "not-json"
        val encodedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val idToken = "header.$encodedPayload.signature"
        manager.parseIdToken(idToken)
        assertNull(manager.email)
        assertNull(manager.displayName)
        assertNull(manager.accountId)
    }

    @Test
    fun testGenerateAndSavePKCERepeatability() {
        val (verifier1, challenge1) = manager.generateAndSavePKCE()
        val (verifier2, challenge2) = manager.generateAndSavePKCE()
        assertNotEquals(verifier1, verifier2)
        assertNotEquals(challenge1, challenge2)
    }

    @Test
    fun testComposableRendering() {
        composeTestRule.setContent {
            // Test that the composable can be rendered
            XAIOAuthManager(context, testInstanceId)
        }
        composeTestRule.onRoot().assertExists()
    }

    @Test
    fun testComposableWithDefaultParameters() {
        composeTestRule.setContent {
            XAIOAuthManager(context, testInstanceId)
        }
        composeTestRule.onRoot().assertExists()
        composeTestRule.onRoot().assertIsDisplayed()
    }
}