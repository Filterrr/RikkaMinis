package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [port/upstream-subagent-child-session] Guards for the persistence wiring.
 *
 * Everything here lives in a file the local JVM harness cannot compile
 * (SubagentRunner → PRoot, ChatViewModel → Android + Compose), so the checks are
 * textual. Each one pins a decision that fails SILENTLY when removed:
 *
 *  - no child session is created → every run is unreadable after the fact, and
 *    nothing logs an error;
 *  - the report is not written on the terminal path → the child session shows
 *    the task and the turn texts but never the answer;
 *  - navigation reads all sessions → the app can open INTO a hidden run.
 */
class SubagentChildSessionWiringTest {

    private fun src(rel: String) = SubagentSourceText.stripComments(ProductionSources.read(rel))

    @Test
    fun `the runner creates the child session and records the task`() {
        val runner = src("tools/SubagentRunner.kt")
        assertTrue(
            "executeLoop must create the child session",
            runner.contains("deps.childSessions().createChildSession("),
        )
        assertTrue(
            "the task must be the child session's opening message",
            runner.contains("SubagentChildTranscript.taskPartsJson("),
        )
    }

    @Test
    fun `each turn's text is persisted once, not per delta`() {
        val runner = src("tools/SubagentRunner.kt")
        assertTrue(runner.contains("SubagentChildTranscript.textPartsJson("))
        // Appending on the delta callback would write hundreds of rows per turn.
        assertFalse(
            "the transcript must not be written from the streaming delta path",
            Regex("appendStreamedText\\([^)]*\\)[^\\n]*appendChildMessage").containsMatchIn(runner),
        )
    }

    @Test
    fun `the terminal report is written on every exit path`() {
        val runner = src("tools/SubagentRunner.kt")
        assertTrue(
            "persistChildReport must be called from executeLoopGuarded's finally",
            runner.contains("persistChildReport(run.id, snapshot)"),
        )
        assertTrue(
            "the terminal write must survive a cancelled coroutine",
            runner.contains("kotlinx.coroutines.NonCancellable"),
        )
        assertTrue(
            "the verdict must travel with the report",
            runner.contains("SubagentChildTranscript.reportPartsJson("),
        )
    }

    @Test
    fun `the view model supplies a repository-backed store`() {
        val vm = src("ui/chat/ChatViewModel.kt")
        assertTrue(
            vm.contains("override fun childSessions(): SubagentRunner.ChildSessionStore"),
        )
        assertTrue(vm.contains("chatRepository.createSession("))
        assertTrue(
            "a sub-agent must not mutate the user's memory",
            vm.contains("memoryEnabled = false"),
        )
    }

    @Test
    fun `the repository links the child to its parent and tool call`() {
        val repo = src("data/repository/ChatRepository.kt")
        assertTrue(repo.contains("parentSessionId = parentSessionId"))
        assertTrue(repo.contains("parentToolUseId = parentToolUseId"))
    }

    @Test
    fun `deleting a chat deletes its child runs first`() {
        val repo = src("data/repository/ChatRepository.kt")
        assertTrue(
            "deleteSession must walk the subtree",
            repo.contains("SessionTree.subtreeDeepestFirst("),
        )
    }

    @Test
    fun `the session list is root-only, and navigation cannot open a hidden run`() {
        val repo = src("data/repository/ChatRepository.kt")
        assertTrue(
            "observeSessions must filter hidden children in SQL",
            repo.contains("dao.observeRootSessions()"),
        )
        val nav = src("ui/navigation/AppNavigation.kt")
        assertFalse(
            "AppNavigation must not pick the most recent session from ALL rows — " +
                "that row can be a hidden sub-agent run",
            nav.contains("dao.listSessions()"),
        )
    }

    @Test
    fun `child sessions are not exported as standalone conversations`() {
        val exporter = src("backup/BackupExporter.kt")
        assertFalse(exporter.contains("for (session in dao.listSessions())"))
        assertTrue(exporter.contains("dao.listRootSessions()"))
    }
}