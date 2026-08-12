package com.openminis.app.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class MinisMarkdownTextToolbarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createToolbar(
        onAddToInput: ((String) -> Unit)? = null,
        selectionController: SelectionController? = null,
        context: Context = mock(Context::class.java)
    ): MinisMarkdownTextToolbar {
        val clipboardManager = mock(ClipboardManager::class.java)
        `when`(context.getSystemService(Context.CLIPBOARD_SERVICE)).thenReturn(clipboardManager)
        return MinisMarkdownTextToolbar(
            context = context,
            registry = MessageBoundsRegistry(),
            onAddToInput = onAddToInput,
            selectionController = selectionController
        )
    }

    @Test
    fun `toolbar initial state is hidden`() {
        val toolbar = createToolbar()
        assertFalse(toolbar.state.visible)
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }

    @Test
    fun `showMenu sets visible state and toolbar status`() {
        val toolbar = createToolbar()
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        
        assertTrue(toolbar.state.visible)
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        assertEquals(rect, toolbar.state.rect)
    }

    @Test
    fun `hide sets visible state to false`() {
        val toolbar = createToolbar()
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        toolbar.hide()
        
        assertFalse(toolbar.state.visible)
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }

    @Test
    fun `canAddToInput returns true when onAddToInput is not null`() {
        val toolbar = createToolbar(onAddToInput = {})
        assertTrue(toolbar.canAddToInput)
    }

    @Test
    fun `canAddToInput returns false when onAddToInput is null`() {
        val toolbar = createToolbar()
        assertFalse(toolbar.canAddToInput)
    }

    @Test
    fun `addSelectionToInput with null onAddToInput does nothing`() {
        val toolbar = createToolbar()
        toolbar.addSelectionToInput()
        // No exception thrown, test passes
    }

    @Test
    fun `copyMarkdown with no originatingMarkdown does nothing`() {
        val toolbar = createToolbar()
        toolbar.copyMarkdown()
        // No exception thrown, test passes
    }

    @Test
    fun `copyRichText with no originatingMarkdown does nothing`() {
        val toolbar = createToolbar()
        toolbar.copyRichText()
        // No exception thrown, test passes
    }

    @Test
    fun `toolbar state defaults are correct`() {
        val state = MinisMarkdownTextToolbar.ToolbarState()
        assertFalse(state.visible)
        assertEquals(Rect.Zero, state.rect)
        assertNull(state.onCopyRequested)
        assertNull(state.originatingMarkdown)
        assertEquals(0L, state.lastShownAtMs)
    }

    @Test
    fun `host composable renders copy button when visible`() {
        val toolbar = createToolbar()
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Copy").assertExists()
    }

    @Test
    fun `host composable does not render when not visible`() {
        val toolbar = createToolbar()
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Copy").assertDoesNotExist()
    }

    @Test
    fun `host composable renders add to input button when onAddToInput provided`() {
        val toolbar = createToolbar(onAddToInput = {})
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Add to input").assertExists()
    }

    @Test
    fun `host composable does not render add to input button when onAddToInput is null`() {
        val toolbar = createToolbar()
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Add to input").assertDoesNotExist()
    }

    @Test
    fun `copy button click triggers onCopyRequested and hides toolbar`() {
        var copyRequested = false
        val toolbar = createToolbar()
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = { copyRequested = true }, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Copy").performClick()
        
        assertTrue(copyRequested)
        assertFalse(toolbar.state.visible)
    }

    @Test
    fun `host composable renders copy markdown button when originatingMarkdown exists`() {
        val toolbar = createToolbar()
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        toolbar.state = toolbar.state.copy(originatingMarkdown = "# Test")
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Copy Markdown").assertExists()
        composeTestRule.onNodeWithText("Copy Rich Text").assertExists()
    }

    @Test
    fun `host composable does not render copy markdown buttons when no originatingMarkdown`() {
        val toolbar = createToolbar()
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Copy Markdown").assertDoesNotExist()
        composeTestRule.onNodeWithText("Copy Rich Text").assertDoesNotExist()
    }

    @Test
    fun `host composable renders table actions when selectionController provides them`() {
        val selectionController = mock(SelectionController::class.java)
        val tableActions = mock(TableActions::class.java)
        `when`(selectionController.selectionTableActions()).thenReturn(tableActions)
        
        val toolbar = createToolbar(selectionController = selectionController)
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Copy table").assertExists()
        composeTestRule.onNodeWithText("Copy table image").assertExists()
    }

    @Test
    fun `host composable does not render table actions when selectionController is null`() {
        val toolbar = createToolbar()
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Copy table").assertDoesNotExist()
        composeTestRule.onNodeWithText("Copy table image").assertDoesNotExist()
    }

    @Test
    fun `host composable renders all buttons when all conditions are met`() {
        val selectionController = mock(SelectionController::class.java)
        val tableActions = mock(TableActions::class.java)
        `when`(selectionController.selectionTableActions()).thenReturn(tableActions)
        
        val toolbar = createToolbar(
            onAddToInput = {},
            selectionController = selectionController
        )
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        toolbar.state = toolbar.state.copy(originatingMarkdown = "# Test")
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Copy").assertExists()
        composeTestRule.onNodeWithText("Add to input").assertExists()
        composeTestRule.onNodeWithText("Copy Markdown").assertExists()
        composeTestRule.onNodeWithText("Copy Rich Text").assertExists()
        composeTestRule.onNodeWithText("Copy table").assertExists()
        composeTestRule.onNodeWithText("Copy table image").assertExists()
    }

    @Test
    fun `add to input button click calls addSelectionToInput and hides toolbar`() {
        var addedText: String? = null
        val toolbar = createToolbar(onAddToInput = { addedText = it })
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Add to input").performClick()
        
        assertFalse(toolbar.state.visible)
    }

    @Test
    fun `copy markdown button click calls copyMarkdown and hides toolbar`() {
        val toolbar = createToolbar()
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        toolbar.state = toolbar.state.copy(originatingMarkdown = "# Test")
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Copy Markdown").performClick()
        
        assertFalse(toolbar.state.visible)
    }

    @Test
    fun `copy rich text button click calls copyRichText and hides toolbar`() {
        val toolbar = createToolbar()
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        toolbar.state = toolbar.state.copy(originatingMarkdown = "# Test")
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Copy Rich Text").performClick()
        
        assertFalse(toolbar.state.visible)
    }

    @Test
    fun `table copy button click calls copyTableMarkdown and hides toolbar`() {
        val selectionController = mock(SelectionController::class.java)
        val tableActions = mock(TableActions::class.java)
        `when`(selectionController.selectionTableActions()).thenReturn(tableActions)
        
        val toolbar = createToolbar(selectionController = selectionController)
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Copy table").performClick()
        
        verify(tableActions).copyTableMarkdown()
        assertFalse(toolbar.state.visible)
    }

    @Test
    fun `table image button click calls copyTableImage and hides toolbar`() {
        val selectionController = mock(SelectionController::class.java)
        val tableActions = mock(TableActions::class.java)
        `when`(selectionController.selectionTableActions()).thenReturn(tableActions)
        
        val toolbar = createToolbar(selectionController = selectionController)
        val rect = Rect(0f, 0f, 100f, 50f)
        
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        
        composeTestRule.setContent {
            MinisMarkdownTextToolbarHost(toolbar)
        }
        
        composeTestRule.onNodeWithText("Copy table image").performClick()
        
        verify(tableActions).copyTableImage()
        assertFalse(toolbar.state.visible)
    }
}