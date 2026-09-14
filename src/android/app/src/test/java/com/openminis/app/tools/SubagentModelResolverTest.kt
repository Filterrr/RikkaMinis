package com.openminis.app.tools

import com.openminis.app.tools.SubagentModelResolver.Candidate
import com.openminis.app.tools.SubagentModelResolver.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-model-routing] Precedence + failure-mode tests for the
 * `spawn_agent(model = …)` resolver. The load-bearing behaviour is FAIL
 * LOUDLY: a typo'd cheap model must never silently fall back to the parent's
 * (expensive) model.
 */
class SubagentModelResolverTest {

    private val catalog = listOf(
        Candidate("inst-anthropic", "uuid-1", "claude-sonnet-4-5", "Claude Sonnet 4.5"),
        Candidate("inst-openai", "uuid-2", "gpt-5", "GPT-5"),
        Candidate("inst-openai", "uuid-3", "gpt-5-mini", "GPT-5 mini"),
    )

    @Test
    fun `absent model inherits the parent provider`() {
        assertEquals(Result.Inherit, SubagentModelResolver.resolve(null, catalog))
        assertEquals(Result.Inherit, SubagentModelResolver.resolve("   ", catalog))
    }

    @Test
    fun `uuid wins and is case-insensitive`() {
        val r = SubagentModelResolver.resolve("UUID-1", catalog)
        assertTrue(r is Result.Resolved)
        assertEquals("uuid-1", (r as Result.Resolved).candidate.entryId)
    }

    @Test
    fun `model id matches exactly, never by prefix`() {
        val r = SubagentModelResolver.resolve("gpt-5", catalog)
        assertTrue(r is Result.Resolved)
        assertEquals("uuid-2", (r as Result.Resolved).candidate.entryId)
    }

    @Test
    fun `display name resolves case-insensitively`() {
        val r = SubagentModelResolver.resolve("claude sonnet 4.5", catalog)
        assertTrue(r is Result.Resolved)
        assertEquals("uuid-1", (r as Result.Resolved).candidate.entryId)
    }

    @Test
    fun `unknown model fails and lists options`() {
        val r = SubagentModelResolver.resolve("gpt-4.9-nano", catalog)
        assertTrue(r is Result.Failed)
        r as Result.Failed
        assertEquals(3, r.options.size)
        assertTrue(r.message.contains("did not match"))
    }

    @Test
    fun `ambiguous display name fails instead of picking the first`() {
        val dup = listOf(
            Candidate("a", "u1", "m1", "Fast Model"),
            Candidate("b", "u2", "m2", "fast model"),
        )
        assertTrue(SubagentModelResolver.resolve("Fast Model", dup) is Result.Failed)
    }

    @Test
    fun `empty catalog reports no models configured`() {
        val r = SubagentModelResolver.resolve("anything", emptyList())
        assertTrue(r is Result.Failed)
        assertTrue((r as Result.Failed).message.contains("no models are configured"))
    }

    @Test
    fun `oversized catalog truncates the option list`() {
        val many = (1..60).map { Candidate("i", "u$it", "m$it", "Model $it") }
        val r = SubagentModelResolver.resolve("nope", many) as Result.Failed
        assertTrue(r.message.contains("first 25 of 60"))
    }
}
