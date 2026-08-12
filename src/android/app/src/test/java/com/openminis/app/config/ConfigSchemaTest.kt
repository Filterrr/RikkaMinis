package com.openminis.app.config

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openminis.app.config.ConfigSchema
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigSchemaTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `Bool helpDescription returns bool`() {
        assertEquals("bool", ConfigSchema.Bool.helpDescription)
    }

    @Test
    fun `Int helpDescription with no bounds`() {
        val schema = ConfigSchema.Int()
        assertEquals("int (-∞..+∞)", schema.helpDescription)
    }

    @Test
    fun `Int helpDescription with min and max`() {
        val schema = ConfigSchema.Int(min = 1, max = 10)
        assertEquals("int (1..10)", schema.helpDescription)
    }

    @Test
    fun `Double helpDescription with no bounds`() {
        val schema = ConfigSchema.Double()
        assertEquals("double (-∞..+∞)", schema.helpDescription)
    }

    @Test
    fun `Double helpDescription with min and max`() {
        val schema = ConfigSchema.Double(min = 1.5, max = 10.5)
        assertEquals("double (1.5..10.5)", schema.helpDescription)
    }

    @Test
    fun `Str helpDescription with no constraints`() {
        val schema = ConfigSchema.Str()
        assertEquals("string", schema.helpDescription)
    }

    @Test
    fun `Str helpDescription with maxLength`() {
        val schema = ConfigSchema.Str(maxLength = 10)
        assertEquals("string max 10 chars", schema.helpDescription)
    }

    @Test
    fun `Str helpDescription with regex`() {
        val schema = ConfigSchema.Str(regex = "\\d+")
        assertEquals("string /\\d+/", schema.helpDescription)
    }

    @Test
    fun `Str helpDescription with maxLength and regex`() {
        val schema = ConfigSchema.Str(maxLength = 10, regex = "\\d+")
        assertEquals("string max 10 chars /\\d+/", schema.helpDescription)
    }

    @Test
    fun `StrEnum helpDescription`() {
        val schema = ConfigSchema.StrEnum(listOf("a", "b", "c"))
        assertEquals("one of: a, b, c", schema.helpDescription)
    }

    @Test
    fun `Path helpDescription`() {
        assertEquals("path", ConfigSchema.Path.helpDescription)
    }

    @Test
    fun `Optional helpDescription`() {
        val schema = ConfigSchema.Optional(ConfigSchema.Bool)
        assertEquals("bool?", schema.helpDescription)
    }

    @Test
    fun `Array helpDescription`() {
        val schema = ConfigSchema.Array(ConfigSchema.Bool)
        assertEquals("[bool]", schema.helpDescription)
    }

    @Test
    fun `Json helpDescription`() {
        assertEquals("json", ConfigSchema.Json.helpDescription)
    }

    @Test
    fun `Bool validate accepts Bool value`() {
        ConfigSchema.Bool.validate(ConfigValue.Bool(true))
    }

    @Test
    fun `Bool validate rejects non-Bool value`() {
        assertThrows(ConfigError.TypeMismatch::class.java) {
            ConfigSchema.Bool.validate(ConfigValue.Int(1))
        }
    }

    @Test
    fun `Int validate accepts Int value within range`() {
        ConfigSchema.Int(min = 1, max = 10).validate(ConfigValue.Int(5))
    }

    @Test
    fun `Int validate rejects Int value below min`() {
        assertThrows(ConfigError.OutOfRange::class.java) {
            ConfigSchema.Int(min = 1, max = 10).validate(ConfigValue.Int(0))
        }
    }

    @Test
    fun `Int validate rejects Int value above max`() {
        assertThrows(ConfigError.OutOfRange::class.java) {
            ConfigSchema.Int(min = 1, max = 10).validate(ConfigValue.Int(11))
        }
    }

    @Test
    fun `Int validate rejects non-Int value`() {
        assertThrows(ConfigError.TypeMismatch::class.java) {
            ConfigSchema.Int().validate(ConfigValue.Str("test"))
        }
    }

    @Test
    fun `Double validate accepts Double value within range`() {
        ConfigSchema.Double(min = 1.0, max = 10.0).validate(ConfigValue.Double(5.5))
    }

    @Test
    fun `Double validate accepts Int value within range`() {
        ConfigSchema.Double(min = 1.0, max = 10.0).validate(ConfigValue.Int(5))
    }

    @Test
    fun `Double validate rejects Double value below min`() {
        assertThrows(ConfigError.OutOfRange::class.java) {
            ConfigSchema.Double(min = 1.0, max = 10.0).validate(ConfigValue.Double(0.5))
        }
    }

    @Test
    fun `Double validate rejects Double value above max`() {
        assertThrows(ConfigError.OutOfRange::class.java) {
            ConfigSchema.Double(min = 1.0, max = 10.0).validate(ConfigValue.Double(10.5))
        }
    }

    @Test
    fun `Double validate rejects non-numeric value`() {
        assertThrows(ConfigError.TypeMismatch::class.java) {
            ConfigSchema.Double().validate(ConfigValue.Str("test"))
        }
    }

    @Test
    fun `Str validate accepts Str value within maxLength`() {
        ConfigSchema.Str(maxLength = 10).validate(ConfigValue.Str("hello"))
    }

    @Test
    fun `Str validate rejects Str value exceeding maxLength`() {
        assertThrows(ConfigError.InvalidValue::class.java) {
            ConfigSchema.Str(maxLength = 5).validate(ConfigValue.Str("hello world"))
        }
    }

    @Test
    fun `Str validate accepts Str value matching regex`() {
        ConfigSchema.Str(regex = "\\d+").validate(ConfigValue.Str("123"))
    }

    @Test
    fun `Str validate rejects Str value not matching regex`() {
        assertThrows(ConfigError.RegexMismatch::class.java) {
            ConfigSchema.Str(regex = "\\d+").validate(ConfigValue.Str("abc"))
        }
    }

    @Test
    fun `Str validate rejects non-Str value`() {
        assertThrows(ConfigError.TypeMismatch::class.java) {
            ConfigSchema.Str().validate(ConfigValue.Int(1))
        }
    }

    @Test
    fun `StrEnum validate accepts valid case`() {
        ConfigSchema.StrEnum(listOf("a", "b", "c")).validate(ConfigValue.Str("a"))
    }

    @Test
    fun `StrEnum validate rejects invalid case`() {
        assertThrows(ConfigError.InvalidValue::class.java) {
            ConfigSchema.StrEnum(listOf("a", "b", "c")).validate(ConfigValue.Str("d"))
        }
    }

    @Test
    fun `StrEnum validate rejects non-Str value`() {
        assertThrows(ConfigError.TypeMismatch::class.java) {
            ConfigSchema.StrEnum(listOf("a", "b")).validate(ConfigValue.Int(1))
        }
    }

    @Test
    fun `Path validate accepts Str value`() {
        ConfigSchema.Path.validate(ConfigValue.Str("/tmp"))
    }

    @Test
    fun `Path validate rejects non-Str value`() {
        assertThrows(ConfigError.TypeMismatch::class.java) {
            ConfigSchema.Path.validate(ConfigValue.Int(1))
        }
    }

    @Test
    fun `Optional validate with Null value`() {
        ConfigSchema.Optional(ConfigSchema.Bool).validate(ConfigValue.Null)
    }

    @Test
    fun `Optional validate with non-Null valid value`() {
        ConfigSchema.Optional(ConfigSchema.Bool).validate(ConfigValue.Bool(true))
    }

    @Test
    fun `Optional validate with non-Null invalid value`() {
        assertThrows(ConfigError.TypeMismatch::class.java) {
            ConfigSchema.Optional(ConfigSchema.Bool).validate(ConfigValue.Int(1))
        }
    }

    @Test
    fun `Array validate accepts empty array`() {
        ConfigSchema.Array(ConfigSchema.Bool).validate(ConfigValue.Arr(listOf()))
    }

    @Test
    fun `Array validate accepts array with valid elements`() {
        ConfigSchema.Array(ConfigSchema.Bool).validate(
            ConfigValue.Arr(listOf(ConfigValue.Bool(true), ConfigValue.Bool(false)))
        )
    }

    @Test
    fun `Array validate rejects array with invalid elements`() {
        assertThrows(ConfigError.TypeMismatch::class.java) {
            ConfigSchema.Array(ConfigSchema.Bool).validate(
                ConfigValue.Arr(listOf(ConfigValue.Bool(true), ConfigValue.Int(1)))
            )
        }
    }

    @Test
    fun `Array validate rejects non-Array value`() {
        assertThrows(ConfigError.TypeMismatch::class.java) {
            ConfigSchema.Array(ConfigSchema.Bool).validate(ConfigValue.Int(1))
        }
    }

    @Test
    fun `Json validate always passes`() {
        ConfigSchema.Json.validate(ConfigValue.Int(1))
        ConfigSchema.Json.validate(ConfigValue.Str("test"))
        ConfigSchema.Json.validate(ConfigValue.Null)
    }

    @Test
    fun `Composable renders Bool help text`() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(ConfigSchema.Bool.helpDescription)
        }
        composeTestRule.onNodeWithText("bool").assertExists()
    }

    @Test
    fun `Composable renders Int help text`() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(ConfigSchema.Int().helpDescription)
        }
        composeTestRule.onNodeWithText("int (-∞..+∞)").assertExists()
    }

    @Test
    fun `Composable renders Double help text`() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(ConfigSchema.Double().helpDescription)
        }
        composeTestRule.onNodeWithText("double (-∞..+∞)").assertExists()
    }

    @Test
    fun `Composable renders Str help text`() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(ConfigSchema.Str().helpDescription)
        }
        composeTestRule.onNodeWithText("string").assertExists()
    }

    @Test
    fun `Composable renders StrEnum help text`() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(ConfigSchema.StrEnum(listOf("a", "b")).helpDescription)
        }
        composeTestRule.onNodeWithText("one of: a, b").assertExists()
    }

    @Test
    fun `Composable renders Path help text`() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(ConfigSchema.Path.helpDescription)
        }
        composeTestRule.onNodeWithText("path").assertExists()
    }

    @Test
    fun `Composable renders Optional help text`() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(ConfigSchema.Optional(ConfigSchema.Bool).helpDescription)
        }
        composeTestRule.onNodeWithText("bool?").assertExists()
    }

    @Test
    fun `Composable renders Array help text`() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(ConfigSchema.Array(ConfigSchema.Bool).helpDescription)
        }
        composeTestRule.onNodeWithText("[bool]").assertExists()
    }

    @Test
    fun `Composable renders Json help text`() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(ConfigSchema.Json.helpDescription)
        }
        composeTestRule.onNodeWithText("json").assertExists()
    }

    @Test
    fun `Composable click event on Bool help text`() {
        var clicked = false
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { clicked = true }) {
                androidx.compose.material3.Text(ConfigSchema.Bool.helpDescription)
            }
        }
        composeTestRule.onNodeWithText("bool").performClick()
        assertTrue(clicked)
    }

    @Test
    fun `Composable click event on Int help text`() {
        var clicked = false
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { clicked = true }) {
                androidx.compose.material3.Text(ConfigSchema.Int().helpDescription)
            }
        }
        composeTestRule.onNodeWithText("int (-∞..+∞)").performClick()
        assertTrue(clicked)
    }
}