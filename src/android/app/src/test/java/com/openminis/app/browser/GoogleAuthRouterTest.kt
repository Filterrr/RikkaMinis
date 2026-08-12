package com.openminis.app.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.logging.AppLogger
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GoogleAuthRouterTest {

    @Test
    fun `shouldRouteExternally returns false for null url`() {
        assertFalse(GoogleAuthRouter.shouldRouteExternally(null))
    }

    @Test
    fun `shouldRouteExternally returns false for empty url`() {
        assertFalse(GoogleAuthRouter.shouldRouteExternally(""))
    }

    @Test
    fun `shouldRouteExternally returns false for invalid url`() {
        assertFalse(GoogleAuthRouter.shouldRouteExternally("not a url"))
    }

    @Test
    fun `shouldRouteExternally returns true for exactly matching host`() {
        assertTrue(GoogleAuthRouter.shouldRouteExternally("https://accounts.google.com/path"))
    }

    @Test
    fun `shouldRouteExternally returns true for subdomain of matching host`() {
        assertTrue(GoogleAuthRouter.shouldRouteExternally("https://sub.accounts.google.com/path"))
    }

    @Test
    fun `shouldRouteExternally returns false for non-matching host`() {
        assertFalse(GoogleAuthRouter.shouldRouteExternally("https://www.google.com"))
    }

    @Test
    fun `openInCustomTab launches custom tab and logs info on success`() {
        val mockContext = mockk<Context>()
        val mockBuilder = mockk<CustomTabsIntent.Builder>()
        val mockIntent = mockk<CustomTabsIntent>()

        mockkConstructor(CustomTabsIntent.Builder::class)
        every { anyConstructed<CustomTabsIntent.Builder>().setShowTitle(true) } returns mockBuilder
        every { mockBuilder.setUrlBarHidingEnabled(false) } returns mockBuilder
        every { mockBuilder.build() } returns mockIntent
        every { mockIntent.launchUrl(mockContext, any()) } just Runs

        mockkObject(AppLogger)
        every { AppLogger.info(any(), any()) } just Runs

        GoogleAuthRouter.openInCustomTab(mockContext, "https://accounts.google.com")

        verify { mockIntent.launchUrl(mockContext, Uri.parse("https://accounts.google.com")) }
        verify { AppLogger.info("GoogleAuthRouter", "opened in Custom Tab: https://accounts.google.com") }

        unmockkAll()
    }

    @Test
    fun `openInCustomTab falls back to ACTION_VIEW on exception and logs warning`() {
        val mockContext = mockk<Context>()
        val mockIntent = mockk<Intent>(relaxed = true)

        mockkConstructor(CustomTabsIntent.Builder::class)
        every { anyConstructed<CustomTabsIntent.Builder>().setShowTitle(true) } throws RuntimeException("Custom Tab failed")

        mockkObject(AppLogger)
        every { AppLogger.warning(any(), any()) } just Runs

        // Mock startActivity to capture the Intent
        val slot = slot<Intent>()
        every { mockContext.startActivity(capture(slot)) } just Runs

        GoogleAuthRouter.openInCustomTab(mockContext, "https://accounts.google.com")

        // Verify fallback intent
        val capturedIntent = slot.captured
        assertEquals(Intent.ACTION_VIEW, capturedIntent.action)
        assertEquals(Uri.parse("https://accounts.google.com"), capturedIntent.data)
        assertTrue(capturedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        verify { AppLogger.warning(any(), "Custom Tab launch failed, falling back to ACTION_VIEW: Custom Tab failed") }

        unmockkAll()
    }
}