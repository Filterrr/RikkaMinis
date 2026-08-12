package com.openminis.app.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

class StreamingFadeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testFadeController_initialState() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            assertNotNull(controller)
            assertEquals("", controller.lastPlainText)
            assertEquals(0, controller.generation.value)
            assertFalse(controller.hasActiveRanges)
            assertTrue(controller.alphas.isEmpty())
        }
    }

    @Test
    fun testFadeController_ingestNewText() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello world")
            assertEquals("Hello world", controller.lastPlainText)
            assertTrue(controller.hasActiveRanges)
            assertEquals(0, controller.generation.value)
        }
    }

    @Test
    fun testFadeController_ingestAppendText() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello")
            controller.ingest("Hello world")
            assertEquals("Hello world", controller.lastPlainText)
            assertTrue(controller.hasActiveRanges)
        }
    }

    @Test
    fun testFadeController_ingestSameText() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello")
            val genBefore = controller.generation.value
            controller.ingest("Hello")
            assertEquals("Hello", controller.lastPlainText)
            assertEquals(genBefore, controller.generation.value)
        }
    }

    @Test
    fun testFadeController_ingestNonPrefixText() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello")
            controller.ingest("World")
            assertEquals("World", controller.lastPlainText)
            assertFalse(controller.hasActiveRanges)
            assertTrue(controller.alphas.isEmpty())
        }
    }

    @Test
    fun testFadeController_tickNoActiveRanges() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            assertFalse(controller.tick(System.nanoTime()))
        }
    }

    @Test
    fun testFadeController_tickWithActiveRanges() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello world")
            val result = controller.tick(System.nanoTime())
            assertTrue(result || !controller.hasActiveRanges)
        }
    }

    @Test
    fun testFadeController_overlayEmptyRanges() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            val base = buildAnnotatedString { append("Hello") }
            val result = controller.overlay(base, Color.Black)
            assertEquals(base, result)
        }
    }

    @Test
    fun testFadeController_overlayWithRanges() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello world")
            controller.tick(System.nanoTime())
            val base = buildAnnotatedString { append("Hello world") }
            val result = controller.overlay(base, Color.Black)
            assertNotNull(result)
            assertTrue(result.length == base.length)
        }
    }

    @Test
    fun testFadeController_overlayWithRangesShorterBase() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello world")
            val base = buildAnnotatedString { append("Hello") }
            val result = controller.overlay(base, Color.Black)
            assertEquals(base, result)
        }
    }

    @Test
    fun testFadeController_overlayWithRangesExceedsMaxFadeWords() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            val longText = (1..200).joinToString(" ") { "word$it" }
            controller.ingest(longText)
            assertFalse(controller.hasActiveRanges)
            val base = buildAnnotatedString { append(longText) }
            val result = controller.overlay(base, Color.Black)
            assertEquals(base, result)
        }
    }

    @Test
    fun testFadeController_ingestWithEmptySuffix() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello")
            val genBefore = controller.generation.value
            controller.ingest("Hello")
            assertEquals(genBefore, controller.generation.value)
        }
    }

    @Test
    fun testFadeController_ingestWithOnlyWhitespaceSuffix() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello")
            val genBefore = controller.generation.value
            controller.ingest("Hello   ")
            assertEquals(genBefore, controller.generation.value)
        }
    }

    @Test
    fun testFadeController_ingestWithMultipleWords() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello world foo bar")
            assertTrue(controller.hasActiveRanges)
            assertEquals(4, controller.rangesState.size)
        }
    }

    @Test
    fun testFadeController_ingestAfterClear() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello")
            controller.ingest("World")
            controller.ingest("Hello")
            assertEquals("Hello", controller.lastPlainText)
            assertFalse(controller.hasActiveRanges)
        }
    }

    @Test
    fun testFadeController_tickReturnsFalseWhenFinished() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello")
            // Simulate enough time passing
            val futureNanos = System.nanoTime() + 1_000_000_000L
            controller.tick(futureNanos)
            // Wait for all animations to complete
            val result = controller.tick(futureNanos + 1_000_000_000L)
            assertFalse(result)
            assertFalse(controller.hasActiveRanges)
        }
    }

    @Test
    fun testFadeController_bumpGenerationOnIngest() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            assertEquals(0, controller.generation.value)
            controller.ingest("Hello")
            assertEquals(0, controller.generation.value)
            controller.ingest("Hello world")
            assertEquals(0, controller.generation.value)
        }
    }

    @Test
    fun testFadeController_bumpGenerationOnNonPrefix() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello")
            controller.ingest("World")
            assertEquals(0, controller.generation.value)
        }
    }

    @Test
    fun testFadeColorHolder_defaultConstructor() {
        composeTestRule.setContent {
            val holder = FadeColorHolder()
            assertEquals(Color.Unspecified, holder.color)
            assertEquals(Color.Unspecified, holder.state.value)
        }
    }

    @Test
    fun testFadeColorHolder_customColor() {
        composeTestRule.setContent {
            val holder = FadeColorHolder(Color.Red)
            assertEquals(Color.Red, holder.color)
            assertEquals(Color.Red, holder.state.value)
        }
    }

    @Test
    fun testFadeColorHolder_updateColor() {
        composeTestRule.setContent {
            val holder = FadeColorHolder()
            holder.color = Color.Blue
            holder.state.value = Color.Blue
            assertEquals(Color.Blue, holder.color)
            assertEquals(Color.Blue, holder.state.value)
        }
    }

    @Test
    fun testLocalAppendOnlyFade_defaultValue() {
        composeTestRule.setContent {
            val value = LocalAppendOnlyFade.current
            assertFalse(value)
        }
    }

    @Test
    fun testLocalLiveIncremental_defaultValue() {
        composeTestRule.setContent {
            val value = LocalLiveIncremental.current
            assertFalse(value)
        }
    }

    @Test
    fun testRememberFadeController_returnsSameInstance() {
        composeTestRule.setContent {
            val controller1 = rememberFadeController()
            val controller2 = rememberFadeController()
            assertEquals(controller1, controller2)
        }
    }

    @Test
    fun testFadeFrameDriver_composableRenders() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            FadeFrameDriver(controller)
        }
        // Verify no crashes
    }

    @Test
    fun testFadeFrameDriver_withActiveRanges() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            controller.ingest("Hello world")
            FadeFrameDriver(controller)
        }
        // Verify no crashes
    }

    @Test
    fun testFadeFrameDriver_withEmptyController() {
        composeTestRule.setContent {
            val controller = rememberFadeController()
            FadeFrameDriver(controller)
        }
        // Verify no crashes
    }
}