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
import com.openminis.app.config.fields.HiddenField
import com.openminis.app.config.fields.ReadOnlyField
import com.openminis.app.data.repository.EnvVarRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class EnvVarsCollectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockRepo = mockk<EnvVarRepository>(relaxed = true)
    private val collection = EnvVarsCollection(mockRepo)

    private val testEntry = EnvVarRepository.EnvVarEntry(
        id = "test-id",
        key = "TEST_KEY",
        value = "test-value",
        note = "test-note",
        createdAt = System.currentTimeMillis()
    )

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Test
    fun `collection properties are correct`() {
        assertEquals("envvars", collection.basePath)
        assertEquals("Environment variables", collection.displayName)
        assertEquals("Metadata only — values are stored in encrypted prefs and never exposed.", collection.description)
        assertFalse(collection.addable)
        assertTrue(collection.removable)
        assertEquals(ConfigRisk.SENSITIVE, collection.risk)
        assertEquals(ConfigSchema.Json, collection.addPayloadSchema)
    }

    @Test
    fun `childIds returns keys from repo entries`() {
        every { mockRepo.entries } returns MutableStateFlow(
            listOf(
                EnvVarRepository.EnvVarEntry("id1", "KEY1", "val1", "note1", 1000L),
                EnvVarRepository.EnvVarEntry("id2", "KEY2", "val2", "note2", 2000L)
            )
        )

        val ids = collection.childIds()
        assertEquals(listOf("KEY1", "KEY2"), ids)
    }

    @Test
    fun `fields returns empty list for non-existent id`() {
        every { mockRepo.entries } returns MutableStateFlow(emptyList())

        val fields = collection.fields("nonexistent")
        assertTrue(fields.isEmpty())
    }

    @Test
    fun `fields returns correct fields for existing entry`() {
        every { mockRepo.entries } returns MutableStateFlow(listOf(testEntry))

        val fields = collection.fields("TEST_KEY")
        assertEquals(3, fields.size)

        // Check first field is ReadOnlyField (createdAt)
        assertTrue(fields[0] is ReadOnlyField)
        assertEquals("envvars.TEST_KEY.createdAt", fields[0].path)
        assertEquals("Created at", fields[0].displayName)
        assertEquals("ISO-8601 timestamp.", fields[0].description)

        // Check second field is ClosureField (note)
        assertTrue(fields[1] is ClosureField)
        assertEquals("envvars.TEST_KEY.note", fields[1].path)
        assertEquals("Note", fields[1].displayName)
        assertEquals("Free-form description (not secret). Empty string clears it.", fields[1].description)
        assertEquals(ConfigRisk.NORMAL, fields[1].risk)

        // Check third field is HiddenField (value)
        assertTrue(fields[2] is HiddenField)
        assertEquals("envvars.TEST_KEY.value", fields[2].path)
        assertEquals("Value", fields[2].displayName)
        assertEquals("Hidden — manage via Settings → Environment Variables.", fields[2].description)
    }

    @Test
    fun `createdAtField reader returns correct timestamp`() {
        every { mockRepo.entries } returns MutableStateFlow(listOf(testEntry))

        val fields = collection.fields("TEST_KEY")
        val createdAtField = fields[0] as ReadOnlyField

        val expectedTimestamp = isoFormatter.format(Date(testEntry.createdAt))
        val result = createdAtField.reader.invoke()
        assertEquals(ConfigValue.Str(expectedTimestamp), result)
    }

    @Test
    fun `createdAtField reader returns Null for missing entry`() {
        every { mockRepo.entries } returns MutableStateFlow(emptyList())

        val fields = collection.fields("TEST_KEY")
        val createdAtField = fields[0] as ReadOnlyField

        val result = createdAtField.reader.invoke()
        assertEquals(ConfigValue.Null, result)
    }

    @Test
    fun `noteField reader returns correct note`() {
        every { mockRepo.entries } returns MutableStateFlow(listOf(testEntry))

        val fields = collection.fields("TEST_KEY")
        val noteField = fields[1] as ClosureField

        val result = noteField.reader.invoke()
        assertEquals(ConfigValue.Str("test-note"), result)
    }

    @Test
    fun `noteField reader returns Null for missing entry`() {
        every { mockRepo.entries } returns MutableStateFlow(emptyList())

        val fields = collection.fields("TEST_KEY")
        val noteField = fields[1] as ClosureField

        val result = noteField.reader.invoke()
        assertEquals(ConfigValue.Null, result)
    }

    @Test
    fun `noteField writer updates note successfully`() {
        val mutableEntries = MutableStateFlow(listOf(testEntry))
        every { mockRepo.entries } returns mutableEntries
        every { mockRepo.getValue("TEST_KEY") } returns "test-value"

        val fields = collection.fields("TEST_KEY")
        val noteField = fields[1] as ClosureField

        noteField.writer.invoke(ConfigValue.Str("updated-note"))

        verify { mockRepo.update("test-id", "TEST_KEY", "test-value", "updated-note") }
    }

    @Test
    fun `noteField writer throws TypeMismatch for non-string value`() {
        every { mockRepo.entries } returns MutableStateFlow(listOf(testEntry))

        val fields = collection.fields("TEST_KEY")
        val noteField = fields[1] as ClosureField

        assertThrows<ConfigError.TypeMismatch> {
            noteField.writer.invoke(ConfigValue.Null)
        }
    }

    @Test
    fun `noteField writer throws UnknownPath for missing entry`() {
        every { mockRepo.entries } returns MutableStateFlow(emptyList())

        val fields = collection.fields("TEST_KEY")
        val noteField = fields[1] as ClosureField

        assertThrows<ConfigError.UnknownPath> {
            noteField.writer.invoke(ConfigValue.Str("test"))
        }
    }

    @Test
    fun `add throws PermissionDenied`() {
        assertThrows<ConfigError.PermissionDenied> {
            collection.add(ConfigValue.Str("test"))
        }
    }

    @Test
    fun `remove deletes entry successfully`() {
        every { mockRepo.entries } returns MutableStateFlow(listOf(testEntry))

        collection.remove("TEST_KEY")
        verify { mockRepo.delete("test-id") }
    }

    @Test
    fun `remove throws UnknownPath for non-existent entry`() {
        every { mockRepo.entries } returns MutableStateFlow(emptyList())

        assertThrows<ConfigError.UnknownPath> {
            collection.remove("nonexistent")
        }
    }

    @Test
    fun `composable renders correctly`() {
        every { mockRepo.entries } returns MutableStateFlow(listOf(testEntry))

        composeTestRule.setContent {
            collection.Display()
        }

        composeTestRule.onNodeWithText("Environment variables").assertExists()
        composeTestRule.onNodeWithText("Metadata only — values are stored in encrypted prefs and never exposed.").assertExists()
    }

    @Test
    fun `composable renders with default parameters`() {
        composeTestRule.setContent {
            collection.Display()
        }

        composeTestRule.onNodeWithText("Environment variables").assertExists()
        composeTestRule.onNodeWithText("Metadata only — values are stored in encrypted prefs and never exposed.").assertExists()
    }

    @Test
    fun `composable handles click events`() {
        every { mockRepo.entries } returns MutableStateFlow(listOf(testEntry))

        composeTestRule.setContent {
            collection.Display()
        }

        composeTestRule.onNodeWithText("Environment variables").performClick()
    }
}