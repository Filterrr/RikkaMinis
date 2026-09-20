package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [fix-todo-session-scope] Regression tests for per-session task-list
 * scoping.
 *
 * The bug: TodoStore held ONE process-wide list. With N concurrently-alive
 * ChatViewModels (ChatViewModelStore keeps them all by design), session B's
 * screen rendered session A's in-flight plan, and clearing on session
 * switch erased A's list outright — "different chat windows show different
 * task lists". These tests pin the replacement contract: one list per
 * session, the published flow always carries the FOREGROUND session's list,
 * and deletion/dispose semantics never disturb a different session's list.
 */
class TodoStoreSessionScopeTest {

    @Before
    fun reset() = TodoStore.clearAll()

    @After
    fun tearDown() = TodoStore.clearAll()

    private fun item(content: String, status: TodoStore.Status = TodoStore.Status.pending) =
        TodoStore.TodoItem(content, status)

    // ───────────────── per-session isolation ─────────────────

    @Test
    fun `writes to different sessions do not leak into each other`() {
        TodoStore.setActiveSession("A")
        TodoStore.write("A", listOf(item("A-plan-1"), item("A-plan-2")))
        TodoStore.setActiveSession("B")
        TodoStore.write("B", listOf(item("B-plan-1")))

        // A's list is untouched by B's write…
        assertEquals(listOf("A-plan-1", "A-plan-2"), TodoStore.listFor("A").items.map { it.content })
        // …and B's list is its own.
        assertEquals(listOf("B-plan-1"), TodoStore.listFor("B").items.map { it.content })
    }

    @Test
    fun `published flow follows the active session`() {
        TodoStore.setActiveSession("A")
        TodoStore.write("A", listOf(item("A1")))
        assertEquals(listOf("A1"), TodoStore.todo.value.items.map { it.content })

        TodoStore.setActiveSession("B")
        // B has no list yet → empty, NOT A's list.
        assertEquals(0, TodoStore.todo.value.items.size)

        TodoStore.setActiveSession("A")
        // Back to A → A's list reappears intact (the old code lost it here).
        assertEquals(listOf("A1"), TodoStore.todo.value.items.map { it.content })
    }

    @Test
    fun `write to a background session does not disturb the foreground flow`() {
        TodoStore.setActiveSession("A")
        TodoStore.write("A", listOf(item("A1")))

        // Session B's agent loop writes while A is on screen (both VMs live).
        TodoStore.setActiveSession("B")
        TodoStore.setActiveSession("A")
        TodoStore.write("B", listOf(item("B1")))

        assertEquals("A", TodoStore.todo.value.sessionId)
        assertEquals(listOf("A1"), TodoStore.todo.value.items.map { it.content })
    }

    @Test
    fun `a background session's write updates its own stored list`() {
        TodoStore.setActiveSession("A")
        TodoStore.write("A", listOf(item("A1")))
        TodoStore.write("B", listOf(item("B1"))) // B is not foreground

        assertEquals(listOf("B1"), TodoStore.listFor("B").items.map { it.content })
        assertEquals(listOf("A1"), TodoStore.todo.value.items.map { it.content })
    }

    // ───────────────── session switch / dispose ─────────────────

    @Test
    fun `setActiveSession to null publishes empty without erasing stored lists`() {
        TodoStore.setActiveSession("A")
        TodoStore.write("A", listOf(item("A1")))
        TodoStore.setActiveSession(null)

        assertEquals(0, TodoStore.todo.value.items.size)
        // Screen dispose must NOT wipe the list — re-entering the session
        // shows it again.
        assertEquals(listOf("A1"), TodoStore.listFor("A").items.map { it.content })
    }

    @Test
    fun `retract only retracts its own publication`() {
        // Real race shape: A mounts (publishes A) → B mounts (publishes B,
        // B has no list yet) → B's screen disposes. The dispose must only
        // retract B — the pending A publication (A is now foreground again)
        // is re-published by A's own mount hook, so the flow ends empty but
        // A's STORED list is intact for the next setActiveSession("A").
        TodoStore.setActiveSession("A")
        TodoStore.write("A", listOf(item("A1")))
        TodoStore.setActiveSession("B")
        TodoStore.retractActiveSession("B")

        // B's retraction must not erase A's stored list…
        assertEquals(listOf("A1"), TodoStore.listFor("A").items.map { it.content })
        // …and re-entering A shows it again.
        TodoStore.setActiveSession("A")
        assertEquals(listOf("A1"), TodoStore.todo.value.items.map { it.content })
    }

    @Test
    fun `retract does not clear a different session's live publication`() {
        // Direct guard on the symmetric condition: active is A, someone
        // retracts B → nothing changes.
        TodoStore.setActiveSession("A")
        TodoStore.write("A", listOf(item("A1")))
        TodoStore.retractActiveSession("B")

        assertEquals("A", TodoStore.todo.value.sessionId)
        assertEquals(listOf("A1"), TodoStore.todo.value.items.map { it.content })
    }

    // ───────────────── deletion ─────────────────

    @Test
    fun `dropSession removes only that session`() {
        TodoStore.setActiveSession("A")
        TodoStore.write("A", listOf(item("A1")))
        TodoStore.write("B", listOf(item("B1")))

        TodoStore.dropSession("B")

        assertNull(TodoStore.listFor("B").sessionId)
        assertEquals(0, TodoStore.listFor("B").items.size)
        assertEquals(listOf("A1"), TodoStore.listFor("A").items.map { it.content })
    }

    @Test
    fun `dropSession of the active session resets the published flow`() {
        TodoStore.setActiveSession("A")
        TodoStore.write("A", listOf(item("A1")))

        TodoStore.dropSession("A")

        assertEquals(0, TodoStore.todo.value.items.size)
        assertNull(TodoStore.todo.value.sessionId)
    }

    // ───────────────── null-session callers ─────────────────

    @Test
    fun `write with null session publishes directly without storing`() {
        TodoStore.setActiveSession(null)
        TodoStore.write(null, listOf(item("headless")))

        assertEquals(listOf("headless"), TodoStore.todo.value.items.map { it.content })
        assertNull(TodoStore.todo.value.sessionId)
    }

    // ───────────────── status counters still work ─────────────────

    @Test
    fun `pending and total counters are per-list`() {
        TodoStore.setActiveSession("A")
        TodoStore.write(
            "A",
            listOf(
                item("done-one", TodoStore.Status.completed),
                item("working", TodoStore.Status.in_progress),
                item("queued"),
            ),
        )
        val list = TodoStore.todo.value
        assertEquals(3, list.totalCount)
        assertEquals(2, list.pendingCount)
    }
}
