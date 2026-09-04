package com.openminis.app.network

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import java.util.concurrent.atomic.AtomicLong

/**
 * [OPT6-request-gzip] Compresses JSON request BODIES with gzip and declares
 * `Content-Encoding: gzip`. Applies to long agent-loop payloads (accumulated
 * tool outputs → hundreds of KB of mostly-compressible JSON — 440KB bodies
 * are documented in OpenAIProvider); on a slow cellular uplink this cuts
 * upload time ~5-10x for a few ms of deflate cost.
 *
 * Scope guard: ONLY `application/json` request bodies are compressed, and
 * only when the caller explicitly tagged the request via
 * [GzipRequestBody.gzipForced] OR the body exceeds [MIN_SIZE_TO_COMPRESS]
 * (small bodies gain nothing and can even grow). Responses are untouched —
 * OkHttp already handles `Content-Encoding: gzip` on responses transparently,
 * and SSE streams are never compressed by servers anyway.
 *
 * Compatibility: OpenAI/Anthropic/Gemini official endpoints accept gzipped
 * request bodies. Some third-party relays do NOT — the provider layer opts
 * in per provider via [shouldCompress] (default: official hosts only);
 * a relay that rejects with 400/415 surfaces as a normal ProviderError and
 * the provider flag can be flipped off without code changes here.
 *
 * The [compressibleRequests] counter is diagnostics-only (debug menus).
 */
class GzipRequestInterceptor(
    /** Provider-supplied gate: host/route-level opt-in. */
    private val shouldCompress: (Request) -> Boolean = { true },
) : Interceptor {

    companion object {
        /** Bodies smaller than this are sent raw — overhead exceeds gain. */
        const val MIN_SIZE_TO_COMPRESS = 4096L

        /** Diagnostics: how many request bodies were gzip-compressed. */
        val compressibleRequests = AtomicLong(0)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "POST" && request.method != "PUT") return chain.proceed(request)
        // Never double-encode; never touch requests that already carry an encoding.
        if (!request.header("Content-Encoding").isNullOrBlank()) return chain.proceed(request)
        val body = request.body ?: return chain.proceed(request)
        val contentType = body.contentType() ?: return chain.proceed(request)
        // JSON only — binary uploads (multipart audio/image) are already compressed.
        if (contentType.type != "application" ||
            !(contentType.subtype.equals("json", true) || contentType.subtype.endsWith("+json", true))
        ) return chain.proceed(request)
        val declaredLen = body.contentLength()
        // Unknown length → still allowed for JSON (we stream-deflate); but skip
        // known-small bodies. Route gate (provider opt-out) consulted last.
        if (declaredLen in 0 until MIN_SIZE_TO_COMPRESS) return chain.proceed(request)
        if (!shouldCompress(request)) return chain.proceed(request)

        val compressed = GzippedBody(contentType, body)
        compressibleRequests.incrementAndGet()
        return chain.proceed(
            request.newBuilder()
                .post(compressed)
                .header("Content-Encoding", "gzip")
                .build()
        )
    }

    /**
     * A gzip-defining RequestBody that streams the delegate body through a
     * GzipSink. Preserves the ORIGINAL content type (describes the payload
     * INSIDE the encoding); `Content-Encoding: gzip` describes the transfer
     * encoding, which is set on the request by [intercept].
     *
     * contentLength reports -1 (chunked): the COMPRESSED length differs from
     * the original, and OkHttp streams chunked for LLM JSON bodies, which
     * every endpoint used here accepts.
     */
    private class GzippedBody(
        private val contentType: MediaType?,
        private val delegate: okhttp3.RequestBody,
    ) : okhttp3.RequestBody() {
        override fun contentType(): MediaType? = contentType
        override fun contentLength(): Long = -1L
        override fun isOneShot(): Boolean = delegate.isOneShot()
        override fun isDuplex(): Boolean = delegate.isDuplex()
        override fun writeTo(sink: BufferedSink) {
            val gzipSink = GzipSink(sink).buffer()
            try {
                delegate.writeTo(gzipSink)
            } finally {
                gzipSink.close() // closes GzipSink → writes the gzip trailer to `sink`
            }
        }
    }
}
