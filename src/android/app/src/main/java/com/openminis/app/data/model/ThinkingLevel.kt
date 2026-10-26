package com.openminis.app.data.model

import kotlinx.serialization.Serializable

/**
 * [feat/subagent-roster-routing] Reasoning-intensity levels, split out of
 * `ProviderConfig.kt`.
 *
 * Why this file exists: `ThinkingLevel` itself is pure (it depends only on
 * kotlinx.serialization), but it lived INSIDE `ProviderConfig.kt` — a file
 * whose imports pull in `androidx.compose.runtime.Stable` and the provider
 * constants. That made every class that merely MENTIONS a thinking level
 * (the sub-agent's `SubagentSkill`, the sub-agent's pure config parsing,
 * their JVM unit tests) drag the Compose toolchain into the local
 * compile — so those tests could only run under Gradle, never on the plain
 * JVM.
 *
 * Same lesson as the sub-agent runtime's other extractions: pure logic
 * belongs in a pure file so it can be unit-tested without Android. The
 * enum's ORDER and members are unchanged — only its location moved — so
 * every persisted value (`@Serializable` by NAME) stays compatible.
 */

@Serializable(with = ThinkingLevelSerializer::class)
enum class ThinkingLevel {
    // [T-android-thinking-level-arch] New cases MUST be appended at the end.
    // Kotlinx Serialization encodes enums by NAME string ("OFF"/"LOW"/...),
    // not declaration-order ordinal, so appending does not corrupt already-
    // persisted data. GPT-5.6 sol/terra reach ULTRA, luna reaches MAX.
    OFF, LOW, MEDIUM, HIGH, XHIGH, MAX, ULTRA;

    val isEnabled: Boolean get() = this != OFF

    val displayName: String
        get() = when (this) {
            OFF -> "Off"
            LOW -> "Low"
            MEDIUM -> "Medium"
            HIGH -> "High"
            XHIGH -> "XHigh"   // was "Max"; the label now belongs to the new MAX case
            MAX -> "Max"
            ULTRA -> "Ultra"
        }

    /** Intensity ordinal used for intersection / clamp comparisons —
     *  follows the enum declaration order. */
    val rank: Int get() = ordinal

    companion object {
        /**
         * [backup-upstream-1.14] Case-insensitive parse, null when unknown —
         * ported from upstream 1.14 for the backup debug surface and the
         * sub-agent record mapper (a hand-edited or newer package may carry a
         * level name this build does not know).
         */
        fun parseOrNull(raw: String): ThinkingLevel? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

        /**
         * [T-android-thinking-level-arch] Safe deserialization fallback for a
         * level string that this app build doesn't recognize — e.g. an older
         * build reading a token a NEWER build persisted (Room DB / JSON mirror).
         * Mirrors the `runCatching { … }.getOrNull()` guard already used for
         * ImageEndpointMode (ProviderConfigMapping.kt). NEVER throws: an unknown
         * value clamps to the highest level THIS build knows (XHIGH) rather than
         * letting the caller handle an exception or drop the whole config. Use
         * this for every "read from persisted data" path.
         */
        fun decoded(raw: String): ThinkingLevel =
            // [backup-upstream-1.14] Case-insensitive via parseOrNull: iOS's
            // raw values are lowercase ("high"), Android's enum names upper —
            // a package or JSON mirror from either side must decode.
            parseOrNull(raw) ?: XHIGH
    }
}

/**
 * [backup-upstream-1.14] Plain-string codec for [ThinkingLevel] (upstream
 * T-android-restore-thinking-level). Used wherever the level rides a
 * persisted/wire structure (ModelOverrides.maxThinkingLevel,
 * SubAgentDefinition.thinkingLevelOverride, the ProviderConfig JSON mirror):
 * a plain string descriptor cannot be coerced to null, and an unrecognized
 * value (a level from a newer build, or iOS's lowercase spelling) decodes
 * through [ThinkingLevel.decoded] to the XHIGH clamp rather than failing the
 * whole document — kotlinx.serialization aborts the entire decode on one bad
 * element, which would drop every provider/group in the same file.
 */
object ThinkingLevelSerializer : kotlinx.serialization.KSerializer<ThinkingLevel> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
            "com.openminis.app.data.model.ThinkingLevel",
            kotlinx.serialization.descriptors.PrimitiveKind.STRING,
        )

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: ThinkingLevel) =
        encoder.encodeString(value.name)

    override fun deserialize(
        decoder: kotlinx.serialization.encoding.Decoder,
    ): ThinkingLevel = ThinkingLevel.decoded(decoder.decodeString())
}
