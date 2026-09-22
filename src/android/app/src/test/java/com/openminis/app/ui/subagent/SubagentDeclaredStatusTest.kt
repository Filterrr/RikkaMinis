package com.openminis.app.ui.subagent

import com.openminis.app.tools.SubagentRunRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-subagent-declared-status] The human-facing half of the report verdict.
 *
 * The parent MODEL already receives a warning annotation when a sub-agent's
 * own report says `status: partial` (SubagentResult.declaredStatusAnnotation).
 * The person staring at the detail page received nothing: the run rendered as
 * a green "Completed" over a report that disclaims its own completeness. These
 * tests pin the two surfaces to the same story.
 */
class SubagentDeclaredStatusTest {

    private fun run(
        status: SubagentRunRegistry.RunStatus,
        declared: String? = null,
    ) = SubagentRunRegistry.Run(
        id = "r1",
        blockId = "b1",
        skillId = "general-agent",
        skillName = "general-agent",
        query = "q",
        title = "t",
        status = status,
        declaredStatus = declared,
    )

    // ── Label ────────────────────────────────────────────────────────────

    @Test
    fun `a successful run that declares partial is labelled partial`() {
        assertEquals(
            "Partial",
            runStatusLabel(run(SubagentRunRegistry.RunStatus.SUCCESS, declared = "partial")),
        )
    }

    @Test
    fun `a successful run with no declared status is labelled completed`() {
        assertEquals(
            "Completed",
            runStatusLabel(run(SubagentRunRegistry.RunStatus.SUCCESS)),
        )
        // A report that declared `done` parses to null (no annotation is the
        // correct rendering for "nothing to warn about") and reads Completed.
        assertEquals(
            "Completed",
            runStatusLabel(run(SubagentRunRegistry.RunStatus.SUCCESS, declared = null)),
        )
    }

    @Test
    fun `terminal failures keep their own label regardless of declaration`() {
        // The runtime status describes WHAT happened; a failed run that also
        // wrote `status: partial` must not be downgraded to "Partial" — it
        // errored, and that is the more important fact.
        assertEquals(
            "Failed",
            runStatusLabel(run(SubagentRunRegistry.RunStatus.FAILED, declared = "partial")),
        )
        assertEquals(
            "Timed out",
            runStatusLabel(run(SubagentRunRegistry.RunStatus.TIMED_OUT, declared = "partial")),
        )
        assertEquals(
            "Cancelled",
            runStatusLabel(run(SubagentRunRegistry.RunStatus.CANCELLED, declared = "partial")),
        )
    }

    @Test
    fun `active states are unaffected by a declaration`() {
        assertEquals(
            "Queued",
            runStatusLabel(run(SubagentRunRegistry.RunStatus.QUEUED, declared = "partial")),
        )
        assertEquals(
            "Running",
            runStatusLabel(run(SubagentRunRegistry.RunStatus.RUNNING, declared = "partial")),
        )
    }

    // ── Explanation line ─────────────────────────────────────────────────

    @Test
    fun `partial and failed produce distinct explanatory notes`() {
        val partial = declaredStatusNote(run(SubagentRunRegistry.RunStatus.SUCCESS, "partial"))
        val failed = declaredStatusNote(run(SubagentRunRegistry.RunStatus.FAILED, "failed"))
        assertEquals(true, partial?.contains("partial"))
        assertEquals(true, failed?.contains("failed"))
        // The two must not be interchangeable: one warns about an incomplete
        // deliverable, the other says there is no deliverable at all.
        assertEquals(false, partial == failed)
    }

    @Test
    fun `a clean run has no note`() {
        assertNull(declaredStatusNote(run(SubagentRunRegistry.RunStatus.SUCCESS)))
    }

    @Test
    fun `an unrecognised declaration produces no note rather than echoing it`() {
        // parseReportStatus only ever yields "partial"/"failed"/null, but the
        // renderer must not turn an unexpected token into user-facing copy.
        assertNull(declaredStatusNote(run(SubagentRunRegistry.RunStatus.SUCCESS, "whatever")))
    }

    // ── Registry write path ──────────────────────────────────────────────

    @Test
    fun `the registry stores a declaration and ignores null`() {
        val registry = SubagentRunRegistry()
        val created = registry.register("b1", "s", "s", "q", "t", 4)

        assertNull(registry.runs.value.single().declaredStatus)
        registry.setDeclaredStatus(created.id, null)
        assertNull(registry.runs.value.single().declaredStatus)

        registry.setDeclaredStatus(created.id, "partial")
        assertEquals("partial", registry.runs.value.single().declaredStatus)
    }

    @Test
    fun `a declaration survives the terminal write`() {
        // finishIfActive replaces the whole run object; if it dropped the
        // declared field, the page would lose the verdict at the exact moment
        // it starts being worth reading.
        val registry = SubagentRunRegistry()
        val created = registry.register("b1", "s", "s", "q", "t", 4)
        registry.setDeclaredStatus(created.id, "partial")
        registry.finishIfActive(
            created.id,
            SubagentRunRegistry.RunStatus.SUCCESS,
            resultText = "status: partial",
        )
        val done = registry.runs.value.single()
        assertEquals("partial", done.declaredStatus)
        assertEquals("Partial", runStatusLabel(done))
    }

    // ── Empty-log placeholder ────────────────────────────────────────────

    @Test
    fun `a queued run explains that it is waiting for a slot`() {
        val queued = run(SubagentRunRegistry.RunStatus.QUEUED)
        val running = run(SubagentRunRegistry.RunStatus.RUNNING)
        assertEquals(true, emptyLogLabel(queued).contains("waiting for a free slot"))
        assertEquals(true, emptyLogLabel(running).contains("first tool call"))
        // Different causes, different copy: a scheduler wait must not read as
        // a slow model.
        assertEquals(false, emptyLogLabel(queued) == emptyLogLabel(running))
    }
}
