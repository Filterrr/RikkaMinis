package com.openminis.app.config.collections

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigValue
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.Rule

class ModelsCollectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var repo: FakeProviderRepository
    private lateinit var collection: ModelsCollection

    @BeforeEach
    fun setUp() {
        repo = FakeProviderRepository()
        collection = ModelsCollection(repo)
    }

    // --- Tests for ModelsCollection properties ---

    @Test
    fun `basePath returns correct value`() {
        assertEquals("models", collection.basePath)
    }

    @Test
    fun `displayName returns correct value`() {
        assertEquals("LLM models", collection.displayName)
    }

    @Test
    fun `description returns correct value`() {
        assertTrue(collection.description.startsWith("Per-model overrides"))
    }

    @Test
    fun `addable returns true`() {
        assertTrue(collection.addable)
    }

    @Test
    fun `removable returns true`() {
        assertTrue(collection.removable)
    }

    @Test
    fun `risk returns SENSITIVE`() {
        assertEquals(ConfigRisk.SENSITIVE, collection.risk)
    }

    @Test
    fun `addPayloadSchema returns Json`() {
        assertEquals(ConfigSchema.Json, collection.addPayloadSchema)
    }

    // --- Tests for childIds() ---

    @Test
    fun `childIds returns empty list when no entries`() {
        assertTrue(collection.childIds().isEmpty())
    }

    @Test
    fun `childIds returns entry ids`() {
        repo.addEntry(createEntry("1", "gpt-4"))
        repo.addEntry(createEntry("2", "gpt-3.5"))
        val ids = collection.childIds()
        assertEquals(2, ids.size)
        assertTrue(ids.contains("1"))
        assertTrue(ids.contains("2"))
    }

    // --- Tests for fields(forId:) ---

    @Test
    fun `fields returns empty list for unknown id`() {
        val fields = collection.fields("nonexistent")
        assertTrue(fields.isEmpty())
    }

    @Test
    fun `fields returns all expected fields for known id`() {
        repo.addEntry(createEntry("test1", "gpt-4"))
        val fields = collection.fields("test1")
        val fieldNames = fields.map { it.path }
        assertTrue(fieldNames.contains("models.test1.providerInstanceId"))
        assertTrue(fieldNames.contains("models.test1.modelId"))
        assertTrue(fieldNames.contains("models.test1.isCustom"))
        assertTrue(fieldNames.contains("models.test1.displayName"))
        assertTrue(fieldNames.contains("models.test1.maxOutputTokens"))
        assertTrue(fieldNames.contains("models.test1.isHidden"))
        assertTrue(fieldNames.contains("models.test1.modalities"))
        assertTrue(fieldNames.contains("models.test1.modalitiesOverride"))
        assertTrue(fieldNames.contains("models.test1.contextWindow"))
        assertTrue(fieldNames.contains("models.test1.contextWindowOverride"))
        assertTrue(fieldNames.contains("models.test1.supportsTools"))
        assertTrue(fieldNames.contains("models.test1.supportsVision"))
    }

    // --- Tests for add(payload:) ---

    @Test
    fun `add throws InvalidValue for non-object payload`() {
        assertThrows<ConfigError.InvalidValue> {
            collection.add(ConfigValue.Str("not an object"))
        }
    }

    @Test
    fun `add throws InvalidValue when instance_id missing`() {
        val payload = ConfigValue.Obj(mapOf("model_id" to ConfigValue.Str("gpt-4")))
        assertThrows<ConfigError.InvalidValue> {
            collection.add(payload)
        }
    }

    @Test
    fun `add throws InvalidValue when model_id missing`() {
        val payload = ConfigValue.Obj(mapOf("instance_id" to ConfigValue.Str("provider1")))
        assertThrows<ConfigError.InvalidValue> {
            collection.add(payload)
        }
    }

    @Test
    fun `add throws InvalidValue when provider instance does not exist`() {
        val payload = ConfigValue.Obj(mapOf(
            "instance_id" to ConfigValue.Str("nonexistent"),
            "model_id" to ConfigValue.Str("gpt-4")
        ))
        assertThrows<ConfigError.InvalidValue> {
            collection.add(payload)
        }
    }

    @Test
    fun `add throws AlreadyExists for duplicate model on same provider`() {
        repo.addInstance("provider1")
        repo.addEntry(createEntry("existing", "gpt-4", "provider1"))
        val payload = ConfigValue.Obj(mapOf(
            "instance_id" to ConfigValue.Str("provider1"),
            "model_id" to ConfigValue.Str("gpt-4")
        ))
        assertThrows<ConfigError.AlreadyExists> {
            collection.add(payload)
        }
    }

    @Test
    fun `add creates entry successfully`() {
        repo.addInstance("provider1")
        val payload = ConfigValue.Obj(mapOf(
            "instance_id" to ConfigValue.Str("provider1"),
            "model_id" to ConfigValue.Str("gpt-4")
        ))
        val id = collection.add(payload)
        assertNotNull(id)
        val entry = repo.config.value.modelEntries.firstOrNull { it.id == id }
        assertNotNull(entry)
        assertEquals("provider1", entry!!.providerInstanceId)
        assertEquals("gpt-4", entry.baseModel.id)
        assertTrue(entry.isCustom)
    }

    @Test
    fun `add accepts provider_id as alternative to instance_id`() {
        repo.addInstance("provider2")
        val payload = ConfigValue.Obj(mapOf(
            "provider_id" to ConfigValue.Str("provider2"),
            "model_id" to ConfigValue.Str("claude-3")
        ))
        val id = collection.add(payload)
        assertNotNull(id)
    }

    @Test
    fun `add uses model_id as display name when display_name not provided`() {
        repo.addInstance("provider1")
        val payload = ConfigValue.Obj(mapOf(
            "instance_id" to ConfigValue.Str("provider1"),
            "model_id" to ConfigValue.Str("gpt-4")
        ))
        val id = collection.add(payload)
        val entry = repo.config.value.modelEntries.first { it.id == id }
        assertEquals("gpt-4", entry.baseModel.displayName)
    }

    @Test
    fun `add uses provided display_name`() {
        repo.addInstance("provider1")
        val payload = ConfigValue.Obj(mapOf(
            "instance_id" to ConfigValue.Str("provider1"),
            "model_id" to ConfigValue.Str("gpt-4"),
            "display_name" to ConfigValue.Str("My GPT-4")
        ))
        val id = collection.add(payload)
        val entry = repo.config.value.modelEntries.first { it.id == id }
        assertEquals("My GPT-4", entry.baseModel.displayName)
    }

    // --- Tests for remove(id:) ---

    @Test
    fun `remove throws UnknownPath for nonexistent id`() {
        assertThrows<ConfigError.UnknownPath> {
            collection.remove("nonexistent")
        }
    }

    @Test
    fun `remove throws PermissionDenied for non-custom entry`() {
        repo.addEntry(createEntry("builtin", "gpt-4", isCustom = false))
        assertThrows<ConfigError.PermissionDenied> {
            collection.remove("builtin")
        }
    }

    @Test
    fun `remove succeeds for custom entry`() {
        repo.addEntry(createEntry("custom1", "my-model", isCustom = true))
        collection.remove("custom1")
        assertNull(repo.config.value.modelEntries.firstOrNull { it.id == "custom1" })
    }

    // --- Helper to create test entries ---

    private fun createEntry(
        id: String,
        modelId: String,
        providerInstanceId: String = "test-provider",
        isCustom: Boolean = true,
        overrides: ModelOverrides = ModelOverrides()
    ): ModelEntry {
        val model = LLMModel(
            id = modelId,
            displayName = modelId,
            provider = providerInstanceId,
            contextWindow = 4096,
            maxOutputTokens = 2048,
            supportsReasoning = false,
            inputModalities = listOf("text"),
            outputModalities = listOf("text")
        )
        return ModelEntry(
            id = id,
            providerInstanceId = providerInstanceId,
            baseModel = model,
            isCustom = isCustom,
            isHidden = false,
            userModifiedAt = System.currentTimeMillis(),
            overrides = overrides
        )
    }

    // --- Fake repository for testing ---

    private class FakeProviderRepository : ProviderRepository {
        private val _config = MutableStateFlow(ConfigData(emptyList(), emptyList()))
        override val config: StateFlow<ConfigData> = _config

        data class ConfigData(
            val modelEntries: List<ModelEntry>,
            val instances: List<String>
        )

        private val _instances = mutableListOf<String>()

        fun addInstance(id: String) {
            _instances.add(id)
            updateConfig()
        }

        fun addEntry(entry: ModelEntry) {
            val entries = _config.value.modelEntries.toMutableList()
            entries.add(entry)
            _config.value = _config.value.copy(modelEntries = entries)
        }

        fun removeEntry(id: String) {
            val entries = _config.value.modelEntries.toMutableList()
            entries.removeAll { it.id == id }
            _config.value = _config.value.copy(modelEntries = entries)
        }

        fun updateEntry(apply: (ModelEntry) -> ModelEntry) {
            val entries = _config.value.modelEntries.toMutableList()
            val index = entries.indexOfFirst { it.id == apply(entries.first()).id }
            if (index >= 0) {
                entries[index] = apply(entries[index])
                _config.value = _config.value.copy(modelEntries = entries)
            }
        }

        fun instance(id: String): String? = _instances.firstOrNull { it == id }

        private fun updateConfig() {
            _config.value = _config.value.copy(instances = _instances.toList())
        }
    }
}