package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the shell generation scheduler pure functions extracted from
 * [ExecutionCoordinator].
 *
 * The scheduler is a progressive-degradation model inspired by connection
 * schedulers running under hard resource ceilings: instead of a single
 * failure cliff (the old 350MB hard cap freezing ALL commands), memory
 * pressure is shed in tiers — shrinking the shell generation budget,
 * recycling leaky/heavy commands earlier, and preserving lightweight command
 * availability so the agent can self-recover.
 *
 * All functions under test are pure (no Context / Debug / Process deps).
 */
class ExecutionCoordinatorSchedulerTest {

    // ── classifyCommand ───────────────────────────────────────────────

    @Test
    fun `classify empty command as light`() {
        assertEquals(CommandClass.LIGHT, classifyCommand(""))
    }

    @Test
    fun `classify ls as light`() {
        assertEquals(CommandClass.LIGHT, classifyCommand("ls -la"))
    }

    @Test
    fun `classify file tools as light`() {
        assertEquals(CommandClass.LIGHT, classifyCommand("cat /etc/hostname"))
        assertEquals(CommandClass.LIGHT, classifyCommand("grep -r foo /var/minis"))
        assertEquals(CommandClass.LIGHT, classifyCommand("echo hello"))
        assertEquals(CommandClass.LIGHT, classifyCommand("which python3"))
    }

    @Test
    fun `classify minis-model-use as leaky regardless of args`() {
        assertEquals(CommandClass.LEAKY, classifyCommand("minis-model-use list"))
        assertEquals(CommandClass.LEAKY, classifyCommand("minis-model-use run --model x --input /tmp/i.json"))
    }

    @Test
    fun `classify minis-model-use with leading whitespace as leaky`() {
        assertEquals(CommandClass.LEAKY, classifyCommand("  minis-model-use search foo"))
    }

