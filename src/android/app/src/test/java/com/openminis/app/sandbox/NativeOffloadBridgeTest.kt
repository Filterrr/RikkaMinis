package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JVM verification of the toolservice↔main bridge protocol
 * ([NativeOffloadBridge]):
 *
 *   1. Every name in [OffloadHandlerCatalog.allHandlerNames] is covered by
 *      either [NativeOffloadBridge.lightHandlerNames] (executed inside
 *      :toolservice) or [NativeOffloadBridge.heavyHandlerNames] (forwarded
 *      to the main process) — otherwise the PRoot stub would reach
 *      :toolservice with no registered handler.
 *   2. The little-endian frame helpers round-trip the same way the socket
 *      peers encode/decode them.
 */
class NativeOffloadBridgeTest {

    // ---- coverage invariants ----

    @Test
    fun `every catalog handler is covered by light or heavy set`() {
        val covered = NativeOffloadBridge.lightHandlerNames + NativeOffloadBridge.heavyHandlerNames
        val missing = OffloadHandlerCatalog.baseHandlerNames.filter { it !in covered }
        assertTrue("catalog names without a covering handler: $missing", missing.isEmpty())
    }

    @Test
    fun `heavy and light sets are disjoint`() {
        val overlap = NativeOffloadBridge.lightHandlerNames intersect NativeOffloadBridge.heavyHandlerNames
        assertTrue("light/heavy overlap: $overlap", overlap.isEmpty())
    }

    @Test
    fun `heavy set contains the six bridge-backed handlers`() {
        assertTrue(NativeOffloadBridge.heavyHandlerNames.contains("minis-browser-use"))
        assertTrue(NativeOffloadBridge.heavyHandlerNames.contains("minis-model-use"))
        assertTrue(NativeOffloadBridge.heavyHandlerNames.contains("minis-sessions-cli"))
        assertTrue(NativeOffloadBridge.heavyHandlerNames.contains("minis-config"))
        assertTrue(NativeOffloadBridge.heavyHandlerNames.contains("android-a11y-cli"))
        assertTrue(NativeOffloadBridge.heavyHandlerNames.contains("android-shizuku-cli"))
    }

    @Test
    fun `light set includes the pure android handlers`() {
        assertTrue(NativeOffloadBridge.lightHandlerNames.contains("android-device"))
        assertTrue(NativeOffloadBridge.lightHandlerNames.contains("android-weather"))
        assertTrue(NativeOffloadBridge.lightHandlerNames.contains("android-clipboard"))
        assertTrue(NativeOffloadBridge.lightHandlerNames.contains("android-open"))
        assertTrue(NativeOffloadBridge.lightHandlerNames.contains("android-player"))
    }

    // ---- LE frame helpers round-trip ----

    private fun encodeHandle(name: String, pid: Int, argv: List<String>, env: Map<String, String>, cwd: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeLEInt(0x4E4F4652)
        out.writeLEInt(1) // version
        out.writeLELong(42L) // requestId
        out.writeLEString(name)
        out.writeLEInt(pid)
        out.writeLEInt(argv.size)
        argv.forEach { out.writeLEString(it) }
        out.writeLEInt(env.size)
        env.forEach { (k, v) -> out.writeLEString("$k=$v") }
        out.writeLEString(cwd)
        out.writeLEString("sid-1")
        out.flush()
        return bos.toByteArray()
    }

    @Test
    fun `request frame round-trips through shared helpers`() {
        val bytes = encodeHandle(
            name = "android-device",
            pid = 1234,
            argv = listOf("android-device", "info"),
            env = mapOf("MINIS_CHAT_SESSION_ID" to "sid-1"),
            cwd = "/root",
        )
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals(0x4E4F4652, input.readLEInt())
        assertEquals(1, input.readLEInt())
        assertEquals(42L, input.readLELong())
        assertEquals("android-device", input.readLEString())
        assertEquals(1234, input.readLEInt())
        assertEquals(2, input.readLEInt())
        assertEquals("android-device", input.readLEString())
        assertEquals("info", input.readLEString())
        assertEquals(1, input.readLEInt())
        assertEquals("MINIS_CHAT_SESSION_ID=sid-1", input.readLEString())
        assertEquals("/root", input.readLEString())
        assertEquals("sid-1", input.readLEString())
    }

    @Test
    fun `response frame round-trips exit code and output`() {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeLEInt(0x5350464F)
        out.writeLEInt(1)
        out.writeLELong(7L)
        out.writeLEInt(0)
        out.writeLEString("{\"ok\":true}")
        out.flush()

        val input = DataInputStream(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(0x5350464F, input.readLEInt())
        assertEquals(1, input.readLEInt())
        assertEquals(7L, input.readLELong())
        assertEquals(0, input.readLEInt())
        assertEquals("{\"ok\":true}", input.readLEString())
    }

    @Test
    fun `empty string encodes as zero length`() {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeLEString("")
        out.flush()
        val input = DataInputStream(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(0, input.readLEInt())
        assertEquals("", input.readLEString())
    }
}