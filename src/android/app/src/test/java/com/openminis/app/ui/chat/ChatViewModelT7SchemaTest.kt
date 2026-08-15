package com.openminis.app.ui.chat

import com.openminis.app.agent.runtime.AgentRunPhase
import com.openminis.app.agent.runtime.AgentTerminal
import com.openminis.app.agent.runtime.AgentTerminalReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [ChatViewModel] companion T7 schema serialization
 * pure functions:
 *
 * - [ChatViewModel.t7PhaseSchema] — map [AgentRunPhase] to trace schema
 *   v2 camelCase string.
 * - [ChatViewModel.t7TerminalSchema] — map [AgentTerminal] to trace schema
 *   v2 terminal_state string.
 * - [ChatViewModel.t7TerminalReasonSchema] — map [AgentTerminalReason] to
 *   trace schema v2 terminal_reason snake_case string.
 */
class ChatViewModelT7SchemaTest {

    // ── t7PhaseSchema ──────────────────────────────────────────────────────

    @Test fun `phase IDLE maps to Idle`() {
        assertEquals("Idle", ChatViewModel.t7PhaseSchema(AgentRunPhase.IDLE))
    }

    @Test fun `phase PREPARING maps to Preparing`() {
        assertEquals("Preparing", ChatViewModel.t7PhaseSchema(AgentRunPhase.PREPARING))
    }

    @Test fun `phase CALLING_MODEL maps to CallingModel`() {
        assertEquals("CallingModel", ChatViewModel.t7PhaseSchema(AgentRunPhase.CALLING_MODEL))
    }

    @Test fun `phase EXECUTING_TOOLS maps to ExecutingTools`() {
        assertEquals("ExecutingTools", ChatViewModel.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS))
    }

    @Test fun `phase RETRYING maps to Retrying`() {
        assertEquals("Retrying", ChatViewModel.t7PhaseSchema(AgentRunPhase.RETRYING))
    }

    @Test fun `phase FALLING_BACK maps to FallingBack`() {
        assertEquals("FallingBack", ChatViewModel.t7PhaseSchema(AgentRunPhase.FALLING_BACK))
    }

    @Test fun `phase COMPACTING maps to Compacting`() {
        assertEquals("Compacting", ChatViewModel.t7PhaseSchema(AgentRunPhase.COMPACTING))
    }

    @Test fun `phase FINALIZING maps to Finalizing`() {
        assertEquals("Finalizing", ChatViewModel.t7PhaseSchema(AgentRunPhase.FINALIZING))
    }

    @Test fun `phase SUCCEEDED maps to Succeeded`() {
        assertEquals("Succeeded", ChatViewModel.t7PhaseSchema(AgentRunPhase.SUCCEEDED))
    }

    @Test fun `phase FAILED maps to Failed`() {
        assertEquals("Failed", ChatViewModel.t7PhaseSchema(AgentRunPhase.FAILED))
    }

    @Test fun `phase CANCELLED maps to Cancelled`() {
        assertEquals("Cancelled", ChatViewModel.t7PhaseSchema(AgentRunPhase.CANCELLED))
    }

    @Test fun `phase INTERRUPTED maps to Interrupted`() {
        assertEquals("Interrupted", ChatViewModel.t7PhaseSchema(AgentRunPhase.INTERRUPTED))
    }

    // ── t7TerminalSchema ──────────────────────────────────────────────────

    @Test fun `terminal SUCCEEDED maps to Succeeded`() {
        assertEquals("Succeeded", ChatViewModel.t7TerminalSchema(AgentTerminal.SUCCEEDED))
    }

    @Test fun `terminal FAILED maps to Failed`() {
        assertEquals("Failed", ChatViewModel.t7TerminalSchema(AgentTerminal.FAILED))
    }

    @Test fun `terminal CANCELLED maps to Cancelled`() {
        assertEquals("Cancelled", ChatViewModel.t7TerminalSchema(AgentTerminal.CANCELLED))
    }

    @Test fun `terminal INTERRUPTED maps to Interrupted`() {
        assertEquals("Interrupted", ChatViewModel.t7TerminalSchema(AgentTerminal.INTERRUPTED))
    }

    // ── t7TerminalReasonSchema ────────────────────────────────────────────

    @Test fun `reason null maps to null`() {
        assertNull(ChatViewModel.t7TerminalReasonSchema(null))
    }

    @Test fun `reason COMPLETED maps to completed_normally`() {
        assertEquals("completed_normally", ChatViewModel.t7TerminalReasonSchema(AgentTerminalReason.COMPLETED))
    }

    @Test fun `reason EXECUTION_FAILED maps to all_fallbacks_exhausted`() {
        assertEquals("all_fallbacks_exhausted", ChatViewModel.t7TerminalReasonSchema(AgentTerminalReason.EXECUTION_FAILED))
    }

    @Test fun `reason USER_CANCELLED maps to user_cancelled`() {
        assertEquals("user_cancelled", ChatViewModel.t7TerminalReasonSchema(AgentTerminalReason.USER_CANCELLED))
    }

    @Test fun `reason DEADLINE_EXCEEDED maps to deadline_reached`() {
        assertEquals("deadline_reached", ChatViewModel.t7TerminalReasonSchema(AgentTerminalReason.DEADLINE_EXCEEDED))
    }

    @Test fun `reason PROCESS_INTERRUPTED maps to process_interrupted`() {
        assertEquals("process_interrupted", ChatViewModel.t7TerminalReasonSchema(AgentTerminalReason.PROCESS_INTERRUPTED))
    }

    @Test fun `reason PERSISTENCE_FAILED maps to persistence_failed`() {
        assertEquals("persistence_failed", ChatViewModel.t7TerminalReasonSchema(AgentTerminalReason.PERSISTENCE_FAILED))
    }

    @Test fun `reason OUTCOME_UNKNOWN maps to process_interrupted (fallback)`() {
        // OUTCOME_UNKNOWN has no direct schema equivalent, so it maps to
        // process_interrupted as the closest semantic match.
        assertEquals("process_interrupted", ChatViewModel.t7TerminalReasonSchema(AgentTerminalReason.OUTCOME_UNKNOWN))
    }
}