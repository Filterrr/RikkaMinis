package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.sanitizeToolPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-parallel-results] Pins the shape the sub-agent sends a
 * turn's tool results in.
 *
 * THE REGRESSION
 *
 * The runner appended one USER message PER tool call, so a turn with N
 * parallel calls produced N CONSECUTIVE user messages. Two mechanisms broke
 * on that shape:
 *
 * 1. The provider-side `sanitizeToolPairing` answers an assistant's tool_use
 *    blocks from the IMMEDIATELY FOLLOWING user message only. With N
 *    separate messages, only the first result answered the assistant turn
 *    and the other N-1 tool_use blocks were stripped as orphans — their real
 *    output never reached the model. Measured: 3 parallel calls → 1
 *    tool_use + 1 tool_result survived, 2 calls silently dropped.
 * 2. The runner's own Pass 1 repair then saw the same shape and injected
 *    "Tool execution was interrupted" placeholders for the results that
 *    were no longer adjacent to their call.
 *
 * The main loop never hit either problem because it sends one user message
 * per turn. The sub-agent now does the same.
 */
class SubagentToolResultBatchTest {

    private fun call(id: String, name: String = "file_read") =
        SubagentToolCall(id = id, name = name, args = org.json.JSONObject())

    private fun ok(output: String) = ToolExecutionResult(output = output, success = true)

    private fun batch(
        ids: List<String>,
        contents: List<String> = ids.map { "OUTPUT OF $it" },
    ): LLMMessage? = SubagentToolResultBatch.buildToolResultMessage(
        results = ids.map { call(it) to ok("OUTPUT OF $it") },
        contents = contents,
    )

    // ─── 1. one turn, one message ─────────────────────────────────────────

    @Test
    fun `all results of a turn share ONE user message`() {
        val msg = batch(listOf("c0", "c1", "c2"))!!
        assertEquals(LLMMessage.Role.USER, msg.role)
        assertEquals(3, msg.contentParts.size)
        val ids = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>().map { it.id }
        assertEquals(listOf("c0", "c1", "c2"), ids)
    }

    @Test
    fun `an empty turn produces no message`() {
        assertNull(batch(emptyList()))
    }

    @Test
    fun `mismatched contents are rejected`() {
        var threw = false
        try {
            SubagentToolResultBatch.buildToolResultMessage(
                results = listOf(call("c0") to ok("a"), call("c1") to ok("b")),
                contents = listOf("only one"),
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a size mismatch must not silently drop a result", threw)
    }

    // ─── 2. the provider must keep every call ─────────────────────────────

    @Test
    fun `the provider sanitizer keeps every parallel call`() {
        // THE core assertion: what the model actually receives.
        val msg = batch(listOf("c0", "c1", "c2"))!!
        val history = listOf(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "reading three files",
                contentParts = listOf("c0", "c1", "c2").map {
                    AgentContentPart.ToolUse(id = it, name = "file_read", input = org.json.JSONObject())
                },
            ),
            msg,
        )
        val sanitized = sanitizeToolPairing(history) { }
        val uses = sanitized.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolUse>()
        val results = sanitized.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()

        assertEquals(
            "every parallel tool call must survive the provider's pairing sanitizer",
            3, uses.size,
        )
        assertEquals(
            "every parallel tool result must survive the provider's pairing sanitizer",
            3, results.size,
        )
        assertEquals(
            listOf("c0", "c1", "c2"), results.map { it.id }.sorted(),
        )
    }

    @Test
    fun `the old one-message-per-call shape loses calls to the sanitizer`() {
        // The shape this fix replaces — pinned so a future change back to it
        // is caught by this test rather than by a production incident.
        val ids = listOf("c0", "c1", "c2")
        val history = listOf(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "reading three files",
                contentParts = ids.map {
                    AgentContentPart.ToolUse(id = it, name = "file_read", input = org.json.JSONObject())
                },
            ),
        ) + ids.map {
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "Result of file_read ($it):\nOUTPUT OF $it",
                contentParts = listOf(
                    AgentContentPart.ToolResult(id = it, name = "file_read", content = "OUTPUT OF $it"),
                ),
            )
        }
        val sanitized = sanitizeToolPairing(history) { }
        val uses = sanitized.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolUse>()
        assertEquals(
            "the OLD shape is expected to lose calls — this test documents why the fix exists",
            1, uses.size,
        )
    }

    // ─── 3. no phantom bridges, no role alternation damage ────────────────

    @Test
    fun `a parallel turn produces no phantom interruption bridge`() {
        val msg = batch(listOf("c0", "c1", "c2"))!!
        val history = mutableListOf(
            LLMMessage(LLMMessage.Role.USER, "the task"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "reading three files",
                contentParts = listOf("c0", "c1", "c2").map {
                    AgentContentPart.ToolUse(id = it, name = "file_read", input = org.json.JSONObject())
                },
            ),
            msg,
        )
        val added = SubagentHistoryRepair.applyBridges(history, "BRIDGE")
        assertEquals("a single batched result message needs no bridge", 0, added)
        assertEquals(3, history.size)
    }

    // ─── 4. payload fidelity ──────────────────────────────────────────────

    @Test
    fun `each result keeps its own payload and error flag`() {
        val msg = SubagentToolResultBatch.buildToolResultMessage(
            results = listOf(
                call("c0") to ok("first output"),
                call("c1") to ToolExecutionResult(output = "boom", success = false),
            ),
            contents = listOf("first output", "Error: boom"),
        )!!
        val results = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
        assertEquals("first output", results[0].content)
        assertTrue(!results[0].isError)
        assertEquals("Error: boom", results[1].content)
        assertTrue(results[1].isError)
    }

    @Test
    fun `the content string stays empty so the budget does not double count`() {
        val msg = batch(listOf("c0"))!!
        assertEquals(
            "a tool_result carrier must not duplicate its payload in `content` — " +
                "SubagentHistoryBudget counts `content` for messages that carry no ToolResult part",
            "", msg.content,
        )
        assertNotNull(msg.contentParts.first())
    }

    // ─── 5. the runner must not build one message per call ────────────────

    @Test
    fun `the runner batches a turn's results into one message`() {
        val src = com.openminis.app.ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "the runner must use the batched builder",
            src.contains("SubagentToolResultBatch.buildToolResultMessage"),
        )
        assertFalse(
            "the runner must not append one user message per tool call any more",
            src.contains("\"Result of \${call.name} (\${call.id}):\\n\$contentForModel\""),
        )
    }
}
