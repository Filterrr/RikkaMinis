package com.openminis.app.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class MinisTextKitSelectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun provideSelectionController_rendersContent() {
        composeTestRule.setContent {
            ProvideSelectionController(controller = SelectionController()) {
                Text("Hello World")
            }
        }
        composeTestRule.onNodeWithText("Hello World").assertExists()
    }

    @Test
    fun provideSelectionController_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            ProvideSelectionController(controller = SelectionController()) {
                Text("Click Me", modifier = Modifier.testTag("clickable"))
            }
        }
        composeTestRule.onNodeWithTag("clickable").performClick()
        // Verify no crash, content still rendered
        composeTestRule.onNodeWithText("Click Me").assertExists()
    }

    @Test
    fun provideSelectionController_defaultParameters() {
        composeTestRule.setContent {
            ProvideSelectionController(controller = SelectionController()) {
                Text("Default Content")
            }
        }
        composeTestRule.onNodeWithText("Default Content").assertExists()
    }

    @Test
    fun registerSelectionShard_rendersContent() {
        composeTestRule.setContent {
            val controller = remember { SelectionController() }
            ProvideSelectionController(controller = controller) {
                val textLayoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
                Text(
                    text = "Test Shard",
                    onTextLayout = { textLayoutResult.value = it },
                    modifier = Modifier.testTag("shardText")
                )
                val layoutResult = textLayoutResult.value
                if (layoutResult != null) {
                    val shard = remember(layoutResult) {
                        TextShard(
                            id = TextShardId("msg1", "shard1"),
                            plainText = "Test Shard",
                            textLayoutResult = layoutResult,
                            positionInWindow = { Offset.Zero },
                            sizePx = { IntSize(100, 20) }
                        )
                    }
                    RegisterSelectionShard(shard = shard)
                }
            }
        }
        composeTestRule.onNodeWithTag("shardText").assertExists()
    }

    @Test
    fun registerSelectionShard_clickEvent() {
        composeTestRule.setContent {
            val controller = remember { SelectionController() }
            ProvideSelectionController(controller = controller) {
                Text(
                    text = "Clickable Shard",
                    modifier = Modifier.testTag("clickableShard")
                )
            }
        }
        composeTestRule.onNodeWithTag("clickableShard").performClick()
        composeTestRule.onNodeWithText("Clickable Shard").assertExists()
    }

    @Test
    fun registerSelectionShard_defaultParameters() {
        composeTestRule.setContent {
            val controller = remember { SelectionController() }
            ProvideSelectionController(controller = controller) {
                RegisterSelectionShard(shard = null)
                Text("After Null Shard")
            }
        }
        composeTestRule.onNodeWithText("After Null Shard").assertExists()
    }

    @Test
    fun selectionController_initialState() {
        val controller = SelectionController()
        assertNull(controller.selection.value)
        assertNull(controller.dragIntent.value)
        assertTrue(controller.currentShards().isEmpty())
    }

    @Test
    fun selectionController_registerAndUnregisterShard() {
        val controller = SelectionController()
        val shard = TextShard(
            id = TextShardId("msg1", "shard1"),
            plainText = "Hello",
            textLayoutResult = createMockTextLayoutResult("Hello"),
            positionInWindow = { Offset.Zero },
            sizePx = { IntSize(100, 20) }
        )
        controller.register(shard)
        assertEquals(1, controller.currentShards().size)
        controller.unregister(shard.id)
        assertTrue(controller.currentShards().isEmpty())
    }

    @Test
    fun selectionController_beginSelection() {
        val controller = SelectionController()
        val pos = TextPosition(TextShardId("msg1", "shard1"), 3)
        controller.beginSelection(pos)
        assertNotNull(controller.selection.value)
        assertEquals(pos, controller.selection.value!!.start)
        assertEquals(pos, controller.selection.value!!.end)
    }

    @Test
    fun selectionController_clearSelection() {
        val controller = SelectionController()
        val pos = TextPosition(TextShardId("msg1", "shard1"), 3)
        controller.beginSelection(pos)
        controller.clearSelection()
        assertNull(controller.selection.value)
    }

    @Test
    fun selectionController_extendSelectionTo() {
        val controller = SelectionController()
        val start = TextPosition(TextShardId("msg1", "shard1"), 0)
        val end = TextPosition(TextShardId("msg1", "shard1"), 5)
        controller.beginSelection(start)
        controller.extendSelectionTo(end)
        assertNotNull(controller.selection.value)
        assertEquals(start, controller.selection.value!!.start)
        assertEquals(end, controller.selection.value!!.end)
    }

    @Test
    fun selectionController_replaceStart() {
        val controller = SelectionController()
        val start = TextPosition(TextShardId("msg1", "shard1"), 0)
        val end = TextPosition(TextShardId("msg1", "shard1"), 5)
        controller.beginSelection(start)
        controller.extendSelectionTo(end)
        val newStart = TextPosition(TextShardId("msg1", "shard1"), 2)
        controller.replaceStart(newStart)
        assertEquals(newStart, controller.selection.value!!.start)
    }

    @Test
    fun selectionController_replaceEnd() {
        val controller = SelectionController()
        val start = TextPosition(TextShardId("msg1", "shard1"), 0)
        val end = TextPosition(TextShardId("msg1", "shard1"), 5)
        controller.beginSelection(start)
        controller.extendSelectionTo(end)
        val newEnd = TextPosition(TextShardId("msg1", "shard1"), 3)
        controller.replaceEnd(newEnd)
        assertEquals(newEnd, controller.selection.value!!.end)
    }

    @Test
    fun selectionController_singleMessageId() {
        val controller = SelectionController()
        val start = TextPosition(TextShardId("msg1", "shard1"), 0)
        val end = TextPosition(TextShardId("msg1", "shard2"), 5)
        controller.beginSelection(start)
        controller.extendSelectionTo(end)
        assertEquals("msg1", controller.singleMessageId())
    }

    @Test
    fun selectionController_singleMessageId_differentMessages() {
        val controller = SelectionController()
        val start = TextPosition(TextShardId("msg1", "shard1"), 0)
        val end = TextPosition(TextShardId("msg2", "shard2"), 5)
        controller.beginSelection(start)
        controller.extendSelectionTo(end)
        assertNull(controller.singleMessageId())
    }

    @Test
    fun selectionController_rememberMessageMarkdown() {
        val controller = SelectionController()
        controller.rememberMessageMarkdown("msg1", "# Hello")
        // No direct getter, but should not throw
    }

    @Test
    fun selectionController_selectionMessageMarkdown_withoutSelection() {
        val controller = SelectionController()
        assertNull(controller.selectionMessageMarkdown())
    }

    @Test
    fun selectionController_rememberTableActions() {
        val controller = SelectionController()
        val actions = SelectionController.TableActions(
            copyTableMarkdown = {},
            copyTableImage = {}
        )
        controller.rememberTableActions("msg1", actions)
        // No direct getter, but should not throw
    }

    @Test
    fun selectionController_forgetTableActions() {
        val controller = SelectionController()
        val actions = SelectionController.TableActions(
            copyTableMarkdown = {},
            copyTableImage = {}
        )
        controller.rememberTableActions("msg1", actions)
        controller.forgetTableActions("msg1")
        // No direct getter, but should not throw
    }

    @Test
    fun selectionController_selectionTableActions_withoutSelection() {
        val controller = SelectionController()
        assertNull(controller.selectionTableActions())
    }

    @Test
    fun selectionController_hitTest_noShards() {
        val controller = SelectionController()
        val result = controller.hitTest(Offset(100f, 100f))
        assertNull(result)
    }

    @Test
    fun selectionController_hitTestStrict_noShards() {
        val controller = SelectionController()
        val result = controller.hitTestStrict(Offset(100f, 100f))
        assertNull(result)
    }

    @Test
    fun selectionController_handleAnchor_noSelection() {
        val controller = SelectionController()
        assertNull(controller.handleAnchor(SelectionController.Handle.Start))
        assertNull(controller.handleAnchor(SelectionController.Handle.End))
    }

    @Test
    fun selectionController_grabHandleAt_noSelection() {
        val controller = SelectionController()
        assertNull(controller.grabHandleAt(Offset(0f, 0f), 10f))
    }

    @Test
    fun selectionController_selectionWindowRect_noSelection() {
        val controller = SelectionController()
        assertNull(controller.selectionWindowRect())
    }

    @Test
    fun selectionController_handlesCenterX_noSelection() {
        val controller = SelectionController()
        assertNull(controller.handlesCenterX())
    }

    @Test
    fun selectionController_draggedHandleLineRect_noSelection() {
        val controller = SelectionController()
        assertNull(controller.draggedHandleLineRect(SelectionController.Handle.Start))
        assertNull(controller.draggedHandleLineRect(SelectionController.Handle.End))
    }

    @Test
    fun selectionController_visibleSelectionEndLineRect_noSelection() {
        val controller = SelectionController()
        assertNull(controller.visibleSelectionEndLineRect())
    }

    @Test
    fun selectionController_visibleSelectionWindowRect_noSelection() {
        val controller = SelectionController()
        assertNull(controller.visibleSelectionWindowRect())
    }

    @Test
    fun selectionController_orderedEndpoints_noSelection() {
        val controller = SelectionController()
        assertNull(controller.orderedEndpoints(TextSelection(
            start = TextPosition(TextShardId("msg1", "shard1"), 0),
            end = TextPosition(TextShardId("msg1", "shard1"), 5)
        )))
    }

    @Test
    fun selectionController_selectedPlainText_noSelection() {
        val controller = SelectionController()
        assertEquals("", controller.selectedPlainText())
    }

    @Test
    fun selectionController_beginSelectionWord_noShard() {
        val controller = SelectionController()
        val pos = TextPosition(TextShardId("msg1", "shard1"), 3)
        controller.beginSelectionWord(pos)
        assertNotNull(controller.selection.value)
        assertEquals(pos, controller.selection.value!!.start)
        assertEquals(pos, controller.selection.value!!.end)
    }

    @Test
    fun textShard_creation() {
        val shard = TextShard(
            id = TextShardId("msg1", "shard1"),
            plainText = "Hello",
            textLayoutResult = createMockTextLayoutResult("Hello"),
            positionInWindow = { Offset.Zero },
            sizePx = { IntSize(100, 20) }
        )
        assertEquals("Hello", shard.plainText)
        assertEquals(TextShardId("msg1", "shard1"), shard.id)
    }

    @Test
    fun textPosition_creation() {
        val pos = TextPosition(TextShardId("msg1", "shard1"), 5)
        assertEquals(TextShardId("msg1", "shard1"), pos.shard)
        assertEquals(5, pos.charOffset)
    }

    @Test
    fun textSelection_creation() {
        val start = TextPosition(TextShardId("msg1", "shard1"), 0)
        val end = TextPosition(TextShardId("msg1", "shard1"), 5)
        val selection = TextSelection(start = start, end = end)
        assertEquals(start, selection.start)
        assertEquals(end, selection.end)
    }

    @Test
    fun textShardId_creation() {
        val id = TextShardId("msg1", "shard1")
        assertEquals("msg1", id.messageId)
        assertEquals("shard1", id.shardId)
    }

    @Test
    fun buildTextShard_createsShard() {
        val shard = buildTextShard(
            id = TextShardId("msg1", "shard1"),
            plainText = "Test",
            layoutResult = createMockTextLayoutResult("Test"),
            coordinatesProvider = { null }
        )
        assertEquals("Test", shard.plainText)
        assertEquals(TextShardId("msg1", "shard1"), shard.id)
    }

    @Test
    fun buildTextShard_withRawMarkdown() {
        val shard = buildTextShard(
            id = TextShardId("msg1", "shard1"),
            plainText = "Test",
            layoutResult = createMockTextLayoutResult("Test"),
            coordinatesProvider = { null },
            rawMarkdown = "# Test"
        )
        assertEquals("# Test", shard.rawMarkdown)
    }

    private fun createMockTextLayoutResult(text: String): TextLayoutResult {
        // This is a simplified mock - in real tests you'd use a proper Compose test environment
        return TextLayoutResult(
            layoutInput = androidx.compose.ui.text.TextLayoutInput(
                text = androidx.compose.ui.text.AnnotatedString(text),
                style = androidx.compose.ui.text.TextStyle.Default,
                placeholders = emptyList(),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                constraints = androidx.compose.ui.unit.Constraints(1000, 1000),
                density = LocalDensity.current,
                layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr
            ),
            firstBaseline = 0f,
            lastBaseline = 20f,
            multiParagraph = null,
            size = IntSize(100, 20)
        )
    }
}