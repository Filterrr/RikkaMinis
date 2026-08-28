package com.openminis.app.sandbox.offload

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
 */
class ModelExecutionOrphanReaperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val oldMtime = System.currentTimeMillis() - 11 * 60_000L // past ORPHAN_AGE_MS
    private val freshMtime = System.currentTimeMillis() - 1_000L

    private fun stagingRoot(): File = tmp.newFolder("model-exec")

    private fun runDir(root: File): File =
        File(root, "run-${UUID.randomUUID()}").apply { mkdirs() }

    private fun writeDeadWorkerRef(run: File) {
        // A pid guaranteed to have no /proc entry on the host → probeLiveness DEAD.
        File(run, ModelExecutionRunDir.FILE_WORKER_PID).writeText(
            ModelExecutionRunDir.encodeWorkerRef(
                ModelExecutionRunDir.WorkerProcessRef(
                    pid = Int.MAX_VALUE,
                    runId = "test",
                    nonce = "nonce",
                    processName = "modelservice",
                    startedAtMs = 0L,
                ),
            ),
        )
    }

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
        writeDeadWorkerRef(run)
        run.setLastModified(oldMtime)

        val reaped = ModelExecutionOrphanReaper.reapOrphans(root)
        assertEquals(0, reaped)
        assertTrue(run.exists())
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
}
