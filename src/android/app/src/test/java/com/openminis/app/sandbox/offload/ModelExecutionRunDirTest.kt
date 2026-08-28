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
     * TF-H: procRoot is injectable so ALIVE/dead tests do not depend on the
     * CI host's /proc (and do not hit pid-1 / real process identity quirks).
     */
    private lateinit var fakeProc: File

    private fun fakeProcRoot(): File {
        if (!::fakeProc.isInitialized) fakeProc = tmp.newFolder("proc-root")
        return fakeProc
    }

    private fun writeFakeProc(pid: Int, comm: String, uid: Int = 10123, startTicks: Long = 424242L) {
        val d = File(fakeProcRoot(), pid.toString())
        d.mkdirs()
        File(d, "comm").writeText(comm + "\n")
        File(d, "status").writeText("Name:\t$comm\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        // `/proc/<pid>/stat`: `pid (comm) state ppid ... starttime(field 22)`.
        // After the closing paren the tokens are field 3 onward; starttime is
        // field 22 → token index 19.
        val tokens = mutableListOf("S")            // field 3 (state), index 0
        repeat(18) { tokens.add("0") }             // fields 4..21, index 1..18
        tokens.add(startTicks.toString())          // field 22, index 19
        repeat(6) { tokens.add("0") }              // fields 23+ padding
        File(d, "stat").writeText("$pid ($comm) ${tokens.joinToString(" ")}\n")
    }

    private fun ref(
        pid: Int,
        runId: String = "abc",
        processName: String = ":modelservice",
        procStartTicks: Long = 0L,
        uid: Int = -1,
    ) = ModelExecutionRunDir.WorkerProcessRef(
        pid = pid,
        runId = runId,
        nonce = "nonce-1",
        processName = processName,
        startedAtMs = 0L,
        procStartTicks = procStartTicks,
        uid = uid,
    )

    // ── pid ref encode/decode round-trip ────────────────────────────

    @Test
    fun `worker pid ref encodes and decodes`() {
        val d = dir()
        assertTrue(ModelExecutionRunDir.writeWorkerPid(d, ref(deadPid)))
        val back = ModelExecutionRunDir.readWorkerRef(d, "abc")
        assertNotNull(back)
        assertEquals(deadPid, back!!.pid)
        assertEquals("abc", back.runId)
        assertEquals("nonce-1", back.nonce)
        assertEquals(":modelservice", back.processName)
    }

    @Test
    fun `worker pid ref runId mismatch is not visible as ours`() {
        val d = dir()
        ModelExecutionRunDir.writeWorkerPid(d, ref(deadPid, runId = "other-run"))
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
        val d = dir()
        writeFakeProc(pid = 9001, comm = ":modelservice", startTicks = 424242L)
        ModelExecutionRunDir.writeWorkerPid(d, ref(9001, processName = ":modelservice", procStartTicks = 424242L, uid = 10123))
        val fake = fakeProcRoot()
        assertEquals(ModelExecutionRunDir.WorkerLiveness.ALIVE, ModelExecutionRunDir.probeLiveness(d, "abc", fake))
    }

    @Test
    fun `matching runId with debased proc identity is not ALIVE`() {
        val d = dir()
        writeFakeProc(pid = 9002, comm = "other.process", startTicks = 1L, uid = 1)
        ModelExecutionRunDir.writeWorkerPid(d, ref(9002, processName = ":modelservice", procStartTicks = 424242L, uid = 10123))
        val fake = fakeProcRoot()
        assertTrue(
            "identity mismatch must not be ALIVE",
            ModelExecutionRunDir.probeLiveness(d, "abc", fake) != ModelExecutionRunDir.WorkerLiveness.ALIVE,
        )
    }

    @Test
    fun `identity mismatch is UNKNOWN not DEAD`() {
        // [mismatch-is-not-death] The pid is occupied by something else —
        // either the worker died and the pid was recycled, or the identity
        // read-back drifted while the worker is still alive. The second
        // possibility forbids reporting DEAD: no destructive caller may act
        // on a mismatch.
        val d = dir()
        writeFakeProc(pid = 9003, comm = "recycled.process", startTicks = 999L, uid = 10123)
        ModelExecutionRunDir.writeWorkerPid(d, ref(9003, processName = ":modelservice", procStartTicks = 424242L, uid = 10123))
        assertEquals(
            ModelExecutionRunDir.WorkerLiveness.UNKNOWN,
            ModelExecutionRunDir.probeLiveness(d, "abc", fakeProcRoot()),
        )
    }

    // ── safeToDelete — the P0 invariant ─────────────────────────────

    @Test
    fun `result json present but no terminal and worker alive is NOT safe to delete`() {
        val d = dir()
        writeFakeProc(pid = 9005, comm = ":modelservice", startTicks = 424242L)
        ModelExecutionRunDir.writeWorkerPid(d, ref(9005, processName = ":modelservice", procStartTicks = 424242L, uid = 10123))
        File(d, ModelExecutionMailbox.FILE_RESULT).writeText("""{"ok":true}""")
        assertFalse(ModelExecutionRunDir.safeToDelete(d, "abc", fakeProcRoot()))
    }

    @Test
    fun `result json plus terminal but worker still alive is NOT safe to delete`() {
        val d = dir()
        writeFakeProc(pid = 9006, comm = ":modelservice", startTicks = 424242L)
        ModelExecutionRunDir.writeWorkerPid(d, ref(9006, processName = ":modelservice", procStartTicks = 424242L, uid = 10123))
        File(d, ModelExecutionMailbox.FILE_RESULT).writeText("""{"ok":true}""")
        ModelExecutionRunDir.writeTerminal(d)
        // terminal marker written but the worker pid is STILL alive — the
        // worker may be about to self-reap; holding the delete until it is
        // gone avoids any torn cleanup.
        assertFalse(ModelExecutionRunDir.safeToDelete(d, "abc", fakeProcRoot()))
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

    @Test
    fun `terminal plus identity mismatch is NOT safe to delete`() {
        // [mismatch-is-not-death] Even with a terminal marker, an identity
        // mismatch is drift suspicion (the worker may be alive with read-back
        // drift), not confirmed death. The old probeLiveness collapsed this
        // into DEAD and authorized the delete; now it must be refused —
        // the dir is kept as an orphan until the pid provably disappears.
        val d = dir()
        writeFakeProc(pid = 9007, comm = "recycled.process", startTicks = 999L, uid = 10123)
        ModelExecutionRunDir.writeWorkerPid(d, ref(9007, processName = ":modelservice", procStartTicks = 424242L, uid = 10123))
        File(d, ModelExecutionMailbox.FILE_RESULT).writeText("""{"ok":true}""")
        ModelExecutionRunDir.writeTerminal(d)
        assertFalse(ModelExecutionRunDir.safeToDelete(d, "abc", fakeProcRoot()))
    }

    // ── writeTerminal atomic commit ([atomic-terminal]) ─────────────

    @Test
    fun `writeTerminal commits complete json and leaves no tmp residue`() {
        val d = dir()
        assertTrue(ModelExecutionRunDir.writeTerminal(d))
        val target = File(d, ModelExecutionRunDir.FILE_TERMINAL)
        assertTrue(target.isFile)
        // Content must be complete, parseable JSON — a partial marker would
        // be a false "run complete" signal for every presence-only consumer.
        val parsed = org.json.JSONObject(target.readText())
        assertTrue(parsed.has("at"))
        assertFalse(File(d, "${ModelExecutionRunDir.FILE_TERMINAL}.tmp").exists())
    }

    @Test
    fun `writeTerminal is idempotent`() {
        val d = dir()
        assertTrue(ModelExecutionRunDir.writeTerminal(d))
        val first = File(d, ModelExecutionRunDir.FILE_TERMINAL).readText()
        assertTrue(ModelExecutionRunDir.writeTerminal(d))
        assertEquals(first, File(d, ModelExecutionRunDir.FILE_TERMINAL).readText())
    }

    @Test
    fun `writeTerminal rename failure leaves no in-place fallback marker`() {
        // Force rename() to fail: a DIRECTORY at the target path cannot be
        // replaced by a file rename (EISDIR on POSIX). The pre-fix fallback
        // then tried an in-place write (which crashes mid-write exactly on
        // the "false completion" path this test pins); the fix instead
        // deletes the tmp and returns false — no partial marker, no residue.
        val d = dir()
        val target = File(d, ModelExecutionRunDir.FILE_TERMINAL)
        target.mkdirs()
        File(target, "sentinel").writeText("x") // non-empty dir
        assertFalse(ModelExecutionRunDir.writeTerminal(d))
        // Still a directory (untouched — never written in place) and the
        // tmp staging file is cleaned up.
        assertTrue(target.isDirectory)
        assertFalse(File(d, "${ModelExecutionRunDir.FILE_TERMINAL}.tmp").exists())
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
        writeFakeProc(pid = 9003, comm = ":modelservice", startTicks = 424242L)
        ModelExecutionRunDir.writeWorkerPid(b, ref(9003, runId = "B", processName = ":modelservice", procStartTicks = 424242L, uid = 10123))
        val fake = fakeProcRoot()
        // A should see ITS OWN worker dead with NO terminal → not safe (still
        // must see terminal), and B's pid must NOT be mistaken for A's.
        assertEquals(ModelExecutionRunDir.WorkerLiveness.DEAD, ModelExecutionRunDir.probeLiveness(a, "A", fake))
        assertEquals(ModelExecutionRunDir.WorkerLiveness.ALIVE, ModelExecutionRunDir.probeLiveness(b, "B", fake))
        assertFalse(ModelExecutionRunDir.safeToDelete(a, "A", fake))
        assertFalse(ModelExecutionRunDir.safeToDelete(b, "B", fake))
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