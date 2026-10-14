package com.openminis.app.backup

import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-restore-provider-type-tolerance] One unreadable provider must not
 * take the other seven with it.
 *
 * The field failure this pins, from a user's restore log:
 *
 * ```
 * [ERROR] [Restore] provider_config.json unreadable:
 *   ProviderType does not contain element with name 'openAIResponses'
 *   at path $.instances[0].providerType
 * [INFO]  [Restore] providers: imported=0 ... unreadable=1 declared=8
 * ```
 *
 * An iOS package carried a provider type Android's enum lacked. The single
 * whole-document `decodeFromString` threw on it, and ALL EIGHT providers were
 * discarded — credentials included. The user was then shown "this backup
 * contains no API keys", which was never true: nothing had been read at all.
 *
 * Fork note: upstream rewrites an unknown type to an `unsupported` sentinel
 * and keeps the row visible-but-unusable. This fork's ProviderType has no
 * `unsupported` member (adding one flips 13 exhaustive `when`s across
 * provider/UI code), so an unknown type is DROPPED and counted instead —
 * same per-instance containment, no placeholder row. The tests below pin
 * that behaviour.
 */
class RestoreProviderTypeToleranceTest {

    private fun instance(id: String, type: String, label: String = "P-$id") = """
        {"id":"$id","label":"$label","providerType":"$type",
         "credentialType":"apiKey","isEnabled":true}
    """.trimIndent()

    private fun config(vararg instances: String) =
        """{"instances":[${instances.joinToString(",")}],"modelEntries":[],"modelGroups":[]}"""

    private fun parse(json: String) = BackupImporter.parseProviderConfigLeniently(json)

    // ─── The exact field regression ──────────────────────────────────────

    @Test
    fun `unknown type no longer fails the whole file`() {
        // The reported package shape: the unknown type sat at instances[0], so
        // it aborted the decode before any sibling was reached. Now only that
        // one instance is dropped and every sibling survives.
        val parsed = parse(
            config(
                instance("a", "openAIResponses"),
                instance("b", "anthropic"),
                instance("c", "gemini"),
                instance("d", "openAI"),
                instance("e", "openRouter"),
                instance("f", "xAI"),
                instance("g", "kimiCode"),
                instance("h", "openAI"),
            ),
        )
        assertEquals("7 siblings survive", 7, parsed.config.instances.size)
        assertEquals("the unknown one is counted", 1, parsed.droppedInstances)
    }

    // ─── Unknown types are dropped, not rewritten ────────────────────────

    @Test
    fun `a type from a newer build is dropped and counted`() {
        // This is the case a hand-edited enum could not cover: a value that
        // does not exist in ANY current build.
        val parsed = parse(
            config(
                instance("a", "someFutureProvider"),
                instance("b", "anthropic"),
            ),
        )
        assertEquals("known sibling kept", 1, parsed.config.instances.size)
        assertEquals("b", parsed.config.instances.single().id)
        assertEquals("unknown counted", 1, parsed.droppedInstances)
    }

    @Test
    fun `every known type still decodes as itself`() {
        // Guards against the normalizer over-reaching and dropping valid types.
        for (type in ProviderType.entries) {
            val parsed = parse(config(instance("x", type.name)))
            assertEquals(
                "type ${type.name} must round-trip",
                type,
                parsed.config.instances.single().providerType,
            )
        }
    }

    // ─── Genuinely malformed instances drop individually ─────────────────

    @Test
    fun `a malformed instance is dropped alone and counted`() {
        // Missing the required `label` — undecodable no matter what the type
        // says. It must cost only itself.
        val parsed = parse(
            """{"instances":[
                {"id":"bad","providerType":"openAI"},
                ${instance("good", "anthropic")}
            ],"modelEntries":[],"modelGroups":[]}""",
        )
        assertEquals("survivor kept", 1, parsed.config.instances.size)
        assertEquals("good", parsed.config.instances.single().id)
        assertEquals("bad one counted", 1, parsed.droppedInstances)
    }

    @Test
    fun `a non-object array element is dropped alone`() {
        val parsed = parse(
            """{"instances":["garbage", ${instance("good", "openAI")}],
                "modelEntries":[],"modelGroups":[]}""",
        )
        assertEquals(1, parsed.config.instances.size)
        assertEquals(1, parsed.droppedInstances)
    }

    @Test
    fun `an unknown credentialType costs only its own instance`() {
        // providerType is not the only enum field without a default —
        // credentialType is the other, so an iOS-only credential kind added
        // later would reproduce this bug exactly. The per-element loop is what
        // contains it: the sibling must survive.
        val parsed = parse(
            """{"instances":[
                {"id":"a","label":"L","providerType":"openAI","credentialType":"futureCred"},
                ${instance("b", "gemini")}
            ],"modelEntries":[],"modelGroups":[]}""",
        )
        assertEquals("sibling survives", 1, parsed.config.instances.size)
        assertEquals("b", parsed.config.instances.single().id)
        assertEquals(1, parsed.droppedInstances)
    }

    // ─── Top-level fields are preserved ──────────────────────────────────

    @Test
    fun `sibling top-level fields survive an instance drop`() {
        val parsed = parse(
            """{"instances":[${instance("a", "someFutureProvider")}],
                "modelEntries":[],"modelGroups":[],
                "defaultPrimaryGroupId":"grp-1"}""",
        )
        assertEquals("grp-1", parsed.config.defaultPrimaryGroupId)
    }

    @Test
    fun `a config with no instances array still parses`() {
        val parsed = parse("""{"modelEntries":[],"modelGroups":[]}""")
        assertNotNull(parsed.config)
        assertEquals(0, parsed.droppedInstances)
        assertTrue(parsed.config.instances.isEmpty())
    }

    @Test
    fun `an empty instances array is not an error`() {
        val parsed = parse(config())
        assertTrue(parsed.config.instances.isEmpty())
        assertEquals(0, parsed.droppedInstances)
    }
}
