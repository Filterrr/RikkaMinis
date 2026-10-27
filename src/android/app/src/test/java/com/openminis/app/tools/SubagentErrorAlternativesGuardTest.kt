package com.openminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [feat/subagent-roster-routing] A failure the model cannot ACT on is a
 * lost delegation.
 *
 * The parent's standing instruction is "do not retry the same spawn", so
 * when a spawn fails, the error text is the parent's ONLY chance to
 * correct itself in the same turn. "No such model" alone leaves it
 * guessing; the error has to name what it could have used instead.
 *
 * The resolver had already collected the candidates in
 * `ModelPlan.Failed.options` — and the runner's message dropped them.
 * The same shape applies to the roster: an unknown agent name must list
 * the roster.
 *
 * This guard is SOURCE-level because the runner is an Android-dependent
 * file the local JVM script cannot compile.
 */
class SubagentErrorAlternativesGuardTest {

    @Test
    fun `a failed model plan names the available alternatives`() {
        val root = com.openminis.app.ProductionSources.mainRoot() ?: return
        val runner = File(root, "tools/SubagentRunner.kt")
        if (!runner.isFile) return
        // Comments are stripped: a mention of `plan.options` in a
        // comment is not a USE of it, and the guard would then pass on a
        // branch that had dropped the candidates.
        val text = SubagentSourceText.stripComments(runner.readText())

        // The `Failed` branch must READ `options` — that is the whole
        // point. A branch that renders `plan.message` alone drops the
        // candidates the resolver already found.
        val failedBranch = Regex(
            """plan is SubagentGroupSpread\.ModelPlan\.Failed -> \{(.*?)\n {12}\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(text)?.groupValues?.get(1)
        assertTrue(
            "the Failed branch of the model plan was not found in SubagentRunner.kt",
            failedBranch != null,
        )
        assertTrue(
            "the Failed branch must render the available alternatives " +
                "(plan.options), not just the failure message",
            failedBranch!!.contains("plan.options"),
        )
    }

    @Test
    fun `an unknown agent name error lists the roster`() {
        val root = com.openminis.app.ProductionSources.mainRoot() ?: return
        val hint = File(root, "tools/SubagentRosterHint.kt")
        if (!hint.isFile) return
        val text = SubagentSourceText.stripComments(hint.readText())
        // The error renders the ROSTER (the whole merged list, so the
        // parent sees both routes) and tells it what to do — not just
        // that it failed.
        val body = Regex("""fun unknownAgentMessage[\s\S]{0,1200}?\n    \}""")
            .find(text)?.value
        assertTrue(
            "the unknown-agent error was not found",
            body != null,
        )
        assertTrue(
            "the unknown-agent error must render the roster",
            body!!.contains("build(entries"),
        )
        assertTrue(
            "the unknown-agent error must tell the parent what to do next",
            body.contains("Pick one of the names above") || body.contains("do the work inline"),
        )
    }
}
