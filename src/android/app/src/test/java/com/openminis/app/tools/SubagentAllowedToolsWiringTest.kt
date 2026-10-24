package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-allowed-tools-enforcement] Source-grep drift guard for the
 * runtime allowlist gate.
 *
 * The unit test ([SubagentAllowedToolsTest]) pins the pure predicate. It
 * cannot see whether the RUNNER actually calls it — and that call site is
 * exactly where the defect lived: the predicate was correct while the
 * dispatcher never consulted it. This guard pins the call site, the same
 * technique the repo already uses for ported decisions (see
 * `ProductionSources`).
 */
class SubagentAllowedToolsWiringTest {

    private val runnerSource: String by lazy {
        ProductionSources.read("tools/SubagentRunner.kt")
    }

    @Test
    fun `the runtime dispatcher consults the allowlist gate`() {
        // The `if (!...)` shape matters: a guard that merely MENTIONS the
        // predicate (`if (false && !...)`, or a call in a comment) enforces
        // nothing. The negative verification for this branch disabled the
        // gate exactly that way and the first version of this guard still
        // passed — so the assertion pins the LIVE condition.
        assertTrue(
            "executeSubagentTool must consult SubagentAllowedTools.isAllowed as a " +
                "live condition — a schema-only allowlist does not stop execution",
            runnerSource.contains("if (!SubagentAllowedTools.isAllowed(name, allowedTools))"),
        )
        assertFalse(
            "the allowlist gate must not be short-circuited away",
            runnerSource.contains("false && !SubagentAllowedTools.isAllowed"),
        )
    }

    @Test
    fun `the dispatcher accepts the allowlist as a parameter`() {
        assertTrue(
            "executeSubagentTool must take the skill's allowedTools so it can " +
                "enforce them at runtime",
            runnerSource.contains("allowedTools: Set<String>? = null"),
        )
    }

    @Test
    fun `the loop passes the skill's allowlist into the dispatcher`() {
        assertTrue(
            "the tool-execution call site must pass config.allowedTools",
            runnerSource.contains("allowedTools = config.allowedTools"),
        )
    }

    @Test
    fun `the gate object exists as a pure object`() {
        val source = ProductionSources.read("tools/SubagentAllowedTools.kt")
        assertTrue(source.contains("object SubagentAllowedTools"))
        assertTrue(source.contains("fun isAllowed"))
        assertTrue(source.contains("fun refusalText"))
    }
}
