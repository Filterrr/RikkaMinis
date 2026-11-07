package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [port/upstream-subagent-orchestration-tools] Every orchestration tool must be
 * REGISTERED, MAPPED, DISPATCHED and FORBIDDEN — all four.
 *
 * This codebase has shipped each failure mode at least once:
 *
 *  - a tool in the catalogue with no capability mapping is silently dropped
 *    from every sub-agent's schema (`web_search`, `todo_write`);
 *  - a tool in the catalogue with no runner branch is granted and then fails
 *    at call time (`SubagentToolCatalogueConsistencyTest`'s whole premise);
 *  - a registry that is never populated (`attachRunToGroup` was called only
 *    from tests — `[T-subagent-orchestration-fix]`).
 *
 * `SubagentRunner` and `ChatViewModel` cannot be compiled by the local JVM
 * harness, so this guard reads them textually: the four lines below are the
 * wiring, and removing any one of them is a real regression rather than a
 * refactor.
 */
class SubagentOrchestrationToolsWiringTest {

    private fun production(rel: String): String {
        val text = ProductionSources.read(rel)
        return SubagentSourceText.stripComments(text)
    }

    private val names = listOf(
        "join_subagents",
        "wait_any",
        "cancel_subagents",
        "subagent_status",
        "steer_subagent",
    )

    @Test
    fun `every orchestration tool is registered in the tool catalogue`() {
        val tools = production("tools/AgentTools.kt")
        for (fn in listOf("joinDefinition", "waitAnyDefinition", "cancelDefinition", "statusDefinition", "steerDefinition")) {
            assertTrue(
                "AgentTools.makeAgentTools does not register SubagentOrchestrationTools.$fn()",
                tools.contains("SubagentOrchestrationTools.$fn()"),
            )
        }
    }

    @Test
    fun `every orchestration tool maps to a capability so a sub-agent cannot be granted it`() {
        val caps = production("tools/AgentCapabilities.kt")
        for (name in names) {
            val line = Regex("\"$name\"\\s*->\\s*AgentCapability\\.([A-Z_]+)").find(caps)
            assertTrue("AgentCapabilities has no mapping for $name", line != null)
            assertTrue(
                "$name must map to SPAWN_AGENT (parent-only), not ${line!!.groupValues[1]}",
                line.groupValues[1] == "SPAWN_AGENT",
            )
        }
    }

    @Test
    fun `every orchestration tool is hard-forbidden for sub-agents`() {
        val skill = production("tools/SubagentSkill.kt")
        for (const in listOf("JOIN_NAME", "WAIT_ANY_NAME", "CANCEL_NAME", "STATUS_NAME", "STEER_NAME")) {
            assertTrue(
                "SubagentSkill.FORBIDDEN_TOOLS is missing SubagentOrchestrationTools.$const",
                skill.contains("SubagentOrchestrationTools.$const"),
            )
        }
    }

    @Test
    fun `every orchestration tool reaches the dispatcher`() {
        val vm = production("ui/chat/ChatViewModel.kt")
        for (const in listOf("JOIN_NAME", "WAIT_ANY_NAME", "CANCEL_NAME", "STATUS_NAME", "STEER_NAME")) {
            assertTrue(
                "ChatViewModel's tool dispatch has no branch for SubagentOrchestrationTools.$const",
                vm.contains("SubagentOrchestrationTools.$const"),
            )
        }
    }

    @Test
    fun `the runner implements a branch for every orchestration tool`() {
        val runner = production("tools/SubagentRunner.kt")
        val start = runner.indexOf("suspend fun executeOrchestrationTool(")
        assertTrue("executeOrchestrationTool is gone", start >= 0)
        val block = runner.substring(start, minOf(start + 20_000, runner.length))
        for (const in listOf("JOIN_NAME", "WAIT_ANY_NAME", "CANCEL_NAME", "STATUS_NAME", "STEER_NAME")) {
            assertTrue(
                "executeOrchestrationTool has no when-branch for $const",
                block.contains("SubagentOrchestrationTools.$const"),
            )
        }
    }

    @Test
    fun `status and steer are exempt from same-turn dedupe`() {
        val utils = production("ui/chat/ChatViewModelUtils.kt")
        assertTrue(utils.contains("SubagentOrchestrationTools.STATUS_NAME"))
        assertTrue(utils.contains("SubagentOrchestrationTools.STEER_NAME"))
    }
}