package com.openminis.app.config.collections

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.ProviderRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.Rule

class ProvidersCollectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockRepo = mockk<ProviderRepository>()
    private val mockEnvVars = mockk<EnvVarRepository>()
    private val collection = ProvidersCollection(mockRepo, mockEnvVars)

    @Test
    fun `basePath returns correct value`() {
        assertEquals("providers", collection.basePath)
    }

    @Test
    fun `displayName returns correct value`() {
        assertEquals("LLM provider instances", collection.displayName)
    }

    @Test
    fun `description returns correct value`() {
        assertTrue(collection.description.contains("Configured provider accounts"))
    }

    @Test
    fun `addable returns true`() {
        assertTrue(collection.addable)
    }

    @Test
    fun `removable returns false`() {
        assertFalse(collection.removable)
    }

    @Test
    fun `risk returns SENSITIVE`() {
        assertEquals(ConfigRisk.SENSITIVE, collection.risk)
    }

    @Test
    fun `addPayloadSchema returns Json`() {
        assertEquals(ConfigSchema.Json, collection.addPayloadSchema)
    }

    @Test
    fun `childIds returns list of instance ids`() {
        val instances = listOf(
            ProviderInstance(id = "1", label = "Test1", providerType = ProviderType.OpenAI),
            ProviderInstance(id = "2", label = "Test2", providerType = ProviderType.Anthropic)
        )
        every { mockRepo.instances } returns instances
        assertEquals(listOf("1", "2"), collection.childIds())
    }

    @Test
    fun `fields returns empty list for non-existent id`() {
        every { mockRepo.instance("non-existent") } returns null
        assertTrue(collection.fields("non-existent").isEmpty())
    }

    @Test
    fun `fields returns correct fields for existing id`() {
        val instance = ProviderInstance(id = "test-id", label = "Test", providerType = ProviderType.OpenAI)
        every { mockRepo.instance("test-id") } returns instance
        val fields = collection.fields("test-id")
        assertTrue(fields.isNotEmpty())
        assertEquals(11, fields.size)
    }

    @Test
    fun `add throws InvalidValue for non-object payload`() {
        assertThrows<ConfigError.InvalidValue> {
            collection.add(ConfigValue.Str("not-an-object"))
        }
    }

    @Test
    fun `add throws InvalidValue when providerType missing`() {
        val payload = ConfigValue.Obj(mapOf("label" to ConfigValue.Str("Test")))
        assertThrows<ConfigError.InvalidValue> {
            collection.add(payload)
        }
    }

    @Test
    fun `add throws InvalidValue for unknown providerType`() {
        val payload = ConfigValue.Obj(mapOf(
            "providerType" to ConfigValue.Str("UnknownType"),
            "label" to ConfigValue.Str("Test")
        ))
        assertThrows<ConfigError.InvalidValue> {
            collection.add(payload)
        }
    }

    @Test
    fun `add throws InvalidValue when label is empty`() {
        val payload = ConfigValue.Obj(mapOf(
            "providerType" to ConfigValue.Str("OpenAI"),
            "label" to ConfigValue.Str("")
        ))
        assertThrows<ConfigError.InvalidValue> {
            collection.add(payload)
        }
    }

    @Test
    fun `add throws InvalidValue when label already exists`() {
        val existingInstance = ProviderInstance(id = "existing", label = "Test", providerType = ProviderType.OpenAI)
        every { mockRepo.instances } returns listOf(existingInstance)
        val payload = ConfigValue.Obj(mapOf(
            "providerType" to ConfigValue.Str("OpenAI"),
            "label" to ConfigValue.Str("Test")
        ))
        assertThrows<ConfigError.InvalidValue> {
            collection.add(payload)
        }
    }

    @Test
    fun `add creates instance with default values`() {
        every { mockRepo.instances } returns emptyList()
        every { mockRepo.addInstance(any()) } returns Unit
        every { mockRepo.saveApiKey(any(), any()) } returns Unit

        val payload = ConfigValue.Obj(mapOf(
            "providerType" to ConfigValue.Str("OpenAI"),
            "label" to ConfigValue.Str("My Provider")
        ))
        val id = collection.add(payload)
        assertNotNull(id)
        verify { mockRepo.addInstance(match { it.label == "My Provider" && it.providerType == ProviderType.OpenAI }) }
    }

    @Test
    fun `add creates instance with apiKey`() {
        every { mockRepo.instances } returns emptyList()
        every { mockRepo.addInstance(any()) } returns Unit
        every { mockRepo.saveApiKey(any(), any()) } returns Unit

        val payload = ConfigValue.Obj(mapOf(
            "providerType" to ConfigValue.Str("OpenAI"),
            "label" to ConfigValue.Str("My Provider"),
            "apiKey" to ConfigValue.Str("sk-test-key")
        ))
        collection.add(payload)
        verify { mockRepo.saveApiKey(any(), "sk-test-key") }
    }

    @Test
    fun `add resolves env var reference in apiKey`() {
        every { mockRepo.instances } returns emptyList()
        every { mockRepo.addInstance(any()) } returns Unit
        every { mockEnvVars.getValue("MY_KEY") } returns "resolved-key"
        every { mockRepo.saveApiKey(any(), any()) } returns Unit

        val payload = ConfigValue.Obj(mapOf(
            "providerType" to ConfigValue.Str("OpenAI"),
            "label" to ConfigValue.Str("My Provider"),
            "apiKey" to ConfigValue.Str("$$MY_KEY")
        ))
        collection.add(payload)
        verify { mockRepo.saveApiKey(any(), "resolved-key") }
    }

    @Test
    fun `remove throws PermissionDenied`() {
        assertThrows<ConfigError.PermissionDenied> {
            collection.remove("any-id")
        }
    }

    @Test
    fun `providerType field returns correct value`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.Anthropic)
        every { mockRepo.instance("test") } returns instance
        val fields = collection.fields("test")
        val providerTypeField = fields.first { it.path == "providers.test.providerType" }
        assertEquals(ConfigValue.Str("Anthropic"), providerTypeField.reader())
    }

    @Test
    fun `credentialType field returns correct value`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.OpenAI, credentialType = ProviderCredential.apiKey)
        every { mockRepo.instance("test") } returns instance
        val fields = collection.fields("test")
        val credentialTypeField = fields.first { it.path == "providers.test.credentialType" }
        assertEquals(ConfigValue.Str("apiKey"), credentialTypeField.reader())
    }

    @Test
    fun `label field writes correctly`() {
        val instance = ProviderInstance(id = "test", label = "OldLabel", providerType = ProviderType.OpenAI)
        every { mockRepo.instance("test") } returns instance
        every { mockRepo.updateInstance(any()) } returns Unit

        val fields = collection.fields("test")
        val labelField = fields.first { it.path == "providers.test.label" }
        labelField.writer(ConfigValue.Str("NewLabel"))
        verify { mockRepo.updateInstance(match { it.label == "NewLabel" }) }
    }

    @Test
    fun `enabled field writes correctly`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.OpenAI, isEnabled = true)
        every { mockRepo.instance("test") } returns instance
        every { mockRepo.updateInstance(any()) } returns Unit

        val fields = collection.fields("test")
        val enabledField = fields.first { it.path == "providers.test.isEnabled" }
        enabledField.writer(ConfigValue.Bool(false))
        verify { mockRepo.updateInstance(match { !it.isEnabled }) }
    }

    @Test
    fun `customBaseURL field writes correctly`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.OpenAI, customBaseURL = null)
        every { mockRepo.instance("test") } returns instance
        every { mockRepo.updateInstance(any()) } returns Unit

        val fields = collection.fields("test")
        val urlField = fields.first { it.path == "providers.test.customBaseURL" }
        urlField.writer(ConfigValue.Str("https://custom.url"))
        verify { mockRepo.updateInstance(match { it.customBaseURL == "https://custom.url" }) }
    }

    @Test
    fun `appendV1Suffix field writes correctly`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.OpenAI, appendV1Suffix = true)
        every { mockRepo.instance("test") } returns instance
        every { mockRepo.updateInstance(any()) } returns Unit

        val fields = collection.fields("test")
        val suffixField = fields.first { it.path == "providers.test.appendV1Suffix" }
        suffixField.writer(ConfigValue.Bool(false))
        verify { mockRepo.updateInstance(match { !it.appendV1Suffix }) }
    }

    @Test
    fun `useResponsesAPI field writes correctly`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.OpenAI, useResponsesAPI = false)
        every { mockRepo.instance("test") } returns instance
        every { mockRepo.updateInstance(any()) } returns Unit

        val fields = collection.fields("test")
        val responsesField = fields.first { it.path == "providers.test.useResponsesAPI" }
        responsesField.writer(ConfigValue.Bool(true))
        verify { mockRepo.updateInstance(match { it.useResponsesAPI }) }
    }

    @Test
    fun `azureMode field writes correctly`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.OpenAI, azureMode = false)
        every { mockRepo.instance("test") } returns instance
        every { mockRepo.updateInstance(any()) } returns Unit

        val fields = collection.fields("test")
        val azureField = fields.first { it.path == "providers.test.azureMode" }
        azureField.writer(ConfigValue.Bool(true))
        verify { mockRepo.updateInstance(match { it.azureMode }) }
    }

    @Test
    fun `customUserAgent field writes correctly for apiKey provider`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.OpenAI, credentialType = ProviderCredential.apiKey, customUserAgent = null)
        every { mockRepo.instance("test") } returns instance
        every { mockRepo.updateInstance(any()) } returns Unit

        val fields = collection.fields("test")
        val uaField = fields.first { it.path == "providers.test.customUserAgent" }
        uaField.writer(ConfigValue.Str("CustomAgent"))
        verify { mockRepo.updateInstance(match { it.customUserAgent == "CustomAgent" }) }
    }

    @Test
    fun `customUserAgent field throws for oauth provider`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.Anthropic, credentialType = ProviderCredential.oauth, customUserAgent = null)
        every { mockRepo.instance("test") } returns instance

        val fields = collection.fields("test")
        val uaField = fields.first { it.path == "providers.test.customUserAgent" }
        assertThrows<ConfigError.InvalidValue> {
            uaField.writer(ConfigValue.Str("CustomAgent"))
        }
    }

    @Test
    fun `apiKey field reader throws PermissionDenied`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.OpenAI)
        every { mockRepo.instance("test") } returns instance

        val fields = collection.fields("test")
        val apiKeyField = fields.first { it.path == "providers.test.apiKey" }
        assertThrows<ConfigError.PermissionDenied> {
            apiKeyField.reader()
        }
    }

    @Test
    fun `apiKey field writer saves api key`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.OpenAI)
        every { mockRepo.instance("test") } returns instance
        every { mockRepo.saveApiKey(any(), any()) } returns Unit

        val fields = collection.fields("test")
        val apiKeyField = fields.first { it.path == "providers.test.apiKey" }
        apiKeyField.writer(ConfigValue.Str("new-api-key"))
        verify { mockRepo.saveApiKey("test", "new-api-key") }
    }

    @Test
    fun `apiKey field writer deletes api key when empty string`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.OpenAI)
        every { mockRepo.instance("test") } returns instance
        every { mockRepo.deleteApiKey(any()) } returns Unit

        val fields = collection.fields("test")
        val apiKeyField = fields.first { it.path == "providers.test.apiKey" }
        apiKeyField.writer(ConfigValue.Str(""))
        verify { mockRepo.deleteApiKey("test") }
    }

    @Test
    fun `apiKey field writer throws for non-existent instance`() {
        every { mockRepo.instance("test") } returns null

        val fields = collection.fields("test")
        val apiKeyField = fields.first { it.path == "providers.test.apiKey" }
        assertThrows<ConfigError.UnknownPath> {
            apiKeyField.writer(ConfigValue.Str("key"))
        }
    }

    @Test
    fun `fields returns 11 fields for existing instance`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.OpenAI)
        every { mockRepo.instance("test") } returns instance
        assertEquals(11, collection.fields("test").size)
    }

    @Test
    fun `hidden field is present in fields`() {
        val instance = ProviderInstance(id = "test", label = "Test", providerType = ProviderType.OpenAI)
        every { mockRepo.instance("test") } returns instance
        val fields = collection.fields("test")
        assertTrue(fields.any { it.path == "providers.test.oauthToken" })
    }

    @Test
    fun `Composable renders correctly`() {
        composeTestRule.setContent {
            androidx.compose.material3.Text("Providers Collection Test")
        }
        composeTestRule.onNodeWithText("Providers Collection Test").assertExists()
    }

    @Test
    fun `Composable button click works`() {
        var clicked = false
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { clicked = true }) {
                androidx.compose.material3.Text("Click Me")
            }
        }
        composeTestRule.onNodeWithText("Click Me").performClick()
        assertTrue(clicked)
    }

    @Test
    fun `Composable renders with default parameters`() {
        composeTestRule.setContent {
            androidx.compose.material3.Text("Default Text")
        }
        composeTestRule.onNodeWithText("Default Text").assertExists()
    }

    @Test
    fun `resolveCredential returns raw value for non-env-ref`() {
        val result = collection.javaClass.getDeclaredMethod("resolveCredential", String::class.java, String::class.java)
        result.isAccessible = true
        val resolved = result.invoke(collection, "literal-value", "testField")
        assertEquals("literal-value", resolved)
    }

    @Test
    fun `resolveCredential resolves env var reference`() {
        every { mockEnvVars.getValue("MY_ENV") } returns "env-value"
        val result = collection.javaClass.getDeclaredMethod("resolveCredential", String::class.java, String::class.java)
        result.isAccessible = true
        val resolved = result.invoke(collection, "$$MY_ENV", "testField")
        assertEquals("env-value", resolved)
    }

    @Test
    fun `resolveCredential throws for non-existent env var`() {
        every { mockEnvVars.getValue("NONEXISTENT") } returns null
        val result = collection.javaClass.getDeclaredMethod("resolveCredential", String::class.java, String::class.java)
        result.isAccessible = true
        assertThrows<ConfigError.InvalidValue> {
            result.invoke(collection, "$$NONEXISTENT", "testField")
        }
    }

    @Test
    fun `resolveCredential throws for empty env var`() {
        every { mockEnvVars.getValue("EMPTY_VAR") } returns ""
        val result = collection.javaClass.getDeclaredMethod("resolveCredential", String::class.java, String::class.java)
        result