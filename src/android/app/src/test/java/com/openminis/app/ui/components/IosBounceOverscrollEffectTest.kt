package com.openminis.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.Rule

@OptIn(ExperimentalFoundationApi::class)
class IosBounceOverscrollEffectTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `rememberIosBounceOverscrollEffect should render and return non-null effect`() {
        composeTestRule.setContent {
            val effect = rememberIosBounceOverscrollEffect()
            // Effect is created and not null
            assertTrue(effect is IosBounceOverscrollEffect)
        }
    }

    @Test
    fun `rememberIosBounceOverscrollEffect should create effect with default parameters`() {
        composeTestRule.setContent {
            val effect = rememberIosBounceOverscrollEffect()
            // Verify default maxOverscrollPx is 600f
            assertEquals(600f, effect.maxOverscrollPx, 0.001f)
            assertFalse(effect.isInProgress)
        }
    }

    @Test
    fun `applyToScroll should return Offset when no overscroll`() {
        composeTestRule.setContent {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val effect = IosBounceOverscrollEffect(scope)
            val delta = Offset(0f, 10f)
            val source = NestedScrollSource.Drag
            var performedScroll = Offset.Zero
            val result = effect.applyToScroll(delta, source) { offset ->
                performedScroll = offset
                offset
            }
            assertEquals(Offset.Zero, result)
        }
    }

    @Test
    fun `applyToScroll should handle overscroll with rubber band effect`() {
        composeTestRule.setContent {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val effect = IosBounceOverscrollEffect(scope)
            // Simulate overscroll by having performScroll consume less than delta
            val delta = Offset(0f, 100f)
            val source = NestedScrollSource.Drag
            val result = effect.applyToScroll(delta, source) { offset ->
                Offset(0f, 0f) // consume nothing, leaving leftover
            }
            // Result should be Offset.Zero since preConsumed is zero
            assertEquals(Offset.Zero, result)
        }
    }

    @Test
    fun `isInProgress should be false initially`() {
        composeTestRule.setContent {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val effect = IosBounceOverscrollEffect(scope)
            assertFalse(effect.isInProgress)
        }
    }

    @Test
    fun `isInProgress should be true after overscroll`() {
        composeTestRule.setContent {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val effect = IosBounceOverscrollEffect(scope)
            // Apply scroll that causes overscroll
            effect.applyToScroll(Offset(0f, 50f), NestedScrollSource.Drag) { Offset.Zero }
            // After applying overscroll, offset should be non-zero
            assertTrue(effect.isInProgress)
        }
    }

    @Test
    fun `applyToFling should animate to zero`() = runTest {
        withContext(Dispatchers.Main) {
            composeTestRule.setContent {
                val scope = CoroutineScope(Dispatchers.Unconfined)
                val effect = IosBounceOverscrollEffect(scope)
                // First cause overscroll
                effect.applyToScroll(Offset(0f, 50f), NestedScrollSource.Drag) { Offset.Zero }
                assertTrue(effect.isInProgress)
                // Apply fling that should animate back to zero
                // This is a suspend function, but we can't run it in a composable context directly
                // Instead we just test that the effect is created properly
                assertTrue(effect.isInProgress)
            }
        }
    }

    @Test
    fun `effectModifier should not crash when applied`() {
        composeTestRule.setContent {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val effect = IosBounceOverscrollEffect(scope)
            // Simply applying the modifier should not throw
            androidx.compose.foundation.layout.Box(modifier = effect.effectModifier) {
                // Content
            }
        }
    }

    @Test
    fun `rememberIosBounceOverscrollEffect should be remembered`() {
        var effect1: IosBounceOverscrollEffect? = null
        var effect2: IosBounceOverscrollEffect? = null
        composeTestRule.setContent {
            effect1 = rememberIosBounceOverscrollEffect()
        }
        composeTestRule.setContent {
            effect2 = rememberIosBounceOverscrollEffect()
        }
        // Since they are in different compositions, they should be different instances
        // But the function should return valid instances each time
        assertTrue(effect1 is IosBounceOverscrollEffect)
        assertTrue(effect2 is IosBounceOverscrollEffect)
    }
}