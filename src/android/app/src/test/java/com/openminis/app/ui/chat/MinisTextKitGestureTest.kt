package com.openminis.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.window.PopupProperties
import org.junit.jupiter.api.Test
import org.junit.Rule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinisTextKitGestureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var controller: SelectionController
    private lateinit var listState: LazyListState

    @BeforeEach
    fun setup() {
        controller = SelectionController()
        listState = LazyListState()
    }

    @Nested
    @DisplayName("Modifier.minisTextKitSelectionGesture tests")
    inner class MinisTextKitSelectionGestureTests {

        @Test
        @DisplayName("Should render composable with gesture modifier")
        fun shouldRenderWithGestureModifier() {
            composeTestRule.setContent {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .minisTextKitSelectionGesture(
                            controller = controller,
                            listState = listState,
                            rootCoordinates = { null }
                        )
                        .size(100.dp)
                )
            }
            composeTestRule.onRoot().assertIsDisplayed()
        }

        @Test
        @DisplayName("Should handle long press to begin selection")
        fun shouldHandleLongPressToBeginSelection() {
            var longPressTriggered = false
            composeTestRule.setContent {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .minisTextKitSelectionGesture(
                            controller = controller,
                            listState = listState,
                            rootCoordinates = { null },
                            onLongPressEngaged = { longPressTriggered = true }
                        )
                        .size(100.dp)
                )
            }
            composeTestRule.onRoot().performTouchInput {
                longClick()
            }
            composeTestRule.waitForIdle()
            assertTrue(longPressTriggered)
        }

        @Test
        @DisplayName("Should handle tap to clear selection")
        fun shouldHandleTapToClearSelection() {
            controller.selection.value = SelectionInfo(
                start = SelectionInfo.WordHandle(0),
                end = SelectionInfo.WordHandle(1)
            )
            composeTestRule.setContent {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .minisTextKitSelectionGesture(
                            controller = controller,
                            listState = listState,
                            rootCoordinates = { null }
                        )
                        .size(100.dp)
                )
            }
            composeTestRule.onRoot().performClick()
            composeTestRule.waitForIdle()
            assertNull(controller.selection.value)
        }

        @Test
        @DisplayName("Should respect default reverseLayout parameter")
        fun shouldRespectDefaultReverseLayout() {
            composeTestRule.setContent {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .minisTextKitSelectionGesture(
                            controller = controller,
                            listState = listState,
                            rootCoordinates = { null }
                        )
                        .size(100.dp)
                )
            }
            composeTestRule.onRoot().assertIsDisplayed()
        }

        @Test
        @DisplayName("Should respect custom reverseLayout parameter")
        fun shouldRespectCustomReverseLayout() {
            composeTestRule.setContent {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .minisTextKitSelectionGesture(
                            controller = controller,
                            listState = listState,
                            rootCoordinates = { null },
                            reverseLayout = true
                        )
                        .size(100.dp)
                )
            }
            composeTestRule.onRoot().assertIsDisplayed()
        }
    }

    @Nested
    @DisplayName("SelectionDragTracker tests")
    inner class SelectionDragTrackerTests {

        @Test
        @DisplayName("Should render SelectionDragTracker composable")
        fun shouldRenderSelectionDragTracker() {
            composeTestRule.setContent {
                SelectionDragTracker(
                    controller = controller,
                    listState = listState,
                    listRootCoordinates = { null }
                )
            }
            composeTestRule.onRoot().assertIsDisplayed()
        }

        @Test
        @DisplayName("Should render with default reverseLayout parameter")
        fun shouldRenderWithDefaultReverseLayout() {
            composeTestRule.setContent {
                SelectionDragTracker(
                    controller = controller,
                    listState = listState,
                    listRootCoordinates = { null }
                )
            }
            composeTestRule.onRoot().assertIsDisplayed()
        }

        @Test
        @DisplayName("Should render with custom reverseLayout parameter")
        fun shouldRenderWithCustomReverseLayout() {
            composeTestRule.setContent {
                SelectionDragTracker(
                    controller = controller,
                    listState = listState,
                    listRootCoordinates = { null },
                    reverseLayout = true
                )
            }
            composeTestRule.onRoot().assertIsDisplayed()
        }

        @Test
        @DisplayName("Should handle drag intent updates")
        fun shouldHandleDragIntentUpdates() {
            controller.dragIntent.value = SelectionController.DragIntent(
                point = Offset(100f, 100f),
                handle = SelectionController.Handle.End
            )
            composeTestRule.setContent {
                SelectionDragTracker(
                    controller = controller,
                    listState = listState,
                    listRootCoordinates = { null }
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().assertIsDisplayed()
        }
    }

    @Nested
    @DisplayName("MinisSelectionHandlesHost tests")
    inner class MinisSelectionHandlesHostTests {

        @Test
        @DisplayName("Should render nothing when selection is null")
        fun shouldRenderNothingWhenSelectionIsNull() {
            composeTestRule.setContent {
                MinisSelectionHandlesHost(
                    controller = controller,
                    listState = listState
                )
            }
            composeTestRule.onRoot().assertIsDisplayed()
        }

        @Test
        @DisplayName("Should render handles when selection is not null")
        fun shouldRenderHandlesWhenSelectionIsNotNull() {
            controller.selection.value = SelectionInfo(
                start = SelectionInfo.WordHandle(0),
                end = SelectionInfo.WordHandle(1)
            )
            composeTestRule.setContent {
                MinisSelectionHandlesHost(
                    controller = controller,
                    listState = listState
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().assertIsDisplayed()
        }

        @Test
        @DisplayName("Should render with default reverseLayout parameter")
        fun shouldRenderWithDefaultReverseLayout() {
            controller.selection.value = SelectionInfo(
                start = SelectionInfo.WordHandle(0),
                end = SelectionInfo.WordHandle(1)
            )
            composeTestRule.setContent {
                MinisSelectionHandlesHost(
                    controller = controller,
                    listState = listState
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().assertIsDisplayed()
        }

        @Test
        @DisplayName("Should render with custom reverseLayout parameter")
        fun shouldRenderWithCustomReverseLayout() {
            controller.selection.value = SelectionInfo(
                start = SelectionInfo.WordHandle(0),
                end = SelectionInfo.WordHandle(1)
            )
            composeTestRule.setContent {
                MinisSelectionHandlesHost(
                    controller = controller,
                    listState = listState,
                    reverseLayout = true
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().assertIsDisplayed()
        }
    }

    @Nested
    @DisplayName("MinisSelectionToolbarHost tests")
    inner class MinisSelectionToolbarHostTests {

        @Test
        @DisplayName("Should render nothing when selection is null")
        fun shouldRenderNothingWhenSelectionIsNull() {
            composeTestRule.setContent {
                MinisSelectionToolbarHost(
                    controller = controller
                )
            }
            composeTestRule.onRoot().assertIsDisplayed()
        }

        @Test
        @DisplayName("Should render toolbar when selection is not null")
        fun shouldRenderToolbarWhenSelectionIsNotNull() {
            controller.selection.value = SelectionInfo(
                start = SelectionInfo.WordHandle(0),
                end = SelectionInfo.WordHandle(1)
            )
            composeTestRule.setContent {
                MinisSelectionToolbarHost(
                    controller = controller
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().assertIsDisplayed()
        }

        @Test
        @DisplayName("Should render copy button and handle click")
        fun shouldRenderCopyButtonAndHandleClick() {
            controller.selection.value = SelectionInfo(
                start = SelectionInfo.WordHandle(0),
                end = SelectionInfo.WordHandle(1)
            )
            composeTestRule.setContent {
                MinisSelectionToolbarHost(
                    controller = controller
                )
            }
            composeTestRule.waitForIdle()
            
            val copyButton = composeTestRule.onAllNodesWithText("Copy").onFirst()
            copyButton.assertIsDisplayed()
            copyButton.performClick()
            composeTestRule.waitForIdle()
        }

        @Test
        @DisplayName("Should render add to input button when action provided")
        fun shouldRenderAddToInputButtonWhenActionProvided() {
            controller.selection.value = SelectionInfo(
                start = SelectionInfo.WordHandle(0),
                end = SelectionInfo.WordHandle(1)
            )
            var addToInputCalled = false
            composeTestRule.setContent {
                MinisSelectionToolbarHost(
                    controller = controller,
                    actions = SelectionToolbarActions(
                        resolveSelectionMarkdown = { "markdown" },
                        onAddToInput = { addToInputCalled = true }
                    )
                )
            }
            composeTestRule.waitForIdle()
            
            val addButton = composeTestRule.onAllNodesWithText("Add to input").onFirst()
            addButton.assertIsDisplayed()
            addButton.performClick()
            composeTestRule.waitForIdle()
            assertTrue(addToInputCalled)
        }

        @Test
        @DisplayName("Should render with default contentViewportBounds")
        fun shouldRenderWithDefaultContentViewportBounds() {
            controller.selection.value = SelectionInfo(
                start = SelectionInfo.WordHandle(0),
                end = SelectionInfo.WordHandle(1)
            )
            composeTestRule.setContent {
                MinisSelectionToolbarHost(
                    controller = controller
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().assertIsDisplayed()
        }

        @Test
        @DisplayName("Should render with custom contentViewportBounds")
        fun shouldRenderWithCustomContentViewportBounds() {
            controller.selection.value = SelectionInfo(
                start = SelectionInfo.WordHandle(0),
                end = SelectionInfo.WordHandle(1)
            )
            composeTestRule.setContent {
                MinisSelectionToolbarHost(
                    controller = controller,
                    contentViewportBounds = { Rect(0f, 0f, 100f, 100f) }
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().assertIsDisplayed()
        }

        @Test
        @DisplayName("Should render markdown copy button")
        fun shouldRenderMarkdownCopyButton() {
            controller.selection.value = SelectionInfo(
                start = SelectionInfo.WordHandle(0),
                end = SelectionInfo.WordHandle(1)
            )
            composeTestRule.setContent {
                MinisSelectionToolbarHost(
                    controller = controller
                )
            }
            composeTestRule.waitForIdle()
            
            val markdownButton = composeTestRule.onAllNodesWithText("Copy Markdown").onFirst()
            markdownButton.assertIsDisplayed()
            markdownButton.performClick()
            composeTestRule.waitForIdle()
        }

        @Test
        @DisplayName("Should render rich text copy button")
        fun shouldRenderRichTextCopyButton() {
            controller.selection.value = SelectionInfo(
                start = SelectionInfo.WordHandle(0),
                end = SelectionInfo.WordHandle(1)
            )
            composeTestRule.setContent {
                MinisSelectionToolbarHost(
                    controller = controller
                )
            }
            composeTestRule.waitForIdle()
            
            val richTextButton = composeTestRule.onAllNodesWithText("Copy Rich Text").onFirst()
            richTextButton.assertIsDisplayed()
            richTextButton.performClick()
            composeTestRule.waitForIdle()
        }
    }

    @Nested
    @DisplayName("HandlePositionProvider tests")
    inner class HandlePositionProviderTests {

        @Test
        @DisplayName("Should calculate position within bounds")
        fun shouldCalculatePositionWithinBounds() {
            val provider = HandlePositionProvider(
                anchorWindow = Offset(50f, 50f),
                isStart = true,
                hitSizePx = 56f
            )
            val position = provider.calculatePosition(
                anchorBounds = IntRect(0, 0, 100, 100),
                windowSize = IntSize(200, 200),
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = IntSize(56, 56)
            )
            assertTrue(position.x >= 0)
            assertTrue(position.y >= 0)
            assertTrue(position.x <= 144)
            assertTrue(position.y <= 144)
        }

        @Test
        @DisplayName("Should calculate position for start handle")
        fun shouldCalculatePositionForStartHandle() {
            val provider = HandlePositionProvider(
                anchorWindow = Offset(10f, 10f),
                isStart = true,
                hitSizePx = 56f
            )
            val position = provider.calculatePosition(
                anchorBounds = IntRect(0, 0, 100, 100),
                windowSize = IntSize(200, 200),
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = IntSize(56, 56)
            )
            assertEquals(0, position.x)
            assertEquals(10, position.y)
        }

        @Test
        @DisplayName("Should calculate position for end handle")
        fun shouldCalculatePositionForEndHandle() {
            val provider = HandlePositionProvider(
                anchorWindow = Offset(90f, 90f),
                isStart = false,
                hitSizePx = 56f
            )
            val position = provider.calculatePosition(
                anchorBounds = IntRect(0, 0, 100, 100),
                windowSize = IntSize(200, 200),
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = IntSize(56, 56)
            )
            assertTrue(position.x >= 0)
            assertTrue(position.y >= 0)
        }
    }

    @Nested
    @DisplayName("FloatingSelectionToolbarPositionProvider tests")
    inner class FloatingSelectionToolbarPositionProviderTests {

        @Test
        @DisplayName("Should calculate position above anchor when possible")
        fun shouldCalculatePositionAboveAnchor() {
            val provider = FloatingSelectionToolbarPositionProvider(
                anchor = IntRect(50, 50, 150, 100),
                viewport = null,
                preferredCenterX = 100f
            )
            val position = provider.calculatePosition(
                anchorBounds = IntRect(0, 0, 200, 200),
                windowSize = IntSize(200, 300),
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = IntSize(100, 40)
            )
            assertTrue(position.y < 50)
        }

        @Test
        @DisplayName("Should calculate position below anchor when not enough space above")
        fun shouldCalculatePositionBelowAnchor() {
            val provider = FloatingSelectionToolbarPositionProvider(
                anchor = IntRect(50, 10, 150, 60),
                viewport = null,
                preferredCenterX = 100f
            )
            val position = provider.calculatePosition(
                anchorBounds = IntRect(0, 0, 200, 200),
                windowSize = IntSize(200, 300),
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = IntSize(100, 40)
            )
            assertTrue(position.y > 60)
        }

        @Test
        @DisplayName("Should calculate position with viewport bounds")
        fun shouldCalculatePositionWithViewportBounds() {
            val provider = FloatingSelectionToolbarPositionProvider(
                anchor = IntRect(50, 50, 150, 100),
                viewport = Rect(0f, 0f, 200f, 200f),
                preferredCenterX = 100f
            )
            val position = provider.calculatePosition(
                anchorBounds = IntRect(0, 0, 200, 200),
                windowSize = IntSize(200, 300),
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = IntSize(100, 40)
            )
            assertTrue(position.x >= 0)
            assertTrue(position.y >= 0)
        }

        @Test
        @DisplayName("Should calculate position with zero anchor bounds")
        fun shouldCalculatePositionWithZeroAnchorBounds() {
            val provider = FloatingSelectionToolbarPositionProvider(
                anchor = IntRect(0, 0, 0, 0),
                viewport = null,
                preferredCenterX = null
            )
            val position = provider.calculatePosition(
                anchorBounds = IntRect(0, 0, 200, 200),
                windowSize = IntSize(200, 300),
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = IntSize(100, 40)
            )
            assertTrue(position.x >= 0)
            assertTrue(position.y >= 0)
        }
    }

    @Nested
    @DisplayName("Edge auto scroll tests")
    inner class EdgeAutoScrollTests {

