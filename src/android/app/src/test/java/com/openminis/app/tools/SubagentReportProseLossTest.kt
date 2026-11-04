package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-contract-prose-loss] + [fix/subagent-artifact-path-truth] +
 * [fix/subagent-contract-budget]
 *
 * The parser reads a report the MODEL wrote, in the layout its own skill
 * documents:
 *
 *     summary: …      (3-5 lines)
 *     status: done
 *     key_findings:   (bullets)
 *     gaps: …
 *     artifacts: …
 *
 * Three defects shipped against that layout, and none of them was visible to
 * the existing tests because every fixture was written in the one shape the
 * code happened to handle:
 *
 *  1. every line between `status:` and the next header was DROPPED — not into
 *     the prose, not into a field, nowhere;
 *  2. `summary:` / `gaps:` swallowed everything until the next header, so a
 *     closing paragraph became part of a scalar field;
 *  3. `looksLikePath` accepted a sentence ("No artifacts were produced.")
 *     because the last word carries a dot.
 */
class SubagentReportProseLossTest {

    private fun parse(text: String) = SubagentReportContract.parse(text)

    // ── 1. the status body ───────────────────────────────────────────────

    /**
     * SKILL.md documents `summary` → `status` → the evidence. A model that
     * explains its status has that explanation delivered as prose now, not
     * deleted.
     */
    @Test
    fun `text after the status field is prose, not silence`() {
        val parsed = parse(
            """
            summary: The migration is done.
            status: done

            I checked three providers and the second one was the blocker;
            the fix lives in ProviderFactory.
            key_findings:
            - provider 2 blocked (ProviderFactory.kt:88)
            gaps: none
            """.trimIndent(),
        )

        assertEquals("The migration is done.", parsed.summary)
        assertEquals("done", parsed.status)
        assertTrue(
            "the explanation after `status:` must survive: <${parsed.prose}>",
            parsed.prose.contains("the second one was the blocker"),
        )
        assertTrue(
            "the whole paragraph must survive: <${parsed.prose}>",
            parsed.prose.contains("the fix lives in ProviderFactory"),
        )
        assertEquals("the findings are unaffected", 1, parsed.findings.size)
    }

    /** The status VALUE is not prose — it must not be duplicated into it. */
    @Test
    fun `the status value itself is not echoed as prose`() {
        val parsed = parse("summary: s\nstatus: partial\nkey_findings:\n- a (x)")

        assertEquals("partial", parsed.status)
        assertFalse(
            "the value of a field is not prose: <${parsed.prose}>",
            parsed.prose.contains("partial"),
        )
    }

    /** A token the vocabulary does not recognise is still text the model wrote. */
    @Test
    fun `an unrecognised status token is kept as prose`() {
        val parsed = parse("summary: s\nstatus: mostly finished\nkey_findings:\n- a (x)")

        assertEquals(null, parsed.status)
        assertTrue(
            "an unknown status must not vanish: <${parsed.prose}>",
            parsed.prose.contains("mostly finished"),
        )
    }

    // ── 2. scalar fields end at a blank line ─────────────────────────────

    /**
     * A blank line ends a prose field. Without that rule the summary swallowed
     * the next paragraph — the field that "survives truncation" was the one
     * eating everything after it.
     */
    @Test
    fun `a blank line ends the summary, and what follows becomes prose`() {
        val parsed = parse(
            """
            summary: The migration is done.
            status: done

            A closing paragraph that belongs to the reasoning.
            key_findings:
            - a (x)
            gaps: none
            """.trimIndent(),
        )

        assertEquals(
            "the summary must not swallow the paragraph",
            "The migration is done.",
            parsed.summary,
        )
        assertTrue(
            "the paragraph must land in the prose: <${parsed.prose}>",
            parsed.prose.contains("A closing paragraph"),
        )
    }

    /** A multi-line summary (no blank line between the lines) still joins. */
    @Test
    fun `a multi-line summary still joins`() {
        val parsed = parse(
            """
            summary: first line
              continuation: still the summary
            key_findings:
            - a (x)
            """.trimIndent(),
        )
        assertEquals("first line continuation: still the summary", parsed.summary)
    }

