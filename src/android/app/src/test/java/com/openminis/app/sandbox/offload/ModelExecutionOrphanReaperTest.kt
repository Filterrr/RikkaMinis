package com.openminis.app.sandbox.offload

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * [worker-crash-cleanup] JVM tests for the orphan run-dir reaper's evidence
 * matrix, including the DIED_MID_STREAM class: a worker that crashed mid-run
 * (LMK kill / native crash) never writes `terminal.json`, so the old
 * terminal-only criteria leaked its run dir forever.
 *
 * Also covers the concurrency-safety matrix:
 *  - the startup race (registered dir is never reaped, even with full dead
 *    evidence — only the live client can prove "the worker will not start");
 *  - stale beat + pid ALIVE / IDENTITY_MISMATCH is NOT death (starvation /
 *    freeze / drift) → keep;
 *  - stale beat + pid CONFIRMED missing → reap.
 */
class ModelExecutionOrphanReaperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val oldMtime = System.currentTimeMillis() - 11 * 60_000L // past ORPHAN_AGE_MS
    private val freshMtime = System.currentTimeMillis() - 1_000L

    @After
    fun clearRegistry() {
        // The registry is process-global; never leak a registration between tests.
        stagingRootSafe().listFiles()?.forEach { ModelExecutionRunRegistry.unregister(it) }
    }

    private fun stagingRootSafe(): File =
        File(tmp.root, "model-exec").apply { mkdirs() }

    private fun stagingRoot(): File = tmp.newFolder("model-exec")

    private fun runDir(root: File): File =
        File(root, "run-${UUID.randomUUID()}").apply { mkdirs() }

    private fun writeWorkerRef(run: File, pid: Int, processName: String) {
        File(run, ModelExecutionRunDir.FILE_WORKER_PID).writeText(
            ModelExecutionRunDir.encodeWorkerRef(
                ModelExecutionRunDir.WorkerProcessRef(
                    pid = pid,
                    // The reaper derives expectedRunId from the dir name —
                    // a ref whose runId does not match the dir reads back as
                    // NO_REF (conservative keep), never MISSING/ALIVE.
                    runId = ModelExecutionDispatcher.runIdOf(run) ?: "test",
                    nonce = "nonce",
                    processName = processName,
                    startedAtMs = 0L,
                ),
            ),
        )
    }

    /** A pid guaranteed to have no /proc entry on the host → probe MISSING. */
    private fun writeDeadWorkerRef(run: File) =
        writeWorkerRef(run, pid = Int.MAX_VALUE, processName = "modelservice")

    /**
     * THIS JVM process's pid — guaranteed to be ALIVE and /proc-readable by
     * the test (same uid). Resolved via /proc/self because `ProcessHandle`
     * is a Java 9+ API absent from the Android mockable jar the unit tests
     * compile against.
     */
    private fun currentPid(): Int =
        File("/proc/self").canonicalFile.name.toIntOrNull()
            ?: error("cannot determine current pid (non-Linux host?)")

    @Test
    fun `reaps crashed-worker dir with stale beat and no terminal`() {
        val root = stagingRoot()
        val run = runDir(root)
        writeDeadWorkerRef(run)
        File(run, "stream.jsonl").writeText("{}\n")
        // Worker started beating, then went silent (crashed mid-run) — the
        // DIED_MID_STREAM class. No terminal.json can ever appear.
        val beat = File(run, ModelExecutionRunDir.FILE_LIVENESS_BEAT)
        beat.writeText("""{"at":1}""")
        beat.setLastModified(System.currentTimeMillis() - 60_000L)
        run.setLastModified(oldMtime)

        val reaped = ModelExecutionOrphanReaper.reapOrphans(root)
        assertEquals(1, reaped)
        assertFalse(run.exists())
    }

    @Test
    fun `keeps active run with fresh beat even when dir is old`() {
        val root = stagingRoot()
        val run = runDir(root)
        writeDeadWorkerRef(run)
        val beat = File(run, ModelExecutionRunDir.FILE_LIVENESS_BEAT)
        beat.writeText("""{"at":1}""")
        beat.setLastModified(System.currentTimeMillis())
        run.setLastModified(oldMtime)

        val reaped = ModelExecutionOrphanReaper.reapOrphans(root)
        assertEquals(0, reaped)
        assertTrue(run.exists())
    }

    @Test
    fun `reaps never-started worker dir with no beat and no pid ref`() {
        val root = stagingRoot()
        val run = runDir(root)
        File(run, "request.json").writeText("{}")
        run.setLastModified(oldMtime)

        val reaped = ModelExecutionOrphanReaper.reapOrphans(root)
        assertEquals(1, reaped)
        assertFalse(run.exists())
    }

    @Test
    fun `keeps dir whose worker registered but has not beaten yet`() {
        val root = stagingRoot()
        val run = runDir(root)
        // Worker alive (this JVM process, wildcard identity) but its first
        // beat has not landed yet — the startup window must NOT be reaped.
        writeWorkerRef(run, pid = currentPid(), processName = "")
        run.setLastModified(oldMtime)

        val reaped = ModelExecutionOrphanReaper.reapOrphans(root)
        assertEquals(0, reaped)
        assertTrue(run.exists())
    }

    @Test
    fun `reaps dir whose worker registered, never beat, and pid is confirmed dead`() {
        val root = stagingRoot()
        val run = runDir(root)
        // Request thread wrote its pid, then the process died before the
        // first beat landed. Old code never reaped this (path (b) demands a
        // beat) — a permanent orphan; now MISSING proves death and reaps.
        writeDeadWorkerRef(run)
        run.setLastModified(oldMtime)

        val reaped = ModelExecutionOrphanReaper.reapOrphans(root)
        assertEquals(1, reaped)
        assertFalse(run.exists())
    }

    @Test
    fun `keeps crashed-worker dir younger than the orphan age`() {
        val root = stagingRoot()
        val run = runDir(root)
        writeDeadWorkerRef(run)
        val beat = File(run, ModelExecutionRunDir.FILE_LIVENESS_BEAT)
        beat.writeText("""{"at":1}""")
        beat.setLastModified(System.currentTimeMillis() - 60_000L)
        run.setLastModified(freshMtime)

        val reaped = ModelExecutionOrphanReaper.reapOrphans(root)
        assertEquals(0, reaped)
        assertTrue(run.exists())
    }

    @Test
    fun `reaps completed run with terminal and dead worker`() {
        val root = stagingRoot()
        val run = runDir(root)
        writeDeadWorkerRef(run)
        File(run, ModelExecutionRunDir.FILE_TERMINAL).writeText("""{"at":1}""")
        run.setLastModified(oldMtime)

        val reaped = ModelExecutionOrphanReaper.reapOrphans(root)
        assertEquals(1, reaped)
        assertFalse(run.exists())
    }

    @Test
    fun `ignores non run-dir entries`() {
        val root = stagingRoot()
        val shutdownMarker = File(root, "shutdown").apply { createNewFile() }
        val randomDir = tmp.newFolder("not-a-run-dir")
        randomDir.setLastModified(oldMtime)

        val reaped = ModelExecutionOrphanReaper.reapOrphans(root)
        assertEquals(0, reaped)
        assertTrue(shutdownMarker.exists())
        assertTrue(randomDir.exists())
    }

    // ── [startup-barrier] worker-startup race vs reaper ──

    @Test
    fun `never reaps a dir registered by a live client even with full dead evidence`() {
        val root = stagingRoot()
        val run = runDir(root)
        // Full "dead" evidence: no beat, no pid ref, old mtime — but the
        // client that owns this dir is still alive and registered it.
        run.setLastModified(oldMtime)
        ModelExecutionRunRegistry.register(run)
        try {
            val reaped = ModelExecutionOrphanReaper.reapOrphans(root)
            assertEquals(0, reaped)
            assertTrue(run.exists())
        } finally {
            ModelExecutionRunRegistry.unregister(run)
        }
        // Once the client releases ownership, the same evidence reaps.
        assertEquals(1, ModelExecutionOrphanReaper.reapOrphans(root))
        assertFalse(run.exists())
    }

    @Test
    fun `never reaps a registered dir with stale beat and dead pid`() {
        val root = stagingRoot()
        val run = runDir(root)
        writeDeadWorkerRef(run)
        val beat = File(run, ModelExecutionRunDir.FILE_LIVENESS_BEAT)
        beat.writeText("""{"at":1}""")
        beat.setLastModified(System.currentTimeMillis() - 60_000L)
        run.setLastModified(oldMtime)
        ModelExecutionRunRegistry.register(run)
        try {
            assertEquals(0, ModelExecutionOrphanReaper.reapOrphans(root))
            assertTrue(run.exists())
        } finally {
            ModelExecutionRunRegistry.unregister(run)
        }
    }

    // ── [worker-crash-cleanup] stale beat + PID evidence matrix ──

    @Test
    fun `keeps crashed-worker dir when pid is still ALIVE despite stale beat`() {
        val root = stagingRoot()
        val run = runDir(root)
        // Wildcard ref (blank name / uid<0 / startTicks<=0) matching THIS JVM
        // process → probeDeathEvidence = ALIVE: a stalled beat alone (GC,
        // scheduler starvation, freeze) is not death.
        writeWorkerRef(run, pid = currentPid(), processName = "")
        val beat = File(run, ModelExecutionRunDir.FILE_LIVENESS_BEAT)
        beat.writeText("""{"at":1}""")
        beat.setLastModified(System.currentTimeMillis() - 60_000L)
        run.setLastModified(oldMtime)

        assertEquals(0, ModelExecutionOrphanReaper.reapOrphans(root))
        assertTrue(run.exists())
    }

    @Test
    fun `keeps crashed-worker dir when pid probe reports IDENTITY_MISMATCH`() {
        val root = stagingRoot()
        val run = runDir(root)
        // Real pid, wrong processName → PRESENT but mismatched: drift
        // suspicion, not proof of death → must keep.
        writeWorkerRef(run, pid = currentPid(), processName = "modelservice")
        val beat = File(run, ModelExecutionRunDir.FILE_LIVENESS_BEAT)
        beat.writeText("""{"at":1}""")
        beat.setLastModified(System.currentTimeMillis() - 60_000L)
        run.setLastModified(oldMtime)

        assertEquals(0, ModelExecutionOrphanReaper.reapOrphans(root))
        assertTrue(run.exists())
    }
}
