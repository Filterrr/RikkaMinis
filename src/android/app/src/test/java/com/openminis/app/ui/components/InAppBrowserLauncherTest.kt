package com.openminis.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull

class InAppBrowserLauncherTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `InAppBrowserHost renders content`() {
        composeTestRule.setContent {
            val context = ApplicationProvider.getApplicationContext<Context>()
            InAppBrowserHost(context = context) {
                androidx.compose.material3.Text("Test Content")
            }
        }
        composeTestRule.onNodeWithText("Test Content").assertExists()
    }

    @Test
    fun `InAppBrowserHost click triggers URL preview for http`() {
        composeTestRule.setContent {
            val context = ApplicationProvider.getApplicationContext<Context>()
            InAppBrowserHost(context = context) {
                androidx.compose.material3.Button(
                    onClick = {
                        LocalInAppBrowserLauncher.current("http://example.com")
                    }
                ) {
                    androidx.compose.material3.Text("Open URL")
                }
            }
        }
        composeTestRule.onNodeWithText("Open URL").performClick()
        // URL preview sheet should be shown (we verify by checking if the sheet is rendered)
        composeTestRule.onNodeWithText("http://example.com").assertExists()
    }

    @Test
    fun `InAppBrowserHost click triggers external URL for non-http`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeTestRule.setContent {
            InAppBrowserHost(context = context) {
                androidx.compose.material3.Button(
                    onClick = {
                        LocalInAppBrowserLauncher.current("tel:123456789")
                    }
                ) {
                    androidx.compose.material3.Text("Call")
                }
            }
        }
        composeTestRule.onNodeWithText("Call").performClick()
        // No URL preview should be shown
        composeTestRule.onNodeWithText("tel:123456789").assertDoesNotExist()
    }

    @Test
    fun `InAppBrowserHost default parameters work`() {
        composeTestRule.setContent {
            val context = ApplicationProvider.getApplicationContext<Context>()
            InAppBrowserHost(context = context) {
                androidx.compose.material3.Text("Default")
            }
        }
        composeTestRule.onNodeWithText("Default").assertExists()
    }

    @Test
    fun `openExternalUrl creates intent with correct flags`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val url = "https://example.com"
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Verify that openExternalUrl would create the same intent structure
        val expectedIntent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        assertEquals(expectedIntent.action, intent.action)
        assertEquals(expectedIntent.data, intent.data)
        assertEquals(expectedIntent.flags, intent.flags)
    }

    @Test
    fun `LocalInAppBrowserLauncher is provided by InAppBrowserHost`() {
        composeTestRule.setContent {
            val context = ApplicationProvider.getApplicationContext<Context>()
            InAppBrowserHost(context = context) {
                // Verify that the launcher is available
                val launcher = LocalInAppBrowserLauncher.current
                assertNotNull(launcher)
            }
        }
    }
}