    /** A blank line ends the gaps; the paragraph after it stays readable. */
    @Test
    fun `a blank line ends the gaps`() {
        val parsed = parse(
            """
            summary: s
            status: done
            key_findings:
            - a (x)
            gaps: none

            That said, the second provider still needs a follow-up.
            """.trimIndent(),
        )

        assertEquals("none", parsed.gaps)
        assertTrue(
            "the trailing paragraph must survive: <${parsed.prose}>",
            parsed.prose.contains("still needs a follow-up"),
        )
    }

    /**
     * A LIST field is exempt: bullets are often written with blank lines
     * between items, and ending the section there would silently drop half the
     * conclusions.
     */
    @Test
    fun `a blank line does not split a findings list`() {
        val parsed = parse(
            """
            summary: s
            status: done
            key_findings:
            - first (a)

            - second (b)
            gaps: none
            """.trimIndent(),
        )

        assertEquals(
            "both findings must survive a blank line between bullets: ${parsed.findings}",
            listOf("first (a)", "second (b)"),
            parsed.findings,
        )
    }

    // ── 3. a sentence is not a path ──────────────────────────────────────

    /**
     * A model that answers the `artifacts:` field in a sentence must not hand
     * the parent invented deliverables. `No artifacts were produced.` used to
     * be parsed as the path `produced.`.
     */
    @Test
    fun `a sentence in the artifacts section is prose, not a path`() {
        val parsed = parse(
            """
            summary: done
            status: done
            key_findings:
            - x (a.kt:1)
            gaps: none
            artifacts:
            - /tmp/real.md
            - No artifacts were produced.
            - see the report above.
            - notes.md
            """.trimIndent(),
        )

        assertEquals(
            "only the real paths are deliverables: ${parsed.artifacts}",
            listOf("/tmp/real.md", "notes.md"),
            parsed.artifacts,
        )
        assertTrue(
            "the prose must keep the sentence: <${parsed.prose}>",
            parsed.prose.contains("No artifacts were produced."),
        )
    }

    /** An empty-field answer is not a deliverable either. */
    @Test
    fun `an explicit none is not an artifact`() {
        listOf("none", "none.", "n/a", "N/A", "无", "-", "没有").forEach { token ->
            val parsed = parse("summary: s\nstatus: done\nartifacts: $token")
            assertTrue(
                "`$token` must not become a deliverable: ${parsed.artifacts}",
                parsed.artifacts.isEmpty(),
            )
        }
    }

    /** The forms a real path takes must all still be accepted. */
    @Test
    fun `real path shapes are still accepted`() {
        val accepted = listOf(
            "/var/minis/workspace/subagents/subagent-1/report.md",
            "data/report.md",
            "notes.md",
            "`/tmp/out.json`",
            "\"/tmp/quoted.csv\"",
            "chart.png",
        )
        accepted.forEach { path ->
            val parsed = parse("summary: s\nstatus: done\nartifacts: $path")
            assertEquals(
                "`$path` must be a deliverable: ${parsed.artifacts}",
                1,
                parsed.artifacts.size,
            )
        }
    }

    /**
     * [fix/subagent-artifact-path-truth] A SENTENCE ABOUT a path is not a path.
     *
     * This is the case only the whitespace rule can reject: the line contains
     * a separator, does not end in a full stop and carries a plausible
     * extension, so every other rule in `looksLikePath` accepts it. Removing
     * the whitespace check must make this test fail — otherwise the check is
     * unpinned and can be deleted in a future refactor without a red test.
     */
    @Test
    fun `a sentence containing a path is not a deliverable`() {
        val parsed = parse(
            """
            summary: done
            status: done
            key_findings:
            - x (a.kt:1)
            gaps: none
            artifacts:
            - /var/minis/workspace/report.md was written by the migration
            - report.md is the deliverable
            """.trimIndent(),
        )

        assertTrue(
            "a sentence about a path must not become a deliverable: ${parsed.artifacts}",
            parsed.artifacts.isEmpty(),
        )
        assertTrue(
            "it must be kept as prose instead: <${parsed.prose}>",
            parsed.prose.contains("was written by the migration"),
        )
    }

    /** A directory is a deliverable; a bare separator is not. */
    @Test
    fun `a directory path is accepted and a bare slash is not`() {
        val dir = parse("summary: s\nstatus: done\nartifacts: /var/minis/workspace/subagents/subagent-1/")
        assertEquals(1, dir.artifacts.size)

        val slash = parse("summary: s\nstatus: done\nartifacts: /")
        assertTrue("/ alone is not a deliverable: ${slash.artifacts}", slash.artifacts.isEmpty())
    }
}