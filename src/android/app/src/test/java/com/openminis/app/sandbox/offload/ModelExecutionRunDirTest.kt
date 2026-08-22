package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TF-F JVM tests for the run-dir ownership / worker-liveness protocol
 * ([ModelExecutionRunDir]) and the defensive mailbox writes
 * ([ModelExecutionMailbox]).
 *
 * These exercise the EXACT P0 invariant that killed :modelservice:
 * "result.json exists" is NOT proof the worker finished writing — it is still
 * in finishRequest() (writeState) when the client deletes the dir → ENOENT
 * FATAL. Deletion must require BOTH a terminal marker AND the worker pid gone.
 */
class ModelExecutionRunDirTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String = "run-${System.nanoTime()}"): File = tmp.newFolder(name)

    /** A pid that is guaranteed to NOT have a /proc entry on the CI host. */
    private val deadPid: Int = Int.MAX_VALUE

    /**
     * PID 1 is the first user-space process on any Linux runner (init /
     * container entrypoint) and always has a live /proc/1 entry. The ALIVE
     * tests below guard on its existence so a non-Linux host (where /proc/1
     * may be absent) simply skips rather than flaking. NOTE: no
     * ProcessHandle/pid-of-self here — that API does not resolve against the
     * Android boot classpath in unit tests.
     */
    private val alivePid: Int = 1
    private fun procAlive(pid: Int): Boolean = File("/proc/$pid").exists()

    private fun ref(pid: Int, runId: String = "abc") = ModelExecutionRunDir.WorkerProcessRef(
        pid = pid,
        runId = runId,
        nonce = "nonce-1",
        processName = ":modelservice",
        startedAtMs = 0L,
    )

    // ── pid ref encode/decode round-trip ────────────────────────────

    @Test
    fun `worker pid ref encodes and decodes`() {
        val d = dir()
        assertTrue(ModelExecutionRunDir.writeWorkerPid(d, ref(alivePid)))
        val back = ModelExecutionRunDir.readWorkerRef(d, "abc")
        assertNotNull(back)
        assertEquals(alivePid, back!!.pid)
        assertEquals("abc", back.runId)
        assertEquals("nonce-1", back.nonce)
        assertEquals(":modelservice", back.processName)
    }

    @Test
    fun `worker pid ref runId mismatch is not visible as ours`() {
        val d = dir()
        ModelExecutionRunDir.writeWorkerPid(d, ref(alivePid, runId = "other-run"))
        // expectedRunId="abc" ≠ "other-run" → filtered out (UNKNOWN, not ours)
        assertNull(ModelExecutionRunDir.readWorkerRef(d, "abc"))
    }

    // ── three-state liveness ────────────────────────────────────────

    @Test
    fun `no pid ref is UNKNOWN not dead`() {
        val d = dir()
        assertEquals(ModelExecutionRunDir.WorkerLiveness.UNKNOWN, ModelExecutionRunDir.probeLiveness(d, "abc"))
    }

    @Test
    fun `torn unreadable pid ref is UNKNOWN`() {
        val d = dir()
        File(d, ModelExecutionRunDir.FILE_WORKER_PID).writeText("garbage-not-a-json")
        assertEquals(ModelExecutionRunDir.WorkerLiveness.UNKNOWN, ModelExecutionRunDir.probeLiveness(d, "abc"))
    }

    @Test
    fun `matching runId with truly dead pid is DEAD`() {
        val d = dir()
        ModelExecutionRunDir.writeWorkerPid(d, ref(deadPid))
        assertEquals(ModelExecutionRunDir.WorkerLiveness.DEAD, ModelExecutionRunDir.probeLiveness(d, "abc"))
    }

    @Test
    fun `matching runId with alive pid is ALIVE`() {
        if (!procAlive(alivePid)) return // non-Linux host: skip
        val d = dir()
        ModelExecutionRunDir.writeWorkerPid(d, ref(alivePid))
        assertEquals(ModelExecutionRunDir.WorkerLiveness.ALIVE, ModelExecutionRunDir.probeLiveness(d, "abc"))
    }

    // ── safeToDelete — the P0 invariant ─────────────────────────────

    @Test
    fun `result json present but no terminal and worker alive is NOT safe to delete`() {
        if (!procAlive(alivePid)) return // non-Linux host: skip
        // The exact P0 race: client sees result.json (worker wrote it), then
        // would delete — but the worker is still alive and has NOT written
        // terminal (it's mid-finishRequest / writeState). Must NOT delete.
        val d = dir()
        ModelExecutionRunDir.writeWorkerPid(d, ref(alivePid))
        File(d, ModelExecutionMailbox.FILE_RESULT).writeText("""{"ok":true}""")
        assertFalse(ModelExecutionRunDir.safeToDelete(d, "abc"))
    }

    @Test
    fun `result json plus terminal but worker still alive is NOT safe to delete`() {
        if (!procAlive(alivePid)) return // non-Linux host: skip
        val d = dir()
        ModelExecutionRunDir.writeWorkerPid(d, ref(alivePid))
        File(d, ModelExecutionMailbox.FILE_RESULT).writeText("""{"ok":true}""")
        ModelExecutionRunDir.writeTerminal(d)
        // terminal marker written but the worker pid is STILL alive — the
        // worker may be about to self-reap; holding the delete until it is
        // gone avoids any torn cleanup.
        assertFalse(ModelExecutionRunDir.safeToDelete(d, "abc"))
    }

    @Test
    fun `terminal plus confirmed dead pid IS safe to delete`() {
        val d = dir()
        ModelExecutionRunDir.writeWorkerPid(d, ref(deadPid))
        File(d, ModelExecutionMailbox.FILE_RESULT).writeText("""{"ok":true}""")
        ModelExecutionRunDir.writeTerminal(d)
        assertTrue(ModelExecutionRunDir.safeToDelete(d, "abc"))
    }

    @Test
    fun `terminal plus worker never started (no ref) IS safe to delete`() {
        val d = dir()
        ModelExecutionRunDir.writeTerminal(d)
        // No pid ref ever written → nothing of ours is alive; terminal present
        // → safe (the old "300ms settle then delete" path is replaced by this
        // explicit condition).
        assertTrue(ModelExecutionRunDir.safeToDelete(d, "abc"))
    }

    @Test
    fun `no terminal marker is NEVER safe even with dead pid`() {
        val d = dir()
        ModelExecutionRunDir.writeWorkerPid(d, ref(deadPid))
        File(d, ModelExecutionMailbox.FILE_RESULT).writeText("""{"ok":true}""")
        assertFalse(ModelExecutionRunDir.safeToDelete(d, "abc"))
    }

    // ── defensive worker writes (P0-B) ──────────────────────────────

    @Test
    fun `writeState on a deleted dir returns false and does not throw`() {
        val d = dir()
        d.deleteRecursively() // simulates the client having reclaimed the dir
        val ok = ModelExecutionMailbox.writeState(d, ModelExecutionWorkerState.ACTIVE, active = 1)
        assertFalse(ok)
        // P0 FATAL was an UNCAUGHT FileNotFoundException; this path must NOT throw.
    }

    @Test
    fun `writeCancelAck on a deleted dir returns false and does not throw`() {
        val d = dir()
        d.deleteRecursively()
        assertFalse(ModelExecutionMailbox.writeCancelAck(d))
    }

    @Test
    fun `writeState round trips after a terminal write`() {
        val d = dir()
        assertTrue(ModelExecutionRunDir.writeTerminal(d))
        assertTrue(ModelExecutionMailbox.writeState(d, ModelExecutionWorkerState.STOPPING, active = 0))
        assertEquals("STOPPING", ModelExecutionMailbox.readStateName(d))
    }

    // ── >1MiB real file round-trip (not toy samples) ────────────────

    @Test
    fun `mailbox and rundir protocol survive a gt 1MiB payload`() {
        val d = dir()
        val big = ";".repeat(1_200_000) // ~1.2 MiB of text
        File(d, ModelExecutionMailbox.FILE_RESULT).writeText("""{"ok":true,"text":"$big"}""")
        assertTrue(File(d, ModelExecutionMailbox.FILE_RESULT).length() > 1_200_000)
        assertTrue(ModelExecutionRunDir.writeTerminal(d))
        // Read it back fully — proves no 1MiB read/parse ceiling in the
        // protocol path the client actually consumes.
        val text = File(d, ModelExecutionMailbox.FILE_RESULT).readText()
        assertTrue(text.contains("\"ok\":true"))
        assertTrue(text.length > 1_200_000)
    }

    @Test
    fun `stream jsonl with gt 1MiB of media chunk round trips`() {
        // A single >1MiB base64-encoded chunk is exactly what blew the old
        // bridge (`bad string len`); ensure the modelservice stream file path
        // is not limbo-bound either.
        val d = dir()
        val bigChunk = ChatStreamJsonl.encode(
            com.openminis.app.data.model.LLMStreamChunk.MediaAttachment(
                com.openminis.app.data.model.LLMMediaAttachment(
                    type = com.openminis.app.data.model.LLMMediaAttachment.MediaType.IMAGE,
                    mimeType = "image/png",
                    data = ByteArray(1_100_000) { (it % 251).toByte() },
                )
            )
        )
        File(d, ModelExecutionService.STREAM_FILE).writeText(bigChunk + "\n")
        val decoded = ChatStreamJsonl.decode(bigChunk)
        assertTrue(decoded is com.openminis.app.data.model.LLMStreamChunk.MediaAttachment)
        val att = (decoded as com.openminis.app.data.model.LLMStreamChunk.MediaAttachment).attachment
        assertEquals(1_100_000, att.data.size)
    }

    // ── each stream uses its own dir ────────────────────────────────

    @Test
    fun `two run dirs are isolated and their pid refs do not cross-contaminate`() {
        val a = dir("run-A")
        val b = dir("run-B")
        ModelExecutionRunDir.writeWorkerPid(a, ref(deadPid, runId = "A"))
        ModelExecutionRunDir.writeWorkerPid(b, ref(if (procAlive(alivePid)) alivePid else deadPid, runId = "B"))
        // A should see ITS OWN worker dead with NO terminal → not safe (still
        // must see terminal), and B's pid must NOT be mistaken for A's.
        assertEquals(ModelExecutionRunDir.WorkerLiveness.DEAD, ModelExecutionRunDir.probeLiveness(a, "A"))
        val expectedB = if (procAlive(alivePid)) ModelExecutionRunDir.WorkerLiveness.ALIVE else ModelExecutionRunDir.WorkerLiveness.DEAD
        assertEquals(expectedB, ModelExecutionRunDir.probeLiveness(b, "B"))
        assertFalse(ModelExecutionRunDir.safeToDelete(a, "A"))
        assertFalse(ModelExecutionRunDir.safeToDelete(b, "B"))
    }

    // ── TF-G: atomic terminal marker (tmp→fsync→rename) ─────────────

    @Test
    fun `writeTerminal is atomic, leaves no tmp residue`() {
        val d = dir()
        assertTrue(ModelExecutionRunDir.writeTerminal(d))
        // The marker must be at the final name, and no partial tmp remains.
        assertTrue(File(d, ModelExecutionRunDir.FILE_TERMINAL).exists())
        assertFalse(File(d, "${ModelExecutionRunDir.FILE_TERMINAL}.tmp").exists())
        assertTrue(ModelExecutionRunDir.terminalPresent(d))
    }

    @Test
    fun `writeTerminal round-trips at and can be read`() {
        val d = dir()
        assertTrue(ModelExecutionRunDir.writeTerminal(d))
        val raw = File(d, ModelExecutionRunDir.FILE_TERMINAL).readText()
        // valid JSON with an "at" timestamp
        assertTrue(raw.contains("\"at\""))
    }

    @Test
    fun `clientAckPresent reflects the ack file`() {
        val d = dir()
        assertFalse(ModelExecutionRunDir.clientAckPresent(d))
        ModelExecutionMailbox.writeClientAck(d)
        assertTrue(ModelExecutionRunDir.clientAckPresent(d))
    }
}