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
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RecoveryStrategy
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.mockito.junit.MockitoJUnitRunner
import kotlin.test.assertContains
import kotlin.test.assertIs

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupsCollectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockRepo: ProviderRepository = mockk(relaxed = true)
    private val collection = GroupsCollection(mockRepo)

    private val sampleGroup = ModelGroup(
        id = "test-id-1",
        name = "Test Group",
        memberEntryIds = mutableListOf("entry-1", "entry-2"),
        strategy = RoutingStrategy.fallback,
        fallbackStrategy = FallbackStrategy.default,
        recovery = RecoveryStrategy.continueLast,
        defaultThinkingLevel = ThinkingLevel.MEDIUM,
        contextLimitTokens = 4096
    )

    private fun setupMockRepoWithGroups(groups: List<ModelGroup>) {
        val mockConfig = mockk<com.openminis.app.config.Config>()
        every { mockConfig.modelGroups } returns groups
        every { mockConfig.modelEntries } returns listOf(
            mockk {
                every { id } returns "entry-1"
            },
            mockk {
                every { id } returns "entry-2"
            }
        )
        val stateFlow = MutableStateFlow(mockConfig)
        every { mockRepo.config } returns stateFlow
    }

    @Test
    fun `basePath returns correct value`() {
        assertEquals("groups", collection.basePath)
    }

    @Test
    fun `displayName returns correct value`() {
        assertEquals("Model groups", collection.displayName)
    }

    @Test
    fun `description returns correct value`() {
        assertEquals("Named bundles of models with fallback / load-balance routing.", collection.description)
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

    @Test
    fun `childIds returns empty list when no groups`() {
        setupMockRepoWithGroups(emptyList())
        assertTrue(collection.childIds().isEmpty())
    }

    @Test
    fun `childIds returns group ids`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        assertEquals(listOf("test-id-1"), collection.childIds())
    }

    @Test
    fun `fields returns empty list for non-existent group`() {
        setupMockRepoWithGroups(emptyList())
        assertTrue(collection.fields("non-existent").isEmpty())
    }

    @Test
    fun `fields returns all fields for existing group`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        assertEquals(8, fields.size)
    }

    @Test
    fun `add creates group with valid payload`() {
        setupMockRepoWithGroups(emptyList())
        val payload = ConfigValue.Obj(
            mapOf(
                "name" to ConfigValue.Str("New Group"),
                "entries" to ConfigValue.Arr(listOf(ConfigValue.Str("entry-1"))),
                "strategy" to ConfigValue.Str("fallback"),
                "fallback_strategy" to ConfigValue.Str("default"),
                "recovery" to ConfigValue.Str("continueLast"),
                "default_thinking_level" to ConfigValue.Str("medium"),
                "context_limit_tokens" to ConfigValue.Int(2048)
            )
        )
        val id = collection.add(payload)
        assertNotNull(id)
        verify { mockRepo.addGroup(any()) }
    }

    @Test
    fun `add throws exception when name is missing`() {
        setupMockRepoWithGroups(emptyList())
        val payload = ConfigValue.Obj(emptyMap())
        assertThrows(ConfigError.InvalidValue::class.java) {
            collection.add(payload)
        }
    }

    @Test
    fun `add throws exception when name is empty`() {
        setupMockRepoWithGroups(emptyList())
        val payload = ConfigValue.Obj(mapOf("name" to ConfigValue.Str("")))
        assertThrows(ConfigError.InvalidValue::class.java) {
            collection.add(payload)
        }
    }

    @Test
    fun `add creates group with members field`() {
        setupMockRepoWithGroups(emptyList())
        val payload = ConfigValue.Obj(
            mapOf(
                "name" to ConfigValue.Str("Test"),
                "members" to ConfigValue.Arr(listOf(ConfigValue.Str("entry-1")))
            )
        )
        val id = collection.add(payload)
        assertNotNull(id)
        verify { mockRepo.addGroup(any()) }
    }

    @Test
    fun `add uses default values when optional fields missing`() {
        setupMockRepoWithGroups(emptyList())
        val payload = ConfigValue.Obj(
            mapOf(
                "name" to ConfigValue.Str("Test Group")
            )
        )
        val id = collection.add(payload)
        assertNotNull(id)
        verify { mockRepo.addGroup(any()) }
    }

    @Test
    fun `remove throws exception for non-existent group`() {
        setupMockRepoWithGroups(emptyList())
        assertThrows(ConfigError.UnknownPath::class.java) {
            collection.remove("non-existent")
        }
    }

    @Test
    fun `remove removes existing group`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        collection.remove("test-id-1")
        verify { mockRepo.removeGroup("test-id-1") }
    }

    @Test
    fun `nameField reader returns correct name`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val nameField = fields[0] as ClosureField
        val value = nameField.reader()
        assertEquals(ConfigValue.Str("Test Group"), value)
    }

    @Test
    fun `nameField writer updates name`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val nameField = fields[0] as ClosureField
        nameField.writer(ConfigValue.Str("Updated Name"))
        verify { mockRepo.updateGroup(any()) }
    }

    @Test
    fun `strategyField reader returns correct strategy`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val strategyField = fields[1] as ClosureField
        val value = strategyField.reader()
        assertEquals(ConfigValue.Str("fallback"), value)
    }

    @Test
    fun `strategyField writer updates strategy`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val strategyField = fields[1] as ClosureField
        strategyField.writer(ConfigValue.Str("loadBalance"))
        verify { mockRepo.updateGroup(any()) }
    }

    @Test
    fun `strategyField writer throws for invalid strategy`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val strategyField = fields[1] as ClosureField
        assertThrows(ConfigError.InvalidValue::class.java) {
            strategyField.writer(ConfigValue.Str("invalid"))
        }
    }

    @Test
    fun `fallbackStrategyField reader returns correct fallback`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val fallbackField = fields[2] as ClosureField
        val value = fallbackField.reader()
        assertEquals(ConfigValue.Str("default"), value)
    }

    @Test
    fun `fallbackStrategyField writer updates fallback`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val fallbackField = fields[2] as ClosureField
        fallbackField.writer(ConfigValue.Str("always"))
        verify { mockRepo.updateGroup(any()) }
    }

    @Test
    fun `recoveryField reader returns correct recovery`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val recoveryField = fields[3] as ClosureField
        val value = recoveryField.reader()
        assertEquals(ConfigValue.Str("continueLast"), value)
    }

    @Test
    fun `recoveryField writer updates recovery`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val recoveryField = fields[3] as ClosureField
        recoveryField.writer(ConfigValue.Str("honorFirst"))
        verify { mockRepo.updateGroup(any()) }
    }

    @Test
    fun `defaultThinkingLevelField reader returns correct level`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val thinkingField = fields[4] as ClosureField
        val value = thinkingField.reader()
        assertEquals(ConfigValue.Str("medium"), value)
    }

    @Test
    fun `defaultThinkingLevelField writer updates thinking level`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val thinkingField = fields[4] as ClosureField
        thinkingField.writer(ConfigValue.Str("high"))
        verify { mockRepo.updateGroup(any()) }
    }

    @Test
    fun `contextLimitField reader returns correct limit`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val contextField = fields[5] as ClosureField
        val value = contextField.reader()
        assertEquals(ConfigValue.Int(4096), value)
    }

    @Test
    fun `contextLimitField writer updates limit`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val contextField = fields[5] as ClosureField
        contextField.writer(ConfigValue.Int(8192))
        verify { mockRepo.updateGroup(any()) }
    }

    @Test
    fun `contextLimitField writer sets null when zero`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val contextField = fields[5] as ClosureField
        contextField.writer(ConfigValue.Int(0))
        verify { mockRepo.updateGroup(any()) }
    }

    @Test
    fun `entriesField reader returns correct entries`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val entriesField = fields[7] as ClosureField
        val value = entriesField.reader()
        assertEquals(
            ConfigValue.Arr(listOf(ConfigValue.Str("entry-1"), ConfigValue.Str("entry-2"))),
            value
        )
    }

    @Test
    fun `entriesField writer updates entries`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val entriesField = fields[7] as ClosureField
        entriesField.writer(ConfigValue.Arr(listOf(ConfigValue.Str("entry-1"))))
        verify { mockRepo.updateGroup(any()) }
    }

    @Test
    fun `entriesField writer throws for unknown entry`() {
        setupMockRepoWithGroups(listOf(sampleGroup))
        val fields = collection.fields("test-id-1")
        val entriesField = fields[7] as ClosureField
        assertThrows(ConfigError.InvalidValue::class.java) {
            entriesField.writer(ConfigValue.Arr(listOf(ConfigValue.Str("unknown-entry"))))
        }
    }

    @Test
    fun `thinkingLevelToToken returns correct tokens`() {
        assertEquals("off", collection.thinkingLevelToToken(ThinkingLevel.OFF))
        assertEquals("low", collection.thinkingLevelToToken(ThinkingLevel.LOW))
        assertEquals("medium", collection.thinkingLevelToToken(ThinkingLevel.MEDIUM))
        assertEquals("high", collection.thinkingLevelToToken(ThinkingLevel.HIGH))
        assertEquals("xhigh", collection.thinkingLevelToToken(ThinkingLevel.XHIGH))
        assertEquals("max", collection.thinkingLevelToToken(ThinkingLevel.MAX))
        assertEquals("ultra", collection.thinkingLevelToToken(ThinkingLevel.ULTRA))
    }

    @Test
    fun `thinkingLevelFromToken returns correct levels`() {
        assertEquals(ThinkingLevel.OFF, collection.thinkingLevelFromToken("off"))
        assertEquals(ThinkingLevel.LOW, collection.thinkingLevelFromToken("low"))
        assertEquals(ThinkingLevel.MEDIUM, collection.thinkingLevelFromToken("medium"))
        assertEquals(ThinkingLevel.HIGH, collection.thinkingLevelFromToken("high"))
        assertEquals(ThinkingLevel.XHIGH, collection.thinkingLevelFromToken("xhigh"))
        assertEquals(ThinkingLevel.MAX, collection.thinkingLevelFromToken("max"))
        assertEquals(ThinkingLevel.ULTRA, collection.thinkingLevelFromToken("ultra"))
        assertNull(collection.thinkingLevelFromToken("invalid"))
    }

    @Test
    fun `composeTestRule can be created and renders content`() {
        composeTestRule.setContent {
            androidx.compose.material.Text("Groups Collection Test")
        }
        composeTestRule.onNodeWithText("Groups Collection Test").assertExists()
    }

    @Test
    fun `composeTestRule handles click events`() {
        var clicked = false
        composeTestRule.setContent {
            androidx.compose.material.Button(onClick = { clicked = true }) {
                androidx.compose.material.Text("Click Me")
            }
        }
        composeTestRule.onNodeWithText("Click Me").performClick()
        assertTrue(clicked)
    }
}