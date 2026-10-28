package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-result-envelope] The four delivery paths must go
 * through the ONE renderer.
 *
 * WHY THIS IS A SOURCE-GREP GUARD
 * ------------------------------
 * The renderer is pure, so its behaviour is unit-tested in
 * [SubagentResultEnvelopeTest]. What a pure test cannot see is whether
 * the PRODUCTION paths call it — a path that quietly kept its own
 * renderer would leave every unit test green while the parent saw two
 * different formats again.
 *
 * This is the same lesson the repository keeps relearning: "extracting
 * a pure function without wiring it up leaves two implementations, and
 * the one under test is not the one that runs."
 *
 * The count matters. A presence check stayed green when BOTH the inline
 * renderings were removed (the substring survived in a comment and in
 * the other branch) — so the guards below COUNT the call sites instead
 * of matching them.
 */
class SubagentResultEnvelopeWiringTest {

    private val envelope = ProductionSources.read("tools/SubagentResultEnvelope.kt")
    private val result = ProductionSources.read("tools/SubagentResult.kt")
    private val delivery = ProductionSources.read("tools/SubagentParentDelivery.kt")
    private val runner = ProductionSources.read("tools/SubagentRunner.kt")

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
     * The inline path must render through the envelope — and it must be
     * the ONLY renderer it uses. A leftover second renderer in
     * [SubagentResult] is exactly the drift this PR removes.
     */
    @Test
    fun `the inline path renders through the envelope and keeps no renderer of its own`() {
        assertTrue(
            "the inline result must call the envelope renderer",
            result.contains("SubagentResultEnvelope.render("),
        )
        assertFalse(
            "the inline result must not keep a private renderer — one envelope, one renderer",
            result.contains("private fun toPromptText"),
        )
    }

    /** The wake path. */
    @Test
    fun `the wake path renders through the envelope`() {
        assertTrue(
            "the wake prompt must call the envelope renderer",
            delivery.contains("SubagentResultEnvelope.render("),
        )
        assertTrue(
            "the wake prompt must use the WAKE framing",
            delivery.contains("SubagentResultEnvelope.Framing.WAKE"),
        )
    }

    /**
     * The join and wait_any paths — both in the runner. TWO call sites:
     * one for the join, one for the wait_any winner.
     */
    @Test
    fun `the join and wait_any paths render through the envelope`() {
        assertTrue(
            "the runner must call the envelope renderer at least twice " +
                "(join + wait_any), found ${runner.countOccurrences("SubagentResultEnvelope.render(")}",
            runner.countOccurrences("SubagentResultEnvelope.render(") >= 2,
        )
        assertTrue(
            "the join prompt must use the JOIN framing",
            runner.contains("SubagentResultEnvelope.Framing.JOIN"),
        )
        assertTrue(
            "the wait_any prompt must use the WAIT_ANY framing",
            runner.contains("SubagentResultEnvelope.Framing.WAIT_ANY"),
        )
    }

    /**
     * The four framings must all EXIST. A framing that was removed while
     * a call site still named it would not compile — but a framing that
     * was removed while NOTHING named it would be dead code that a
     * future path might "restore" instead of using the envelope.
     */
    @Test
    fun `the envelope declares all four framings`() {
        for (framing in listOf("INLINE", "EARLY_READOUT", "WAKE", "JOIN", "WAIT_ANY")) {
            assertTrue(
                "the envelope must declare the $framing framing",
                envelope.contains("framing$framing") || envelope.contains("$framing,") || envelope.contains("$framing\n"),
            )
        }
    }

    /**
     * The inline path used to have NO body budget. It has one now, and
     * it is the join path's budget — a spawn is at least as explicit a
     * request as a join.
     */
    @Test
    fun `the inline path has a body budget`() {
        assertTrue(
            "the envelope must declare a body budget",
            envelope.contains("INLINE_BODY_MAX_CHARS"),
        )
        assertTrue(
            "the inline budget must BE the join budget — one number, not two " +
                "literals that happen to match today and drift tomorrow",
            envelope.contains("INLINE_BODY_MAX_CHARS = JOIN_REPORT_MAX_CHARS"),
        )
        assertFalse(
            "the inline budget must not be a second literal",
            envelope.contains("INLINE_BODY_MAX_CHARS = 6_000") ||
                envelope.contains("INLINE_BODY_MAX_CHARS = 6000"),
        )
    }

    /**
     * One implementation of "how do we cut": the delivery helpers must
     * DELEGATE to the envelope, not carry their own copy. A second copy
     * is how the cut marker and the recovery route drifted before.
     */
    @Test
    fun `the body cutter has one implementation`() {
        assertTrue(
            "the delivery helper must delegate to the envelope's cutter",
            delivery.contains("SubagentResultEnvelope.boundBody("),
        )
        assertFalse(
            "the delivery helper must not cut the body itself",
            delivery.contains("report.take(maxChars)"),
        )
    }

    /**
     * The envelope must receive the facts it needs. A missing fact is a
     * field the parent cannot see — the exact defect this workstream
     * started with.
     */
    @Test
    fun `the envelope receives every fact the parent needs`() {
        for (field in listOf(
            "runId", "skillName", "outcome", "reportSource", "executedToolCalls",
            "turns", "maxTurns", "modelLabel", "tokensIn", "tokensOut",
            "report", "parsed", "artifacts", "journalPath", "error", "declaredStatus",
        )) {
            assertTrue(
                "the envelope's Facts must carry $field",
                envelope.contains("val $field"),
            )
        }
    }
}
