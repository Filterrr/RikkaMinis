package com.openminis.app.tools

import com.openminis.app.data.EnvVarPrivacyStore
import com.openminis.app.data.EnvVarRedactor

/**
 * [fix/subagent-journal-redaction] Secret scrubbing for the sub-agent journal.
 *
 * Why this exists: shell output is scrubbed before it reaches the MODEL
 * ([EnvVarRedactor] on the shell-execute path), but the JOURNAL was written
 * from the raw text. A sub-agent that echoed a secret into its own report —
 * or into a step's captured output, or into the run's query — wrote that
 * secret verbatim into `/var/minis/workspace/.subagent/<runId>.md`, a file
 * the parent agent (and the user) can `file_read` at any time.
 *
 * The journal is a DURABLE record: it outlives the run and the session. It
 * is therefore the last place a secret should be allowed to rest.
 *
 * Pure by construction: the caller supplies the values to scrub. The live
 * wiring ([currentSecretValues]) is a separate function so the redaction
 * rule stays unit-testable on the plain JVM — no Context, no repository.
 */
object SubagentJournalRedaction {

    /**
     * Mask every occurrence of a secret value in [text].
     *
     * Returns [text] unchanged when there is nothing to scrub. The masking
     * rule is [EnvVarRedactor]'s (first 2 + stars + last 2), and values
     * shorter than its minimum match length are skipped — the same
     * threshold the live shell path uses, so the journal and the model
     * context agree on what counts as scrubbed.
     */
    fun redact(text: String, secretValues: Collection<String>): String {
        if (text.isBlank() || secretValues.isEmpty()) return text
        val (masked, hits) = EnvVarRedactor.redact(text, secretValues)
        return if (hits > 0) masked else text
    }

    /**
     * The env-var values currently registered as secrets.
     *
     * Empty when the repository is not wired yet (early boot), when the user
     * has no env vars, or when Privacy Mode is OFF — all ordinary states.
     *
     * The Privacy Mode check matters: the live shell path scrubs only when
     * the user opted in ([EnvVarRedactor.redactIfEnabled]). A journal that
     * scrubbed unconditionally would be stricter than the model context,
     * which would surprise a user who deliberately turned Privacy Mode off
     * to inspect a raw value. Same rule on both sides.
     */
    fun currentSecretValues(): Collection<String> {
        if (!EnvVarPrivacyStore.isEnabled) return emptyList()
        return EnvVarRedactor.envVarRepository?.allAsDict()?.values.orEmpty()
    }
}
