package com.openminis.app.auth

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(10, unit = TimeUnit.SECONDS)
class OpenAIOAuthManagerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: android.content.Context
    private lateinit var providerRepository: ProviderRepository
    private lateinit var oauthManager: OpenAIOAuthManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        providerRepository = mockk<ProviderRepository>(relaxed = true)
        oauthManager = OpenAIOAuthManager(context, "test-instance")
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test login companion function - successful login`() = runTest {
        coEvery { providerRepository.saveApiKey(any(), any()) } returns Unit

        val result = OpenAIOAuthManager.login(context, "test-instance", providerRepository)

        assertNotNull(result)
        assertTrue(result.isNotEmpty())
        coVerify { providerRepository.saveApiKey("test-instance", result) }
    }

    @Test
    fun `test login companion function - with invalid context`() = runTest {
        coEvery { providerRepository.saveApiKey(any(), any()) } throws Exception("Save failed")

        try {
            OpenAIOAuthManager.login(context, "test-instance", providerRepository)
            // Should not reach here
            assertTrue(false)
        } catch (e: Exception) {
            assertEquals("Save failed", e.message)
        }
    }

    @Test
    fun `test buildAuthorizationUrl - default parameters`() {
        val url = oauthManager.buildAuthorizationUrl()

        assertNotNull(url)
        assertTrue(url.startsWith("https:"))
        assertTrue(url.contains("client_id=app_EMoamEEZ73f0CkXaXp7hrann"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("codex_cli_simplified_flow=true"))
        assertTrue(url.contains("originator=codex_cli_rs"))
        assertTrue(url.contains("id_token_add_organizations=true"))
        assertTrue(url.contains("redirect_uri="))
        assertTrue(url.contains("scope="))
        assertTrue(url.contains("state="))
        assertTrue(url.contains("code_challenge="))
    }

    @Test
    fun `test buildAuthorizationUrl - contains PKCE parameters`() {
        val url = oauthManager.buildAuthorizationUrl()

        assertTrue(url.contains("code_challenge="))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state="))
        assertTrue(url.contains("redirect_uri="))
    }

    @Test
    fun `test buildAuthorizationUrl - scopes are correct`() {
        val url = oauthManager.buildAuthorizationUrl()

        assertTrue(url.contains("scope=openid%20profile%20email%20offline_access"))
        assertTrue(url.contains("redirect_uri="))
    }

    @Test
    fun `test accountId getter and setter`() {
        assertNull(oauthManager.accountId)

        oauthManager.accountId = "test-account-id"

        assertNotNull(oauthManager.accountId)
        assertEquals("test-account-id", oauthManager.accountId)
    }

    @Test
    fun `test planType getter and setter`() {
        assertNull(oauthManager.planType)

        oauthManager.planType = "free"

        assertNotNull(oauthManager.planType)
        assertEquals("free", oauthManager.planType)
    }

    @Test
    fun `test onTokensReceived - with valid id_token`() = runTest {
        val json = org.json.JSONObject()
        json.put("id_token", "header.payload.signature")
        
        val payload = org.json.JSONObject()
        payload.put("chatgpt_account_id", "account-123")
        payload.put("chatgpt_plan_type", "pro")
        
        val encodedPayload = java.util.Base64.getUrlEncoder().encodeToString(payload.toString().toByteArray())
        val idToken = "header.$encodedPayload.signature"
        
        json.put("id_token", idToken)

        oauthManager.onTokensReceived(json)

        assertEquals("account-123", oauthManager.accountId)
        assertEquals("pro", oauthManager.planType)
    }

    @Test
    fun `test onTokensReceived - with empty id_token`() = runTest {
        val json = org.json.JSONObject()
        json.put("id_token", "")

        oauthManager.onTokensReceived(json)

        assertNull(oauthManager.accountId)
        assertNull(oauthManager.planType)
    }

    @Test
    fun `test onTokensReceived - with invalid id_token`() = runTest {
        val json = org.json.JSONObject()
        json.put("id_token", "invalid-token")

        oauthManager.onTokensReceived(json)

        assertNull(oauthManager.accountId)
        assertNull(oauthManager.planType)
    }

    @Test
    fun `test exchangeCodeJson - with valid code`() = runTest {
        val code = "test-auth-code"
        
        // Use a mock HTTP client to avoid real network calls
        val mockClient = mockk<okhttp3.OkHttpClient>()
        val mockResponse = mockk<okhttp3.Response>()
        
        coEvery { mockClient.newCall(any()) } returns mockk {
            coEvery { execute() } returns mockResponse
        }
        
        coEvery { mockResponse.code } returns 200
        coEvery { mockResponse.body?.string() } returns """
            {
                "access_token": "test-access-token",
                "expires_in": 3600,
                "refresh_token": "test-refresh-token",
                "id_token": "header.payload.signature"
            }
        """.trimIndent()

        // Use reflection to set the mock client
        val field = OpenAIOAuthManager::class.java.getDeclaredField("systemAwareHttpClient")
        field.isAccessible = true
        field.set(oauthManager, mockClient)

        val result = oauthManager.exchangeCodeJson(code)

        assertNotNull(result)
        assertEquals("test-access-token", result)
    }

    @Test
    fun `test exchangeCodeJson - with non-2xx response`() = runTest {
        val code = "test-auth-code"
        
        val mockClient = mockk<okhttp3.OkHttpClient>()
        val mockResponse = mockk<okhttp3.Response>()
        
        coEvery { mockClient.newCall(any()) } returns mockk {
            coEvery { execute() } returns mockResponse
        }
        
        coEvery { mockResponse.code } returns 400
        coEvery { mockResponse.body?.string() } returns "Bad Request"

        val field = OpenAIOAuthManager::class.java.getDeclaredField("systemAwareHttpClient")
        field.isAccessible = true
        field.set(oauthManager, mockClient)

        try {
            oauthManager.exchangeCodeJson(code)
            assertTrue(false) // Should not reach here
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("400"))
        }
    }

    @Test
    fun `test exchangeCodeJson - with network error`() = runTest {
        val code = "test-auth-code"
        
        val mockClient = mockk<okhttp3.OkHttpClient>()
        
        coEvery { mockClient.newCall(any()) } throws java.net.UnknownHostException("Unknown host")

        val field = OpenAIOAuthManager::class.java.getDeclaredField("systemAwareHttpClient")
        field.isAccessible = true
        field.set(oauthManager, mockClient)

        try {
            oauthManager.exchangeCodeJson(code)
            assertTrue(false) // Should not reach here
        } catch (e: OAuthNetworkUnreachableException) {
            assertTrue(e is OAuthNetworkUnreachableException)
        }
    }

    @Test
    fun `test exchangeCodeJson - with missing access_token`() = runTest {
        val code = "test-auth-code"
        
        val mockClient = mockk<okhttp3.OkHttpClient>()
        val mockResponse = mockk<okhttp3.Response>()
        
        coEvery { mockClient.newCall(any()) } returns mockk {
            coEvery { execute() } returns mockResponse
        }
        
        coEvery { mockResponse.code } returns 200
        coEvery { mockResponse.body?.string() } returns """
            {
                "expires_in": 3600
            }
        """.trimIndent()

        val field = OpenAIOAuthManager::class.java.getDeclaredField("systemAwareHttpClient")
        field.isAccessible = true
        field.set(oauthManager, mockClient)

        try {
            oauthManager.exchangeCodeJson(code)
            assertTrue(false) // Should not reach here
        } catch (e: Exception) {
            assertEquals("No access_token in response", e.message)
        }
    }

    @Test
    fun `test wrapIfNetworkError - UnknownHostException`() {
        val exception = java.net.UnknownHostException("test")
        val result = oauthManager.wrapIfNetworkError(exception)
        assertTrue(result is OAuthNetworkUnreachableException)
    }

    @Test
    fun `test wrapIfNetworkError - SocketTimeoutException`() {
        val exception = java.net.SocketTimeoutException("timeout")
        val result = oauthManager.wrapIfNetworkError(exception)
        assertTrue(result is OAuthNetworkUnreachableException)
    }

    @Test
    fun `test wrapIfNetworkError - regular exception`() {
        val exception = Exception("regular error")
        val result = oauthManager.wrapIfNetworkError(exception)
        assertEquals(exception, result)
    }

    @Test
    fun `test logNetworkEnvironment - valid URL`() {
        oauthManager.logNetworkEnvironment("https://example.com")
        // Should not throw
    }

    @Test
    fun `test logNetworkEnvironment - invalid URL`() {
        oauthManager.logNetworkEnvironment("invalid-url")
        // Should not throw
    }

    @Test
    fun `test parseIdToken - valid token`() {
        val payload = org.json.JSONObject()
        payload.put("chatgpt_account_id", "account-456")
        payload.put("chatgpt_plan_type", "plus")
        
        val encodedPayload = java.util.Base64.getUrlEncoder().encodeToString(payload.toString().toByteArray())
        val token = "header.$encodedPayload.signature"
        
        oauthManager.parseIdToken(token)

        assertEquals("account-456", oauthManager.accountId)
        assertEquals("plus", oauthManager.planType)
    }

    @Test
    fun `test parseIdToken - invalid token`() {
        oauthManager.parseIdToken("invalid")
        
        assertNull(oauthManager.accountId)
        assertNull(oauthManager.planType)
    }

    @Test
    fun `test parseIdToken - empty token`() {
        oauthManager.parseIdToken("")
        
        assertNull(oauthManager.accountId)
        assertNull(oauthManager.planType)
    }

    @Test
    fun `test performLogin - with mocked dependencies`() = runTest {
        // Mock the callback server and HTTP client
        val mockClient = mockk<okhttp3.OkHttpClient>()
        val mockResponse = mockk<okhttp3.Response>()
        
        coEvery { mockClient.newCall(any()) } returns mockk {
            coEvery { execute() } returns mockResponse
        }
        
        coEvery { mockResponse.code } returns 200
        coEvery { mockResponse.body?.string() } returns """
            {
                "access_token": "test-access-token",
                "expires_in": 3600,
                "refresh_token": "test-refresh-token"
            }
        """.trimIndent()

        val field = OpenAIOAuthManager::class.java.getDeclaredField("systemAwareHttpClient")
        field.isAccessible = true
        field.set(oauthManager, mockClient)

        // Mock the callback server to immediately return a code
        val serverField = OpenAIOAuthManager::class.java.getDeclaredField("loginCallbackServer")
        serverField.isAccessible = true
        serverField.set(oauthManager, object : OAuthCallbackServer(1455) {
            override fun start() {
                // Simulate callback
                callbackHandler?.invoke("test-code", oauthManager.expectedState)
            }
        })

        val result = oauthManager.performLogin(context)

        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }
}