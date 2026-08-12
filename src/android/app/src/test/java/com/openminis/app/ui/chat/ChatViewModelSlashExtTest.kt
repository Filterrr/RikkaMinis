package com.openminis.app.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.Extensions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class ChatViewModelSlashExtTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Helper to create a mock ChatViewModel for testing
    private fun createMockViewModel(): ChatViewModel {
        // In a real test you would use a mock or a real ViewModel with mocked dependencies
        // For this test structure, we'll create a minimal mock that satisfies the interface
        return object : ChatViewModel() {
            // Override necessary properties for testing
            override val availableSlashCommands: List<SlashCommand> = listOf(
                SlashCommand(id = "memory", title = "memory", subtitle = ""),
                SlashCommand(id = "clear", title = "clear", subtitle = ""),
                SlashCommand(id = "help", title = "help", subtitle = "")
            )
            override val _slashFilter = MutableStateFlow("")
            override val _showSlashMenu = MutableStateFlow(false)
            override val _slashMenuSelectedIndex = MutableStateFlow(-1)
            override val _memoryEnabled = MutableStateFlow(false)
            override val _pendingCaret = MutableStateFlow(0)
            override var savedInputBeforeSlash: String? = null
            override var activeSessionId: String = "test_session"
            override var skillRepository: SkillRepository? = null
            override var mcpRepository: McpRepository? = null
            override val context: Context = mockContext()
        }
    }

    @Test
    fun `filteredSlashCommands with empty filter returns all commands`() {
        val viewModel = createMockViewModel()
        viewModel._slashFilter.value = ""
        
        val result = viewModel.filteredSlashCommands()
        
        assertEquals(3, result.size)
        assertTrue(result.any { it.id == "memory" })
        assertTrue(result.any { it.id == "clear" })
        assertTrue(result.any { it.id == "help" })
    }

    @Test
    fun `filteredSlashCommands with filter returns matching commands`() {
        val viewModel = createMockViewModel()
        viewModel._slashFilter.value = "mem"
        
        val result = viewModel.filteredSlashCommands()
        
        assertEquals(1, result.size)
        assertEquals("memory", result[0].id)
    }

    @Test
    fun `filteredSlashCommands with non-matching filter returns empty list`() {
        val viewModel = createMockViewModel()
        viewModel._slashFilter.value = "xyz"
        
        val result = viewModel.filteredSlashCommands()
        
        assertTrue(result.isEmpty())
    }

    @Test
    fun `filteredSlashCommands memory command subtitle changes based on memoryEnabled`() {
        val viewModel = createMockViewModel()
        viewModel._memoryEnabled.value = true
        
        val result = viewModel.filteredSlashCommands()
        val memoryCmd = result.find { it.id == "memory" }
        assertNotNull(memoryCmd)
        assertEquals("Memory writes ON", memoryCmd?.subtitle)
    }

    @Test
    fun `filteredSlashCommands memory command subtitle shows off when disabled`() {
        val viewModel = createMockViewModel()
        viewModel._memoryEnabled.value = false
        
        val result = viewModel.filteredSlashCommands()
        val memoryCmd = result.find { it.id == "memory" }
        assertNotNull(memoryCmd)
        assertEquals("Memory writes OFF", memoryCmd?.subtitle)
    }

    @Test
    fun `updateSlashMenuState with slash shows menu`() {
        val viewModel = createMockViewModel()
        
        viewModel.updateSlashMenuState("/test")
        
        assertTrue(viewModel._showSlashMenu.value)
        assertEquals("test", viewModel._slashFilter.value)
        assertEquals(-1, viewModel._slashMenuSelectedIndex.value)
    }

    @Test
    fun `updateSlashMenuState with fullwidth slash shows menu`() {
        val viewModel = createMockViewModel()
        
        viewModel.updateSlashMenuState("／test")
        
        assertTrue(viewModel._showSlashMenu.value)
        assertEquals("test", viewModel._slashFilter.value)
    }

    @Test
    fun `updateSlashMenuState without slash hides menu`() {
        val viewModel = createMockViewModel()
        
        viewModel.updateSlashMenuState("no slash")
        
        assertFalse(viewModel._showSlashMenu.value)
        assertEquals(-1, viewModel._slashMenuSelectedIndex.value)
    }

    @Test
    fun `updateSlashMenuState with newline hides menu`() {
        val viewModel = createMockViewModel()
        
        viewModel.updateSlashMenuState("/test\n")
        
        assertFalse(viewModel._showSlashMenu.value)
    }

    @Test
    fun `updateSlashMenuState with long text hides menu`() {
        val viewModel = createMockViewModel()
        
        viewModel.updateSlashMenuState("/" + "a".repeat(30))
        
        assertFalse(viewModel._showSlashMenu.value)
    }

    @Test
    fun `updateSlashMenuState with savedInputBeforeSlash and slash continues`() {
        val viewModel = createMockViewModel()
        viewModel.savedInputBeforeSlash = "previous input"
        
        viewModel.updateSlashMenuState("/test")
        
        assertTrue(viewModel._showSlashMenu.value)
        assertEquals("test", viewModel._slashFilter.value)
    }

    @Test
    fun `updateSlashMenuState with savedInputBeforeSlash and no slash dismisses`() {
        val viewModel = createMockViewModel()
        viewModel.savedInputBeforeSlash = "previous input"
        
        viewModel.updateSlashMenuState("no slash")
        
        assertNull(viewModel.savedInputBeforeSlash)
        assertFalse(viewModel._showSlashMenu.value)
        assertEquals(-1, viewModel._slashMenuSelectedIndex.value)
    }

    @Test
    fun `showSlashMenuOverInput with empty trimmed input returns slash`() {
        val viewModel = createMockViewModel()
        
        val result = viewModel.showSlashMenuOverInput("   ")
        
        assertEquals("/", result)
        assertTrue(viewModel._showSlashMenu.value)
        assertEquals("", viewModel._slashFilter.value)
    }

    @Test
    fun `showSlashMenuOverInput with slash input returns same`() {
        val viewModel = createMockViewModel()
        
        val result = viewModel.showSlashMenuOverInput("/help")
        
        assertEquals("/help", result)
        assertTrue(viewModel._showSlashMenu.value)
        assertEquals("help", viewModel._slashFilter.value)
    }

    @Test
    fun `showSlashMenuOverInput with non-empty non-slash input saves and returns slash plus input`() {
        val viewModel = createMockViewModel()
        
        val result = viewModel.showSlashMenuOverInput("hello")
        
        assertEquals("/ hello", result)
        assertEquals("hello", viewModel.savedInputBeforeSlash)
        assertTrue(viewModel._showSlashMenu.value)
        assertEquals(1, viewModel._pendingCaret.value)
    }

    @Test
    fun `showSlashMenuOverInput with fullwidth slash input returns same`() {
        val viewModel = createMockViewModel()
        
        val result = viewModel.showSlashMenuOverInput("／help")
        
        assertEquals("／help", result)
        assertTrue(viewModel._showSlashMenu.value)
    }

    @Test
    fun `dismissSlashMenu with saved input returns saved input`() {
        val viewModel = createMockViewModel()
        viewModel.savedInputBeforeSlash = "saved input"
        
        val result = viewModel.dismissSlashMenu("/current")
        
        assertEquals("saved input", result)
        assertNull(viewModel.savedInputBeforeSlash)
        assertFalse(viewModel._showSlashMenu.value)
        assertEquals("saved input".length, viewModel._pendingCaret.value)
    }

    @Test
    fun `dismissSlashMenu without saved input and with slash returns empty`() {
        val viewModel = createMockViewModel()
        
        val result = viewModel.dismissSlashMenu("/test")
        
        assertEquals("", result)
        assertNull(viewModel.savedInputBeforeSlash)
        assertFalse(viewModel._showSlashMenu.value)
    }

    @Test
    fun `dismissSlashMenu without saved input and without slash returns same input`() {
        val viewModel = createMockViewModel()
        
        val result = viewModel.dismissSlashMenu("test")
        
        assertEquals("test", result)
        assertNull(viewModel.savedInputBeforeSlash)
        assertFalse(viewModel._showSlashMenu.value)
    }

    @Test
    fun `slashMenuSetSelectedIndex sets index correctly`() {
        val viewModel = createMockViewModel()
        
        viewModel.slashMenuSetSelectedIndex(2)
        
        assertEquals(2, viewModel._slashMenuSelectedIndex.value)
    }

    @Test
    fun `slashMenuSetSelectedIndex sets negative index`() {
        val viewModel = createMockViewModel()
        
        viewModel.slashMenuSetSelectedIndex(-1)
        
        assertEquals(-1, viewModel._slashMenuSelectedIndex.value)
    }

    private fun mockContext(): Context {
        return org.mockito.kotlin.mock()
    }
}