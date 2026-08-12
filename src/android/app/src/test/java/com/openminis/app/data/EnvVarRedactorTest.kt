package com.openminis.app.data

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openminis.app.data.repository.EnvVarRepository
import org.junit.Rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class EnvVarRedactorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @BeforeEach
    fun setUp() {
        EnvVarRedactor.envVarRepository = null
        EnvVarPrivacyStore.isEnabled = false
    }

    @Test
    fun `mask should render correctly with default parameters`() {
        composeTestRule.setContent {
            // Test mask function directly
            val result = EnvVarRedactor.mask("testvalue123")
            assertNotNull(result)
            assertTrue(result.length == 13)
            assertTrue(result.startsWith("te"))
            assertTrue(result.endsWith("23"))
        }
    }

    @Test
    fun `mask should handle short values`() {
        composeTestRule.setContent {
            val result = EnvVarRedactor.mask("short")
            assertNotNull(result)
            assertEquals("*****", result)
        }
    }

    @Test
    fun `redact should render correctly with default parameters`() {
        composeTestRule.setContent {
            val (masked, hits) = EnvVarRedactor.redact("Hello secret123 world", listOf("secret123"))
            assertNotNull(masked)
            assertEquals(1, hits)
            assertTrue(masked.contains("se****23"))
        }
    }

    @Test
    fun `redact should handle no matches`() {
        composeTestRule.setContent {
            val (masked, hits) = EnvVarRedactor.redact("No secrets here", listOf("secret123"))
            assertNotNull(masked)
            assertEquals(0, hits)
            assertEquals("No secrets here", masked)
        }
    }

    @Test
    fun `redact should handle empty candidates`() {
        composeTestRule.setContent {
            val (masked, hits) = EnvVarRedactor.redact("Some output", emptyList())
            assertNotNull(masked)
            assertEquals(0, hits)
            assertEquals("Some output", masked)
        }
    }

    @Test
    fun `redactIfEnabled should not redact when disabled`() {
        composeTestRule.setContent {
            EnvVarPrivacyStore.isEnabled = false
            val (masked, hits) = EnvVarRedactor.redactIfEnabled("Some output with secret")
            assertNotNull(masked)
            assertEquals(0, hits)
            assertEquals("Some output with secret", masked)
        }
    }

    @Test
    fun `redactIfEnabled should not redact when repository is null`() {
        composeTestRule.setContent {
            EnvVarPrivacyStore.isEnabled = true
            EnvVarRedactor.envVarRepository = null
            val (masked, hits) = EnvVarRedactor.redactIfEnabled("Some output with secret")
            assertNotNull(masked)
            assertEquals(0, hits)
            assertEquals("Some output with secret", masked)
        }
    }

    @Test
    fun `redactIfEnabled should redact and add reminder`() {
        composeTestRule.setContent {
            EnvVarPrivacyStore.isEnabled = true
            val fakeRepository = object : EnvVarRepository {
                override fun allAsDict(): Map<String, String> = mapOf("API_KEY" to "secret12345")
            }
            EnvVarRedactor.envVarRepository = fakeRepository
            val (masked, hits) = EnvVarRedactor.redactIfEnabled("Output with secret12345 value")
            assertNotNull(masked)
            assertEquals(1, hits)
            assertTrue(masked.contains("se****45"))
            assertTrue(masked.contains(EnvVarRedactor.SYSTEM_REMINDER))
        }
    }

    @Test
    fun `redactIfEnabled should handle empty repository values`() {
        composeTestRule.setContent {
            EnvVarPrivacyStore.isEnabled = true
            val fakeRepository = object : EnvVarRepository {
                override fun allAsDict(): Map<String, String> = emptyMap()
            }
            EnvVarRedactor.envVarRepository = fakeRepository
            val (masked, hits) = EnvVarRedactor.redactIfEnabled("Output with secret value")
            assertNotNull(masked)
            assertEquals(0, hits)
            assertEquals("Output with secret value", masked)
        }
    }

    @Test
    fun `redactIfEnabled should handle no hits`() {
        composeTestRule.setContent {
            EnvVarPrivacyStore.isEnabled = true
            val fakeRepository = object : EnvVarRepository {
                override fun allAsDict(): Map<String, String> = mapOf("API_KEY" to "secret12345")
            }
            EnvVarRedactor.envVarRepository = fakeRepository
            val (masked, hits) = EnvVarRedactor.redactIfEnabled("Output without any secrets")
            assertNotNull(masked)
            assertEquals(0, hits)
            assertEquals("Output without any secrets", masked)
        }
    }

    @Test
    fun `system reminder should render correctly`() {
        composeTestRule.setContent {
            val reminder = EnvVarRedactor.SYSTEM_REMINDER
            assertNotNull(reminder)
            assertTrue(reminder.contains("Privacy mode is ON"))
            assertTrue(reminder.contains("system-reminder"))
        }
    }

    @Test
    fun `min match length constant should be correct`() {
        composeTestRule.setContent {
            assertEquals(5, EnvVarRedactor.MIN_MATCH_LEN)
        }
    }
}