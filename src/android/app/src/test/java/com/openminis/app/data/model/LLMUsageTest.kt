package com.openminis.app.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LLMUsageTest {

    @Test
    fun constructor_assignsAllRequiredFields() {
        val usage = LLMUsage(
            inputTokens = 10,
            outputTokens = 20,
            cacheCreationInputTokens = 5,
            cacheReadInputTokens = 3,
            latestContextTokens = 100,
        )
        assertEquals(10, usage.inputTokens)
        assertEquals(20, usage.outputTokens)
        assertEquals(5, usage.cacheCreationInputTokens)
        assertEquals(3, usage.cacheReadInputTokens)
        assertEquals(100, usage.latestContextTokens)
    }

    @Test
    fun constructor_appliesDefaultsForOptionalFields() {
        val usage = LLMUsage(inputTokens = 1, outputTokens = 2)
        assertEquals(1, usage.inputTokens)
        assertEquals(2, usage.outputTokens)
        assertNull(usage.cacheCreationInputTokens)
        assertNull(usage.cacheReadInputTokens)
        assertEquals(0, usage.latestContextTokens)
    }

    @Test
    fun copy_createsEqualInstanceWhenNoChanges() {
        val usage = LLMUsage(
            inputTokens = 10,
            outputTokens = 20,
            cacheCreationInputTokens = 5,
            cacheReadInputTokens = 3,
            latestContextTokens = 100,
        )
        val copied = usage.copy()
        assertEquals(usage, copied)
        assertEquals(usage.hashCode(), copied.hashCode())
    }

    @Test
    fun copy_overridesSpecifiedFieldsOnly() {
        val usage = LLMUsage(
            inputTokens = 10,
            outputTokens = 20,
            cacheCreationInputTokens = 5,
            cacheReadInputTokens = 3,
            latestContextTokens = 100,
        )
        val copied = usage.copy(
            inputTokens = 99,
            cacheReadInputTokens = null,
        )
        assertEquals(99, copied.inputTokens)
        assertEquals(20, copied.outputTokens)
        assertEquals(5, copied.cacheCreationInputTokens)
        assertNull(copied.cacheReadInputTokens)
        assertEquals(100, copied.latestContextTokens)
    }

    @Test
    fun equals_returnsTrueForSameValues() {
        val a = LLMUsage(1, 2, 3, 4, 5)
        val b = LLMUsage(1, 2, 3, 4, 5)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_returnsFalseForDifferentInputTokens() {
        val a = LLMUsage(1, 2)
        val b = LLMUsage(2, 2)
        assertNotEquals(a, b)
    }

    @Test
    fun equals_returnsFalseForDifferentOutputTokens() {
        val a = LLMUsage(1, 2)
        val b = LLMUsage(1, 3)
        assertNotEquals(a, b)
    }

    @Test
    fun equals_returnsFalseForDifferentCacheCreationInputTokens() {
        val a = LLMUsage(1, 2, cacheCreationInputTokens = 3)
        val b = LLMUsage(1, 2, cacheCreationInputTokens = null)
        assertNotEquals(a, b)
    }

    @Test
    fun equals_returnsFalseForDifferentCacheReadInputTokens() {
        val a = LLMUsage(1, 2, cacheReadInputTokens = 3)
        val b = LLMUsage(1, 2, cacheReadInputTokens = null)
        assertNotEquals(a, b)
    }

    @Test
    fun equals_returnsFalseForDifferentLatestContextTokens() {
        val a = LLMUsage(1, 2, latestContextTokens = 5)
        val b = LLMUsage(1, 2, latestContextTokens = 6)
        assertNotEquals(a, b)
    }

    @Test
    fun equals_isReflexiveAndSymmetric() {
        val a = LLMUsage(1, 2, 3, 4, 5)
        assertEquals(a, a)
        val b = LLMUsage(1, 2, 3, 4, 5)
        assertEquals(a, b)
        assertEquals(b, a)
    }

    @Test
    fun equals_returnsFalseForNullAndOtherType() {
        val a = LLMUsage(1, 2)
        assertFalse(a.equals(null))
        assertFalse(a.equals("not a LLMUsage"))
    }

    @Test
    fun hashCode_isConsistentWithEquals() {
        val a = LLMUsage(1, 2, 3, 4, 5)
        val b = LLMUsage(1, 2, 3, 4, 5)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun toString_containsAllFields() {
        val usage = LLMUsage(
            inputTokens = 10,
            outputTokens = 20,
            cacheCreationInputTokens = 5,
            cacheReadInputTokens = 3,
            latestContextTokens = 100,
        )
        val str = usage.toString()
        assert(str.contains("LLMUsage"))
        assert(str.contains("inputTokens=10"))
        assert(str.contains("outputTokens=20"))
        assert(str.contains("cacheCreationInputTokens=5"))
        assert(str.contains("cacheReadInputTokens=3"))
        assert(str.contains("latestContextTokens=100"))
    }

    @Test
    fun componentFunctions_returnCorrectValues() {
        val usage = LLMUsage(
            inputTokens = 10,
            outputTokens = 20,
            cacheCreationInputTokens = 5,
            cacheReadInputTokens = 3,
            latestContextTokens = 100,
        )
        assertEquals(10, usage.component1())
        assertEquals(20, usage.component2())
        assertEquals(5, usage.component3())
        assertEquals(3, usage.component4())
        assertEquals(100, usage.component5())
    }

    @Test
    fun destructuringDeclaration_assignsAllFields() {
        val usage = LLMUsage(
            inputTokens = 10,
            outputTokens = 20,
            cacheCreationInputTokens = 5,
            cacheReadInputTokens = 3,
            latestContextTokens = 100,
        )
        val (inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens, latestContextTokens) = usage
        assertEquals(10, inputTokens)
        assertEquals(20, outputTokens)
        assertEquals(5, cacheCreationInputTokens)
        assertEquals(3, cacheReadInputTokens)
        assertEquals(100, latestContextTokens)
    }
}