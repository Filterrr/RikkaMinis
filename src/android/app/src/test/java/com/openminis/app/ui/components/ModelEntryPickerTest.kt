package com.openminis.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.Modality
import org.junit.jupiter.api.Test
import org.junit.Rule

class ModelEntryPickerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testModelEntryPickerItems_rendersSearchBar() {
        val instances = emptyList<ProviderInstance>()
        val availableEntries = emptyList<ModelEntry>()
        val selectedIds = mutableStateOf(emptySet<String>())
        val searchQuery = mutableStateOf("")
        val collapsedInstanceIds = mutableStateOf(emptySet<String>())

        composeTestRule.setContent {
            LazyColumn {
                modelEntryPickerItems(
                    instances = instances,
                    availableEntries = availableEntries,
                    selectedIds = selectedIds.value,
                    onToggleSelection = { },
                    searchQuery = searchQuery,
                    collapsedInstanceIds = collapsedInstanceIds,
                    emptyTextRes = com.openminis.app.R.string.empty_text,
                    emptySearchTextRes = com.openminis.app.R.string.empty_search_text,
                    searchPlaceholderRes = com.openminis.app.R.string.search_placeholder,
                    clearContentDescriptionRes = com.openminis.app.R.string.clear_content_description
                )
            }
        }

        composeTestRule.onNodeWithTag("search_input").assertIsDisplayed()
    }

    @Test
    fun testModelEntryPickerItems_rendersEmptyText() {
        val instances = emptyList<ProviderInstance>()
        val availableEntries = emptyList<ModelEntry>()
        val selectedIds = mutableStateOf(emptySet<String>())
        val searchQuery = mutableStateOf("")
        val collapsedInstanceIds = mutableStateOf(emptySet<String>())

        composeTestRule.setContent {
            LazyColumn {
                modelEntryPickerItems(
                    instances = instances,
                    availableEntries = availableEntries,
                    selectedIds = selectedIds.value,
                    onToggleSelection = { },
                    searchQuery = searchQuery,
                    collapsedInstanceIds = collapsedInstanceIds,
                    emptyTextRes = com.openminis.app.R.string.empty_text,
                    emptySearchTextRes = com.openminis.app.R.string.empty_search_text,
                    searchPlaceholderRes = com.openminis.app.R.string.search_placeholder,
                    clearContentDescriptionRes = com.openminis.app.R.string.clear_content_description
                )
            }
        }

        composeTestRule.onNodeWithText("No models available").assertIsDisplayed()
    }

    @Test
    fun testModelEntryPickerItems_rendersEmptySearchText() {
        val instances = emptyList<ProviderInstance>()
        val availableEntries = emptyList<ModelEntry>()
        val selectedIds = mutableStateOf(emptySet<String>())
        val searchQuery = mutableStateOf("nonexistent")
        val collapsedInstanceIds = mutableStateOf(emptySet<String>())

        composeTestRule.setContent {
            LazyColumn {
                modelEntryPickerItems(
                    instances = instances,
                    availableEntries = availableEntries,
                    selectedIds = selectedIds.value,
                    onToggleSelection = { },
                    searchQuery = searchQuery,
                    collapsedInstanceIds = collapsedInstanceIds,
                    emptyTextRes = com.openminis.app.R.string.empty_text,
                    emptySearchTextRes = com.openminis.app.R.string.empty_search_text,
                    searchPlaceholderRes = com.openminis.app.R.string.search_placeholder,
                    clearContentDescriptionRes = com.openminis.app.R.string.clear_content_description
                )
            }
        }

        composeTestRule.onNodeWithText("No models match your search").assertIsDisplayed()
    }

    @Test
    fun testModelEntryPickerItems_rendersProviderHeader() {
        val model = LLMModel(
            id = "gpt-4",
            displayName = "GPT-4",
            providerType = ProviderType.openAI,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            hasAudioInput = false,
            hasAudioOutput = false
        )
        val entry = ModelEntry(
            id = "openai-gpt-4",
            model = model,
            providerInstanceId = "openai"
        )
        val instance = ProviderInstance(
            id = "openai",
            label = "OpenAI",
            providerType = ProviderType.openAI,
            isEnabled = true
        )
        val instances = listOf(instance)
        val availableEntries = listOf(entry)
        val selectedIds = mutableStateOf(emptySet<String>())
        val searchQuery = mutableStateOf("")
        val collapsedInstanceIds = mutableStateOf(emptySet<String>())

        composeTestRule.setContent {
            LazyColumn {
                modelEntryPickerItems(
                    instances = instances,
                    availableEntries = availableEntries,
                    selectedIds = selectedIds.value,
                    onToggleSelection = { },
                    searchQuery = searchQuery,
                    collapsedInstanceIds = collapsedInstanceIds,
                    emptyTextRes = com.openminis.app.R.string.empty_text,
                    emptySearchTextRes = com.openminis.app.R.string.empty_search_text,
                    searchPlaceholderRes = com.openminis.app.R.string.search_placeholder,
                    clearContentDescriptionRes = com.openminis.app.R.string.clear_content_description
                )
            }
        }

        composeTestRule.onNodeWithText("OpenAI").assertIsDisplayed()
    }

    @Test
    fun testModelEntryPickerItems_rendersModelEntry() {
        val model = LLMModel(
            id = "gpt-4",
            displayName = "GPT-4",
            providerType = ProviderType.openAI,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            hasAudioInput = false,
            hasAudioOutput = false
        )
        val entry = ModelEntry(
            id = "openai-gpt-4",
            model = model,
            providerInstanceId = "openai"
        )
        val instance = ProviderInstance(
            id = "openai",
            label = "OpenAI",
            providerType = ProviderType.openAI,
            isEnabled = true
        )
        val instances = listOf(instance)
        val availableEntries = listOf(entry)
        val selectedIds = mutableStateOf(emptySet<String>())
        val searchQuery = mutableStateOf("")
        val collapsedInstanceIds = mutableStateOf(emptySet<String>())

        composeTestRule.setContent {
            LazyColumn {
                modelEntryPickerItems(
                    instances = instances,
                    availableEntries = availableEntries,
                    selectedIds = selectedIds.value,
                    onToggleSelection = { },
                    searchQuery = searchQuery,
                    collapsedInstanceIds = collapsedInstanceIds,
                    emptyTextRes = com.openminis.app.R.string.empty_text,
                    emptySearchTextRes = com.openminis.app.R.string.empty_search_text,
                    searchPlaceholderRes = com.openminis.app.R.string.search_placeholder,
                    clearContentDescriptionRes = com.openminis.app.R.string.clear_content_description
                )
            }
        }

        composeTestRule.onNodeWithText("GPT-4").assertIsDisplayed()
        composeTestRule.onNodeWithText("gpt-4").assertIsDisplayed()
    }

    @Test
    fun testModelEntryPickerItems_clickEntry_selectsModel() {
        val model = LLMModel(
            id = "gpt-4",
            displayName = "GPT-4",
            providerType = ProviderType.openAI,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            hasAudioInput = false,
            hasAudioOutput = false
        )
        val entry = ModelEntry(
            id = "openai-gpt-4",
            model = model,
            providerInstanceId = "openai"
        )
        val instance = ProviderInstance(
            id = "openai",
            label = "OpenAI",
            providerType = ProviderType.openAI,
            isEnabled = true
        )
        val instances = listOf(instance)
        val availableEntries = listOf(entry)
        var selectedId: String? = null
        val selectedIds = mutableStateOf(emptySet<String>())
        val searchQuery = mutableStateOf("")
        val collapsedInstanceIds = mutableStateOf(emptySet<String>())

        composeTestRule.setContent {
            LazyColumn {
                modelEntryPickerItems(
                    instances = instances,
                    availableEntries = availableEntries,
                    selectedIds = selectedIds.value,
                    onToggleSelection = { id -> selectedId = id },
                    searchQuery = searchQuery,
                    collapsedInstanceIds = collapsedInstanceIds,
                    emptyTextRes = com.openminis.app.R.string.empty_text,
                    emptySearchTextRes = com.openminis.app.R.string.empty_search_text,
                    searchPlaceholderRes = com.openminis.app.R.string.search_placeholder,
                    clearContentDescriptionRes = com.openminis.app.R.string.clear_content_description
                )
            }
        }

        composeTestRule.onNodeWithText("GPT-4").performClick()
        assert(selectedId == "openai-gpt-4")
    }

    @Test
    fun testModelEntryPickerItems_clickEntryWithModalityFilter_selectsModel() {
        val model = LLMModel(
            id = "gpt-4",
            displayName = "GPT-4",
            providerType = ProviderType.openAI,
            inputModalities = listOf(Modality.TEXT, Modality.AUDIO),
            outputModalities = listOf(Modality.TEXT),
            hasAudioInput = true,
            hasAudioOutput = false
        )
        val entry = ModelEntry(
            id = "openai-gpt-4",
            model = model,
            providerInstanceId = "openai"
        )
        val instance = ProviderInstance(
            id = "openai",
            label = "OpenAI",
            providerType = ProviderType.openAI,
            isEnabled = true
        )
        val instances = listOf(instance)
        val availableEntries = listOf(entry)
        var selectedId: String? = null
        val selectedIds = mutableStateOf(emptySet<String>())
        val searchQuery = mutableStateOf("")
        val collapsedInstanceIds = mutableStateOf(emptySet<String>())

        composeTestRule.setContent {
            LazyColumn {
                modelEntryPickerItems(
                    instances = instances,
                    availableEntries = availableEntries,
                    selectedIds = selectedIds.value,
                    onToggleSelection = { id -> selectedId = id },
                    searchQuery = searchQuery,
                    collapsedInstanceIds = collapsedInstanceIds,
                    emptyTextRes = com.openminis.app.R.string.empty_text,
                    emptySearchTextRes = com.openminis.app.R.string.empty_search_text,
                    searchPlaceholderRes = com.openminis.app.R.string.search_placeholder,
                    clearContentDescriptionRes = com.openminis.app.R.string.clear_content_description,
                    modalityFilter = PickerModalityFilter.AUDIO_INPUT
                )
            }
        }

        composeTestRule.onNodeWithText("GPT-4").performClick()
        assert(selectedId == "openai-gpt-4")
    }

    @Test
    fun testModelEntryPickerItems_rendersCollapsedState() {
        val model = LLMModel(
            id = "gpt-4",
            displayName = "GPT-4",
            providerType = ProviderType.openAI,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            hasAudioInput = false,
            hasAudioOutput = false
        )
        val entry = ModelEntry(
            id = "openai-gpt-4",
            model = model,
            providerInstanceId = "openai"
        )
        val instance = ProviderInstance(
            id = "openai",
            label = "OpenAI",
            providerType = ProviderType.openAI,
            isEnabled = true
        )
        val instances = listOf(instance)
        val availableEntries = listOf(entry)
        val selectedIds = mutableStateOf(emptySet<String>())
        val searchQuery = mutableStateOf("")
        val collapsedInstanceIds = mutableStateOf(setOf("openai"))

        composeTestRule.setContent {
            LazyColumn {
                modelEntryPickerItems(
                    instances = instances,
                    availableEntries = availableEntries,
                    selectedIds = selectedIds.value,
                    onToggleSelection = { },
                    searchQuery = searchQuery,
                    collapsedInstanceIds = collapsedInstanceIds,
                    emptyTextRes = com.openminis.app.R.string.empty_text,
                    emptySearchTextRes = com.openminis.app.R.string.empty_search_text,
                    searchPlaceholderRes = com.openminis.app.R.string.search_placeholder,
                    clearContentDescriptionRes = com.openminis.app.R.string.clear_content_description
                )
            }
        }

        composeTestRule.onNodeWithText("1 models").assertIsDisplayed()
    }

    @Test
    fun testModelEntryPickerItems_rendersQuickTestButton() {
        val model = LLMModel(
            id = "gpt-4",
            displayName = "GPT-4",
            providerType = ProviderType.openAI,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            hasAudioInput = false,
            hasAudioOutput = false
        )
        val entry = ModelEntry(
            id = "openai-gpt-4",
            model = model,
            providerInstanceId = "openai"
        )
        val instance = ProviderInstance(
            id = "openai",
            label = "OpenAI",
            providerType = ProviderType.openAI,
            isEnabled = true
        )
        val instances = listOf(instance)
        val availableEntries = listOf(entry)
        var quickTestModel: ModelEntry? = null
        val selectedIds = mutableStateOf(emptySet<String>())
        val searchQuery = mutableStateOf("")
        val collapsedInstanceIds = mutableStateOf(emptySet<String>())

        composeTestRule.setContent {
            LazyColumn {
                modelEntryPickerItems(
                    instances = instances,
                    availableEntries = availableEntries,
                    selectedIds = selectedIds.value,
                    onToggleSelection = { },
                    searchQuery = searchQuery,
                    collapsedInstanceIds = collapsedInstanceIds,
                    emptyTextRes = com.openminis.app.R.string.empty_text,
                    emptySearchTextRes = com.openminis.app.R.string.empty_search_text,
                    searchPlaceholderRes = com.openminis.app.R.string.search_placeholder,
                    clearContentDescriptionRes = com.openminis.app.R.string.clear_content_description,
                    onQuickTest = { model -> quickTestModel = model }
                )
            }
        }

        composeTestRule.onNodeWithText("GPT-4").performClick()
        assert(quickTestModel != null)
    }

    @Test
    fun testModelEntryPickerItems_rendersWithExcludeIds() {
        val model = LLMModel(
            id = "gpt-4",
            displayName = "GPT-4",
            providerType = ProviderType.openAI,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            hasAudioInput = false,
            hasAudioOutput = false
        )
        val entry = ModelEntry(
            id = "openai-gpt-4",
            model = model,
            providerInstanceId = "openai"
        )
        val instance = ProviderInstance(
            id = "openai",
            label = "OpenAI",
            providerType = ProviderType.openAI,
            isEnabled = true
        )
        val instances = listOf(instance)
        val availableEntries = listOf(entry)
        val selectedIds = mutableStateOf(emptySet<String>())
        val searchQuery = mutableStateOf("")
        val collapsedInstanceIds = mutableStateOf(emptySet<String>())

        composeTestRule.setContent {
            LazyColumn {
                modelEntryPickerItems(
                    instances = instances,
                    availableEntries = availableEntries,
                    selectedIds = selectedIds.value,
                    onToggleSelection = { },
                    searchQuery = searchQuery,
                    collapsedInstanceIds = collapsedInstanceIds,
                    emptyTextRes = com.openminis.app.R.string.empty_text,
                    emptySearchTextRes = com.openminis.app.R.string.empty_search_text,
                    searchPlaceholderRes = com.openminis.app.R.string.search_placeholder,
                    clearContentDescriptionRes = com.openminis.app.R.string.clear_content_description,
                    excludeIds = setOf("openai-gpt-4")
                )
            }
        }

        composeTestRule.onNodeWithText("No models available").assertIsDisplayed()
    }

    @Test
    fun testModelEntryPickerItems_rendersWithSystemProviderLabel() {
        val model = LLMModel(
            id = "gpt-4",
            displayName = "GPT-4",
            providerType = ProviderType.openAI,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            hasAudioInput = false,
            hasAudioOutput = false
        )
        val entry = ModelEntry(
            id = "openai-gpt-4",
            model = model,
            providerInstanceId = "openai"
        )
        val instance = ProviderInstance(
            id = "openai",
            label = "OpenAI",
            providerType = ProviderType.openAI,
            isEnabled = true
        )
        val instances = listOf(instance)
        val availableEntries = listOf(entry)
        val selectedIds = mutableStateOf(emptySet<String>())
        val searchQuery = mutableStateOf("")
        val collapsedInstanceIds = mutableStateOf(emptySet<String>())

        composeTestRule.setContent {
            LazyColumn {
                modelEntryPickerItems(
                    instances = instances,
                    availableEntries = availableEntries,
                    selectedIds = selectedIds.value,
                    onToggleSelection = { },
                    searchQuery = searchQuery,
                    collapsedInstanceIds = collapsedInstanceIds,
                    emptyTextRes = com.openminis.app.R.string.empty_text,
                    emptySearchTextRes = com.openminis.app.R.string.empty_search_text,
                    searchPlaceholderRes = com.openminis.app.R.string.search_placeholder,
                    clearContentDescriptionRes = com.openminis.app