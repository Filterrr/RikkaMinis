package com.openminis.app.provider.antigravity

import com.openminis.app.data.model.LLMModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix-antigravity-minimal-400] Regression coverage for the real-device 400
 * `INVALID_ARGUMENT: Thinking level MINIMAL is not supported for this model`.
 *
 * Root cause: the OFF path used to translate "no thinking" into
 * `thinkingLevel: "minimal"` for flash targets, but the upstream registry
 * (CLIProxyAPI internal/registry/models/models.json) shows several
 * Antigravity models — gemini-3.7-flash-high, gemini-3.8-flash-high,
 * gemini-pro-agent, gemini-3.1-pro-low — support only low/medium/high.
 * Upstream's Applier (internal/thinking/provider/antigravity/apply.go)
 * deletes thinkingConfig entirely for ModeNone instead.
 */
class AntigravityThinkingConfigTest {

    private fun config(modelId: String, level: com.openminis.app.data.model.ThinkingLevel): JSONObject? {
        val provider = AntigravityProvider(
            accessToken = "test-token",
            model = LLMModel(modelId, modelId, "Antigravity"),
        )
        return provider.buildThinkingConfig(level)
    }

    @Test
    fun `off emits no thinkingConfig at all`() {
        // For every Antigravity target — not just models that happen to
        // support a lowest level Google would accept.
        listOf(
            "gemini-3-flash",
            "gemini-3.7-flash-high",
            "gemini-pro-agent",
            "claude-opus-4-6-thinking",
        ).forEach { modelId ->
            assertNull("OFF must omit thinkingConfig for $modelId", config(modelId, com.openminis.app.data.model.ThinkingLevel.OFF))
        }
    }

    @Test
    fun `low and medium pass through for models that reject minimal`() {
        val medium = config("gemini-3.7-flash-high", com.openminis.app.data.model.ThinkingLevel.MEDIUM)!!
        assertEquals("medium", medium.getString("thinkingLevel"))
        val low = config("gemini-pro-agent", com.openminis.app.data.model.ThinkingLevel.LOW)!!
        assertEquals("low", low.getString("thinkingLevel"))
        assertTrue(medium.getBoolean("includeThoughts"))
    }

    @Test
    fun `flash image clamps low and medium to minimal`() {
        // gemini-3.1-flash-image supports only minimal/high (registry parity).
        val low = config("gemini-3.1-flash-image", com.openminis.app.data.model.ThinkingLevel.LOW)!!
        assertEquals("minimal", low.getString("thinkingLevel"))
        val medium = config("gemini-3.1-flash-image", com.openminis.app.data.model.ThinkingLevel.MEDIUM)!!
        assertEquals("minimal", medium.getString("thinkingLevel"))
        val high = config("gemini-3.1-flash-image", com.openminis.app.data.model.ThinkingLevel.HIGH)!!
        assertEquals("high", high.getString("thinkingLevel"))
    }

    @Test
    fun `higher ranks collapse to high`() {
        for (level in listOf(
            com.openminis.app.data.model.ThinkingLevel.HIGH,
            com.openminis.app.data.model.ThinkingLevel.XHIGH,
            com.openminis.app.data.model.ThinkingLevel.MAX,
            com.openminis.app.data.model.ThinkingLevel.ULTRA,
        )) {
            val json = config("gemini-3-flash", level)!!
            assertEquals("high", json.getString("thinkingLevel"))
        }
    }

    @Test
    fun `no level string is ever minimal outside the image-only model`() {
        // Guard the exact device failure: minimal must never reach models
        // whose registry levels exclude it.
        val strictModels = listOf(
            "gemini-3.7-flash-high",
            "gemini-3.8-flash-high",
            "gemini-pro-agent",
            "gemini-3.1-pro-low",
        )
        for (modelId in strictModels) {
            for (level in com.openminis.app.data.model.ThinkingLevel.entries) {
                val json = config(modelId, level) ?: continue
                assertFalse(
                    "minimal leaked for $modelId at $level",
                    json.getString("thinkingLevel") == "minimal",
                )
            }
        }
    }
}
