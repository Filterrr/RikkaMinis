package com.openminis.app.sandbox.offload

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class OffloadArgsTest {

    @Test
    fun `test positional arguments`() {
        val args = OffloadArgs(listOf("file1", "file2", "file3"))
        assertEquals(listOf("file1", "file2", "file3"), args.positional)
        assertFalse(args.hasFlag("any"))
        assertNull(args.get("key"))
    }

    @Test
    fun `test boolean flags with --flag and -f`() {
        val args = OffloadArgs(listOf("--verbose", "-d", "--debug"), booleanFlags = setOf("verbose", "debug"))
        assertTrue(args.hasFlag("verbose"))
        assertTrue(args.hasFlag("debug"))
        assertTrue(args.hasFlag("d"))
        assertFalse(args.hasFlag("quiet"))
        assertTrue(args.hasFlag("verbose", "quiet"))
        assertFalse(args.hasFlag("quiet", "none"))
    }

    @Test
    fun `test key-value with --key=value`() {
        val args = OffloadArgs(listOf("--name=test", "--count=42"))
        assertEquals("test", args.get("name"))
        assertEquals("42", args.get("count"))
        assertNull(args.get("missing"))
        assertEquals("test", args.get("name", "other"))
        assertNull(args.get("missing", "other"))
    }

    @Test
    fun `test key-value with --key value`() {
        val args = OffloadArgs(listOf("--name", "test", "--count", "42"))
        assertEquals("test", args.get("name"))
        assertEquals("42", args.get("count"))
        assertTrue(args.positional.isEmpty())
    }

    @Test
    fun `test flag that is followed by non-flag argument but not boolean flag becomes value`() {
        val args = OffloadArgs(listOf("--name", "test", "--verbose", "ignored"), booleanFlags = setOf("verbose"))
        assertTrue(args.hasFlag("verbose"))
        assertNull(args.get("verbose"))
        assertEquals(listOf("ignored"), args.positional)
        assertEquals("test", args.get("name"))
    }

    @Test
    fun `test flag followed by nothing becomes boolean`() {
        val args = OffloadArgs(listOf("--verbose"), booleanFlags = setOf("verbose"))
        assertTrue(args.hasFlag("verbose"))
        assertTrue(args.positional.isEmpty())
    }

    @Test
    fun `test dash only is not a flag`() {
        val args = OffloadArgs(listOf("-"))
        assertEquals(listOf("-"), args.positional)
    }

    @Test
    fun `test getInt valid and invalid`() {
        val args = OffloadArgs(listOf("--int", "123", "--bad", "abc"))
        assertEquals(123, args.getInt("int"))
        assertNull(args.getInt("bad"))
        assertNull(args.getInt("missing"))
    }

    @Test
    fun `test getLong valid and invalid`() {
        val args = OffloadArgs(listOf("--long", "9876543210", "--bad", "abc"))
        assertEquals(9876543210L, args.getLong("long"))
        assertNull(args.getLong("bad"))
        assertNull(args.getLong("missing"))
    }

    @Test
    fun `test getDouble valid and invalid`() {
        val args = OffloadArgs(listOf("--double", "3.14", "--bad", "abc"))
        assertEquals(3.14, args.getDouble("double"), 1e-9)
        assertNull(args.getDouble("bad"))
        assertNull(args.getDouble("missing"))
    }

    @Test
    fun `test getBool true values`() {
        val args = OffloadArgs(listOf("--flag1=true", "--flag2=1", "--flag3=yes", "--flag4=TRUE", "--flag5=Yes"))
        assertTrue(args.getBool("flag1")!!)
        assertTrue(args.getBool("flag2")!!)
        assertTrue(args.getBool("flag3")!!)
        assertTrue(args.getBool("flag4")!!)
        assertTrue(args.getBool("flag5")!!)
    }

    @Test
    fun `test getBool false values`() {
        val args = OffloadArgs(listOf("--flag1=false", "--flag2=0", "--flag3=no", "--flag4=FALSE", "--flag5=No"))
        assertFalse(args.getBool("flag1")!!)
        assertFalse(args.getBool("flag2")!!)
        assertFalse(args.getBool("flag3")!!)
        assertFalse(args.getBool("flag4")!!)
        assertFalse(args.getBool("flag5")!!)
    }

    @Test
    fun `test getBool invalid values`() {
        val args = OffloadArgs(listOf("--flag=maybe"))
        assertNull(args.getBool("flag"))
    }

    @Test
    fun `test getBool with missing key`() {
        val args = OffloadArgs(emptyList())
        assertNull(args.getBool("missing"))
    }

    @Test
    fun `test default boolean flags from OffloadOutput`() {
        val args = OffloadArgs(listOf("--output", "somefile"), booleanFlags = emptySet())
        if ("output" in OffloadOutput.OUTPUT_FLAGS) {
            assertTrue(args.hasFlag("output"))
            assertEquals(listOf("somefile"), args.positional)
        }
    }

    @Test
    fun `test multiple aliases for get`() {
        val args = OffloadArgs(listOf("--name", "test", "--n", "alt"))
        assertEquals("test", args.get("name", "n"))
        assertNull(args.get("name"))
        assertEquals("alt", args.get("n"))
    }
}