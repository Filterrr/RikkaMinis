package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-rootfs-integrity] Pure-JVM tests for [RootfsHealth]'s derived state.
 * Pins down the contract the boot path and the Repair Terminal button rely
 * on: `healthy` ⇔ sandbox can function, `terminalOk` ⇔ interactive bash can
 * start, `missing` is an accurate diff.
 */
class RootfsHealthTest {

    private fun healthy() = RootfsHealth(
        bash = true, sh = true, libc = true,
        libreadline = true, libncursesw = true, apk = true, apkDatabase = true,
    )

    @Test
    fun `all present is healthy and terminal-ready`() {
        val h = healthy()
        assertTrue(h.healthy)
        assertTrue(h.terminalOk)
        assertTrue(h.missing.isEmpty())
    }

    @Test
    fun `missing libreadline breaks terminal but not sandbox`() {
        val h = healthy().copy(libreadline = false)
        // Sandbox (sh + libc + apk) still healthy…
        assertTrue(h.healthy)
        // …but interactive bash can't start (readline is a hard dep).
        assertFalse(h.terminalOk)
        assertEquals(listOf("/usr/lib/libreadline.so.8"), h.missing)
    }

    @Test
    fun `missing bash breaks both terminal and sandbox-health`() {
        val h = healthy().copy(bash = false)
        assertFalse(h.healthy)
        assertFalse(h.terminalOk)
        assertEquals(listOf("/bin/bash"), h.missing)
    }

    @Test
    fun `missing apk breaks sandbox health`() {
        val h = healthy().copy(apk = false)
        assertFalse(h.healthy)
        assertTrue(h.terminalOk) // bash can still run
        assertEquals(listOf("/sbin/apk"), h.missing)
    }

    @Test
    fun `multiple missing aggregate in canonical order`() {
        val h = RootfsHealth(
            bash = false, sh = false, libc = true,
            libreadline = false, libncursesw = false, apk = false, apkDatabase = true,
        )
        assertFalse(h.healthy)
        assertFalse(h.terminalOk)
        assertEquals(
            listOf("/bin/bash", "/bin/sh", "/usr/lib/libreadline.so.8", "/usr/lib/libncursesw.so.6", "/sbin/apk"),
            h.missing,
        )
    }
}
