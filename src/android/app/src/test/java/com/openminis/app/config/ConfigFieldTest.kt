package com.openminis.app.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfigFieldTest {

    private class SimpleConfigField(
        override val path: String,
        override val displayName: String = "display",
        override val description: String = "desc",
        override val valueSchema: ConfigSchema = object : ConfigSchema {},
        override val access: ConfigAccess = object : ConfigAccess {},
        override val risk: ConfigRisk = object : ConfigRisk {},
        override val revertable: Boolean = false,
        private var storedValue: ConfigValue = object : ConfigValue {}
    ) : ConfigField {
        override fun read() = storedValue
        override fun write(value: ConfigValue) { storedValue = value }
    }

    @Test
    fun `scope returns substring before first dot`() {
        val field = SimpleConfigField(path = "group.setting")
        assertEquals("group", field.scope)
    }

    @Test
    fun `scope returns unknown when no dot`() {
        val field = SimpleConfigField(path = "nosetting")
        assertEquals("unknown", field.scope)
    }

    @Test
    fun `unavailableReason returns null by default`() {
        val field = SimpleConfigField(path = "x")
        assertEquals(null, field.unavailableReason)
    }

    @Test
    fun `UnavailableField delegates path`() {
        val delegate = SimpleConfigField(path = "test.path")
        val field = UnavailableField(delegate, "reason")
        assertEquals("test.path", field.path)
    }

    @Test
    fun `UnavailableField delegates displayName`() {
        val delegate = SimpleConfigField(path = "a", displayName = "My Name")
        val field = UnavailableField(delegate, "reason")
        assertEquals("My Name", field.displayName)
    }

    @Test
    fun `UnavailableField delegates description`() {
        val delegate = SimpleConfigField(path = "a", description = "Some desc")
        val field = UnavailableField(delegate, "reason")
        assertEquals("Some desc", field.description)
    }

    @Test
    fun `UnavailableField delegates valueSchema`() {
        val schema = object : ConfigSchema {}
        val delegate = SimpleConfigField(path = "a", valueSchema = schema)
        val field = UnavailableField(delegate, "reason")
        assertEquals(schema, field.valueSchema)
    }

    @Test
    fun `UnavailableField delegates access`() {
        val access = object : ConfigAccess {}
        val delegate = SimpleConfigField(path = "a", access = access)
        val field = UnavailableField(delegate, "reason")
        assertEquals(access, field.access)
    }

    @Test
    fun `UnavailableField delegates risk`() {
        val risk = object : ConfigRisk {}
        val delegate = SimpleConfigField(path = "a", risk = risk)
        val field = UnavailableField(delegate, "reason")
        assertEquals(risk, field.risk)
    }

    @Test
    fun `UnavailableField delegates revertable`() {
        val delegate = SimpleConfigField(path = "a", revertable = true)
        val field = UnavailableField(delegate, "reason")
        assertEquals(true, field.revertable)
    }

    @Test
    fun `UnavailableField delegates read`() {
        val value = object : ConfigValue {}
        val delegate = SimpleConfigField(path = "a", storedValue = value)
        val field = UnavailableField(delegate, "reason")
        assertEquals(value, field.read())
    }

    @Test
    fun `UnavailableField delegates write`() {
        val delegate = SimpleConfigField(path = "a")
        val field = UnavailableField(delegate, "reason")
        val newValue = object : ConfigValue {}
        field.write(newValue)
        assertEquals(newValue, delegate.read())
    }

    @Test
    fun `UnavailableField delegates scope`() {
        val delegate = SimpleConfigField(path = "my.scope.value")
        val field = UnavailableField(delegate, "reason")
        assertEquals("my", field.scope)
    }

    @Test
    fun `UnavailableField overrides unavailableReason`() {
        val delegate = SimpleConfigField(path = "a")
        val field = UnavailableField(delegate, "custom reason")
        assertEquals("custom reason", field.unavailableReason)
    }

    @Test
    fun `UnavailableField does not use delegate's unavailableReason`() {
        val delegate = SimpleConfigField(path = "a")
        val field = UnavailableField(delegate, "override")
        assertEquals("override", field.unavailableReason)
        assertEquals(null, delegate.unavailableReason)
    }
}