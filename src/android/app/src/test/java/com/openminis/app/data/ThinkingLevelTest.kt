package com.openminis.app.data

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-thinking-level-arch] Phase 1 — deserialization safety for the
 * expanded ThinkingLevel enum. Guards the exact failure mode the architecture
 * spec calls out: an older build reading a level string a NEWER build wrote
 * must NOT throw (which would blow up the whole config load and wipe the UI).
 */
class ThinkingLevelTest {

    @Test
    fun decoded_returnsKnownLevels() {
        assertEquals(ThinkingLevel.MAX, ThinkingLevel.decoded("MAX"))
        assertEquals(ThinkingLevel.ULTRA, ThinkingLevel.decoded("ULTRA"))
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.decoded("HIGH"))
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.decoded("OFF"))
    }

    @Test
    fun decoded_unknownValueClampsToXHigh_neverThrows() {
        // A completely unrecognized future value must clamp to the highest
        // level THIS build knows, not throw.
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded("SUPREME"))
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded(""))
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded("garbage-token"))
    }

    @Test
    fun rank_followsDeclarationOrder() {
        assertEquals(0, ThinkingLevel.OFF.rank)
        assertEquals(4, ThinkingLevel.XHIGH.rank)
        assertEquals(5, ThinkingLevel.MAX.rank)
        assertEquals(6, ThinkingLevel.ULTRA.rank)
        // Sanity: intersection/clamp via rank works as expected.
        assertEquals(
            ThinkingLevel.HIGH,
            minOf(ThinkingLevel.ULTRA, ThinkingLevel.HIGH, compareBy { it.rank }),
        )
    }

    /**
     * The repository's Json instance uses coerceInputValues so an unknown enum
     * value in a persisted blob falls back to the property default instead of
     * throwing SerializationException. This is what stops a newer-build config
     * from wiping the UI when read by an older build.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun modelGroup_unknownThinkingLevel_coercesToNull_notThrow() {
        // Simulate JSON a NEWER build wrote: defaultThinkingLevel="ULTRA" is a
        // value this test's enum DOES know, so also throw in a truly-unknown one.
        val wire = """{"id":"g1","name":"G","memberEntryIds":[],"defaultThinkingLevel":"SUPREME"}"""
        val group = json.decodeFromString(ModelGroup.serializer(), wire)
        // Unknown enum value on a nullable-with-default field coerces to null.
        assertNull(group.defaultThinkingLevel)
        assertEquals("G", group.name) // other fields intact — config NOT wiped
    }

    @Test
    fun modelOverrides_unknownMaxThinkingLevel_coercesToNull() {
        val wire = """{"maxThinkingLevel":"SUPREME","displayName":"X"}"""
        val ov = json.decodeFromString(ModelOverrides.serializer(), wire)
        assertNull(ov.maxThinkingLevel)
        assertEquals("X", ov.displayName)
    }

    @Test
    fun modelOverrides_knownMaxThinkingLevel_roundTrips() {
        val wire = """{"maxThinkingLevel":"MAX"}"""
        val ov = json.decodeFromString(ModelOverrides.serializer(), wire)
        assertEquals(ThinkingLevel.MAX, ov.maxThinkingLevel)
    }

    // ── [T-android-thinking-level-arch] Max (极限) ceiling-override chain ──
    // The ModelEntryDetailScreen picker persists overrides.maxThinkingLevel;
    // these tests verify the resolution that makes Max selectable on a model
    // the catalog caps lower, plus the picker's rank-based availability filter.

    private fun entry(
        id: String,
        override: ThinkingLevel? = null,
        supportsReasoning: Boolean? = true,
    ) = ModelEntry(
        providerInstanceId = "p1",
        baseModel = LLMModel(
            id = id,
            displayName = id,
            provider = "openai",
            supportsReasoning = supportsReasoning,
        ),
        overrides = ModelOverrides(maxThinkingLevel = override),
    )

    @Test
    fun effectiveMax_noOverride_inheritsCatalogDefault() {
        // gpt-5.5 → catalog XHIGH; a mimo → HIGH.
        assertEquals(ThinkingLevel.XHIGH, entry("gpt-5.5-chat").effectiveMaxThinkingLevel)
        assertEquals(ThinkingLevel.HIGH, entry("mimo-v2.5-pro").effectiveMaxThinkingLevel)
    }

    @Test
    fun effectiveMax_userOverride_liftsCeilingToMax() {
        // The core of the feature: a model the catalog caps at XHIGH is lifted
        // to MAX (极限) by the user-set ceiling — exactly what the new picker
        // on ModelEntryDetailScreen persists.
        assertEquals(ThinkingLevel.MAX, entry("gpt-5.5-chat", override = ThinkingLevel.MAX).effectiveMaxThinkingLevel)
        // And can clamp down just as well.
        assertEquals(ThinkingLevel.HIGH, entry("claude-opus-4-8", override = ThinkingLevel.HIGH).effectiveMaxThinkingLevel)
    }

    @Test
    fun effectiveMax_overrideBeatsCatalog_forMaxCapableModel() {
        // gpt-5.6-sol is catalog-MAX; the explicit MAX override resolves to
        // the same ceiling (no regression), and ULTRA keeps working above it.
        assertEquals(ThinkingLevel.MAX, entry("gpt-5.6-sol", override = ThinkingLevel.MAX).effectiveMaxThinkingLevel)
        assertEquals(ThinkingLevel.ULTRA, entry("gpt-5.6-sol", override = ThinkingLevel.ULTRA).effectiveMaxThinkingLevel)
    }

    @Test
    fun availableLevels_rankFilter_includesMaxOnceCeilingLifted() {
        // Mirrors ChatViewModel.availableThinkingLevels:
        // filter { it != OFF && it.rank <= ceiling.rank }.
        fun available(ceiling: ThinkingLevel): List<ThinkingLevel> =
            ThinkingLevel.entries.filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }

        // Pre-override (catalog XHIGH): Max NOT offered.
        assertEquals(
            listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH),
            available(ThinkingLevel.XHIGH),
        )
        // Post-override (Max): Max IS offered, Ultra still isn't.
        assertEquals(
            listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX),
            available(ThinkingLevel.MAX),
        )
    }
}
