package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/tool-output-env-redaction] Source-grep drift guards for the tool
 * output masking.
 *
 * The masking RULE lives in a pure object ([ToolOutputRedaction]) and is
 * unit-tested. What unit tests cannot see is whether the PRODUCTION call
 * sites still CALL it — a refactor that replaces
 * `ToolOutputRedaction.maskForModel(result.output)` with `result.output`
 * would keep every rule test green while the leak returns.
 *
 * These guards assert the load-bearing call sites, so the leak cannot
 * silently come back.
 */
class ToolOutputRedactionWiringTest {

    private val chatVmSource: String by lazy {
        ProductionSources.read("ui/chat/ChatViewModel.kt")
    }

    private val runnerSource: String by lazy {
        ProductionSources.read("tools/SubagentRunner.kt")
    }

    private val checkpointSource: String by lazy {
        ProductionSources.read("tools/SubagentRunCheckpoint.kt")
    }

    // ── The main loop must mask before the model sees the output ────────

    @Test
    fun `the main loop masks the tool result before handing it to the model`() {
        assertTrue(
            "the main loop must mask the tool output for the model",
            chatVmSource.contains(
                "val maskedOutput = ToolOutputRedaction.maskForModel(result.output)",
            ),
        )
        assertTrue(
            "the masked text — not the raw one — must be what the model gets",
            chatVmSource.contains(
                "val outputForLLM = if (postRecord.level == Level.WARNING && postRecord.message != null) {",
            ),
        )
    }

    @Test
    fun `the main loop must not feed the raw tool output to the model`() {
        // The raw output must not be the value that reaches the tool_result
        // the model reads. The user's own tool block keeps the raw text on
        // purpose (same contract as the shell path) — this guard is about
        // the MODEL-facing variable only.
        assertFalse(
            "the model-facing output must not be the raw result",
            chatVmSource.contains(
                """                val outputForLLM = if (postRecord.level == Level.WARNING && postRecord.message != null) {
                    AppLogger.debug("ChatViewModel",
                        "appending loop-warning to tool result name=${'$'}name key=${'$'}{postRecord.warningKey}")
                    "${'$'}{result.output}\n\n${'$'}{postRecord.message}"
                } else {
                    result.output
                }""",
            ),
        )
    }

    @Test
    fun `the main loop masks the trace and the learning hook`() {
        assertTrue(
            "the trace must not receive the raw tool output",
            chatVmSource.contains("output = ToolOutputRedaction.maskQuiet(result.output),"),
        )
        assertTrue(
            "the failure-learning hook must not receive the raw tool output",
            chatVmSource.contains(
                "ToolOutputRedaction.maskQuiet(result.output),\n                    argsJson,",
            ),
        )
    }

    // ── The sub-agent loop must mask too ────────────────────────────────

    @Test
    fun `the sub-agent loop masks the tool result before handing it to the model`() {
        assertTrue(
            "the sub-agent must mask the tool output for its model",
            runnerSource.contains(
                "val maskedResultContent = ToolOutputRedaction.maskForModel(resultContent)",
            ),
        )
    }

    @Test
    fun `the sub-agent loop must not feed the raw tool output to the model`() {
        assertFalse(
            "the model-facing content must not be the raw result",
            runnerSource.contains(
                """                    val contentForModel = if (
                        postRecord.level == com.openminis.app.agent.Level.WARNING &&
                        postRecord.message != null
                    ) {
                        "${'$'}resultContent\n\n${'$'}{postRecord.message}"
                    } else {
                        resultContent
                    }""",
            ),
        )
    }

    // ── The durable checkpoint must mask ────────────────────────────────

    @Test
    fun `the checkpoint masks the query and the turn preview`() {
        assertTrue(
            "the checkpoint must scrub the query",
            checkpointSource.contains(
                "oneLine(SubagentJournalRedaction.redact(it, secrets)).take(200)",
            ),
        )
        assertTrue(
            "the checkpoint must scrub the turn preview",
            checkpointSource.contains(
                "oneLine(SubagentJournalRedaction.redact(lastTextPreview, secrets)).take(300)",
            ),
        )
    }
}
