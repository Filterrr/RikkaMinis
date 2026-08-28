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
 * TEST-2 / [beat-state-machine]: worker-startup race tests.
 *
 * The race: client creates the run dir, startService() is issued, but the
 * `:modelservice` worker has not yet run its request thread — no
 * `liveness.beat`. A cleanup decision that treats an ABSENT beat as "worker
 * gone" would delete the dir under a worker that is only starting up
 * (exactly what awaitWorkerExit used to do). Only a beat that was present
 * and went STALE proves "worker was alive, then stopped".
 */
class WorkerStartupRaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun runDir(): File =
        File(tmp.newFolder("model-exec"), "run-${UUID.randomUUID()}").apply { mkdirs() }

    @Test
    fun `never-seen beat is NOT an exit signal (worker may still be starting)`() {
        val run = runDir()
        // Startup window: dir created, worker scheduled, no beat, no terminal.
        assertEquals(ModelExecutionRunDir.BeatState.NEVER_SEEN, ModelExecutionRunDir.beatState(run))
        assertFalse(ModelExecutionRunDir.workerStoppedWriting(run))
    }

    @Test
    fun `fresh beat means worker alive — not an exit signal`() {
        val run = runDir()
        ModelExecutionRunDir.touchLivenessBeat(run)
        assertEquals(ModelExecutionRunDir.BeatState.FRESH, ModelExecutionRunDir.beatState(run))
        assertFalse(ModelExecutionRunDir.workerStoppedWriting(run))
    }

    @Test
    fun `beat seen then stale proves worker stopped — IS an exit signal`() {
        val run = runDir()
        val beat = File(run, ModelExecutionRunDir.FILE_LIVENESS_BEAT)
        ModelExecutionRunDir.touchLivenessBeat(run)
        // Worker was alive (beat exists), then stopped beating past the
        // staleness threshold — crash / kill class.
        beat.setLastModified(
            System.currentTimeMillis() - ModelExecutionRunDir.LIVENESS_STALE_MS - 1_000L,
        )
        assertEquals(ModelExecutionRunDir.BeatState.STALE, ModelExecutionRunDir.beatState(run))
        assertTrue(ModelExecutionRunDir.workerStoppedWriting(run))
    }

    @Test
    fun `terminal marker alone is an exit signal even with never-seen beat`() {
        val run = runDir()
        File(run, ModelExecutionRunDir.FILE_TERMINAL).writeText("""{"at":1}""")
        // Worker finished through the protocol (beat write may have failed);
        // terminal is the LAST durable write, so deletion is safe.
        assertEquals(ModelExecutionRunDir.BeatState.NEVER_SEEN, ModelExecutionRunDir.beatState(run))
        assertTrue(ModelExecutionRunDir.workerStoppedWriting(run))
    }

    @Test
    fun `late-starting worker beats never-seen first — state transitions NEVER_SEEN to FRESH`() {
        // Simulates the widened startup window: the cleanup path polls
        // workerStoppedWriting BEFORE the worker's first beat, between the
        // first beat and staleness, and after staleness. The decision may
        // only flip to true at the STALE stage.
        val run = runDir()
        assertFalse(ModelExecutionRunDir.workerStoppedWriting(run)) // pre-beat

        // worker's request thread finally runs → first beat
        ModelExecutionRunDir.touchLivenessBeat(run)
        assertFalse(ModelExecutionRunDir.workerStoppedWriting(run)) // alive

        // worker crashes → beat goes stale
        File(run, ModelExecutionRunDir.FILE_LIVENESS_BEAT).setLastModified(
            System.currentTimeMillis() - ModelExecutionRunDir.LIVENESS_STALE_MS - 1_000L,
        )
        assertTrue(ModelExecutionRunDir.workerStoppedWriting(run)) // provably stopped
    }
}
