package com.openminis.app.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.repository.ProviderRepository
import org.junit.jupiter.api.Test
import org.junit.Rule

class ChatModelPickerSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testModelPickerSheet_renders() {
        val groups = emptyList<ModelGroup>()
        val providerConfig = ProviderConfig(
            instances = emptyList(),
            modelEntries = emptyList(),
            revision = 0
        )
        val providerRepository = ProviderRepository()

        composeTestRule.setContent {
            ModelPickerSheet(
                groups = groups,
                selectedGroupId = null,
                activeEntryId = null,
                defaultPrimaryGroupId = null,
                config = providerConfig,
                providerRepository = providerRepository,
                onSelectGroup = {},
                onSelectGroupEntry = { _, _ -> },
                onSelectEntry = {},
                onDismiss = {},
                onEditGroups = null
            )
        }

        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun testModelPickerSheet_withGroups() {
        val modelEntry = ModelEntry(
            id = "entry1",
            model = LLMModel(id = "model1", displayName = "Model 1"),
            providerInstanceId = "instance1",
            isHidden = false
        )
        val group = ModelGroup(
            id = "group1",
            name = "Group 1",
            strategy = RoutingStrategy.fallback,
            memberEntryIds = listOf("entry1")
        )
        val providerConfig = ProviderConfig(
            instances = listOf(
                ProviderInstance(
                    id = "instance1",
                    providerType = ProviderType.openAI,
                    isEnabled = true,
                    pinned = false,
                    label = "OpenAI"
                )
            ),
            modelEntries = listOf(modelEntry),
            revision = 0
        )
        val providerRepository = ProviderRepository()

        composeTestRule.setContent {
            ModelPickerSheet(
                groups = listOf(group),
                selectedGroupId = null,
                activeEntryId = null,
                defaultPrimaryGroupId = null,
                config = providerConfig,
                providerRepository = providerRepository,
                onSelectGroup = {},
                onSelectGroupEntry = { _, _ -> },
                onSelectEntry = {},
                onDismiss = {},
                onEditGroups = null
            )
        }

        composeTestRule.onNodeWithText("Group 1").assertIsDisplayed()
    }

    @Test
    fun testModelPickerSheet_clickOnGroup() {
        var selectedGroupId: String? = null
        val group = ModelGroup(
            id = "group1",
            name = "Group 1",
            strategy = RoutingStrategy.fallback,
            memberEntryIds = emptyList()
        )
        val providerConfig = ProviderConfig(
            instances = emptyList(),
            modelEntries = emptyList(),
            revision = 0
        )
        val providerRepository = ProviderRepository()

        composeTestRule.setContent {
            ModelPickerSheet(
                groups = listOf(group),
                selectedGroupId = selectedGroupId,
                activeEntryId = null,
                defaultPrimaryGroupId = null,
                config = providerConfig,
                providerRepository = providerRepository,
                onSelectGroup = { selectedGroupId = it },
                onSelectGroupEntry = { _, _ -> },
                onSelectEntry = {},
                onDismiss = {},
                onEditGroups = null
            )
        }

        composeTestRule.onNodeWithText("Group 1").performClick()
        assert(selectedGroupId == "group1")
    }

    @Test
    fun testModelPickerSheet_clickOnEntry() {
        var selectedEntryId: String? = null
        val modelEntry = ModelEntry(
            id = "entry1",
            model = LLMModel(id = "model1", displayName = "Model 1"),
            providerInstanceId = "instance1",
            isHidden = false
        )
        val providerConfig = ProviderConfig(
            instances = listOf(
                ProviderInstance(
                    id = "instance1",
                    providerType = ProviderType.openAI,
                    isEnabled = true,
                    pinned = false,
                    label = "OpenAI"
                )
            ),
            modelEntries = listOf(modelEntry),
            revision = 0
        )
        val providerRepository = ProviderRepository()

        composeTestRule.setContent {
            ModelPickerSheet(
                groups = emptyList(),
                selectedGroupId = null,
                activeEntryId = null,
                defaultPrimaryGroupId = null,
                config = providerConfig,
                providerRepository = providerRepository,
                onSelectGroup = {},
                onSelectGroupEntry = { _, _ -> },
                onSelectEntry = { selectedEntryId = it },
                onDismiss = {},
                onEditGroups = null
            )
        }

        composeTestRule.onNodeWithText("Model 1").performClick()
        assert(selectedEntryId == "entry1")
    }

