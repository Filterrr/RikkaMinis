package com.openminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [feat/subagent-roster-routing] The tool CATALOGUE and the tool RUNTIME
 * must describe the same set.
 *
 * Three lists have to agree, and nothing checked that they did:
 *
 *  - the catalogue the schema is built from (`AgentTools.makeAgentTools`)
 *  - the capability map (`AgentCapabilities.capabilityOf`), which decides
 *    what a sub-agent may be granted
 *  - the branches the runner actually executes
 *
 * A tool in the catalogue but not in the capability map is silently
 * dropped from every sub-agent's schema — a capability loss that reads
 * as "the agent just cannot do that". A tool in the capability map but
 * with no runner branch is granted to the agent and then fails at call
 * time, which is worse: the model was told it had the tool.
 *
 * This is source-level because the runner is an Android-dependent file
 * the local JVM script cannot compile.
 */
class SubagentToolCatalogueConsistencyTest {

    private fun source(rel: String): String? {
        val root = com.openminis.app.ProductionSources.mainRoot() ?: return null
        val f = File(root, rel)
        if (!f.isFile) return null
        return SubagentSourceText.stripComments(f.readText())
    }

    @Test
    fun `every tool the runner can execute is in the capability catalog`() {
        val runner = source("tools/SubagentRunner.kt") ?: return
        val caps = source("tools/AgentCapabilities.kt") ?: return

        // The branches the runner executes.
        val executed = mutableSetOf<String>()
        for (marker in listOf("suspend fun executeSubagentTool(", "private fun executeFileTool(")) {
            val i = runner.indexOf(marker)
            if (i < 0) continue
            val j = runner.indexOf("when (name) {", i)
            if (j < 0) continue
            var depth = 0
            var k = runner.indexOf('{', j)
            while (k < runner.length) {
                if (runner[k] == '{') depth++
                else if (runner[k] == '}') depth--
                if (depth == 0) break
                k++
            }
            val block = runner.substring(j, k)
            Regex("\"([a-z_]+)\"\\s*->").findAll(block).forEach {
                executed += it.groupValues[1]
            }
            for (pair in listOf(
                "FileReadTool.NAME" to "file_read",
                "FileWriteTool.NAME" to "file_write",
                "FileEditTool.NAME" to "file_edit",
                "ReadImageTool.NAME" to "read_image",
            )) {
                if (block.contains(pair.first)) executed += pair.second
            }
        }
        // browser_use / web_search go through deps.
        if (runner.contains("executeBrowserUse")) executed += "browser_use"
        if (runner.contains("executeWebSearch")) executed += "web_search"

        val missing = executed.filterNot { caps.contains("\"$it\"") }.sorted()
        assertTrue(
            "the runner can execute tools the capability catalog does not know: " +
                missing.joinToString(", "),
            missing.isEmpty(),
        )
    }

    @Test
    fun `every tool the catalogue offers is in the capability catalog`() {
        val agentTools = source("tools/AgentTools.kt") ?: return
        val caps = source("tools/AgentCapabilities.kt") ?: return
        val catalogue = Regex("name = \"([a-z_]+)\"").findAll(agentTools)
            .map { it.groupValues[1] }
            .toSet()
        val missing = catalogue.filterNot { caps.contains("\"$it\"") }.sorted()
        assertTrue(
            "the catalogue offers tools the capability map does not know: " +
                missing.joinToString(", "),
            missing.isEmpty(),
        )
    }
}
