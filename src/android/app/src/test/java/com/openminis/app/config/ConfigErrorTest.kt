package com.openminis.app.config

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.jupiter.api.Test
import org.junit.Rule

class ConfigErrorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testTypeMismatchMessage() {
        val error = ConfigError.TypeMismatch("String")
        assert(error.message == "type_mismatch: expected String")
    }

    @Test
    fun testInvalidValueMessage() {
        val error = ConfigError.InvalidValue("Invalid input")
        assert(error.message == "invalid_value: Invalid input")
    }

    @Test
    fun testOutOfRangeWithBothBounds() {
        val error = ConfigError.OutOfRange(1.0, 10.0)
        assert(error.message == "out_of_range: min=1.0, max=10.0")
    }

    @Test
    fun testOutOfRangeWithMinOnly() {
        val error = ConfigError.OutOfRange(1.0, null)
        assert(error.message == "out_of_range: min=1.0")
    }

    @Test
    fun testOutOfRangeWithMaxOnly() {
        val error = ConfigError.OutOfRange(null, 10.0)
        assert(error.message == "out_of_range: max=10.0")
    }

    @Test
    fun testOutOfRangeWithNoBounds() {
        val error = ConfigError.OutOfRange(null, null)
        assert(error.message == "out_of_range: ")
    }

    @Test
    fun testRegexMismatchMessage() {
        val error = ConfigError.RegexMismatch("[a-z]+")
        assert(error.message == "regex_mismatch: must match /[a-z]+/")
    }

    @Test
    fun testPermissionDeniedWithDefaultReason() {
        val error = ConfigError.PermissionDenied()
        assert(error.message == "permission_denied: Hidden from minis-config")
    }

    @Test
    fun testPermissionDeniedWithCustomReason() {
        val error = ConfigError.PermissionDenied("Access denied")
        assert(error.message == "permission_denied: Access denied")
    }

    @Test
    fun testUnknownPathMessage() {
        val error = ConfigError.UnknownPath("/config/param")
        assert(error.message == "unknown_path: /config/param")
    }

    @Test
    fun testAlreadyExistsMessage() {
        val error = ConfigError.AlreadyExists("Duplicate key")
        assert(error.message == "already_exists: Duplicate key")
    }

    @Test
    fun testIoErrorMessage() {
        val error = ConfigError.IoError("File not found")
        assert(error.message == "io_error: File not found")
    }

    @Test
    fun testConfigErrorDisplay() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(
                text = ConfigError.TypeMismatch("String").message ?: ""
            )
        }
        composeTestRule.onNodeWithText("type_mismatch: expected String").assertIsDisplayed()
    }

    @Test
    fun testConfigErrorClick() {
        var clicked = false
        composeTestRule.setContent {
            androidx.compose.material3.Button(
                onClick = { clicked = true }
            ) {
                androidx.compose.material3.Text(
                    text = ConfigError.InvalidValue("test").message ?: ""
                )
            }
        }
        composeTestRule.onNodeWithText("invalid_value: test").performClick()
        assert(clicked)
    }
}