    @Test
    fun testModelPickerSheet_clickOnGroupEntry() {
        var selectedGroupEntry: Pair<String, String>? = null
        val modelEntry = ModelEntry(
            id = "entry1",
            model = LLMModel(id = "model1", displayName = "Model 1"),
            providerInstanceId = "instance1",
            isHidden = false
        )
        val group = ModelGroup(
            id = "group1",
            name = "Group 1",
            strategy = RoutingStrategy.fallback,
            memberEntryIds = listOf("entry1")
        )
        val providerConfig = ProviderConfig(
            instances = listOf(
                ProviderInstance(
                    id = "instance1",
                    providerType = ProviderType.openAI,
                    isEnabled = true,
                    pinned = false,
                    label = "OpenAI"
                )
            ),
            modelEntries = listOf(modelEntry),
            revision = 0
        )
        val providerRepository = ProviderRepository()

        composeTestRule.setContent {
            ModelPickerSheet(
                groups = listOf(group),
                selectedGroupId = "group1",
                activeEntryId = null,
                defaultPrimaryGroupId = null,
                config = providerConfig,
                providerRepository = providerRepository,
                onSelectGroup = {},
                onSelectGroupEntry = { groupId, entryId ->
                    selectedGroupEntry = groupId to entryId
                },
                onSelectEntry = {},
                onDismiss = {},
                onEditGroups = null
            )
        }

        composeTestRule.onNodeWithText("Model 1").performClick()
        assert(selectedGroupEntry == "group1" to "entry1")
    }

    @Test
    fun testModelPickerSheet_clickOnDismiss() {
        var dismissed = false
        val providerConfig = ProviderConfig(
            instances = emptyList(),
            modelEntries = emptyList(),
            revision = 0
        )
        val providerRepository = ProviderRepository()

        composeTestRule.setContent {
            ModelPickerSheet(
                groups = emptyList(),
                selectedGroupId = null,
                activeEntryId = null,
                defaultPrimaryGroupId = null,
                config = providerConfig,
                providerRepository = providerRepository,
                onSelectGroup = {},
                onSelectGroupEntry = { _, _ -> },
                onSelectEntry = {},
                onDismiss = { dismissed = true },
                onEditGroups = null
            )
        }

        composeTestRule.onNodeWithText("Done").performClick()
        assert(dismissed)
    }

    @Test
    fun testModelPickerSheet_withEditGroups() {
        var editClicked = false
        val group = ModelGroup(
            id = "group1",
            name = "Group 1",
            strategy = RoutingStrategy.fallback,
            memberEntryIds = emptyList()
        )
        val providerConfig = ProviderConfig(
            instances = emptyList(),
            modelEntries = emptyList(),
            revision = 0
        )
        val providerRepository = ProviderRepository()

        composeTestRule.setContent {
            ModelPickerSheet(
                groups = listOf(group),
                selectedGroupId = null,
                activeEntryId = null,
                defaultPrimaryGroupId = null,
                config = providerConfig,
                providerRepository = providerRepository,
                onSelectGroup = {},
                onSelectGroupEntry = { _, _ -> },
                onSelectEntry = {},
                onDismiss = {},
                onEditGroups = { editClicked = true }
            )
        }

        composeTestRule.onNodeWithText("Edit").performClick()
        assert(editClicked)
    }

