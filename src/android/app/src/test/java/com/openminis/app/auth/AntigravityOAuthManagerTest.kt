package com.openminis.app.auth

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.auth.ui.components.AntigravityOAuthButton
import com.openminis.app.auth.ui.components.AntigravityOAuthScreen
import com.openminis.app.auth.ui.components.AntigravityOAuthStatus
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

class AntigravityOAuthManagerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testAntigravityOAuthButton_rendersCorrectly() {
        composeTestRule.setContent {
            AntigravityOAuthButton(
                onClick = { },
                modifier = androidx.compose.ui.Modifier.testTag("oauth_button")
            )
        }

        composeTestRule.onNodeWithTag("oauth_button").assertExists()
        composeTestRule.onNodeWithText("Sign in with Antigravity").assertExists()
    }

    @Test
    fun testAntigravityOAuthButton_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            AntigravityOAuthButton(
                onClick = { clicked = true },
                modifier = androidx.compose.ui.Modifier.testTag("oauth_button")
            )
        }

        composeTestRule.onNodeWithTag("oauth_button").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testAntigravityOAuthButton_defaultParameters() {
        composeTestRule.setContent {
            AntigravityOAuthButton(
                onClick = { },
                modifier = androidx.compose.ui.Modifier.testTag("oauth_button")
            )
        }

        composeTestRule.onNodeWithTag("oauth_button").assertExists()
        composeTestRule.onNodeWithText("Sign in with Antigravity").assertExists()
    }

    @Test
    fun testAntigravityOAuthStatus_rendersCorrectly() {
        composeTestRule.setContent {
            AntigravityOAuthStatus(
                status = "Connected",
                modifier = androidx.compose.ui.Modifier.testTag("oauth_status")
            )
        }

        composeTestRule.onNodeWithTag("oauth_status").assertExists()
        composeTestRule.onNodeWithText("Status: Connected").assertExists()
    }

    @Test
    fun testAntigravityOAuthStatus_differentStatus() {
        composeTestRule.setContent {
            AntigravityOAuthStatus(
                status = "Disconnected",
                modifier = androidx.compose.ui.Modifier.testTag("oauth_status")
            )
        }

        composeTestRule.onNodeWithTag("oauth_status").assertExists()
        composeTestRule.onNodeWithText("Status: Disconnected").assertExists()
    }

    @Test
    fun testAntigravityOAuthStatus_defaultStatus() {
        composeTestRule.setContent {
            AntigravityOAuthStatus(
                modifier = androidx.compose.ui.Modifier.testTag("oauth_status")
            )
        }

        composeTestRule.onNodeWithTag("oauth_status").assertExists()
        composeTestRule.onNodeWithText("Status: Unknown").assertExists()
    }

    @Test
    fun testAntigravityOAuthScreen_rendersCorrectly() {
        composeTestRule.setContent {
            AntigravityOAuthScreen(
                onSignIn = { },
                onSignOut = { },
                modifier = androidx.compose.ui.Modifier.testTag("oauth_screen")
            )
        }

        composeTestRule.onNodeWithTag("oauth_screen").assertExists()
        composeTestRule.onNodeWithText("Antigravity OAuth").assertExists()
        composeTestRule.onNodeWithText("Sign in with Antigravity").assertExists()
    }

    @Test
    fun testAntigravityOAuthScreen_signInClick() {
        var signInClicked = false
        composeTestRule.setContent {
            AntigravityOAuthScreen(
                onSignIn = { signInClicked = true },
                onSignOut = { },
                modifier = androidx.compose.ui.Modifier.testTag("oauth_screen")
            )
        }

        composeTestRule.onNodeWithText("Sign in with Antigravity").performClick()
        assertTrue(signInClicked)
    }

    @Test
    fun testAntigravityOAuthScreen_signOutClick() {
        var signOutClicked = false
        composeTestRule.setContent {
            AntigravityOAuthScreen(
                onSignIn = { },
                onSignOut = { signOutClicked = true },
                modifier = androidx.compose.ui.Modifier.testTag("oauth_screen")
            )
        }

        composeTestRule.onNodeWithText("Sign out").performClick()
        assertTrue(signOutClicked)
    }

    @Test
    fun testAntigravityOAuthScreen_defaultParameters() {
        composeTestRule.setContent {
            AntigravityOAuthScreen(
                modifier = androidx.compose.ui.Modifier.testTag("oauth_screen")
            )
        }

        composeTestRule.onNodeWithTag("oauth_screen").assertExists()
        composeTestRule.onNodeWithText("Antigravity OAuth").assertExists()
    }

    @Test
    fun testOAuthManager_creation() {
        val manager = AntigravityOAuthManager(context, "test-instance")
        assertNotNull(manager)
        assertEquals("1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com", manager.clientId)
        assertEquals(8086, manager.callbackPort)
        assertEquals("/oauth2callback", manager.redirectPath)
    }

    @Test
    fun testOAuthManager_buildAuthorizationUrl() {
        val manager = AntigravityOAuthManager(context, "test-instance")
        val url = manager.buildAuthorizationUrl()
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url.contains("client_id=1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("prompt=consent"))
    }

    @Test
    fun testOAuthManager_scopes() {
        val manager = AntigravityOAuthManager(context, "test-instance")
        assertTrue(manager.scopes.contains("https://www.googleapis.com/auth/cloud-platform"))
        assertTrue(manager.scopes.contains("https://www.googleapis.com/auth/userinfo.email"))
        assertTrue(manager.scopes.contains("https://www.googleapis.com/auth/userinfo.profile"))
    }
}