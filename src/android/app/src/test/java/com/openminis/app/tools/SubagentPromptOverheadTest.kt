package com.openminis.app.tools

import com.openminis.app.ProductionSources
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-prompt-overhead] Pins the REAL per-request payload a
 * sub-agent sends, so the number cannot silently drift.
 *
 * Why this matters: a sub-agent's request carries the system prompt AND
 * the tool schemas, and BOTH are re-sent on every turn. With a 75-turn
 * budget the schemas alone are re-sent 75 times.
 *
 * The measurement here is deliberately coarse — it measures the SOURCE
 * size of each tool definition, not the exact serialized JSON the
 * provider receives. That is enough to catch an order-of-magnitude
 * regression, which is what this test exists for.
 */
class SubagentPromptOverheadTest {

    private fun sourceOf(path: String): String = ProductionSources.read(path)

    /**
     * The SKILL.md files live in `src/main/assets`, not under `src/main/java`
     * — [ProductionSources] only walks the Kotlin tree, so they are read
     * directly.
     */
    private fun skillBody(dir: String): String {
        val moduleDir = File("").absoluteFile
        val candidates = listOf(
            File(moduleDir, "src/main/assets/skills/$dir/SKILL.md"),
            File(moduleDir, "src/android/app/src/main/assets/skills/$dir/SKILL.md"),
        )
        val f = candidates.firstOrNull { it.isFile }
            ?: error("skill not found: $dir (looked in $candidates)")
        return f.readText()
    }

    /**
     * The size of one `AgentToolDefinition(...)` literal in [source],
     * bounded by the next definition. Returns 0 when the tool is not
     * defined in that file.
     */
    private fun definitionSize(source: String, toolName: String): Int {
        val starts = Regex("AgentToolDefinition\\(").findAll(source)
            .map { it.range.first }.toList()
        for (i in starts.indices) {
            val end = if (i + 1 < starts.size) starts[i + 1] else source.length
            val segment = source.substring(starts[i], end)
            if (segment.contains("name = \"$toolName\"")) {
                val close = segment.indexOf("\n    )")
                return if (close > 0) close else segment.length
            }
        }
        return 0
    }

    @Test
    fun `browser_use dominates the sub-agent tool schema`() {
        // browser_use's schema is ~4x the next biggest tool's. It is the
        // single biggest lever on the sub-agent's per-request cost, so a
        // change to it deserves a deliberate decision rather than a
        // drive-by edit.
        val agentTools = sourceOf("tools/AgentTools.kt")
        val browser = definitionSize(agentTools, "browser_use")
        val shell = definitionSize(agentTools, "shell_execute")
        val webSearch = definitionSize(agentTools, "web_search")

        assertTrue("browser_use definition not found", browser > 0)
        assertTrue(
            "browser_use should dominate the schema (browser=$browser shell=$shell web=$webSearch)",
            browser > shell * 3,
        )
    }

    @Test
    fun `the plan directive is a small fraction of the system prompt`() {
        // The plan directive is sent on EVERY request. It has to stay
        // cheap: it is pure overhead repeated once per turn.
        val contract = sourceOf("tools/SubagentPlanContract.kt")
        val directive = contract.substringAfter("val DIRECTIVE: String = \"\"\"")
            .substringBefore("\"\"\"")

        assertTrue(
            "the plan directive should stay under 1.5 KB, was ${directive.length}",
            directive.length < 1500,
        )
    }

    @Test
    fun `the runtime context is a small fraction of the system prompt`() {
        // The runtime preamble (date, artifacts dir, budgets) is also sent
        // on every request. It grew when the budgets were added; the test
        // keeps it from growing without bound.
        val skill = sourceOf("tools/SubagentSkill.kt")
        // Bound the slice to the function body, not to a fixed window —
        // a fixed window would include whatever follows it and drift.
        val ctx = skill.substringAfter("fun buildRuntimeContext(")
            .substringBefore("\n    }")
        assertTrue(
            "the runtime context builder should stay under 2 KB of source, was ${ctx.length}",
            ctx.length < 2000,
        )
    }

    @Test
    fun `skills that do not declare allowed_tools get the full tool set`() {
        // This is the finding this measurement was commissioned to check:
        // 6 of the 7 shipped skills declare NO allowlist, so they all
        // receive browser_use - which is ~76% of the schema - whether or
        // not they ever browse. The schema is filtered by the allowlist
        // only; without one, nothing is filtered.
        //
        // The test states the FACT, not a target: if a skill later gains
        // an allowlist, this test starts failing and someone has to look
        // at whether the number still means what it meant.
        val skillDirs = listOf(
            "general-agent", "github-ops", "ubuntu-companion", "skill-creator",
            "semantic-memory", "cloudflare-fullright-ops", "read-only-researcher",
        )
        var withAllowlist = 0
        for (dir in skillDirs) {
            val md = skillBody(dir)
            if (md.contains("allowed_tools:")) withAllowlist++
        }
        assertTrue(
            "the shipped skill set was measured with exactly one allowlist; found $withAllowlist",
            withAllowlist == 1,
        )
    }
}
