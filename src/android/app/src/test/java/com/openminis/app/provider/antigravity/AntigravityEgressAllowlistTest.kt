package com.openminis.app.provider.antigravity

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-antigravity-egress-allowlist] The Antigravity module's egress
 * invariant, enforced at build time.
 *
 * Background (2026-09-14 WorkBuddy2API audit): a hidden behavior was a
 * split-Base64 constant that silently POSTed the user's web session cookie
 * to `<origin>/activity/workbuddy/invitation/v2/bind` after OAuth login.
 * Nothing about it was visible from the manifest or permissions — only a
 * URL/path audit caught it. This test freezes RikkaMinis' Antigravity
 * module to the opposite guarantee:
 *
 *  1. Every absolute URL literal must point at an allowlisted host.
 *     Adding ANY new host (telemetry, an "activity" endpoint, a
 *     redirector…) — even accidentally — turns this test red.
 *  2. Every multi-segment PATH literal used in request construction must
 *     be in a reviewed path allowlist. The WorkBuddy-style pattern
 *     ("$base/activity/workbuddy/invitation/v2/bind") is exactly a new
 *     multi-segment path — it cannot sneak in without an explicit,
 *     reviewable allowlist edit.
 *
 * Interpolations (`${…}`, `$var`) are stripped before scanning so log
 * templates don't produce phantom hosts.
 */
class AntigravityEgressAllowlistTest {

    companion object {
        /** Allowed host suffixes (suffix match on registrable domain). */
        private val ALLOWED_HOST_SUFFIXES = listOf(
            "accounts.google.com",
            "oauth2.googleapis.com",
            "www.googleapis.com",
            "cloudcode-pa.googleapis.com",
            "daily-cloudcode-pa.googleapis.com",
            "localhost",
            "127.0.0.1",
        )

        /**
         * Every multi-segment request path the module is allowed to speak.
         * Deliberately tiny. Adding an endpoint = adding a line here, which
         * is exactly the review hook we want.
         */
        private val PATH_ALLOWLIST = setOf(
            // CLIProxyAPI management bridge (AntigravityCliProxyBridge).
            "/v0/management",
            "/auth-files/download",
            // Loopback catcher paths (single-segment, listed for completeness).
            "/oauth-callback",
        )

        /**
         * Linux/Android FILESYSTEM roots — these are file paths, never HTTP
         * endpoints (e.g. NativeStore's /proc/self/cmdline process probe).
         */
        private val FS_PATH_PREFIXES = listOf(
            "/proc/", "/dev/", "/sys/", "/data/", "/system/", "/storage/",
            "/cache/", "/odm/", "/vendor/", "/mnt/",
        )

        // Consumes the ENTIRE URL (host + path + query) so the path scanner
        // never sees URL fragments; hosts alone are checked by the host test.
        private val URL_PATTERN = Regex("""https?://[A-Za-z0-9._\-/\[\]:%?=&~]+""")
        private val TEMPLATE_PATTERN = Regex("""\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*""")
        private val PATH_PATTERN = Regex("""/[A-Za-z0-9_\-]+(?:/[A-Za-z0-9_\-]+)+""")

        private fun moduleSources(): List<File> {
            val candidates = listOf(
                File("src/main/java/com/openminis/app/provider/antigravity"),
                File("src/android/app/src/main/java/com/openminis/app/provider/antigravity"),
            )
            val dir = candidates.firstOrNull { it.isDirectory }
                ?: error("antigravity source dir not found; tried $candidates")
            return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

        private fun hostAllowed(host: String): Boolean {
            val h = host.lowercase().trimStart('[').trimEnd(']')
            return ALLOWED_HOST_SUFFIXES.any { h == it || h.endsWith(".$it") }
        }
    }

    @Test
    fun `absolute URL literals point only at allowlisted hosts`() {
        val violations = mutableListOf<String>()
        val files = moduleSources()
        assertTrue("expected sources to scan", files.size >= 5)

        for (file in files) {
            val content = file.readText()
            for (match in Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(content)) {
                val literal = match.groupValues[1]
                // Strip templates first: "http://localhost:$PORT" must yield
                // host "localhost", not "localhost:$port-interpolation".
                val static = TEMPLATE_PATTERN.replace(literal, "\u0000")
                for (m in URL_PATTERN.findAll(static)) {
                    val rest = m.value.substringAfter("://")
                    val host = rest.substringBefore('/').substringBefore('?').trimEnd(':')
                    if (host.isNotEmpty() && !hostAllowed(host)) {
                        violations += "${file.name}: URL '${m.value.take(90)}'"
                    }
                }
            }
        }
        assertTrue(
            "Egress host violations (review: is this host really necessary? " +
                "If yes, extend ALLOWED_HOST_SUFFIXES with justification)\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `multi-segment path literals stay on the reviewed allowlist`() {
        val violations = mutableListOf<String>()
        for (file in moduleSources()) {
            val content = file.readText()
            for (match in Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(content)) {
                val literal = match.groupValues[1]
                // Strip interpolations: templates resolve at runtime, their
                // TEXT contributes nothing routable. Then strip COMPLETE URLs
                // (host+path scanned by the host test above) so scopes like
                // "https://www.googleapis.com/auth/userinfo.email" don't
                // double-report as bare paths.
                val withoutUrls = URL_PATTERN.replace(literal, "\u0000")
                val static = TEMPLATE_PATTERN.replace(withoutUrls, "\u0000")
                for (m in PATH_PATTERN.findAll(static)) {
                    val path = "/" + m.value.trimStart('/')
                    val clean = path.substringBefore('?')
                    if (FS_PATH_PREFIXES.any { clean.startsWith(it) }) continue
                    val hit = PATH_ALLOWLIST.any { clean == it || clean.startsWith("$it?") }
                    if (!hit) {
                        violations += "${file.name}: path '$clean' (literal: ${literal.take(80)})"
                    }
                }
            }
        }
        assertTrue(
            "Un-reviewed request paths found (if this endpoint is legitimate, " +
                "add it to PATH_ALLOWLIST with a justification)\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
