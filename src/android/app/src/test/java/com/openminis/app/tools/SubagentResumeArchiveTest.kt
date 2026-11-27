package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-resume-durability] The on-disk continuation archive.
 *
 * The registry is memory-only (last 20 runs), so a resume used to die with
 * the process — precisely the case a continuation exists for. The archive is
 * small, written by the runtime at terminal time, and read back by the
 * runtime only; these tests pin the round-trip and, more importantly, the
 * failure direction: a corrupt archive must look ABSENT (so the caller
 * reports "not a run") rather than look like a run whose evidence is empty
 * (which would silently start a continuation with no context).
 */
class SubagentResumeArchiveTest {

    private fun snapshot(
        runId: String = "run-3",
        status: String = "TIMED_OUT",
        report: String = "half-done work, notes in notes.md",
        artifacts: List<String> = listOf("/var/minis/workspace/subagents/run-3/notes.md"),
    ) = SubagentResumeArchive.Snapshot(
        runId = runId,
        skillId = "general-agent",
        skillName = "general-agent",
        status = status,
        error = "exceeded the 600-second run budget after 40 turn(s)",
        turns = 40,
        maxTurns = 40,
        report = report,
        artifacts = artifacts,
    )

    @Test
    fun `a snapshot survives the json round-trip`() {
        val original = snapshot()
        val parsed = SubagentResumeArchive.parse(SubagentResumeArchive.toJson(original))
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun `audit fields survive the archive`() {
        // [T-subagent-audit] After a restart this file is the ONLY place that
        // still says which configuration ran: the registry is memory-only and
        // both the skill file and the permission snapshot can change later.
        val original = snapshot().copy(
            agentKind = "profile",
            toolsHash = "file_read,shell_execute|deny:",
            profileVersion = 2,
            roleSummary = "auditing a Rust parser",
        )
        val parsed = SubagentResumeArchive.parse(SubagentResumeArchive.toJson(original))!!
        assertEquals("profile", parsed.agentKind)
        assertEquals("file_read,shell_execute|deny:", parsed.toolsHash)
        assertEquals(2, parsed.profileVersion)
        assertEquals("auditing a Rust parser", parsed.roleSummary)
    }

    @Test
    fun `an archive written before the audit fields keeps reading`() {
        // Incremental fields: an old file must still resume, with defaults -
        // NOT be refused as a version mismatch.
        val legacy = """{"v":1,"runId":"run-old","skillId":"s","skillName":"s",""" +
            """"status":"SUCCESS","error":"","turns":3,"maxTurns":5,"report":"ok","artifacts":[]}"""
        val parsed = SubagentResumeArchive.parse(legacy)!!
        assertEquals("skill", parsed.agentKind)
        assertEquals("", parsed.toolsHash)
        assertEquals(0, parsed.profileVersion)
    }

    @Test
    fun `the report is bounded at serialisation time, not just at hand-off`() {
        // The file is read back into a USER message later; letting the file
        // grow with the report moves the cost to disk and to whoever reads
        // it. Bound once, where the bytes are written.
        val parsed = SubagentResumeArchive.parse(
            SubagentResumeArchive.toJson(snapshot(report = "x".repeat(10_000))),
        )!!
        assertEquals(SubagentResumeArchive.MAX_REPORT_CHARS, parsed.report.length)
    }

    @Test
    fun `artifacts are bounded and blank paths dropped`() {
        val many = (1..60).map { if (it % 10 == 0) "  " else "/tmp/f$it" }
        val parsed = SubagentResumeArchive.parse(
            SubagentResumeArchive.toJson(snapshot(artifacts = many)),
        )!!
        assertEquals(SubagentResumeArchive.MAX_ARTIFACTS, parsed.artifacts.size)
        assertTrue(parsed.artifacts.none { it.isBlank() })
    }

    @Test
    fun `a corrupt archive looks absent, not empty`() {
        assertNull(SubagentResumeArchive.parse("not json at all"))
        assertNull(SubagentResumeArchive.parse(""))
        assertNull(SubagentResumeArchive.parse("{}"))
        assertNull(
            "a missing run id cannot identify a continuation",
            SubagentResumeArchive.parse("""{"v":1,"skillId":"general-agent"}"""),
        )
    }

    @Test
    fun `an unknown schema version is refused instead of half-read`() {
        val json = SubagentResumeArchive.toJson(snapshot()).replace("\"v\":1", "\"v\":99")
        assertNull(SubagentResumeArchive.parse(json))
    }

    @Test
    fun `a kill-mid-run archive reads back as INTERRUPTED, not RUNNING`() {
        // The archive is written from a finally that can run while the
        // registry still says RUNNING (process death, host kill). A
        // continuation must be able to pick that up; status is only a
        // label.
        val json = SubagentResumeArchive.toJson(snapshot(status = "RUNNING"))
        val parsed = SubagentResumeArchive.parse(json)!!
        assertEquals("RUNNING", parsed.status)
        val blank = SubagentResumeArchive.parse(
            """{"v":1,"runId":"r1","skillId":"s","skillName":"s","status":"","report":""}""",
        )!!
        assertEquals("INTERRUPTED", blank.status)
    }

    @Test
    fun `a blank skill name falls back to the id so the hand-off always names something`() {
        val parsed = SubagentResumeArchive.parse(
            """{"v":1,"runId":"r1","skillId":"general-agent","skillName":"","status":"TIMED_OUT"}""",
        )!!
        assertEquals("general-agent", parsed.skillName)
    }

    @Test
    fun `negative counters are clamped instead of rendered as nonsense`() {
        val parsed = SubagentResumeArchive.parse(
            """{"v":1,"runId":"r1","skillId":"s","skillName":"s","status":"TIMED_OUT","turns":-4,"maxTurns":-1}""",
        )!!
        assertEquals(0, parsed.turns)
        assertEquals(0, parsed.maxTurns)
    }
}