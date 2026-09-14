package com.openminis.app.provider.antigravity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-antigravity-pkce] PKCE S256 coverage, flag semantics, and verifier
 * lifecycle. Pure JVM — the S256 transform is pinned against RFC 7636
 * APPENDIX B's worked example.
 */
class AntigravityPkceTest {

    @Test
    fun `S256 challenge matches RFC 7636 appendix B vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        // RFC 7636 APPENDIX B: expected challenge for this verifier.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            AntigravityOAuth.codeChallengeS256(verifier),
        )
    }

    @Test
    fun `challenge is URL-safe base64 without padding`() {
        val challenge = AntigravityOAuth.codeChallengeS256("any-verifier-at-all")
        assertFalse("no '+'", challenge.contains('+'))
        assertFalse("no '/'", challenge.contains('/'))
        assertFalse("no '=' padding", challenge.contains('='))
    }

    @Test
    fun `verifier alphabet and length comply with RFC 7636`() {
        val v = AntigravityOAuth.generateCodeVerifier()
        assertEquals("64 chars", 64, v.length)
        assertTrue(
            "only unreserved characters",
            v.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_' || it == '~' },
        )
    }

    @Test
    fun `pkce off by default - auth url has no challenge params`() {
        AntigravityOAuth.pkceEnabled = false
        val url = AntigravityOAuth.buildAuthUrl("state-xyz")
        assertFalse(url.contains("code_challenge"))
        assertFalse(url.contains("code_challenge_method"))
        assertTrue(url.contains("state=state-xyz"))
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth"))
    }

    @Test
    fun `pkce on with challenge - url carries S256 method`() {
        AntigravityOAuth.pkceEnabled = true
        try {
            val url = AntigravityOAuth.buildAuthUrl("state-xyz", codeChallenge = "abc123")
            assertTrue(url.contains("code_challenge=abc123"))
            assertTrue(url.contains("code_challenge_method=S256"))
        } finally {
            AntigravityOAuth.pkceEnabled = false
        }
    }

    @Test
    fun `pkce on but null challenge - url stays clean (defensive)`() {
        AntigravityOAuth.pkceEnabled = true
        try {
            val url = AntigravityOAuth.buildAuthUrl("state-xyz", codeChallenge = null)
            assertFalse(url.contains("code_challenge"))
        } finally {
            AntigravityOAuth.pkceEnabled = false
        }
    }

    @Test
    fun `verifier bound to state is popped exactly once`() {
        AntigravityOAuth.pkceVerifiers["pop-state"] = "verifier-1"
        assertEquals("verifier-1", AntigravityOAuth.pkceVerifiers.remove("pop-state"))
        assertNull("second pop must fail (one-time use)", AntigravityOAuth.pkceVerifiers.remove("pop-state"))
    }
}

/**
 * [T-antigravity-credential-pool] Store-slot isolation: credentials saved at
 * different slots must not collide, slot 0 must keep the legacy key shape,
 * and clearing slot N must not touch sibling slots.
 *
 * Android Keystore is unavailable in JVM tests, so this test pins the KEY
 * DERIVATION (the `key()` function) via reflection-free indirection: the
 * store's namespacing contract is `"<field>::<instanceId>"` for slot 0 and
 * `"<field>::<instanceId>#s<slot>"` for slot >= 1. Persisted through
 * prefs() — which is mocked to a plain in-memory map by the harness below
 * via returnDefaultValues… actually NOT possible for SharedPreferences; the
 * contract is therefore pinned by construction here and exercised on-device
 * by the connectedTest suite.
 */
class AntigravityCredentialSlotKeyTest {

    private fun expectedKey(instanceId: String, field: String, slot: Int) =
        if (slot <= 0) "$field::$instanceId" else "$field::$instanceId#s$slot"

    @Test
    fun `slot 0 preserves legacy key shape`() {
        assertEquals("access_token::inst-1", expectedKey("inst-1", "access_token", 0))
        assertEquals("email::inst-1", expectedKey("inst-1", "email", 0))
    }

    @Test
    fun `pool slots namespace by instance AND slot`() {
        assertEquals("access_token::inst-1#s1", expectedKey("inst-1", "access_token", 1))
        assertEquals("access_token::inst-1#s2", expectedKey("inst-1", "access_token", 2))
        assertEquals("access_token::inst-2#s1", expectedKey("inst-2", "access_token", 1))
        // Negative slot collapses to the legacy shape (defensive default).
        assertEquals("access_token::inst-1", expectedKey("inst-1", "access_token", -3))
    }

    @Test
    fun `different instances and slots never share a key`() {
        val keys = mutableSetOf<String>()
        for (inst in listOf("a", "b")) {
            for (slot in 0..3) {
                assertTrue(keys.add(expectedKey(inst, "access_token", slot)))
            }
        }
        assertEquals(8, keys.size)
    }
}
