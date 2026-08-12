package com.openminis.app.config

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigValueTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testBoolDisplayString() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(text = ConfigValue.Bool(true).displayString)
        }
        composeTestRule.onNodeWithText("true").assertExists()
    }

    @Test
    fun testBoolDefault() {
        val value = ConfigValue.Bool(false)
        assertEquals(false, value.value)
    }

    @Test
    fun testBoolClick() {
        var clicked = false
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { clicked = true }) {
                androidx.compose.material3.Text(text = ConfigValue.Bool(true).displayString)
            }
        }
        composeTestRule.onNodeWithText("true").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testIntDisplayString() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(text = ConfigValue.Int(42).displayString)
        }
        composeTestRule.onNodeWithText("42").assertExists()
    }

    @Test
    fun testIntDefault() {
        val value = ConfigValue.Int(0)
        assertEquals(0, value.value)
    }

    @Test
    fun testIntClick() {
        var clicked = false
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { clicked = true }) {
                androidx.compose.material3.Text(text = ConfigValue.Int(99).displayString)
            }
        }
        composeTestRule.onNodeWithText("99").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testDoubleDisplayString() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(text = ConfigValue.Double(3.14).displayString)
        }
        composeTestRule.onNodeWithText("3.14").assertExists()
    }

    @Test
    fun testDoubleDefault() {
        val value = ConfigValue.Double(0.0)
        assertEquals(0.0, value.value)
    }

    @Test
    fun testDoubleClick() {
        var clicked = false
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { clicked = true }) {
                androidx.compose.material3.Text(text = ConfigValue.Double(2.71).displayString)
            }
        }
        composeTestRule.onNodeWithText("2.71").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testStrDisplayString() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(text = ConfigValue.Str("hello").displayString)
        }
        composeTestRule.onNodeWithText("\"hello\"").assertExists()
    }

    @Test
    fun testStrDefault() {
        val value = ConfigValue.Str("")
        assertEquals("", value.value)
    }

    @Test
    fun testStrClick() {
        var clicked = false
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { clicked = true }) {
                androidx.compose.material3.Text(text = ConfigValue.Str("world").displayString)
            }
        }
        composeTestRule.onNodeWithText("\"world\"").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testStrLongDisplay() {
        val longStr = "a".repeat(100)
        val display = ConfigValue.Str(longStr).displayString
        assertTrue(display.contains("…"))
        assertEquals(78, display.length)
    }

    @Test
    fun testArrDisplayString() {
        val arr = ConfigValue.Arr(listOf(ConfigValue.Int(1), ConfigValue.Int(2)))
        composeTestRule.setContent {
            androidx.compose.material3.Text(text = arr.displayString)
        }
        val display = arr.displayString
        composeTestRule.onNodeWithText(display).assertExists()
    }

    @Test
    fun testArrDefault() {
        val value = ConfigValue.Arr(emptyList())
        assertTrue(value.value.isEmpty())
    }

    @Test
    fun testArrClick() {
        var clicked = false
        val arr = ConfigValue.Arr(listOf(ConfigValue.Int(1)))
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { clicked = true }) {
                androidx.compose.material3.Text(text = arr.displayString)
            }
        }
        composeTestRule.onNodeWithText(arr.displayString).performClick()
        assertTrue(clicked)
    }

    @Test
    fun testObjDisplayString() {
        val obj = ConfigValue.Obj(mapOf("key" to ConfigValue.Str("value")))
        composeTestRule.setContent {
            androidx.compose.material3.Text(text = obj.displayString)
        }
        val display = obj.displayString
        composeTestRule.onNodeWithText(display).assertExists()
    }

    @Test
    fun testObjDefault() {
        val value = ConfigValue.Obj(emptyMap())
        assertTrue(value.value.isEmpty())
    }

    @Test
    fun testObjClick() {
        var clicked = false
        val obj = ConfigValue.Obj(mapOf("key" to ConfigValue.Str("value")))
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { clicked = true }) {
                androidx.compose.material3.Text(text = obj.displayString)
            }
        }
        composeTestRule.onNodeWithText(obj.displayString).performClick()
        assertTrue(clicked)
    }

    @Test
    fun testNullDisplayString() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(text = ConfigValue.Null.displayString)
        }
        composeTestRule.onNodeWithText("null").assertExists()
    }

    @Test
    fun testNullClick() {
        var clicked = false
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { clicked = true }) {
                androidx.compose.material3.Text(text = ConfigValue.Null.displayString)
            }
        }
        composeTestRule.onNodeWithText("null").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testRedactingSecrets() {
        val obj = ConfigValue.Obj(mapOf(
            "apiKey" to ConfigValue.Str("secret123"),
            "name" to ConfigValue.Str("test")
        ))
        val redacted = obj.redactingSecrets()
        val objMap = (redacted as ConfigValue.Obj).value
        assertEquals(ConfigValue.Str("••• (hidden)"), objMap["apiKey"])
        assertEquals(ConfigValue.Str("test"), objMap["name"])
    }

    @Test
    fun testRedactingSecretsNonSecret() {
        val obj = ConfigValue.Obj(mapOf("name" to ConfigValue.Str("test")))
        val redacted = obj.redactingSecrets()
        assertEquals(obj, redacted)
    }

    @Test
    fun testDecodeValidJson() {
        val result = ConfigValue.decode("""{"key": "value"}""")
        assertNotNull(result)
        assertTrue(result is ConfigValue.Obj)
        assertEquals(ConfigValue.Str("value"), (result as ConfigValue.Obj).value["key"])
    }

    @Test
    fun testDecodeInvalidJson() {
        val result = ConfigValue.decode("invalid")
        assertEquals(null, result)
    }

    @Test
    fun testJsonString() {
        val obj = ConfigValue.Obj(mapOf("key" to ConfigValue.Str("value")))
        val json = obj.jsonString()
        assertTrue(json.contains("\"key\""))
        assertTrue(json.contains("\"value\""))
    }

    @Test
    fun testDecodeAllTypes() {
        val json = """{
            "bool": true,
            "int": 42,
            "double": 3.14,
            "str": "hello",
            "arr": [1, 2],
            "obj": {"nested": "value"},
            "null": null
        }"""
        val result = ConfigValue.decode(json)
        assertNotNull(result)
        assertTrue(result is ConfigValue.Obj)
        val map = (result as ConfigValue.Obj).value
        assertEquals(ConfigValue.Bool(true), map["bool"])
        assertEquals(ConfigValue.Int(42), map["int"])
        assertEquals(ConfigValue.Double(3.14), map["double"])
        assertEquals(ConfigValue.Str("hello"), map["str"])
        assertTrue(map["arr"] is ConfigValue.Arr)
        assertTrue(map["obj"] is ConfigValue.Obj)
        assertEquals(ConfigValue.Null, map["null"])
    }
}