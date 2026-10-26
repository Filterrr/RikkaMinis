package com.openminis.app.tools

import com.openminis.app.data.EnvVarPrivacyStore
import com.openminis.app.data.EnvVarRedactor

/**
 * [fix/tool-output-env-redaction] Mask env-var values in ANY tool output
 * before it reaches the model.
 *
 * Why this file exists: [EnvVarRedactor] was only called from the two
 * shell executors (`ChatViewModel.executeShell` and
 * `SubagentRunner.executeSubagentShell`). Every other tool — `file_read`,
 * `file_write`, `file_edit`, `browser_use`, `web_search` — returned its
 * output verbatim. That is a real leak, not a theoretical one:
 *
 * - A sub-agent told to "read the settings file" gets the plaintext of
 *   every configured secret, and the model then repeats it in its report.
 * - The value lands in the tool_result message, which is persisted in the
 *   chat DB — so the secret outlives the session.
 * - The sub-agent's journal (`.subagent/<runId>.md`) is a durable,
 *   `file_read`-able file that records the transcript verbatim.
 *
 * PR #79 added [SubagentJournalRedaction], which scrubs the journal on
 * WRITE. That protects the journal file but NOT the bytes the model saw
 * while producing it, and not the other tools. The rule has to sit where
 * the output is handed to the model, so every tool is covered by one rule.
 *
 * Where the masking happens:
 *
 * - [maskForModel] is used on the text that goes into the tool_result the
 *   MODEL reads. The user's own tool block keeps the raw text, exactly
 *   like the shell path does ("the user still sees the unmasked output in
 *   chat; only the bytes flowing into the agent's context are rewritten").
 * - [maskQuiet] is used for durable diagnostics that are not model
 *   context (the agent trace, the failure-learning hook) — masked, but
 *   without the system reminder, which is an instruction to the model and
 *   would be noise in a log file.
 *
 * Pure by construction: the caller supplies the values to scrub. The live
 * wiring ([currentSecretValues]) is a separate function so the masking
 * rule stays unit-testable on the plain JVM — no Context, no repository.
 */
object ToolOutputRedaction {

    /**
     * The env-var values currently registered as secrets.
     *
     * Empty when Privacy Mode is OFF, when the repository is not wired yet
     * (early boot), or when the user has no env vars — all ordinary states.
     *
     * The Privacy Mode check matters: the shell path scrubs only when the
     * user opted in ([EnvVarRedactor.redactIfEnabled]). A tool output that
     * scrubbed unconditionally would be stricter than the shell path, and
     * would surprise a user who deliberately turned Privacy Mode off to
     * inspect a raw value. Same rule on both sides.
     */
    fun currentSecretValues(): Collection<String> {
        if (!EnvVarPrivacyStore.isEnabled) return emptyList()
        return EnvVarRedactor.envVarRepository?.allAsDict()?.values.orEmpty()
            .filter { it.isNotEmpty() }
    }

    /**
     * Mask every occurrence of a secret value in [text].
     *
     * The masking rule is [EnvVarRedactor]'s (first 2 + stars + last 2) and
     * values shorter than its minimum match length are skipped — the same
     * threshold the shell path uses, so the tool output and the shell
     * output agree on what counts as scrubbed.
     *
     * No reminder is appended: this is the quiet variant for logs.
     */
    fun mask(text: String, secretValues: Collection<String>): String {
        if (text.isBlank() || secretValues.isEmpty()) return text
        val (masked, hits) = EnvVarRedactor.redact(text, secretValues)
        return if (hits > 0) masked else text
    }

    /**
     * Mask [text] and append the privacy reminder, so the model knows the
     * value it is looking at was masked (otherwise it may treat the stars
     * as the file's real content).
     */
    fun maskForModel(text: String, secretValues: Collection<String>): String {
        if (text.isBlank() || secretValues.isEmpty()) return text
        val (masked, hits) = EnvVarRedactor.redact(text, secretValues)
        return if (hits > 0) masked + "\n\n" + EnvVarRedactor.SYSTEM_REMINDER else text
    }

    /**
     * Live variant of [mask] — reads the registered secrets and the Privacy
     * Mode switch. No-op when Privacy Mode is OFF or no env vars exist.
     */
    fun maskQuiet(text: String): String = mask(text, currentSecretValues())

    /**
     * Live variant of [maskForModel] — the one the MODEL-facing paths use.
     */
    fun maskForModel(text: String): String = maskForModel(text, currentSecretValues())
}
