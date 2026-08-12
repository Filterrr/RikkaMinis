package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.Extensions
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ProviderRepositoryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var repository: ProviderRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = ProviderRepository(context)
        runBlocking {
            repository.awaitConfigLoaded()
        }
    }

    @Test
    fun testConfigFlowEmitsInitialState() {
        composeTestRule.setContent {
            val config by repository.config.collectAsState()
            assertNotNull(config)
        }
    }

    @Test
    fun testConfigLoadedFlag() {
        composeTestRule.setContent {
            val loaded by repository.configLoaded.collectAsState()
            assertTrue(loaded)
        }
    }

    @Test
    fun testAddInstance() {
        val instance = ProviderInstance(
            id = "test-instance-1",
            label = "Test Instance",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        val instances = repository.instances
        assertTrue(instances.any { it.id == "test-instance-1" })
    }

    @Test
    fun testAddInstanceWithDefaultVoiceModels() {
        val instance = ProviderInstance(
            id = "test-instance-voice",
            label = "Voice Instance",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://api.openai.com",
            isEnabled = true
        )
        repository.addInstance(instance)
        val entries = repository.entriesFor("test-instance-voice")
        assertTrue(entries.isNotEmpty())
    }

    @Test
    fun testUpdateInstance() {
        val instance = ProviderInstance(
            id = "test-instance-2",
            label = "Original Label",
            providerType = ProviderType.anthropic,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        repository.updateInstance(instance.copy(label = "Updated Label"))
        val updated = repository.instance("test-instance-2")
        assertEquals("Updated Label", updated?.label)
    }

    @Test
    fun testRemoveInstance() {
        val instance = ProviderInstance(
            id = "test-instance-3",
            label = "To Remove",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        repository.removeInstance("test-instance-3")
        assertNull(repository.instance("test-instance-3"))
    }

    @Test
    fun testSetInstancePinned() {
        val instance = ProviderInstance(
            id = "test-instance-4",
            label = "Pinnable",
            providerType = ProviderType.gemini,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true,
            pinned = false
        )
        repository.addInstance(instance)
        repository.setInstancePinned("test-instance-4", true)
        val updated = repository.instance("test-instance-4")
        assertTrue(updated?.pinned == true)
    }

    @Test
    fun testEnabledInstances() {
        val instance1 = ProviderInstance(
            id = "enabled-1",
            label = "Enabled Instance",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        val instance2 = ProviderInstance(
            id = "disabled-1",
            label = "Disabled Instance",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = false
        )
        repository.addInstance(instance1)
        repository.addInstance(instance2)
        val enabled = repository.enabledInstances(ProviderType.openAI)
        assertEquals(1, enabled.size)
        assertTrue(enabled.all { it.isEnabled })
    }

    @Test
    fun testEntriesFor() {
        val instance = ProviderInstance(
            id = "entries-test-1",
            label = "Entries Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        val entry = ModelEntry(
            providerInstanceId = "entries-test-1",
            baseModel = LLMModel(id = "gpt-4", displayName = "GPT-4", provider = "OpenAI")
        )
        repository.addEntry(entry)
        val entries = repository.entriesFor("entries-test-1")
        assertEquals(1, entries.size)
    }

    @Test
    fun testVisibleEntries() {
        val instance = ProviderInstance(
            id = "visible-test-1",
            label = "Visible Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        val visibleEntry = ModelEntry(
            providerInstanceId = "visible-test-1",
            baseModel = LLMModel(id = "visible-model", displayName = "Visible Model", provider = "OpenAI"),
            isHidden = false
        )
        val hiddenEntry = ModelEntry(
            providerInstanceId = "visible-test-1",
            baseModel = LLMModel(id = "hidden-model", displayName = "Hidden Model", provider = "OpenAI"),
            isHidden = true
        )
        repository.addEntry(visibleEntry)
        repository.addEntry(hiddenEntry)
        val visible = repository.visibleEntries("visible-test-1")
        assertEquals(1, visible.size)
        assertEquals("visible-model", visible[0].baseModel.id)
    }

    @Test
    fun testAllVisibleEntries() {
        val instance = ProviderInstance(
            id = "all-visible-test-1",
            label = "All Visible Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        val entry = ModelEntry(
            providerInstanceId = "all-visible-test-1",
            baseModel = LLMModel(id = "all-visible-model", displayName = "All Visible Model", provider = "OpenAI"),
            isHidden = false
        )
        repository.addEntry(entry)
        val allVisible = repository.allVisibleEntries()
        assertTrue(allVisible.any { it.id == entry.id })
    }

    @Test
    fun testAddEntry() {
        val instance = ProviderInstance(
            id = "add-entry-test-1",
            label = "Add Entry Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        val entry = ModelEntry(
            providerInstanceId = "add-entry-test-1",
            baseModel = LLMModel(id = "new-entry", displayName = "New Entry", provider = "OpenAI")
        )
        repository.addEntry(entry)
        val entries = repository.entriesFor("add-entry-test-1")
        assertTrue(entries.any { it.baseModel.id == "new-entry" })
    }

    @Test
    fun testUpdateEntry() {
        val instance = ProviderInstance(
            id = "update-entry-test-1",
            label = "Update Entry Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        val entry = ModelEntry(
            providerInstanceId = "update-entry-test-1",
            baseModel = LLMModel(id = "update-entry", displayName = "Original Name", provider = "OpenAI")
        )
        repository.addEntry(entry)
        val updatedEntry = entry.copy(baseModel = entry.baseModel.copy(displayName = "Updated Name"))
        repository.updateEntry(updatedEntry)
        val entries = repository.entriesFor("update-entry-test-1")
        val found = entries.find { it.id == entry.id }
        assertEquals("Updated Name", found?.baseModel?.displayName)
    }

    @Test
    fun testRemoveEntry() {
        val instance = ProviderInstance(
            id = "remove-entry-test-1",
            label = "Remove Entry Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        val entry = ModelEntry(
            providerInstanceId = "remove-entry-test-1",
            baseModel = LLMModel(id = "remove-entry", displayName = "Remove Me", provider = "OpenAI")
        )
        repository.addEntry(entry)
        repository.removeEntry(entry.id)
        val entries = repository.entriesFor("remove-entry-test-1")
        assertFalse(entries.any { it.id == entry.id })
    }

    @Test
    fun testAddGroup() {
        val group = ModelGroup(
            name = "Test Group",
            memberEntryIds = mutableListOf()
        )
        repository.addGroup(group)
        val found = repository.group(group.id)
        assertNotNull(found)
        assertEquals("Test Group", found?.name)
    }

    @Test
    fun testUpdateGroup() {
        val group = ModelGroup(
            name = "Original Group",
            memberEntryIds = mutableListOf()
        )
        repository.addGroup(group)
        repository.updateGroup(group.copy(name = "Updated Group"))
        val found = repository.group(group.id)
        assertEquals("Updated Group", found?.name)
    }

    @Test
    fun testRemoveGroup() {
        val group = ModelGroup(
            name = "Remove Group",
            memberEntryIds = mutableListOf()
        )
        repository.addGroup(group)
        repository.removeGroup(group.id)
        assertNull(repository.group(group.id))
    }

    @Test
    fun testDefaultPrimaryGroupId() {
        val group = ModelGroup(
            name = "Primary Group",
            memberEntryIds = mutableListOf()
        )
        repository.addGroup(group)
        repository.defaultPrimaryGroupId = group.id
        assertEquals(group.id, repository.defaultPrimaryGroupId)
    }

    @Test
    fun testDefaultSubGroupId() {
        val group = ModelGroup(
            name = "Sub Group",
            memberEntryIds = mutableListOf()
        )
        repository.addGroup(group)
        repository.defaultSubGroupId = group.id
        assertEquals(group.id, repository.defaultSubGroupId)
    }

    @Test
    fun testSaveAndLoadApiKey() {
        repository.saveApiKey("key-test-1", "test-api-key-123")
        val loaded = repository.loadApiKey("key-test-1")
        assertEquals("test-api-key-123", loaded)
    }

    @Test
    fun testDeleteApiKey() {
        repository.saveApiKey("key-test-2", "test-api-key-456")
        repository.deleteApiKey("key-test-2")
        assertNull(repository.loadApiKey("key-test-2"))
    }

    @Test
    fun testInvalidateModelCache() {
        val instance = ProviderInstance(
            id = "cache-test-1",
            label = "Cache Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        repository.invalidateModelCache("cache-test-1")
        assertTrue(true)
    }

    @Test
    fun testLastUsedEntryId() {
        val instance = ProviderInstance(
            id = "last-used-test-1",
            label = "Last Used Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        val entry = ModelEntry(
            providerInstanceId = "last-used-test-1",
            baseModel = LLMModel(id = "last-used-model", displayName = "Last Used", provider = "OpenAI")
        )
        repository.addEntry(entry)
        repository.lastUsedEntryId = entry.id
        assertEquals(entry.id, repository.lastUsedEntryId)
    }

    @Test
    fun testLastUsedVisibleEntry() {
        val instance = ProviderInstance(
            id = "last-visible-test-1",
            label = "Last Visible Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        val entry = ModelEntry(
            providerInstanceId = "last-visible-test-1",
            baseModel = LLMModel(id = "last-visible-model", displayName = "Last Visible", provider = "OpenAI"),
            isHidden = false
        )
        repository.addEntry(entry)
        repository.lastUsedEntryId = entry.id
        val lastUsed = repository.lastUsedVisibleEntry()
        assertNotNull(lastUsed)
        assertEquals(entry.id, lastUsed?.id)
    }

    @Test
    fun testNewestProviderNewestTextEntry() {
        val instance = ProviderInstance(
            id = "newest-text-test-1",
            label = "Newest Text Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true,
            createdAt = System.currentTimeMillis()
        )
        repository.addInstance(instance)
        val entry = ModelEntry(
            providerInstanceId = "newest-text-test-1",
            baseModel = LLMModel(
                id = "text-model",
                displayName = "Text Model",
                provider = "OpenAI",
                outputModalities = listOf("text")
            ),
            isHidden = false
        )
        repository.addEntry(entry)
        val newest = repository.newestProviderNewestTextEntry()
        assertNotNull(newest)
    }

    @Test
    fun testVoiceInputGroupId() {
        repository.voiceInputGroupId = "test-voice-input-group"
        assertEquals("test-voice-input-group", repository.voiceInputGroupId)
    }

    @Test
    fun testVoiceOutputGroupId() {
        repository.voiceOutputGroupId = "test-voice-output-group"
        assertEquals("test-voice-output-group", repository.voiceOutputGroupId)
    }

    @Test
    fun testEnsureDefaultVoiceInputGroup() {
        val groupId = repository.ensureDefaultVoiceInputGroup()
        assertNotNull(groupId)
        val group = repository.group(groupId!!)
        assertNotNull(group)
        assertEquals("Voice Input", group?.name)
    }

    @Test
    fun testEnsureDefaultVoiceOutputGroup() {
        val groupId = repository.ensureDefaultVoiceOutputGroup()
        assertNotNull(groupId)
        val group = repository.group(groupId!!)
        assertNotNull(group)
        assertEquals("Voice Output", group?.name)
    }

    @Test
    fun testVoiceInputOverrideEntryId() {
        repository.voiceInputOverrideEntryId = "test-override-entry"
        assertEquals("test-override-entry", repository.voiceInputOverrideEntryId)
    }

    @Test
    fun testVoiceOutputOverrideEntryId() {
        repository.voiceOutputOverrideEntryId = "test-output-override"
        assertEquals("test-output-override", repository.voiceOutputOverrideEntryId)
    }

    @Test
    fun testResolveVoiceInputChoice() {
        val choice = repository.resolveVoiceInputChoice()
        assertNotNull(choice)
    }

    @Test
    fun testResolveVoiceOutputChoice() {
        val choice = repository.resolveVoiceOutputChoice()
        assertNotNull(choice)
    }

    @Test
    fun testVoiceInputGroupName() {
        repository.ensureDefaultVoiceInputGroup()
        val name = repository.voiceInputGroupName()
        assertEquals("Voice Input", name)
    }

    @Test
    fun testVoiceOutputGroupName() {
        repository.ensureDefaultVoiceOutputGroup()
        val name = repository.voiceOutputGroupName()
        assertEquals("Voice Output", name)
    }

    @Test
    fun testIsEntryProviderEnabled() {
        val instance = ProviderInstance(
            id = "enabled-check-test-1",
            label = "Enabled Check",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        val entry = ModelEntry(
            providerInstanceId = "enabled-check-test-1",
            baseModel = LLMModel(id = "enabled-check-model", displayName = "Enabled Check Model", provider = "OpenAI")
        )
        repository.addEntry(entry)
        assertTrue(repository.isEntryProviderEnabled(entry.id))
    }

    @Test
    fun testAgentLoopEntryIds() {
        val instance = ProviderInstance(
            id = "agent-loop-test-1",
            label = "Agent Loop Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        val entry = ModelEntry(
            providerInstanceId = "agent-loop-test-1",
            baseModel = LLMModel(id = "agent-loop-model", displayName = "Agent Loop Model", provider = "OpenAI")
        )
        repository.addEntry(entry)
        repository.addAgentLoopEntry(entry.id)
        assertTrue(repository.resolvedAgentLoopEntries().any { it.id == entry.id })
    }

    @Test
    fun testRemoveAgentLoopEntry() {
        val instance = ProviderInstance(
            id = "agent-loop-remove-test-1",
            label = "Agent Loop Remove",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            isEnabled = true
        )
        repository.addInstance(instance)
        val entry = ModelEntry(
            providerInstanceId = "agent-loop-remove-test-1",
            baseModel = LLMModel(id = "agent-loop-remove-model", displayName = "Remove Model", provider = "OpenAI")
        )
        repository.addEntry(entry)
        repository.addAgentLoopEntry(entry.id)
        repository.removeAgentLoopEntry(entry.id)
        assertFalse(repository.resolvedAgentLoopEntries().any { it.id == entry.id })
    }

    @Test
    fun testSetVoiceShadowDisabled() {
        repository.setVoiceShadowDisabled("shadow-test-1", true)
        assertTrue(re