package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-ua-global-default-android] Pure-logic coverage for the global user-agent
 * default: [UserAgentProfile.effectiveString] is the single resolver both the
 * pool ([BrowserTabPool.resolvedUserAgentString]) and per-tab
 * [BrowserUseManager] go through, so the tests pin the contract at the source.
 */
class UserAgentProfileTest {

    @Test
    fun `non-custom profiles resolve to their built-in string`() {
        assertEquals(
            UserAgentProfile.MOBILE_CHROME.userAgentString,
            UserAgentProfile.effectiveString(UserAgentProfile.MOBILE_CHROME, null),
        )
        assertEquals(
            UserAgentProfile.MOBILE_CHROME.userAgentString,
            // Custom string is ignored for non-custom profiles.
            UserAgentProfile.effectiveString(UserAgentProfile.MOBILE_CHROME, "Mozilla/5.0 Spoof"),
        )
        assertEquals(
            UserAgentProfile.DESKTOP_CHROME.userAgentString,
            UserAgentProfile.effectiveString(UserAgentProfile.DESKTOP_CHROME, null),
        )
    }

    @Test
    fun `custom profile uses the provided string`() {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Test/1.0"
        assertEquals(ua, UserAgentProfile.effectiveString(UserAgentProfile.CUSTOM, ua))
    }

    @Test
    fun `custom profile with blank value resolves to null`() {
        // null = "leave the WebView default untouched" — the profile is a
        // shell until the user types a real string.
        assertNull(UserAgentProfile.effectiveString(UserAgentProfile.CUSTOM, null))
        assertNull(UserAgentProfile.effectiveString(UserAgentProfile.CUSTOM, ""))
        assertNull(UserAgentProfile.effectiveString(UserAgentProfile.CUSTOM, "   "))
    }

    @Test
    fun `custom profile trims surrounding whitespace`() {
        val ua = "Mozilla/5.0 (X11; Fedora) Test/2.0"
        assertEquals(
            ua,
            UserAgentProfile.effectiveString(UserAgentProfile.CUSTOM, "  $ua  "),
        )
    }

    @Test
    fun `fromString round-trips every profile value`() {
        for (profile in UserAgentProfile.entries) {
            assertEquals(profile, UserAgentProfile.fromString(profile.value))
        }
        assertNull(UserAgentProfile.fromString("nonsense"))
    }
}
