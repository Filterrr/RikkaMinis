package com.openminis.app.provider.workbuddy

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * WorkBuddy chat provider.
 *
 * The CodeBuddy/WorkBuddy chat route (`POST /v2/chat/completions`) is a plain
 * OpenAI Chat Completions surface — same request body, same SSE chunk shape,
 * including `reasoning_content` deltas and OpenAI-style `tool_calls`. Rather
 * than reimplement a wire layer that already exists and is battle-tested,
 * this provider **delegates to [OpenAIProvider]** configured for the
 * WorkBuddy backend, and adds the two things the backend needs that a plain
 * OpenAI-compatible instance cannot express:
 *
 * 1. **Per-request credential resolution.** WorkBuddy credentials are OAuth
 *    tokens with a lifetime, not static API keys. The token is re-resolved
 *    through [WorkBuddyCredentialStore] on *every* call, so an agent loop
 *    that runs longer than one token lifetime rotates transparently instead
 *    of failing mid-conversation. Mirrors the Antigravity design
 *    (`accessTokenRefresher`) — but resolved eagerly rather than on a 401,
 *    because WorkBuddy's expiry is a known timestamp.
 *
 * 2. **Tenant identity headers.** The backend routes by account, so every
 *    request must echo `X-User-Id` / `X-Enterprise-Id` / `X-Tenant-Id` /
 *    `X-Domain` (upstream `headersFor`). The values come from the token
 *    bundle, which is why the delegate has to be rebuilt per call.
 *
 * A 401 that survives the proactive refresh (clock skew, revoked session) is
 * retried once after a forced rotation, mirroring the one-shot retry in
 * [com.openminis.app.provider.antigravity.AntigravityProvider].
 */
class WorkBuddyProvider(
    private val context: Context,
    /**
     * Resolves a usable access token, refreshing when it is near expiry.
     * Returning null means the instance has no stored credential at all —
     * surfaced as an auth error rather than a silent empty request.
     */
    private val tokenResolver: suspend () -> WorkBuddyCredentialStore.Tokens?,
    override var model: LLMModel = WorkBuddyConstants.BUNDLED_INTERNATIONAL.first(),
    /** Region supplies the backend host the delegate talks to. */
    private val region: WorkBuddyConstants.Region = WorkBuddyConstants.DEFAULT_REGION,
    /**
     * Instance `customBaseURL` — set when the user routes traffic through an
     * enterprise proxy or mirror. Expected to include the version path
     * (e.g. `https://proxy.example.com/v2`), matching the field's own
     * documentation. Null keeps the region's own backend.
     */
    private val customBackend: String? = null,
) : LLMProvider {

    override val name = "WorkBuddy"

    override var instanceContext: ProviderInstance? = null

    /**
     * OpenAI Chat Completions streams one monolithic `content` string with no
     * positional relationship to tool-call deltas — same contract as the
     * OpenAI provider this delegates to.
     */
    override val streamTextIsMonolithic: Boolean get() = true

    /** WorkBuddy accepts an assistant message as the final turn (prefill). */
    override val supportsPrefill: Boolean get() = true

    override val defaultMaxOutputTokens: Int get() = 32_000

    /**
     * One-shot guard for the 401 refresh-retry, reset per request so a long
     * conversation can self-heal more than once (a session outliving two
     * token rotations must not degrade into a permanent auth failure).
     */
    @Volatile
    private var authRetryTried = false

    /** Resolve the credential bundle or fail with an honest auth error. */
    private suspend fun requireTokens(): WorkBuddyCredentialStore.Tokens =
        tokenResolver() ?: throw LLMError.InvalidApiKey("WorkBuddy 未登录 — 请在供应商设置中登录")

    /**
     * Build the delegate for one request. Rebuilt per call on purpose: the
     * access token, the tenant identity headers and `model` are all resolved
     * fresh, so a rotation mid-conversation is picked up automatically.
     */
    private fun buildDelegate(tokens: WorkBuddyCredentialStore.Tokens): OpenAIProvider {
        val enterpriseId = tokens.enterpriseId
        val domain = tokens.domain.ifBlank { region.defaultDomain }
        // A custom backend is expected to carry its own version path; the
        // region default needs `/v2` appended (the API version prefix the
        // delegate then extends with `/chat/completions`).
        val base = customBackend
            ?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: "${region.backend}/v2"
        return OpenAIProvider(
            apiKey = tokens.accessToken,
            model = model,
            basePath = base,
            extraHeaders = mapOf(
                "X-User-Id" to tokens.uid,
                "X-Enterprise-Id" to enterpriseId,
                "X-Tenant-Id" to enterpriseId,
                "X-Domain" to domain,
                "User-Agent" to WorkBuddyConstants.CLIENT_UA,
            ),
        ).also { it.instanceContext = instanceContext }
    }

    /**
     * Force a rotation and report whether it produced a different token. Used
     * by the 401 path; a null/unchanged result means re-login is genuinely
     * required, so the original auth error is the honest answer.
     */
    private suspend fun rotateToken(): Boolean {
        val before = tokenResolver()?.accessToken
        val rotated = try {
            WorkBuddyCredentialStore.refreshAccessToken(context, instanceContext?.id ?: "")
        } catch (e: Exception) {
            AppLogger.warning(name, "forced rotation failed: ${e.message}")
            null
        }
        val after = rotated?.accessToken ?: tokenResolver()?.accessToken
        return after != null && after != before
    }

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse {
        authRetryTried = false
        val delegate = buildDelegate(requireTokens())
        return try {
            delegate.sendMessageClamped(
                messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
            )
        } catch (e: LLMError.InvalidApiKey) {
            if (!authRetryTried && rotateToken()) {
                authRetryTried = true
                AppLogger.info(name, "retrying after forced token rotation")
                buildDelegate(requireTokens()).sendMessageClamped(
                    messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
                )
            } else {
                throw e
            }
        }
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = flow {
        authRetryTried = false
        val first = buildDelegate(requireTokens())
        // The delegate's flow is cold, so a swallowed auth failure lets us
        // re-collect and re-issue the request with the rotated credential.
        // Only an auth failure is retried — everything else propagates
        // untouched, so transient/rate-limit handling upstream is unchanged.
        var retryWithFreshToken = false
        first.streamMessageClamped(
            messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
        )
            .catch { e ->
                if (e is LLMError.InvalidApiKey && !authRetryTried && rotateToken()) {
                    authRetryTried = true
                    retryWithFreshToken = true
                    AppLogger.info(name, "stream: retrying after forced token rotation")
                } else {
                    throw e
                }
            }
            .collect { emit(it) }

        if (retryWithFreshToken) {
            emitAll(
                buildDelegate(requireTokens()).streamMessageClamped(
                    messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
                ),
            )
        }
    }
}
