package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-parallel-tool-results] Source-grep drift guard for the
 * RUNNER CALL SITE.
 *
 * The behaviour test ([SubagentParallelToolResultsTest]) pins the pure
 * batching object, but the defect lived in the CALL SITE: the runner
 * appended one USER message per tool call. A pure-object test cannot see
 * that — the object was correct while the runner misused it. This guard
 * pins the call site itself, the same technique the repo already uses for
 * ported decisions (see `ProductionSources`).
 *
 * If someone restores the per-result append, this test fails.
 */
class SubagentParallelToolResultsWiringTest {

    private val runnerSource: String by lazy {
        ProductionSources.read("tools/SubagentRunner.kt")
    }

    @Test
    fun `the runner does not append one user message per tool result`() {
        // The old shape: a history.add(...) inside the per-result loop with a
        // "Result of <tool> (<id>):" content string.
        assertFalse(
            "the runner must not build a 'Result of <tool> (<id>):' carrier " +
                "per tool call — the whole turn goes into ONE user message",
            runnerSource.contains("\"Result of \${call.name} (\${call.id}):\\n\$contentForModel\""),
        )
    }

    @Test
    fun `the runner emits the turn's results through the batch builder`() {
        assertTrue(
            "the runner must route the turn's tool results through " +
                "SubagentToolResultBatch.buildToolResultMessage",
            runnerSource.contains("SubagentToolResultBatch.buildToolResultMessage"),
        )
    }

    @Test
    fun `the batch builder exists as a pure object`() {
        val source = ProductionSources.read("tools/SubagentToolResultBatch.kt")
        assertTrue(
            "SubagentToolResultBatch must be an object with a pure builder",
            source.contains("object SubagentToolResultBatch"),
        )
        assertTrue(
            source.contains("fun buildToolResultMessage"),
        )
    }
}
