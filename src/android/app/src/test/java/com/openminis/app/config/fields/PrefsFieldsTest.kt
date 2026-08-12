package com.openminis.app.config.fields

import android.content.SharedPreferences
import com.openminis.app.config.ConfigAccess
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times

class PrefsFieldsTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @BeforeEach
    fun setUp() {
        prefs = mock()
        editor = mock()
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor)
        `when`(editor.putInt(anyString(), anyInt())).thenReturn(editor)
        `when`(editor.putLong(anyString(), anyLong())).thenReturn(editor)
        `when`(editor.putFloat(anyString(), Mockito.anyFloat())).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.putString(anyString(), null)).thenReturn(editor)
    }

    // ---------- PrefsBoolField ----------

    @Test
    fun `PrefsBoolField properties`() {
        val field = PrefsBoolField(
            path = "p.bool",
            displayName = "Bool",
            description = "desc",
            prefs = prefs,
            key = "k",
            defaultValue = true,
            risk = ConfigRisk.HIGH,
        )
        assertEquals("p.bool", field.path)
        assertEquals("Bool", field.displayName)
        assertEquals("desc", field.description)
        assertEquals(ConfigRisk.HIGH, field.risk)
        assertEquals(ConfigSchema.Bool, field.valueSchema)
        assertEquals(ConfigAccess.READWRITE, field.access)
        assertTrue(field.revertable)
    }

    @Test
    fun `PrefsBoolField read returns default when key absent`() {
        `when`(prefs.contains("k")).thenReturn(false)
        val field = PrefsBoolField("p", "n", "d", prefs, "k", false)
        assertEquals(ConfigValue.Bool(false), field.read())
    }

    @Test
    fun `PrefsBoolField read returns stored value when key present`() {
        `when`(prefs.contains("k")).thenReturn(true)
        `when`(prefs.getBoolean("k", false)).thenReturn(true)
        val field = PrefsBoolField("p", "n", "d", prefs, "k", false)
        assertEquals(ConfigValue.Bool(true), field.read())
    }

    @Test
    fun `PrefsBoolField write stores boolean`() {
        val field = PrefsBoolField("p", "n", "d", prefs, "k", false)
        field.write(ConfigValue.Bool(true))
        verify(editor, times(1)).putBoolean("k", true)
        verify(editor, times(1)).apply()
    }

    @Test
    fun `PrefsBoolField write rejects non-bool value`() {
        val field = PrefsBoolField("p", "n", "d", prefs, "k", false)
        assertThrows(ConfigError::class.java) {
            field.write(ConfigValue.Int(1))
        }
        verify(editor, never()).putBoolean(anyString(), anyBoolean())
    }

    // ---------- PrefsIntField ----------

    @Test
    fun `PrefsIntField properties`() {
        val field = PrefsIntField(
            path = "p.int",
            displayName = "Int",
            description = "desc",
            prefs = prefs,
            key = "k",
            defaultValue = 5,
            minValue = 0,
            maxValue = 10,
            risk = ConfigRisk.NORMAL,
        )
        assertEquals(ConfigSchema.Int(min = 0, max = 10), field.valueSchema)
        assertEquals(ConfigAccess.READWRITE, field.access)
        assertTrue(field.revertable)
        assertEquals(ConfigRisk.NORMAL, field.risk)
    }

    @Test
    fun `PrefsIntField read returns default when key absent`() {
        `when`(prefs.contains("k")).thenReturn(false)
        val field = PrefsIntField("p", "n", "d", prefs, "k", 42)
        assertEquals(ConfigValue.Int(42), field.read())
    }

    @Test
    fun `PrefsIntField read returns stored value when key present`() {
        `when`(prefs.contains("k")).thenReturn(true)
        `when`(prefs.getInt("k", 42)).thenReturn(7)
        val field = PrefsIntField("p", "n", "d", prefs, "k", 42)
        assertEquals(ConfigValue.Int(7), field.read())
    }

    @Test
    fun `PrefsIntField write stores int`() {
        val field = PrefsIntField("p", "n", "d", prefs, "k", 0, minValue = 0, maxValue = 10)
        field.write(ConfigValue.Int(5))
        verify(editor, times(1)).putInt("k", 5)
        verify(editor, times(1)).apply()
    }

    @Test
    fun `PrefsIntField write rejects out-of-range value`() {
        val field = PrefsIntField("p", "n", "d", prefs, "k", 0, minValue = 0, maxValue = 10)
        assertThrows(ConfigError::class.java) {
            field.write(ConfigValue.Int(100))
        }
        verify(editor, never()).putInt(anyString(), anyInt())
    }

    @Test
    fun `PrefsIntField write rejects non-int value`() {
        val field = PrefsIntField("p", "n", "d", prefs, "k", 0)
        assertThrows(ConfigError::class.java) {
            field.write(ConfigValue.Bool(true))
        }
    }

    // ---------- PrefsLongField ----------

    @Test
    fun `PrefsLongField properties`() {
        val field = PrefsLongField(
            path = "p.long",
            displayName = "Long",
            description = "desc",
            prefs = prefs,
            key = "k",
            defaultValue = 100L,
            minValue = 0L,
            maxValue = 1000L,
        )
        assertEquals(ConfigSchema.Int(min = 0, max = 1000), field.valueSchema)
        assertEquals(ConfigAccess.READWRITE, field.access)
        assertTrue(field.revertable)
    }

    @Test
    fun `PrefsLongField read returns default when key absent`() {
        `when`(prefs.contains("k")).thenReturn(false)
        val field = PrefsLongField("p", "n", "d", prefs, "k", 100L)
        assertEquals(ConfigValue.Int(100), field.read())
    }

    @Test
    fun `PrefsLongField read returns stored value when key present`() {
        `when`(prefs.contains("k")).thenReturn(true)
        `when`(prefs.getLong("k", 100L)).thenReturn(555L)
        val field = PrefsLongField("p", "n", "d", prefs, "k", 100L)
        assertEquals(ConfigValue.Int(555), field.read())
    }

    @Test
    fun `PrefsLongField write stores long`() {
        val field = PrefsLongField("p", "n", "d", prefs, "k", 0L, minValue = 0L, maxValue = 1000L)
        field.write(ConfigValue.Int(42))
        verify(editor, times(1)).putLong("k", 42L)
        verify(editor, times(1)).apply()
    }

    @Test
    fun `PrefsLongField write rejects out-of-range value`() {
        val field = PrefsLongField("p", "n", "d", prefs, "k", 0L, minValue = 0L, maxValue = 10L)
        assertThrows(ConfigError::class.java) {
            field.write(ConfigValue.Int(100))
        }
        verify(editor, never()).putLong(anyString(), anyLong())
    }

    @Test
    fun `PrefsLongField write rejects non-int value`() {
        val field = PrefsLongField("p", "n", "d", prefs, "k", 0L)
        assertThrows(ConfigError::class.java) {
            field.write(ConfigValue.Bool(true))
        }
    }

    // ---------- PrefsDoubleField ----------

    @Test
    fun `PrefsDoubleField properties`() {
        val field = PrefsDoubleField(
            path = "p.double",
            displayName = "Double",
            description = "desc",
            prefs = prefs,
            key = "k",
            defaultValue = 1.5,
            minValue = 0.0,
            maxValue = 10.0,
        )
        assertEquals(ConfigSchema.Double(min = 0.0, max = 10.0), field.valueSchema)
        assertEquals(ConfigAccess.READWRITE, field.access)
        assertTrue(field.revertable)
    }

    @Test
    fun `PrefsDoubleField read returns default when key absent`() {
        `when`(prefs.contains("k")).thenReturn(false)
        val field = PrefsDoubleField("p", "n", "d", prefs, "k", 2.5)
        assertEquals(ConfigValue.Double(2.5), field.read())
    }

    @Test
    fun `PrefsDoubleField read returns stored value when key present`() {
        `when`(prefs.contains("k")).thenReturn(true)
        `when`(prefs.getFloat("k", 1.0f)).thenReturn(3.14f)
        val field = PrefsDoubleField("p", "n", "d", prefs, "k", 1.0)
        assertEquals(ConfigValue.Double(3.14f.toDouble()), field.read())
    }

    @Test
    fun `PrefsDoubleField write stores float from Double value`() {
        val field = PrefsDoubleField("p", "n", "d", prefs, "k", 0.0, minValue = 0.0, maxValue = 10.0)
        field.write(ConfigValue.Double(5.5))
        verify(editor, times(1)).putFloat("k", 5.5f)
        verify(editor, times(1)).apply()
    }

    @Test
    fun `PrefsDoubleField write stores float from Int value`() {
        val field = PrefsDoubleField("p", "n", "d", prefs, "k", 0.0, minValue = 0.0, maxValue = 10.0)
        field.write(ConfigValue.Int(5))
        verify(editor, times(1)).putFloat("k", 5.0f)
        verify(editor, times(1)).apply()
    }

    @Test
    fun `PrefsDoubleField write rejects out-of-range value`() {
        val field = PrefsDoubleField("p", "n", "d", prefs, "k", 0.0, minValue = 0.0, maxValue = 1.0)
        assertThrows(ConfigError::class.java) {
            field.write(ConfigValue.Double(5.0))
        }
        verify(editor, never()).putFloat(anyString(), Mockito.anyFloat())
    }

    @Test
    fun `PrefsDoubleField write rejects unsupported value type`() {
        val field = PrefsDoubleField("p", "n", "d", prefs, "k", 0.0, minValue = 0.0, maxValue = 10.0)
        assertThrows(ConfigError.TypeMismatch::class.java) {
            field.write(ConfigValue.Bool(true))
        }
    }

    // ---------- PrefsStringField ----------

    @Test
    fun `PrefsStringField properties`() {
        val field = PrefsStringField(
            path = "p.str",
            displayName = "Str",
            description = "desc",
            prefs = prefs,
            key = "k",
            defaultValue = "x",
            maxLength = 10,
            regex = "[a-z]+",
        )
        assertEquals(ConfigSchema.Str(maxLength = 10, regex = "[a-z]+"), field.valueSchema)
        assertEquals(ConfigAccess.READWRITE, field.access)
        assertTrue(field.revertable)
    }

    @Test
    fun `PrefsStringField read returns default when key absent`() {
        `when`(prefs.getString("k", null)).thenReturn(null)
        val field = PrefsStringField("p", "n", "d", prefs, "k", "default")
        assertEquals(ConfigValue.Str("default"), field.read())
    }

    @Test
    fun `PrefsStringField read returns stored value when key present`() {
        `when`(prefs.getString("k", null)).thenReturn("stored")
        val field = PrefsStringField("p", "n", "d", prefs, "k", "default")
        assertEquals(ConfigValue.Str("stored"), field.read())
    }

    @Test
    fun `PrefsStringField write stores string`() {
        val field = PrefsStringField("p", "n", "d", prefs, "k", "default", maxLength = 10, regex = "[a-z]+")
        field.write(ConfigValue.Str("hello"))
        verify(editor, times(1)).putString("k", "hello")
        verify(editor, times(1)).apply()
    }

    @Test
    fun `PrefsStringField write rejects too long value`() {
        val field = PrefsStringField("p", "n", "d", prefs, "k", "default", maxLength = 3)
        assertThrows(ConfigError::class.java) {
            field.write(ConfigValue.Str("toolong"))
        }
        verify(editor, never()).putString(anyString(), anyString())
    }

    @Test
    fun `PrefsStringField write rejects non-matching regex`() {
        val field = PrefsStringField("p", "n", "d", prefs, "k", "default", regex = "[a-z]+")
        assertThrows(ConfigError::class.java) {
            field.write(ConfigValue.Str("ABC123"))
        }
    }

    @Test
    fun `PrefsStringField write rejects non-string value`() {
        val field = PrefsStringField("p", "n", "d", prefs, "k", "default")
        assertThrows(ConfigError::class.java) {
            field.write(ConfigValue.Int(1))
        }
    }

    // ---------- PrefsEnumField ----------

    @Test
    fun `PrefsEnumField properties`() {
        val cases = listOf("a", "b", "c")
        val field = PrefsEnumField(
            path = "p.enum",
            displayName = "Enum",
            description = "desc",
            prefs = prefs,
            key = "k",
            cases = cases,
            defaultValue = "a",
        )
        assertEquals(ConfigSchema.StrEnum(cases), field.valueSchema)
        assertEquals(ConfigAccess.READWRITE, field.access)
        assertTrue(field.revertable)
    }

    @Test
    fun `PrefsEnumField read returns default when key absent`() {
        `when`(prefs.getString("k", null)).thenReturn(null)
        val field = PrefsEnumField("p", "n", "d", prefs, "k", listOf("a", "b"), "a")
        assertEquals(ConfigValue.Str("a"), field.read())
    }

    @Test
    fun `PrefsEnumField read returns stored value when in cases`() {
        `when`(prefs.getString("k", null)).thenReturn("b")
        val field = PrefsEnumField("p", "n", "d", prefs, "k", listOf("a", "b"), "a")
        assertEquals(ConfigValue.Str("b"), field.read())
    }

    @Test
    fun `PrefsEnumField read returns default when stored value not in cases`() {
        `when`(prefs.getString("k", null)).thenReturn("zzz")
        val field = PrefsEnumField("p", "n", "d", prefs, "k", listOf("a", "b"), "a")
        assertEquals(ConfigValue.Str("a"), field.read())
    }

    @Test
    fun `PrefsEnumField write stores string`() {
        val field = PrefsEnumField("p", "n", "d", prefs, "k", listOf("a", "b"), "a")
        field.write(ConfigValue.Str("b"))
        verify(editor, times(1)).putString("k", "b")
        verify(editor, times(1)).apply()
    }

    @Test
    fun `PrefsEnumField write rejects value not in cases`() {
        val field = PrefsEnumField("p", "n", "d", prefs, "k", listOf("a", "b"), "a")
        assertThrows(ConfigError::class.java) {
            field.write(ConfigValue.Str("zzz"))
        }
        verify(editor, never()).putString(anyString(), anyString())
    }

    @Test
    fun `PrefsEnumField write rejects non-string value`() {
        val field = PrefsEnumField("p", "n", "d", prefs, "k", listOf("a", "b"), "a")
        assertThrows(ConfigError::class.java) {
            field.write(ConfigValue.Int(0))
        }
    }

    // ---------- PrefsIntCodedEnumField ----------

    @Test
    fun `PrefsIntCodedEnumField properties`() {
        val cases = listOf("x", "y", "z")
        val field = PrefsIntCodedEnumField(
            path = "p.icode",
            displayName = "ICode",
            description = "desc",
            prefs = prefs,
            key = "k",
            cases = cases,
            defaultIndex = 0,
        )
        assertEquals(ConfigSchema.StrEnum(cases), field.valueSchema)
        assertEquals(ConfigAccess.READWRITE, field.access)
        assertTrue(field.revertable)
    }

    @Test
    fun `PrefsIntCodedEnumField read returns default when key absent`() {
        `when`(prefs.contains("k")).thenReturn(false)
        val field = PrefsIntCodedEnumField("p", "n", "d", prefs, "k", listOf("a", "b"), 1)
        assertEquals(ConfigValue.Str("b"), field.read())
    }

    @Test
    fun `PrefsIntCodedEnumField read returns stored case when key present`() {
        `when`(prefs.contains("k")).thenReturn(true)
        `when`(prefs.getInt("k", 0)).thenReturn(1)
        val field = PrefsIntCodedEnumField("p", "n", "d", prefs, "k", listOf("a", "b"), 0)
        assertEquals(ConfigValue.Str("b"), field.read())
    }

    @Test
    fun `PrefsIntCodedEnumField read returns default when stored index out of range`() {
        `when`(prefs.contains("k")).thenReturn(true)
        `when`(prefs.getInt("k", 0)).thenReturn(99)
        val field = PrefsIntCodedEnumField("p", "n", "d", prefs, "k", listOf("a", "b"), 0)
        assertEquals(ConfigValue.Str("a"), field.read())
    }

    @Test
    fun `PrefsIntCodedEnumField read returns default when stored index negative`() {
        `when`(prefs.contains("k")).thenReturn(true)
        `when`(prefs.getInt("k", 0)).thenReturn(-1)
        val field = PrefsIntCodedEnumField("p", "n", "d", prefs, "k", listOf("a", "b"), 1)
        assertEquals(ConfigValue.Str("b"), field.read())
    }

    @Test
    fun `PrefsIntCodedEnumField write stores index`() {
        val field = PrefsIntCodedEnumField("p", "n", "d", prefs, "k", listOf("a", "b", "c"), 0)
        field.write(ConfigValue.Str("c"))
        verify(editor, times(1)).putInt("k", 2)
        verify(editor, times(1)).apply()
    }

    @Test
    fun `PrefsIntCodedEnumField write rejects value not in cases`() {
        val field = PrefsIntCodedEnumField("p", "n", "d", prefs, "k", listOf("a", "b"), 0)
        assertThrows(ConfigError.InvalidValue::class.java) {
            field.write(ConfigValue.Str("zzz"))
        }
        verify(editor, never()).putInt(anyString(), anyInt())
    }

    @Test
    fun `PrefsIntCodedEnumField write rejects non-string value`() {
        val field = PrefsIntCodedEnumField("p", "n", "d", prefs, "k", listOf("a", "b"), 0)
        assertThrows(ConfigError::class.java) {
            field.write(ConfigValue.Int(0))
        }
    }
}