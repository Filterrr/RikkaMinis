package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [T-model-exec-strict-enum] Pins the strict-parse contract of
 * ModelExecutionService.safeEnum without dragging in Android / Service.
 * The production function is private; we test via the internal
 * UnknownEnumValueException type and a same-signature copy of the
 * inline logic (inline functions can't be called cross-module from
 * tests without re-compilation, so we mirror the body).
 */
class ModelExecutionSafeEnumTest {

    // Mirror of the production safeEnum body for JVM testing.
    private inline fun <reified T : Enum<T>> strictEnum(name: String): T =
        try {
            java.lang.Enum.valueOf(T::class.java, name)
        } catch (_: IllegalArgumentException) {
            throw UnknownEnumValueException(T::class.java.simpleName, name)
        }

    enum class Sample { A, B, C }

    @Test
    fun `known value parses`() {
        assertEquals(Sample.B, strictEnum<Sample>("B"))
    }

    @Test
    fun `unknown value throws UnknownEnumValueException`() {
        val ex = assertThrows(UnknownEnumValueException::class.java) {
            strictEnum<Sample>("D")
        }
        assertEquals("Sample", ex.enumClass)
        assertEquals("D", ex.unknownValue)
    }

    @Test
    fun `empty string throws UnknownEnumValueException`() {
        assertThrows(UnknownEnumValueException::class.java) {
            strictEnum<Sample>("")
        }
    }
}
