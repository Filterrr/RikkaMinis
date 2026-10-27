package com.openminis.app.data.repository

/**
 * [feat/subagent-roster-routing] JVM stub for [EnvVarRepository].
 *
 * The REAL repository is an Android class: it holds a `Context`, reads a
 * JSON metadata file and pulls VALUES out of `EncryptedSharedPreferences`
 * (AES256-GCM via the Android keystore). None of that is available on the
 * plain JVM, and none of it is what the redaction rule depends on — the
 * rule only needs "the registered env-var values, as a map".
 *
 * So the local test harness compiles THIS instead of the real class: the
 * production `EnvVarRedactor` and `EnvVarPrivacyStore` are compiled from
 * source against it, and the redaction behaviour under test is the REAL
 * one. The stub is only a data holder.
 *
 * The value map is settable so a test can install secrets. Nothing else
 * about the real repository's storage is modelled — and nothing needs to
 * be: `allAsDict()` is the only member the redactor touches.
 */
class EnvVarRepository(private val context: android.content.Context? = null) {

    companion object {
        /** The map the stub serves. Set by tests; empty by default. */
        @JvmStatic
        var stubValues: Map<String, String> = emptyMap()
    }

    fun allAsDict(): Map<String, String> = stubValues
}
