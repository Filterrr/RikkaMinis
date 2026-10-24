package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-tool-preflight] The sub-agent runner had NO preflight: a
 * malformed tool call was dispatched and only failed inside the tool, so
 * the model burned a turn of a scarce budget learning that `path` was
 * missing. The main agent has had preflight all along — this guard pins
 * that the sub-agent now shares the SAME rule.
 *
 * The pure predicate lives in [ToolCallPreflight]; this guard pins the
 * CALL SITE, which a unit test cannot reach.
 */
class SubagentToolPreflightWiringTest {

    private val runnerSource: String =
        ProductionSources.read("tools/SubagentRunner.kt")

    @Test
    fun `the runner validates a tool call before dispatching it`() {
        assertTrue(
            "the sub-agent runner must consult the shared preflight before " +
                "dispatching a tool call",
            runnerSource.contains("ToolCallPreflight.validate(call.name, args, tools)") ||
                runnerSource.contains("ToolCallPreflight.validate(call.name, args, subagentTools)"),
        )
    }

    @Test
    fun `the preflight rejection is a live condition`() {
        // A guard that merely MENTIONS the predicate enforces nothing. The
        // negative verification for this branch wrapped the call in
        // `if (false && ...)` and the first version of this guard still
        // passed — so the assertion pins the LIVE shape.
        assertTrue(
            "the preflight check must be a live condition, not disabled",
            runnerSource.contains("val preflightError = preflightErrorFor(call, subagentTools)") &&
                !runnerSource.contains("false && preflightErrorFor"),
        )
    }

    @Test
    fun `a preflight-rejected call is not counted as executed`() {
        // A rejected call never ran its tool, so it must not push the run
        // out of UNSTARTED — the same rule the loop-detector short-circuit
        // follows.
        assertTrue(
            "a preflight-rejected call must be recorded as blocked",
            runnerSource.contains("blockedCallIds.add(call.id)") &&
                runnerSource.contains("[preflight rejected:"),
        )
    }

    @Test
    fun `the main agent and the sub-agent share ONE preflight implementation`() {
        // Two implementations would drift. The main agent's companion must
        // delegate to the shared object.
        val chatSource = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(
            "ChatViewModel.preflightValidateToolCallImpl must delegate to " +
                "ToolCallPreflight — two implementations will drift",
            chatSource.contains("ToolCallPreflight.validate(name, args, tools)"),
        )
    }
}
