package com.openminis.app.provider.opencode

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONObject

/**
 * [T-opencode-free] Provider for OpenCode's keyless free tier.
 *
 * This is a thin [OpenAIProvider] subclass rather than a standalone
 * implementation: the wire format IS OpenAI chat completions, and the parent
 * already owns the whole streaming machine (SSE framing, tool-call delta
 * accumulation, thinking extraction, usage accounting, error mapping). The
 * only things the free tier needs on top are the identity headers and the body
 * gate, so this class overrides exactly two seams:
 *
 *  - [extraHeadersForInstance] — no-op for every other provider, overridden
 *    here to emit the free-tier identity block.
 *  - [gateBodyForInstance] — no-op for every other provider, overridden here to
 *    apply the free-tier body contract.
 *
 * Both hooks default to "do nothing" in the parent, so no other provider's
 * outbound bytes change. Mirrors how `customUserAgent` was threaded in.
 */
class OpenCodeFreeProvider(
    model: LLMModel = OpenCodeFree.builtInModels.first(),
) : OpenAIProvider(
    apiKey = OpenCodeFree.PUBLIC_KEY,
    model = model,
    basePath = OpenCodeFree.BASE_URL,
    // The free tier forces streaming, and the parent's chat-completions path is
    // the one that carries our hooks (the Responses path is a different wire
    // format entirely and is not served here).
    useResponsesAPI = false,
) {

    override val name = "OpenCode 免费"

    init {
        // [T-opencode-free] Learn the newest published CLI version off the
        // request path, so a request sent later in this process already
        // carries a version above a floor that may have moved since release.
        // Non-blocking: the first request just uses the pinned default.
        OpenCodeFree.refreshAdvertisedVersionAsync()
    }

    /**
     * One session identity per provider instance, reused for every request.
     *
     * The upstream rate-limits per session id, so minting a fresh id per call
     * would buy a marginally different fingerprint at the cost of immediate
     * 429s. A provider instance is rebuilt per execution, so this lifetime
     * matches the "one session, many turns" behaviour of the official CLI.
     */
    private val sessionId: String by lazy { OpenCodeFree.newSessionId() }

    /**
     * Free-tier identity headers. Applied AFTER the parent's auth header, so
     * our `Authorization: Bearer public` wins over the generic
     * `Bearer <apiKey>` (same value, but keeping the contract in one place
     * means the two cannot drift apart later).
     */
    override fun extraHeadersForInstance(): Map<String, String> =
        OpenCodeFree.identityHeaders(sessionId)

    /** Apply the free-tier body contract (forced stream + gate tools). */
    override fun gateBodyForInstance(body: JSONObject) {
        OpenCodeFree.applyBodyGate(body)
    }

    /**
     * [T-opencode-free] Self-heal on an upgrade demand.
     *
     * When the upstream raises its floor it answers 426 with the version it
     * wants in the body. The parent maps that to a retryable error, so this
     * callback runs first and adopts the demanded version — which means the
     * automatic retry re-builds its UA ([extraHeadersForInstance] runs per
     * build) already above the new floor and succeeds, without the user
     * updating the app.
     */
    override fun onHttpErrorResponse(statusCode: Int, body: String) {
        if (statusCode == HTTP_UPGRADE_REQUIRED) {
            val adopted = OpenCodeFree.adoptRequiredVersion(body)
            com.openminis.app.logging.AppLogger.warning(
                "OpenCodeFree",
                "[T-opencode-free] upstream demanded a newer client (426); " +
                    "adoptedVersion=$adopted effective=${OpenCodeFree.currentVersion()}",
            )
        } else if (statusCode == HTTP_FORBIDDEN && body.contains("FreeTierError")) {
            // The gate rejected our identity outright (e.g. the UA shape
            // changed). The registry probe is the only lever we have left.
            com.openminis.app.logging.AppLogger.warning(
                "OpenCodeFree",
                "[T-opencode-free] identity rejected (403 FreeTierError); " +
                    "effective=${OpenCodeFree.currentVersion()} body=${body.take(200)}",
            )
        }
    }

    private companion object {
        const val HTTP_UPGRADE_REQUIRED = 426
        const val HTTP_FORBIDDEN = 403
    }
}
