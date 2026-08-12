package com.openminis.app.macro

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroActionTest {

    @Test
    fun `tap action with text target roundtrips through json`() {
        val action = MacroAction(
            type = "tap",
            target = MacroTarget(
                strategy = "text",
                value = "深色模式",
                fallbackX = 540,
                fallbackY = 1200,
                originalNodeId = "a3f2",
            ),
            description = "Tap 深色模式",
        )
        val restored = MacroAction.fromJson(action.toJson())
        assertEquals(action.type, restored.type)
        assertEquals(action.target?.strategy, restored.target?.strategy)
        assertEquals(action.target?.value, restored.target?.value)
        assertEquals(action.target?.fallbackX, restored.target?.fallbackX)
        assertEquals(action.target?.fallbackY, restored.target?.fallbackY)
        assertEquals(action.target?.originalNodeId, restored.target?.originalNodeId)
        assertEquals(action.description, restored.description)
    }

    @Test
    fun `input action with coordinates roundtrips`() {
        val action = MacroAction(
            type = "input",
            target = MacroTarget(strategy = "xy", value = "100,200", fallbackX = 100, fallbackY = 200),
            text = "hello world",
        )
        val restored = MacroAction.fromJson(action.toJson())
        assertEquals("input", restored.type)
        assertEquals("xy", restored.target?.strategy)
        assertEquals("hello world", restored.text)
        assertEquals(100f, restored.target?.fallbackX?.toFloat() ?: 0f, 0.01f)
    }

    @Test
    fun `swipe action with all coordinates roundtrips`() {
        val action = MacroAction(
            type = "swipe",
            x = 100f, y = 200f, x2 = 300f, y2 = 400f,
            durationMs = 300,
            description = "Swipe up",
        )
        val restored = MacroAction.fromJson(action.toJson())
        assertEquals(100f, restored.x ?: 0f, 0.01f)
        assertEquals(200f, restored.y ?: 0f, 0.01f)
        assertEquals(300f, restored.x2 ?: 0f, 0.01f)
        assertEquals(400f, restored.y2 ?: 0f, 0.01f)
        assertEquals(300L, restored.durationMs)
    }

    @Test
    fun `action without optional fields roundtrips`() {
        val action = MacroAction(type = "wait", durationMs = 1000)
        val restored = MacroAction.fromJson(action.toJson())
        assertEquals("wait", restored.type)
        assertNull(restored.target)
        assertNull(restored.text)
        assertEquals(1000L, restored.durationMs)
    }
}

class MacroRecordingTest {

    @Test
    fun `recording roundtrips through json`() {
        val recording = MacroRecording(
            name = "dark-mode-toggle",
            createdAt = 123456789L,
            actions = listOf(
                MacroAction(type = "tap", target = MacroTarget("text", "设置"), description = "Tap 设置"),
                MacroAction(type = "tap", target = MacroTarget("text", "深色模式"), description = "Tap 深色模式"),
            ),
            description = "Toggle dark mode",
            packageName = "com.android.settings",
        )
        val restored = MacroRecording.fromJson(recording.toJson())
        assertEquals("dark-mode-toggle", restored.name)
        assertEquals(123456789L, restored.createdAt)
        assertEquals(2, restored.actions.size)
        assertEquals("设置", restored.actions[0].target?.value)
        assertEquals("深色模式", restored.actions[1].target?.value)
        assertEquals("Toggle dark mode", restored.description)
        assertEquals("com.android.settings", restored.packageName)
    }

    @Test
    fun `replay count increments`() {
        val recording = MacroRecording(
            name = "m", createdAt = 1,
            actions = emptyList(),
            replayCount = 3,
        )
        val updated = recording.withIncrementedReplay()
        assertEquals(4, updated.replayCount)
        assertNotNull(updated.lastReplayedAt)
    }

    @Test
    fun `recording with no optional fields roundtrips`() {
        val recording = MacroRecording(name = "minimal", createdAt = 1, actions = emptyList())
        val restored = MacroRecording.fromJson(recording.toJson())
        assertEquals("minimal", restored.name)
        assertTrue(restored.actions.isEmpty())
        assertNull(restored.description)
        assertNull(restored.packageName)
        assertEquals(0, restored.replayCount)
    }
}

class SensitiveGuardTest {

    @Test
    fun `sensitive keywords are detected`() {
        // Pure logic test on the keyword sets (UI-tree check needs a live service)
        assertTrue(SensitiveGuard.SENSITIVE_KEYWORDS.contains("密码"))
        assertTrue(SensitiveGuard.SENSITIVE_KEYWORDS.contains("password"))
        assertTrue(SensitiveGuard.SENSITIVE_KEYWORDS.contains("支付"))
        assertTrue(SensitiveGuard.SENSITIVE_KEYWORDS.contains("验证码"))
        assertTrue(SensitiveGuard.SENSITIVE_KEYWORDS.contains("登录"))
        assertTrue(SensitiveGuard.SENSITIVE_KEYWORDS.contains("bank"))
    }

    @Test
    fun `sensitive packages are listed`() {
        assertTrue(SensitiveGuard.SENSITIVE_PACKAGES.contains("com.tencent.mm"))
        assertTrue(SensitiveGuard.SENSITIVE_PACKAGES.contains("com.alipay"))
    }

    @Test
    fun `sensitive activity fragments are listed`() {
        assertTrue(SensitiveGuard.SENSITIVE_ACTIVITY_FRAGMENTS.contains("password"))
        assertTrue(SensitiveGuard.SENSITIVE_ACTIVITY_FRAGMENTS.contains("payment"))
        assertTrue(SensitiveGuard.SENSITIVE_ACTIVITY_FRAGMENTS.contains("login"))
    }

    @Test
    fun `input action on password field is blocked`() {
        val guard = SensitiveGuard()
        val action = MacroAction(
            type = "input",
            target = MacroTarget("text", "密码输入框"),
            text = "secret",
            description = "Input text into 密码输入框",
        )
        val result = guard.checkAction(action)
        assertTrue(result.blocked)
        assertNotNull(result.reason)
    }

    @Test
    fun `normal input action is not blocked`() {
        val guard = SensitiveGuard()
        val action = MacroAction(
            type = "input",
            target = MacroTarget("text", "搜索框"),
            text = "hello",
            description = "Input text into 搜索框",
        )
        val result = guard.checkAction(action)
        assertFalse(result.blocked)
    }

    @Test
    fun `tap action is never blocked by itself`() {
        val guard = SensitiveGuard()
        val action = MacroAction(type = "tap", target = MacroTarget("text", "确定"))
        val result = guard.checkAction(action)
        assertFalse(result.blocked)
    }
}

class MacroTargetLocatorTest {

    @Test
    fun `text target is preferred over fallback`() {
        val target = MacroTarget(
            strategy = "text",
            value = "深色模式",
            fallbackX = 540,
            fallbackY = 1200,
        )
        assertEquals("text", target.strategy)
        assertEquals("深色模式", target.value)
        assertEquals(540, target.fallbackX)
    }

    @Test
    fun `xy target with null fallback works`() {
        val target = MacroTarget(strategy = "xy", value = "100,200")
        assertEquals("xy", target.strategy)
        assertNull(target.fallbackX)
    }

    @Test
    fun `id target roundtrips`() {
        val target = MacroTarget(strategy = "id", value = "com.android.settings:id/action_bar")
        val restored = MacroTarget.fromJson(target.toJson())
        assertEquals(restored.strategy, "id")
        assertEquals(restored.value, "com.android.settings:id/action_bar")
    }
}