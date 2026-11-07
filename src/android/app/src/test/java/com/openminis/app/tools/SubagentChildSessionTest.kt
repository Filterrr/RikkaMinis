package com.openminis.app.tools

import com.openminis.app.ProductionSources
import com.openminis.app.data.session.SessionTree
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * [port/upstream-subagent-child-session] A persisted child session has two
 * contracts to keep, and both fail silently when broken:
 *
 *  1. **Deletion and accounting must cover the subtree.** A chat owns hidden
 *     child rows now; deleting only the parent leaves transcripts behind that
 *     nothing can reach.
 *  2. **The written parts_json must be the shape the REAL reader parses.**
 *     A wrong key does not throw — `tryParsePartsJson` returns an EMPTY part
 *     list, which renders exactly like "the sub-agent said nothing".
 *
 * The second one cannot be tested by compiling the parser (it pulls Room in),
 * so the JSON is asserted structurally here and the parser's key names are
 * pinned by a source guard at the bottom.
 */
class SubagentChildSessionTest {

    // ── Session graph ───────────────────────────────────────────────────

    private val graph = mapOf(
        "root" to null,
        "a" to "root",
        "b" to "root",
        "a1" to "a",
        "a1x" to "a1",
        "other" to null,
    )

    @Test
    fun `descendants come back deepest first and exclude the root`() = runBlocking {
        val order = SessionTree.descendantsDeepestFirst("root") { id ->
            graph.filterValues { it == id }.keys.toList().sorted()
        }
        assertEquals(setOf("a", "b", "a1", "a1x"), order.toSet())
        assertFalse(order.contains("root"))
        // Every child appears after its own children.
        assertTrue(order.indexOf("a1x") < order.indexOf("a1"))
        assertTrue(order.indexOf("a1") < order.indexOf("a"))
        assertTrue(order.indexOf("a1x") < order.indexOf("b") || order.indexOf("b") < order.indexOf("a1x"))
    }

    @Test
    fun `a corrupt cycle cannot loop forever`() = runBlocking {
        // x -> y -> x
        val cyclic = mapOf("x" to listOf("y"), "y" to listOf("x"))
        val order = SessionTree.descendantsDeepestFirst("x") { cyclic[it].orEmpty() }
        assertEquals(listOf("y"), order)
    }

    @Test
    fun `deleting a subtree includes the root last`() = runBlocking {
        val order = SessionTree.subtreeDeepestFirst("root") { id ->
            graph.filterValues { it == id }.keys.toList()
        }
        assertEquals("root", order.last())
        // root + a + b + a1 + a1x — "other" is a different root.
        assertEquals(5, order.size)
    }

    @Test
    fun `a dangling parent is treated as a root rather than vanishing`() {
        // "orphan" points at a parent that no longer exists.
        assertEquals("orphan", SessionTree.rootOf("orphan", mapOf("orphan" to "gone")))
    }

    @Test
    fun `sizes aggregate onto the root, counted once`() {
        val sizes = mapOf("root" to 10L, "a" to 5L, "a1" to 1L, "b" to 2L, "other" to 7L)
        val agg = SessionTree.aggregateToRoots(graph, sizes)
        assertEquals(18L, agg["root"])
        assertEquals(7L, agg["other"])
        assertEquals("only roots in the result", 2, agg.size)
    }

    @Test
    fun `members of a root are exactly its subtree`() {
        assertEquals(
            setOf("root", "a", "b", "a1", "a1x"),
            SessionTree.membersOf("root", graph).toSet(),
        )
    }

    // ── Transcript shape ────────────────────────────────────────────────

    @Test
    fun `a text part uses the type-value shape the reader expects`() {
        val array = JSONArray(SubagentChildTranscript.textPartsJson("hello"))
        assertEquals(1, array.length())
        val part = array.getJSONObject(0)
        assertEquals("text", part.getString("type"))
        assertEquals("hello", part.getString("value"))
    }

    @Test
    fun `the task message names the skill so it is not mistaken for a user message`() {
        val array = JSONArray(SubagentChildTranscript.taskPartsJson("survey the repo", "general-agent"))
        val text = array.getJSONObject(0).getString("value")
        assertTrue(text.startsWith("[general-agent]"))
        assertTrue(text.contains("survey the repo"))
    }

    @Test
    fun `the report message carries the verdict`() {
        val array = JSONArray(SubagentChildTranscript.reportPartsJson("findings", "DEGRADED"))
        val text = array.getJSONObject(0).getString("value")
        assertTrue(text.startsWith("[DEGRADED]"))
        assertTrue(text.contains("findings"))
    }

    @Test
    fun `an empty report says so instead of writing a blank row`() {
        val text = JSONArray(
            SubagentChildTranscript.reportPartsJson("", "FAILED"),
        ).getJSONObject(0).getString("value")
        assertTrue(text.contains("no report text"))
    }

    @Test
    fun `a long message is cut and the cut is announced`() {
        val long = "x".repeat(SubagentChildTranscript.MAX_MESSAGE_CHARS + 500)
        assertTrue(SubagentChildTranscript.isClipped(long))
        val stored = JSONArray(SubagentChildTranscript.textPartsJson(long))
            .getJSONObject(0).getString("value")
        assertTrue("must be bounded", stored.length <= SubagentChildTranscript.MAX_MESSAGE_CHARS + 200)
        assertTrue("must say it was cut", stored.contains("truncated"))
    }

    @Test
    fun `a short message is not marked as cut`() {
        assertFalse(SubagentChildTranscript.isClipped("short"))
        val stored = JSONArray(SubagentChildTranscript.textPartsJson("short"))
            .getJSONObject(0).getString("value")
        assertEquals("short", stored)
    }

    @Test
    fun `the title is prefixed and bounded`() {
        assertEquals("Agent · Survey the repo", SubagentChildTranscript.title("Survey the repo", "general-agent"))
        assertEquals("Agent · general-agent", SubagentChildTranscript.title("  ", "general-agent"))
        val long = SubagentChildTranscript.title("y".repeat(500), "s")
        assertTrue(long.length <= SubagentChildTranscript.MAX_TITLE_CHARS)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `the reader still parses type text with a value key`() {
        // The coupling that matters: ChatViewModelMessageParser maps
        // `type == "text"` to `ParsedPart.Text(value = obj.optString("value"))`.
        // A writer that renamed either key would produce rows that parse to an
        // EMPTY part list — indistinguishable from "the run said nothing".
        val parser = ProductionSources.read("ui/chat/ChatViewModelMessageParser.kt")
        val stripped = SubagentSourceText.stripComments(parser)
        val branch = Regex("\"text\"\\s*->\\s*ParsedPart\\.Text\\(value\\s*=\\s*obj\\.optString\\(\"value\"")
        assertTrue(
            "ChatViewModelMessageParser no longer reads text parts as type=text/value=…; " +
                "SubagentChildTranscript must be updated with it",
            branch.containsMatchIn(stripped),
        )
    }
}