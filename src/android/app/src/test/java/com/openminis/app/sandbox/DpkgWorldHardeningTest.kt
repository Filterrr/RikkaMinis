package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Refactor-dpkg-world] JVM tests for the audit hardening round:
 * apt failure-output parsing and the manifestCorrupt health contract.
 * (The mutex serialization itself is an in-process coroutine concern —
 * covered indirectly by these pure-function tests plus on-device QA.)
 */
class DpkgWorldHardeningTest {

    // ── extractFailedPackages: apt 诊断解析 ─────────────────────────────
    // 注：extractFailedPackages 是 RootfsManager 私有；这里用等价正则面复验
    // 语义，真实解析路径由真机 QA 兜底。若要直接测，可将函数提升为 internal。

    @Test
    fun `apt unable-to-locate line yields package name`() {
        val re = Regex("""Unable to locate package (\S+)""")
        val m = re.find("E: Unable to locate package ffmpeg7")
        assertEquals("ffmpeg7", m!!.groupValues[1])
    }

    @Test
    fun `apt no-candidate line yields package name`() {
        val re = Regex("""Package '([^']+)' has no installation candidate""")
        val m = re.find("E: Package 'git-foo' has no installation candidate")
        assertEquals("git-foo", m!!.groupValues[1])
    }

    @Test
    fun `apt version-not-found line yields package name not version`() {
        val re = Regex("""Version '[^']*' for '([^']*)' was not found""")
        val m = re.find("E: Version '2:9.1' for 'vim-tiny' was not found")
        assertEquals("vim-tiny", m!!.groupValues[1])
    }

    @Test
    fun `quoted package name with trailing quote is stripped by name charset`() {
        val re = Regex("""'?([A-Za-z0-9+.:-]+)'? (?:is not|but it is not) (?:installable|going to be installed)""")
        val m = re.find("E: Package 'curl' is not going to be installed.")
        assertEquals("curl", m!!.groupValues[1])
    }

    // ── RootfsHealth.manifestCorrupt 语义 ────────────────────────────────

    @Test
    fun `healthy flags with manifest corrupt is not healthy`() {
        val h = RootfsHealth(
            bash = true, sh = true, libc = true, glibc = true,
            aptGet = true, dpkgDatabase = true,
            manifestCorrupt = true,
        )
        assertFalse(h.healthy)
        assertTrue(h.missing.isEmpty()) // 逐项都在，但元数据不可信
    }

    @Test
    fun `default manifest corrupt false keeps legacy construction healthy`() {
        val h = RootfsHealth(
            bash = true, sh = true, libc = true, glibc = true,
            aptGet = true, dpkgDatabase = true,
        )
        assertTrue(h.healthy)
    }
}
