package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderCredentialMeta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-multi-api-key] Round-trip tests for the credential layer of the backup /
 * multi-device-sync document.
 *
 * ## Why this seam
 *
 * `ProviderRepository` needs an Android Context, so the export/import bodies
 * are pinned through the pure internal seams
 * [writeCredentialMeta] / [readCredentialMeta] — the same pattern the project
 * uses for the run-config fields ([ProviderRunConfigRoundTripTest]).
 *
 * ## What actually matters here
 *
 * The user asked specifically for multiple keys to travel in the sync
 * document. Two properties make that safe to ship:
 *
 *  1. **Upgrade in both directions.** A payload written by an older build has
 *     only the singular `apiKey`; it must become a well-formed one-credential
 *     list rather than the ambiguous "never configured" state (which would
 *     make the user's existing key look absent).
 *  2. **Metadata survives without secrets.** A secrets-stripped sync snapshot
 *     still carries labels/notes/order, so a sibling device shows the user's
 *     naming even when it deliberately does not receive the keys.
 */
class CredentialMetaRoundTripTest {

    private fun meta(
        label: String,
        note: String = "",
        enabled: Boolean = true,
        migrated: Boolean = false,
    ) = ProviderCredentialMeta(
        label = label,
        note = note,
        isEnabled = enabled,
        migrated = migrated,
    )

    // ─── multi-credential round-trip ───────────────────────────────────────

    @Test fun roundTrip_preservesOrderLabelsNotesAndEnabledFlags() {
        val source = listOf(
            meta("主号", note = "公司卡"),
            meta("备用", note = "室友的卡", enabled = false),
            meta("", note = "third"),
        )
        val json = JSONObject()
        writeCredentialMeta(json, source)

        val restored = readCredentialMeta(json)
        assertEquals(3, restored.size)
        // Order is meaningful (it is the rotation order the user arranged).
        assertEquals(listOf("主号", "备用", ""), restored.map { it.label })
        assertEquals(listOf("公司卡", "室友的卡", "third"), restored.map { it.note })
        assertEquals(listOf(true, false, true), restored.map { it.isEnabled })
    }

    @Test fun roundTrip_preservesStableIdentity() {
        val source = listOf(meta("a"), meta("b"))
        val json = JSONObject()
        writeCredentialMeta(json, source)
        val restored = readCredentialMeta(json)
        // Identity must survive verbatim: it is what keeps a credential's
        // health record attached to the right secret across a restore, where
        // position alone would drift.
        assertEquals(source.map { it.id }, restored.map { it.id })
    }

    @Test fun emptyMetadataWritesNothing() {
        val json = JSONObject()
        writeCredentialMeta(json, emptyList())
        // No field at all → readCredentialMeta's "leave the instance alone"
        // branch, not a synthesized credential.
        assertFalse(json.has("credentials"))
        assertTrue(readCredentialMeta(json).isEmpty())
    }

    @Test fun migratedFlagSurvivesRoundTrip() {
        val json = JSONObject()
        writeCredentialMeta(json, listOf(meta("old", migrated = true)))
        assertTrue(readCredentialMeta(json).single().migrated)
    }

    // ─── legacy upgrade ────────────────────────────────────────────────────

    @Test fun legacySingularApiKey_synthesizesOneMigratedCredential() {
        // An older build's export: a working key, no metadata. Leaving the list
        // empty would put the instance in the "never configured" state while it
        // actually holds a secret — the key would look lost to the user.
        val legacy = JSONObject().apply { put("apiKey", "c2stbGVnYWN5") }
        val restored = readCredentialMeta(legacy)
        assertEquals(1, restored.size)
        assertTrue(restored.single().migrated)
        assertEquals("", restored.single().label)
    }

    @Test fun legacyPluralApiKeysWithoutMetadata_alsoSynthesizesOne() {
        val legacy = JSONObject().apply {
            put("apiKeys", org.json.JSONArray().put("YQ==").put("Yg=="))
        }
        // The upgrade path keys off "a secret is present", not on which
        // spelling carried it, so a half-migrated payload still restores.
        assertEquals(1, readCredentialMeta(legacy).size)
    }

    @Test fun payloadWithNoSecretsAndNoMetadata_staysEmpty() {
        // A secrets-stripped SYNC snapshot for a provider that never had
        // credentials configured: nothing to invent.
        val empty = JSONObject().apply { put("providerType", "openAI") }
        assertTrue(readCredentialMeta(empty).isEmpty())
    }

    @Test fun secretsStrippedSyncSnapshot_keepsMetadata() {
        // The sync path removes apiKey/apiKeys (SECRET_PROVIDER_KEYS) but MUST
        // leave the metadata array — a label is not a secret, and the user's
        // naming should reach the sibling device.
        val full = JSONObject().apply {
            put("apiKey", "c2stYQ==")
            put("apiKeys", org.json.JSONArray().put("c2stYQ==").put("c2stYg=="))
            writeCredentialMeta(this, listOf(meta("主号"), meta("备用")))
        }
        for (k in listOf("apiKey", "apiKeys")) full.remove(k)

        val restored = readCredentialMeta(full)
        assertEquals(listOf("主号", "备用"), restored.map { it.label })
    }

    // ─── tolerance ─────────────────────────────────────────────────────────

    @Test fun malformedEntryIsSkippedNotFatal() {
        // One bad row must not abort the whole provider import — same tolerance
        // the enum-parse seam shows for image-endpoint modes.
        val json = JSONObject().apply {
            put(
                "credentials",
                org.json.JSONArray()
                    .put(JSONObject().apply { put("label", "good") })
                    .put("not-an-object")
                    .put(JSONObject().apply { put("label", "also-good") }),
            )
        }
        val restored = readCredentialMeta(json)
        assertEquals(listOf("good", "also-good"), restored.map { it.label })
    }

    @Test fun unknownExtraKeysAreIgnored() {
        // Forward compatibility: a newer build may add fields; an older reader
        // must still restore what it understands.
        val json = JSONObject().apply {
            put(
                "credentials",
                org.json.JSONArray().put(
                    JSONObject().apply {
                        put("label", "k")
                        put("futureField", "whatever")
                    },
                ),
            )
        }
        assertEquals("k", readCredentialMeta(json).single().label)
    }

    @Test fun missingEnabledFlagDefaultsToEnabled() {
        val json = JSONObject().apply {
            put("credentials", org.json.JSONArray().put(JSONObject().put("label", "k")))
        }
        // Absent means "not disabled" — defaulting to disabled would silently
        // park a key the user never turned off.
        assertTrue(readCredentialMeta(json).single().isEnabled)
    }

    // ─── secret decoding ───────────────────────────────────────────────────

    @Test fun decodeOfEmptyIsNull() {
        // The one branch that is pure Kotlin (no platform Base64 call):
        // an empty slot must read as "absent", not as an empty credential —
        // a blank Bearer token would burn a rotation attempt.
        assertNull(decodeExportedSecret(""))
    }

    @Test fun decodeOfPlaintextFallsBackToRaw() {
        // JVM unit tests stub android.util.Base64 (returnDefaultValues), so a
        // real base64 round-trip is only meaningful in an instrumented test.
        // What IS pinned here is the contract that matters under stubbing and
        // for hand-written payloads: a non-empty input never throws and never
        // yields null, so a plaintext secret survives.
        val out = decodeExportedSecret("sk-plain-text-key")
        assertTrue("expected a non-null decode, got $out", out != null)
        assertTrue("expected non-empty decode, got '$out'", out!!.isNotEmpty())
    }
}
