package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-report-summary] WIRING guard.
 *
 * The parser is pure and unit-tested in
 * [SubagentReportContractTest]. What those tests CANNOT see is whether
 * the runtime actually PARSES — and what it does with the result.
 *
 * A parser that is never called is a parser that does not exist: the
 * report reaches the parent as the same prose it always did, and every
 * pure test stays green. That is exactly the failure mode this repo
 * has already been bitten by (a pure function extracted from the runner
 * and then never wired to it — see `SubagentModelChoiceWiringTest`).
 *
 * So these tests grep the production source and assert the call sites
 * exist. The pattern is the one the repo already uses
 * (see `ProductionSources`).
 */
class SubagentReportSummaryWiringTest {

    private val runner by lazy { ProductionSources.read("tools/SubagentRunner.kt") }
    private val registry by lazy { ProductionSources.read("tools/SubagentRunRegistry.kt") }
    private val orchestration by lazy { ProductionSources.read("tools/SubagentOrchestration.kt") }
    private val result by lazy { ProductionSources.read("tools/SubagentResult.kt") }
    private val delivery by lazy { ProductionSources.read("tools/SubagentParentDelivery.kt") }

    /**
     * The parse must happen ONCE, on the DELIVERED text — after the
     * artifact reconciliation, so the parsed artifact paths are the
     * repaired ones — and never on a runtime-assembled digest (which is
     * not a contract and would manufacture fields the model never
     * wrote).
     *
     * The COUNT matters: a presence check stays green when the parse is
     * replaced by a no-op, because the substring is still there. The
     * negative verification caught exactly that.
     */
    @Test
    fun `the runner parses the report contract`() {
        assertTrue(
            "the runner must parse the delivered report: found no SubagentReportContract.parse call",
            runner.contains("SubagentReportContract.parse(deliveredText)"),
        )
        assertEquals(
            "the delivered text must be parsed exactly once — two parses " +
                "of the same text could disagree",
            1,
            runner.countOccurrences("SubagentReportContract.parse(deliveredText)"),
        )
        assertTrue(
            "the parse must be guarded by the report source — a runtime " +
                "digest is not a contract and parsing it would manufacture fields",
            runner.contains("isModelAuthored(reportSource)"),
        )
    }

    /**
     * The parsed object must reach the REGISTRY, because the detached
     * paths (wake / join / wait_any) read everything from the snapshot.
     * A parse that never reaches the registry helps only the inline
     * path — which is one of the four.
     */
    @Test
    fun `the parsed report reaches the registry snapshot`() {
        assertTrue(
            "the runner must store the parsed report in the registry",
            runner.contains("registry.setParsedReport("),
        )
        assertTrue(
            "the registry must expose the setter",
            registry.contains("fun setParsedReport("),
        )
    }

    /** The detached paths must read it back out of the snapshot. */
    @Test
    fun `the detached outcome carries the parsed report`() {
        assertTrue(
            "the JobOutcome must have a parsed field",
            orchestration.contains("val parsed: SubagentReportContract.ParsedReport?"),
        )
        assertTrue(
            "the runner must read the parsed report back from the snapshot",
            runner.contains("parsed = snapshot?.parsedReport"),
        )
    }

    /**
     * The whole POINT of the field is that the parent sees it. If the
     * surfaces never render it, the parse is dead weight — the parent
     * still has to read the whole report.
     *
     * The inline path renders the summary in TWO branches — the success
     * branch and the failure branch — and the guard counts them rather
     * than checking for their presence. A presence check is not enough:
     * removing BOTH renderings still leaves the substring in the file
     * (in a comment, in the failed branch, in the guard's own message),
     * and the guard stays green while the parent sees nothing. That
     * exact false green is what the negative verification caught.
     */
    @Test
    fun `every delivery surface renders the summary`() {
        // [feat/subagent-result-envelope] The inline path used to have
        // its own renderer with TWO summary lines (one per branch). It
        // now renders through the ONE envelope, so the summary is
        // rendered ONCE, in the envelope, and BOTH inline branches get
        // it — a single render site that every path shares.
        //
        // The guard therefore asks: does the envelope render the
        // summary, and does the inline path go through the envelope?
        val envelope = ProductionSources.read("tools/SubagentResultEnvelope.kt")
        assertTrue(
            "the envelope must render the summary",
            envelope.contains("summary: "),
        )
        assertTrue(
            "the envelope must render the summary from the parsed report",
            envelope.contains("parsed.summary"),
        )
        assertTrue(
            "the inline result must render through the envelope",
            result.contains("SubagentResultEnvelope.render("),
        )
        // [feat/subagent-result-envelope] The detached paths render
        // through the envelope too, so the summary rides on the envelope's
        // own field — there is no separate digest helper any more.
        assertTrue(
            "the wake prompt must render through the envelope",
            delivery.contains("SubagentResultEnvelope.render("),
        )
        assertTrue(
            "the join prompt must render through the envelope",
            runner.contains("SubagentResultEnvelope.render("),
        )
        assertTrue(
            "the wait_any prompt must render through the envelope",
            runner.countOccurrences("SubagentResultEnvelope.render(") >= 2,
        )
    }

    /**
     * The skills live under `src/main/assets/skills/`, not under the
     * Java source root [ProductionSources.mainRoot] resolves to, so they
     * are found by walking up from it. The walk, not a fixed number of
     * `parentFile` hops: the source root's depth depends on how the
     * test is launched (module dir vs repository root), and a fixed hop
     * count is a test that passes in one layout and fails in another.
     */
    private fun findSkillsDir(from: java.io.File): java.io.File {
        var dir: java.io.File? = from
        while (dir != null) {
            val candidate = java.io.File(dir, "assets/skills")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("skills directory not found from ${from.absolutePath}")
    }

    /**
     * Count how many times [needle] occurs in [haystack].
     *
     * A presence check answers "does the file mention this at all?"; a
     * count answers "how many of the places that must have it DO have
     * it?". The difference is the difference between a guard that
     * catches a removed call site and one that does not.
     */
    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    /**
     * The skill contract is what makes the model WRITE the field. A
     * parser for a field no model produces is a parser that always
     * returns the fallback — and the parent would be reading a derived
     * finding while believing it was a summary.
     *
     * The skills live under `src/main/assets/skills/`, not under the
     * Java source root [ProductionSources.mainRoot] resolves to, so they
     * are read from the module directory directly.
     */
    @Test
    fun `both sub-agent skills require the summary field`() {
        val skillsDir = findSkillsDir(ProductionSources.mainRoot()!!)
        assertTrue(
            "the skills directory must exist: ${skillsDir.absolutePath}",
            skillsDir.isDirectory,
        )

        val general = java.io.File(skillsDir, "general-agent/SKILL.md")
        val readOnly = java.io.File(skillsDir, "read-only-researcher/SKILL.md")

        assertTrue("the general-agent skill must exist", general.isFile)
        assertTrue("the read-only skill must exist", readOnly.isFile)

        assertTrue(
            "the general-agent skill must require a summary field",
            general.readText().contains("`summary`:"),
        )
        assertTrue(
            "the read-only skill must require a summary field",
            readOnly.readText().contains("`summary`:"),
        )
    }
}
