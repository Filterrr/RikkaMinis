package com.openminis.app.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Persistence contract for [ConditionalGetInterceptor]. Implementations keep
 * the last-seen ETag per URL key; the only mandated behaviour is "survive
 * process death" — an in-memory map would re-download the full body on
 * every cold start, which is exactly the cost this interceptor exists to
 * avoid.
 */
interface EtagStore {
    fun get(urlKey: String): String?
    fun put(urlKey: String, etag: String)
}

/**
 * [P2-9-conditional-etag] Persistent conditional GET for read-only polling
 * endpoints (today: the GitHub releases listing in UpdateChecker).
 *
 * Flow:
 *   1. On a GET whose URL has a stored ETag → add `If-None-Match: <etag>`.
 *   2. Server says 304 Not Modified → return a synthesized 200 with an EMPTY
 *      body. Callers that parse the body must treat empty as "unchanged" —
 *      UpdateChecker maps an empty/zero-length releases body to
 *      NoReleaseAvailable… which is wrong for 304.
 *
 * …which is why the synthesized response carries the header
 * `X-Minis-Not-Modified: 1` and UpdateChecker short-circuits to UpToDate
 * when it sees it BEFORE parsing. Any other caller that doesn't know the
 * header keeps working: it just sees an empty body and its own empty-body
 * handling (pre-existing behaviour for a genuinely empty release list).
 *
 * Only idempotent GETs are touched. Non-2xx/304 responses pass through and
 * never update the store; a response WITHOUT an ETag clears the stored one
 * (the resource stopped participating in conditional requests — e.g. some
 * proxies strip it).
 */
class ConditionalGetInterceptor(
    private val store: EtagStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET") return chain.proceed(request)

        val urlKey = request.url.toString()
        val etag = store.get(urlKey)
        val tagged: Request = if (etag != null) {
            request.newBuilder().header("If-None-Match", etag).build()
        } else {
            request
        }

        val response = chain.proceed(tagged)
        return when {
            response.code == 304 -> {
                response.close()
                Response.Builder()
                    .request(request)
                    .protocol(response.protocol)
                    .code(200)
                    .message("Not Modified (served from ETag)")
                    .body(okhttp3.ResponseBody.create(null, ""))
                    .headers(response.headers)
                    .header("X-Minis-Not-Modified", "1")
                    .build()
            }
            response.isSuccessful -> {
                val newEtag = response.header("ETag")
                if (newEtag != null) store.put(urlKey, newEtag) else store.put(urlKey, "")
                response
            }
            else -> response
        }
    }
}