    @Test
    fun `classify python3 as heavy`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("python3 /tmp/script.py"))
        assertEquals(CommandClass.HEAVY, classifyCommand("python3 --version"))
    }

    @Test
    fun `classify apk as heavy`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("apk add py3-numpy"))
        assertEquals(CommandClass.HEAVY, classifyCommand("apk"))
        assertEquals(CommandClass.HEAVY, classifyCommand("apk update"))
    }

    @Test
    fun `classify pip npm go as heavy`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("pip install pandas"))
        assertEquals(CommandClass.HEAVY, classifyCommand("pip3 install torch"))
        assertEquals(CommandClass.HEAVY, classifyCommand("npm install"))
        assertEquals(CommandClass.HEAVY, classifyCommand("go build ./..."))
    }

    @Test
    fun `classify chained command by its first token`() {
        // Leading tokens dominate; `cd` itself is light.
        assertEquals(CommandClass.LIGHT, classifyCommand("cd /tmp && ls"))
        assertEquals(CommandClass.HEAVY, classifyCommand("cd /tmp && python3 run.py"))
    }

    // ── internalDegradationPhase ──────────────────────────────────────

    @Test
    fun `phase is normal below 50MB`() {
        assertEquals(ShellPhase.NORMAL, internalDegradationPhase(0))
        assertEquals(ShellPhase.NORMAL, internalDegradationPhase(49))
    }

    @Test
    fun `phase is mild at 50MB boundary and below 80`() {
        assertEquals(ShellPhase.MILD, internalDegradationPhase(50))
        assertEquals(ShellPhase.MILD, internalDegradationPhase(79))
    }

    @Test
    fun `phase is moderate at 80MB boundary and below 100`() {
        assertEquals(ShellPhase.MODERATE, internalDegradationPhase(80))
        assertEquals(ShellPhase.MODERATE, internalDegradationPhase(99))
    }

    @Test
    fun `phase is severe at 100MB boundary and below 120`() {
        assertEquals(ShellPhase.SEVERE, internalDegradationPhase(100))
        assertEquals(ShellPhase.SEVERE, internalDegradationPhase(119))
    }

    @Test
    fun `phase is critical at 120MB boundary and below 350`() {
        assertEquals(ShellPhase.CRITICAL, internalDegradationPhase(120))
        assertEquals(ShellPhase.CRITICAL, internalDegradationPhase(200))
        assertEquals(ShellPhase.CRITICAL, internalDegradationPhase(349))
    }

    @Test
    fun `phase is locked at 350MB and above`() {
        assertEquals(ShellPhase.LOCKED, internalDegradationPhase(350))
        assertEquals(ShellPhase.LOCKED, internalDegradationPhase(1000))
    }

    // ── internalGenerationBudget ──────────────────────────────────────

    @Test
    fun `budget is 30 below 50MB`() {
        assertEquals(30, internalGenerationBudget(0))
        assertEquals(30, internalGenerationBudget(49))
    }

    @Test
    fun `budget is 15 between 50 and 80MB`() {
        assertEquals(15, internalGenerationBudget(50))
        assertEquals(15, internalGenerationBudget(79))
    }

    @Test
    fun `budget is 5 between 80 and 100MB`() {
        assertEquals(5, internalGenerationBudget(80))
        assertEquals(5, internalGenerationBudget(99))
    }

    @Test
    fun `budget is 2 between 100 and 120MB`() {
        assertEquals(2, internalGenerationBudget(100))
        assertEquals(2, internalGenerationBudget(119))
    }

    @Test
    fun `budget is 1 at 120MB and above`() {
        assertEquals(1, internalGenerationBudget(120))
        assertEquals(1, internalGenerationBudget(350))
        assertEquals(1, internalGenerationBudget(1000))
    }

    // ── shouldRecycleByClass ──────────────────────────────────────────

    @Test
    fun `leaky command always recycles regardless of memory`() {
        assertTrue(shouldRecycleByClass(CommandClass.LEAKY, 0))
        assertTrue(shouldRecycleByClass(CommandClass.LEAKY, 30))
        assertTrue(shouldRecycleByClass(CommandClass.LEAKY, 120))
        assertTrue(shouldRecycleByClass(CommandClass.LEAKY, 999))
    }

    @Test
    fun `heavy command recycles only above 80MB threshold`() {
        assertEquals(false, shouldRecycleByClass(CommandClass.HEAVY, 0))
        assertEquals(false, shouldRecycleByClass(CommandClass.HEAVY, 80))
        assertEquals(true, shouldRecycleByClass(CommandClass.HEAVY, 81))
        assertEquals(true, shouldRecycleByClass(CommandClass.HEAVY, 120))
    }

    @Test
    fun `light command never recycles by class`() {
        assertEquals(false, shouldRecycleByClass(CommandClass.LIGHT, 0))
        assertEquals(false, shouldRecycleByClass(CommandClass.LIGHT, 120))
        assertEquals(false, shouldRecycleByClass(CommandClass.LIGHT, 999))
    }

    @Test
    fun `heavy threshold is configurable`() {
        assertEquals(true, shouldRecycleByClass(CommandClass.HEAVY, 60, heavyRecycleThresholdMB = 50))
        assertEquals(false, shouldRecycleByClass(CommandClass.HEAVY, 40, heavyRecycleThresholdMB = 50))
    }

    // ── preExecRejectionMessage ───────────────────────────────────────

    @Test
    fun `normal and mild phases never reject`() {
        assertNull(preExecRejectionMessage(ShellPhase.NORMAL, CommandClass.LIGHT, 30))
        assertNull(preExecRejectionMessage(ShellPhase.NORMAL, CommandClass.HEAVY, 30))
        assertNull(preExecRejectionMessage(ShellPhase.NORMAL, CommandClass.LEAKY, 30))
        assertNull(preExecRejectionMessage(ShellPhase.MILD, CommandClass.HEAVY, 60))
    }

    @Test
    fun `moderate phase never rejects either`() {
        assertNull(preExecRejectionMessage(ShellPhase.MODERATE, CommandClass.LIGHT, 90))
        assertNull(preExecRejectionMessage(ShellPhase.MODERATE, CommandClass.LEAKY, 90))
        assertNull(preExecRejectionMessage(ShellPhase.MODERATE, CommandClass.HEAVY, 90))
    }

    @Test
    fun `severe phase rejects only leaky commands`() {
        assertNull(preExecRejectionMessage(ShellPhase.SEVERE, CommandClass.LIGHT, 110))
        assertNull(preExecRejectionMessage(ShellPhase.SEVERE, CommandClass.HEAVY, 110))
        val msg = preExecRejectionMessage(ShellPhase.SEVERE, CommandClass.LEAKY, 110)
        assertTrue(msg != null && msg.contains("model calls"))
    }

    @Test
    fun `critical phase rejects heavy and leaky but allows light`() {
        assertNull(preExecRejectionMessage(ShellPhase.CRITICAL, CommandClass.LIGHT, 150))
        val heavyMsg = preExecRejectionMessage(ShellPhase.CRITICAL, CommandClass.HEAVY, 150)
        assertTrue(heavyMsg != null && heavyMsg.contains("lightweight"))
        val leakyMsg = preExecRejectionMessage(ShellPhase.CRITICAL, CommandClass.LEAKY, 150)
        assertTrue(leakyMsg != null && leakyMsg.contains("lightweight"))
    }

    @Test
    fun `locked phase rejects everything`() {
        assertTrue(preExecRejectionMessage(ShellPhase.LOCKED, CommandClass.LIGHT, 400) != null)
        assertTrue(preExecRejectionMessage(ShellPhase.LOCKED, CommandClass.HEAVY, 400) != null)
        assertTrue(preExecRejectionMessage(ShellPhase.LOCKED, CommandClass.LEAKY, 400) != null)
    }
}