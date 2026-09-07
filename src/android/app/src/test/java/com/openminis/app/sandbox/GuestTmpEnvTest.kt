package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fix-tmpdir-leak] + [T-ca-bootstrap-heal] pure-JVM tests.
 *
 * Pins down two contracts:
 *  1. GUEST_TMP_ENV — the POSIX temp-dir variables handed to every proot
 *     child must point INSIDE the rootfs. The Android host seeds the app
 *     process with TMPDIR=<app cache dir> (/data/user/0/<pkg>/cache); when
 *     that leaks into the guest, Debian maintainer scripts die under
 *     `set -e` (update-ca-certificates: `mktemp -p "${TMPDIR:-/tmp}"`),
 *     which is how ca-certificates installs silently broke.
 *  2. Trust-bundle health — the PEM-certificate counter that drives the
 *     boot-time ensureCaTrust() gate must treat the factory stub bundle
 *     (~2 certs) as unhealthy and a real bundle (100+) as healthy.
 */
class GuestTmpEnvTest {

    // ==================== GUEST_TMP_ENV ====================

    @Test
    fun `all POSIX temp variables point inside the guest rootfs`() {
        for ((key, value) in PRootKernel.GUEST_TMP_ENV) {
            assertTrue(
                "expected guest-side path for $key, got '$value'",
                value.startsWith("/tmp"),
            )
        }
    }

    @Test
    fun `covers the variables Debian maintainer scripts and libcs honor`() {
        val keys = PRootKernel.GUEST_TMP_ENV.keys
        // update-ca-certificates / mktemp / most glibc + perl tooling.
        assertTrue(keys.contains("TMPDIR"))
        // Some configure scripts and MSYS-influenced tooling probe TMP/TEMP.
        assertTrue(keys.contains("TMP"))
        assertTrue(keys.contains("TEMP"))
    }

    @Test
    fun `TMPDIR value equals the canonical guest temp dir`() {
        assertEquals("/tmp", PRootKernel.GUEST_TMP_ENV["TMPDIR"])
    }

    @Test
    fun `no host Android cache path is baked into the map`() {
        for (value in PRootKernel.GUEST_TMP_ENV.values) {
            // The leak signature: host sandbox paths start with /data/user.
            assertTrue(
                "host path leaked into guest env: $value",
                !value.startsWith("/data/"),
            )
        }
    }

    // ==================== CA trust-bundle health ====================

    private fun pemWith(n: Int): String =
        (1..n).joinToString("\n") { i ->
            "-----BEGIN CERTIFICATE-----\nMi$i\n-----END CERTIFICATE-----"
        }

    @Test
    fun `counts every PEM certificate block`() {
        assertEquals(3, RootfsManager.countTrustedCerts(pemWith(3)))
    }

    @Test
    fun `empty bundle is zero`() {
        assertEquals(0, RootfsManager.countTrustedCerts(""))
    }

    @Test
    fun `factory stub bundle (~2 certs) is below the healthy floor`() {
        val certs = RootfsManager.countTrustedCerts(pemWith(2))
        assertTrue(certs < RootfsManager.MIN_EXPECTED_CA_CERTS)
    }

    @Test
    fun `real bootstrap bundle (100+ certs) is healthy`() {
        val certs = RootfsManager.countTrustedCerts(pemWith(120))
        assertTrue(certs >= RootfsManager.MIN_EXPECTED_CA_CERTS)
    }

    @Test
    fun `bundle path is the canonical Debian trust store`() {
        assertEquals("etc/ssl/certs/ca-certificates.crt", RootfsManager.CA_BUNDLE_PATH)
    }
}
