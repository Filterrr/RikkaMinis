package com.openminis.app.config.fields

import com.openminis.app.config.ConfigAccess
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class ClosureFieldTest {

    @Test
    fun `read should return value from reader lambda`() {
        val expected = ConfigValue.StringValue("test")
        val field = ClosureField(
            path = "/test",
            displayName = "Test",
            description = "A test field",
            valueSchema = ConfigSchema.String,
            reader = { expected },
            writer = {}
        )
        assertEquals(expected, field.read())
    }

    @Test
    fun `write should call writer lambda with validated value`() {
        var writtenValue: ConfigValue? = null
        val field = ClosureField(
            path = "/test",
            displayName = "Test",
            description = "A test field",
            valueSchema = ConfigSchema.String,
            reader = { ConfigValue.StringValue("") },
            writer = { value -> writtenValue = value }
        )
        val value = ConfigValue.StringValue("hello")
        field.write(value)
        assertEquals(value, writtenValue)
    }

    @Test
    fun `write should throw ConfigError when value does not match schema`() {
        val field = ClosureField(
            path = "/test",
            displayName = "Test",
            description = "A test field",
            valueSchema = ConfigSchema.Number,
            reader = { ConfigValue.NumberValue(0) },
            writer = {}
        )
        assertThrows<ConfigError> {
            field.write(ConfigValue.StringValue("not a number"))
        }
    }

    @Test
    fun `default access should be READWRITE`() {
        val field = ClosureField(
            path = "/test",
            displayName = "Test",
            description = "A test field",
            valueSchema = ConfigSchema.String,
            reader = { ConfigValue.StringValue("") },
            writer = {}
        )
        assertEquals(ConfigAccess.READWRITE, field.access)
    }

    @Test
    fun `default risk should be NORMAL`() {
        val field = ClosureField(
            path = "/test",
            displayName = "Test",
            description = "A test field",
            valueSchema = ConfigSchema.String,
            reader = { ConfigValue.StringValue("") },
            writer = {}
        )
        assertEquals(ConfigRisk.NORMAL, field.risk)
    }

    @Test
    fun `default revertable should be true`() {
        val field = ClosureField(
            path = "/test",
            displayName = "Test",
            description = "A test field",
            valueSchema = ConfigSchema.String,
            reader = { ConfigValue.StringValue("") },
            writer = {}
        )
        assertEquals(true, field.revertable)
    }

    @Test
    fun `properties should return constructor values`() {
        val field = ClosureField(
            path = "/custom/path",
            displayName = "Custom Display",
            description = "Custom description",
            valueSchema = ConfigSchema.Boolean,
            access = ConfigAccess.READONLY,
            risk = ConfigRisk.DANGEROUS,
            revertable = false,
            reader = { ConfigValue.BooleanValue(true) },
            writer = {}
        )
        assertEquals("/custom/path", field.path)
        assertEquals("Custom Display", field.displayName)
        assertEquals("Custom description", field.description)
        assertEquals(ConfigSchema.Boolean, field.valueSchema)
        assertEquals(ConfigAccess.READONLY, field.access)
        assertEquals(ConfigRisk.DANGEROUS, field.risk)
        assertEquals(false, field.revertable)
    }
}