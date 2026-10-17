package com.openminis.app.provider.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-opencode-free] Pure-JVM tests for free-catalogue discovery.
 *
 * The load-bearing claim is that the `-free` SUFFIX is not sufficient (nor
 * necessary) to decide what the free tier serves, so discovery may not key off
 * the name alone. These cases pin that down.
 */
class OpenCodeFreeModelsTest {

    @Test
    fun `suffixed free ids are candidates`() {
        assertTrue(OpenCodeFreeModelsApi.looksFree("deepseek-v4.1-flash-free"))
        assertTrue(OpenCodeFreeModelsApi.looksFree("glm-5.3-flash-free"))
        assertTrue(OpenCodeFreeModelsApi.looksFree("nemotron-3.5-lightning-free"))
    }

    @Test
    fun `known-unusable id is excluded despite the suffix`() {
        // Listed with -free, but answers ModelProtocolUnsupported every time.
        assertFalse(OpenCodeFreeModelsApi.looksFree("jev-1.13-free"))
    }

    @Test
    fun `paid ids are excluded`() {
        assertFalse(OpenCodeFreeModelsApi.looksFree("gpt-5.4-pro"))
        assertFalse(OpenCodeFreeModelsApi.looksFree("claude-opus-4-6"))
        assertFalse(OpenCodeFreeModelsApi.looksFree("gemini-3.5-flash"))
        assertFalse(OpenCodeFreeModelsApi.looksFree("grok-4.6"))
    }

    @Test
    fun `free-without-suffix ids are not claimed by the name filter`() {
        // big-pickle IS free, but the name says nothing. It stays in the
        // curated catalogue; the point is that the filter must not be trusted
        // as exhaustive, which is why discovery is only ever additive.
        assertFalse(OpenCodeFreeModelsApi.looksFree("big-pickle"))
    }

    @Test
    fun `paid marker inside a suffixed id is rejected`() {
        assertFalse(OpenCodeFreeModelsApi.looksFree("whatever-pro-free"))
        assertFalse(OpenCodeFreeModelsApi.looksFree("whatever-codex-free"))
    }

    @Test
    fun `blank ids are rejected`() {
        assertFalse(OpenCodeFreeModelsApi.looksFree(""))
        assertFalse(OpenCodeFreeModelsApi.looksFree("   "))
    }

    @Test
    fun `probe discriminates paid from served by status code`() {
        // This is the ONLY reliable signal: the endpoint returns 401 for a
        // paid model (account required) and serves free ones.
        assertEquals(
            OpenCodeFreeModelsApi.ProbeVerdict.SERVED,
            OpenCodeFreeModelsApi.classifyProbe(200),
        )
        assertEquals(
            OpenCodeFreeModelsApi.ProbeVerdict.PAID,
            OpenCodeFreeModelsApi.classifyProbe(401),
        )
    }

    @Test
    fun `403 is not treated as a verdict`() {
        // 403 FreeTierError refuses the whole tier for the current network
        // egress, so it must not
        // be read as "this model is unavailable" -- doing so would drop a
        // perfectly good model every time the endpoint was busy.
        assertEquals(
            OpenCodeFreeModelsApi.ProbeVerdict.UNKNOWN,
            OpenCodeFreeModelsApi.classifyProbe(403),
        )
        assertEquals(
            OpenCodeFreeModelsApi.ProbeVerdict.UNKNOWN,
            OpenCodeFreeModelsApi.classifyProbe(500),
        )
    }

    @Test
    fun `tier refusal is recognised so the catalogue is not wiped`() {
        // The refresh path keeps the cached/curated list when this is true;
        // otherwise a refused tier would empty the model picker.
        assertTrue(
            OpenCodeFreeModelsApi.isTierRefusal(
                403,
                """{"type":"error","error":{"type":"FreeTierError","message":"..."}}""",
            ),
        )
        assertFalse(OpenCodeFreeModelsApi.isTierRefusal(403, """{"error":"something else"}"""))
        assertFalse(OpenCodeFreeModelsApi.isTierRefusal(401, "FreeTierError"))
        assertFalse(OpenCodeFreeModelsApi.isTierRefusal(200, "FreeTierError"))
    }

    @Test
    fun `modelForId reuses curated display names and falls back to the id`() {
        val known = OpenCodeFree.modelForId("beam-free")
        assertTrue("curated id keeps its friendly name", known.displayName.contains("Beam"))

        val discovered = OpenCodeFree.modelForId("some-brand-new-model-free")
        assertEquals("some-brand-new-model-free", discovered.displayName)
        assertEquals(OpenCodeFree.MODEL_PROVIDER_TAG, discovered.provider)
        assertTrue(discovered.contextWindow != null && discovered.contextWindow!! > 0)
    }
}
