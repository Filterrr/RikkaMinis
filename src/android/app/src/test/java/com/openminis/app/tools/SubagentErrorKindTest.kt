package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [port/upstream-subagent-error-kind] The two rules that matter:
 * never invent a category, and resolve the collisions in the documented order.
 *
 * The collisions are not hypothetical — they are the pairs that actually
 * appear in provider error text (a Chrome/proxy 529 mentions "rate"; a context
 * overflow is phrased as "exceeded the maximum context length"; a revoked key
 * arrives as 429 from several gateways).
 */
class SubagentErrorKindTest {

    @Test
    fun `an unrecognised failure is not given a category`() {
        assertNull(SubagentErrorKind.classify("something odd happened"))
        assertNull(SubagentErrorKind.classify(""))
        assertNull(SubagentErrorKind.classify(null))
        assertNull(SubagentErrorKind.annotation("Exception in thread main"))
    }

    @Test
    fun `a cancellation outranks any provider wording`() {
        // The cancel reason string can carry the user's own words.
        assertEquals(
            SubagentErrorKind.Kind.CANCELLED,
            SubagentErrorKind.classify("cancelled by parent agent: quota was wrong"),
        )
    }

    @Test
    fun `context overflow is not mistaken for a run timeout`() {
        // Both messages contain "exceeded".
        assertEquals(
            SubagentErrorKind.Kind.CONTEXT_LIMIT,
            SubagentErrorKind.classify("This model's maximum context length is 128000 tokens"),
        )
        assertEquals(
            SubagentErrorKind.Kind.TIMED_OUT,
            SubagentErrorKind.classify("exceeded the 1800-second run budget after 12 turn(s)"),
        )
    }

    @Test
    fun `a quota failure is not mistaken for rate limiting`() {
        assertEquals(
            SubagentErrorKind.Kind.QUOTA_EXHAUSTED,
            SubagentErrorKind.classify("429 You exceeded your current quota, check your plan"),
        )
        assertEquals(
            SubagentErrorKind.Kind.RATE_LIMITED,
            SubagentErrorKind.classify("429 Too many requests, please slow down"),
        )
    }

    @Test
    fun `overload beats rate limit, because the advice differs`() {
        assertEquals(
            SubagentErrorKind.Kind.PROVIDER_OVERLOADED,
            SubagentErrorKind.classify("529 overloaded_error: the model is at capacity, retry at a lower rate"),
        )
    }

    @Test
    fun `credentials are attributed`() {
        assertEquals(
            SubagentErrorKind.Kind.AUTH_FAILED,
            SubagentErrorKind.classify("401 Unauthorized: invalid api key provided"),
        )
    }

    @Test
    fun `transport failures are attributed`() {
        assertEquals(
            SubagentErrorKind.Kind.NETWORK_ERROR,
            SubagentErrorKind.classify("javax.net.ssl.SSLException: connection reset by peer"),
        )
    }

    @Test
    fun `every kind has a stable wire value and an actionable sentence`() {
        val wires = SubagentErrorKind.Kind.entries.map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
        for (kind in SubagentErrorKind.Kind.entries) {
            assertTrue("no advice for $kind", kind.advice.isNotBlank())
            assertTrue("non-ascii wire value $kind", kind.wire.all { it in 'a'..'z' || it == '_' })
        }
    }

    @Test
    fun `the annotation names the kind and the next move`() {
        val text = SubagentErrorKind.annotation("maximum context length exceeded")
        assertTrue(text != null && text.startsWith("context_limit"))
        assertTrue(text!!.contains("narrower task"))
    }

    @Test
    fun `the status report shows why a run failed`() {
        val run = SubagentRunRegistry.Run(
            id = "abcdef012345",
            blockId = "b",
            skillId = "s",
            skillName = "general-agent",
            query = "q",
            title = "t",
            status = SubagentRunRegistry.RunStatus.FAILED,
            startedAtMs = 0L,
            endedAtMs = 1_000L,
            error = "maximum context length is 128000 tokens",
        )
        val out = SubagentStatus.line(run, 1_000L)
        assertTrue(out.contains("error:   maximum context length"))
        assertTrue(out.contains("why:     context_limit"))
    }

    @Test
    fun `an unclassifiable error adds no why line`() {
        val run = SubagentRunRegistry.Run(
            id = "abcdef012345",
            blockId = "b",
            skillId = "s",
            skillName = "general-agent",
            query = "q",
            title = "t",
            status = SubagentRunRegistry.RunStatus.FAILED,
            startedAtMs = 0L,
            endedAtMs = 1_000L,
            error = "something odd happened",
        )
        val out = SubagentStatus.line(run, 1_000L)
        assertTrue(out.contains("error:"))
        assertTrue("must not invent a category", !out.contains("why:"))
    }
}