    @Test
    fun testModelPickerSheet_withActiveEntry() {
        val modelEntry = ModelEntry(
            id = "entry1",
            model = LLMModel(id = "model1", displayName = "Model 1"),
            providerInstanceId = "instance1",
            isHidden = false
        )
        val providerConfig = ProviderConfig(
            instances = listOf(
                ProviderInstance(
                    id = "instance1",
                    providerType = ProviderType.openAI,
                    isEnabled = true,
                    pinned = false,
                    label = "OpenAI"
                )
            ),
            modelEntries = listOf(modelEntry),
            revision = 0
        )
        val providerRepository = ProviderRepository()

        composeTestRule.setContent {
            ModelPickerSheet(
                groups = emptyList(),
                selectedGroupId = null,
                activeEntryId = "entry1",
                defaultPrimaryGroupId = null,
                config = providerConfig,
                providerRepository = providerRepository,
                onSelectGroup = {},
                onSelectGroupEntry = { _, _ -> },
                onSelectEntry = {},
                onDismiss = {},
                onEditGroups = null
            )
        }

        composeTestRule.onNodeWithText("Active").assertIsDisplayed()
    }

    @Test
    fun testModelPickerSheet_withDefaultGroup() {
        val group = ModelGroup(
            id = "group1",
            name = "Group 1",
            strategy = RoutingStrategy.fallback,
            memberEntryIds = emptyList()
        )
        val providerConfig = ProviderConfig(
            instances = emptyList(),
            modelEntries = emptyList(),
            revision = 0
        )
        val providerRepository = ProviderRepository()

        composeTestRule.setContent {
            ModelPickerSheet(
                groups = listOf(group),
                selectedGroupId = null,
                activeEntryId = null,
                defaultPrimaryGroupId = "group1",
                config = providerConfig,
                providerRepository = providerRepository,
                onSelectGroup = {},
                onSelectGroupEntry = { _, _ -> },
                onSelectEntry = {},
                onDismiss = {},
                onEditGroups = null
            )
        }

        composeTestRule.onNodeWithText("Default").assertIsDisplayed()
    }

    @Test
    fun testModelPickerSheet_search() {
        val modelEntry = ModelEntry(
            id = "entry1",
            model = LLMModel(id = "model1", displayName = "Model 1"),
            providerInstanceId = "instance1",
            isHidden = false
        )
        val providerConfig = ProviderConfig(
            instances = listOf(
                ProviderInstance(
                    id = "instance1",
                    providerType = ProviderType.openAI,
                    isEnabled = true,
                    pinned = false,
                    label = "OpenAI"
                )
            ),
            modelEntries = listOf(modelEntry),
            revision = 0
        )
        val providerRepository = ProviderRepository()

        composeTestRule.setContent {
            ModelPickerSheet(
                groups = emptyList(),
                selectedGroupId = null,
                activeEntryId = null,
                defaultPrimaryGroupId = null,
                config = providerConfig,
                providerRepository = providerRepository,
                onSelectGroup = {},
                onSelectGroupEntry = { _, _ -> },
                onSelectEntry = {},
                onDismiss = {},
                onEditGroups = null
            )
        }

        composeTestRule.onNodeWithTag("search_field").assertIsDisplayed()
    }

    @Test
    fun testModelPickerSheet_emptyState() {
        val providerConfig = ProviderConfig(
            instances = emptyList(),
            modelEntries = emptyList(),
            revision = 0
        )
        val providerRepository = ProviderRepository()

        composeTestRule.setContent {
            ModelPickerSheet(
                groups = emptyList(),
                selectedGroupId = null,
                activeEntryId = null,
                defaultPrimaryGroupId = null,
                config = providerConfig,
                providerRepository = providerRepository,
                onSelectGroup = {},
                onSelectGroupEntry = { _, _ -> },
                onSelectEntry = {},
                onDismiss = {},
                onEditGroups = null
            )
        }

        composeTestRule.onNodeWithText("No models configured").assertIsDisplayed()
    }
}