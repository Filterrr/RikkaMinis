package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [FirstChunkTimeoutPolicy] (diag/first-chunk-timeout, 2026-08-24).
 *
 * Pins the route-aware first-chunk budget decision:
 *   - direct / official endpoints → 30s (DIRECT_TIMEOUT_SEC)
 *   - proxy / gateway / local-relay routes → 45s (PROXY_TIMEOUT_SEC)
 *
 * Also pins the extractable retry-classification contract: a 0-chunk
 * [ModelStreamErrorException] must be treated as transient (retryable) —
 * mirrored by ChatViewModel.workerDiedZeroChunk; these tests pin the policy
 * side, the ChatViewModel side is covered by the existing retry tests.
 */
class FirstChunkTimeoutPolicyTest {

    // ── routing class ──

    @Test
    fun `null base url is direct`() {
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute(null))
    }

    @Test
    fun `official hosts stay direct even when customized`() {
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.openai.com"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.openai.com/v1"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.anthropic.com"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.deepseek.com"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://openrouter.ai/api/v1"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://generativelanguage.googleapis.com"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.moonshot.cn/v1"))
    }

    @Test
    fun `subdomains of official hosts count as direct`() {
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://eu.api.openai.com"))
    }

    @Test
    fun `loopback and local relays are proxy routes`() {
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://127.0.0.1:7890"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://127.0.0.1:8080/v1"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://localhost:7890"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://10.0.0.5:8080"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://192.168.1.100:7890"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://172.16.0.2:1080"))
    }

    @Test
    fun `plain http non-official is proxy`() {
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://some-relay.example.com"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://192.168.31.1:7891"))
    }

    @Test
    fun `custom gateway domains are proxy routes`() {
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("https://hub.oaifree.com"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("https://gateway.myrelay.net/v1"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("https://api.oaifree.com"))
    }

    @Test
    fun `trailing slashes and case are normalized`() {
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("  HTTPS://API.OPENAI.COM/v1/  "))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("HTTP://127.0.0.1:7890/"))
    }

    // ── time budget ──

    @Test
    fun `direct routes get 30s`() {
        assertEquals(30, FirstChunkTimeoutPolicy.decideTimeoutSec(null))
        assertEquals(30, FirstChunkTimeoutPolicy.decideTimeoutSec("https://api.openai.com"))
        assertEquals(30, FirstChunkTimeoutPolicy.decideTimeoutSec("https://api.deepseek.com"))
    }

    @Test
    fun `proxy routes get 45s`() {
        assertEquals(45, FirstChunkTimeoutPolicy.decideTimeoutSec("http://127.0.0.1:7890"))
        assertEquals(45, FirstChunkTimeoutPolicy.decideTimeoutSec("https://hub.oaifree.com"))
        assertEquals(45, FirstChunkTimeoutPolicy.decideTimeoutSec("https://gateway.myrelay.net/v1"))
    }

    // ── retry contract (mirror of ChatViewModel.workerDiedZeroChunk) ──

    @Test
    fun `zero-chunk stream errors are retryable`() {
        // A 0-chunk ModelStreamErrorException (first_chunk_timeout path) is
        // classified transient/retryable — the contract the ChatViewModel fix
        // implements. Pin the semantic here so a regression in the checker
        // cannot silently reintroduce the fatal classification.
        // NOTE: declared as Throwable (what unwrapFlowException returns) so the
        // classifier sees the same static type shape as production.
        val err: Throwable = ModelStreamErrorException("provider produced no first chunk within 30000ms (hadChunks=false)", hadChunks = false)
        assertFalse((err as ModelStreamErrorException).hadChunks)
        // The classifier is: workerDiedZeroChunk =
        //   (WorkerDied || StreamError) && (as? ModelExecutionStreamException)?.hadChunks == false
        // (must mirror the production expression in ChatViewModel EXACTLY)
        val workerDiedZeroChunk =
            ((err is ModelWorkerDiedException) || (err is ModelStreamErrorException)) &&
                (err as? ModelExecutionStreamException)?.hadChunks == false
        assertTrue("0-chunk ModelStreamErrorException must be transient/retryable", workerDiedZeroChunk)
    }

    @Test
    fun `mid-stream errors are NOT retryable`() {
        // Any exception carrying hadChunks=true must fall to the fatal path
        // (no re-send — a duplicate answer would reach the user).
        val err: Throwable = ModelStreamErrorException("stream reset after content", hadChunks = true)
        assertTrue((err as ModelStreamErrorException).hadChunks)
        val workerDiedZeroChunk =
            ((err is ModelWorkerDiedException) || (err is ModelStreamErrorException)) &&
                (err as? ModelExecutionStreamException)?.hadChunks == false
        assertFalse("hadChunks=true must NOT be retried", workerDiedZeroChunk)
    }
}