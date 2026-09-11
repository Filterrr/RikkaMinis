package com.openminis.app.provider.antigravity

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.parseRetryAfterMs
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.sanitizeToolPairing
import com.openminis.app.provider.safeOptString
import com.openminis.app.provider.clampOutboundMaxTokens
import com.openminis.app.provider.clampOutboundTemperature
import com.openminis.app.provider.failOnSilentEmptyCompletion
import com.openminis.app.sandbox.offload.FirstChunkTimeoutPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Antigravity chat provider — request/response wire format ported 1:1 from
 * CLIProxyAPI's antigravity executor:
 *
 *   POST <base>/v1internal:generateContent          (non-stream)
 *   POST <base>/v1internal:streamGenerateContent?alt=sse
 *
 * Body = Gemini-format payload wrapped in the Antigravity envelope
 * (`geminiToAntigravity`, antigravity_executor_request.go:459):
 *
 * ```
 * {
 *   "model": <id>, "userAgent": "antigravity", "requestType": "agent",
 *   "project": <project_id>, "requestId": "agent-<uuid>",
 *   "request": { contents, systemInstruction, tools, generationConfig, sessionId }
 * }
 * ```
 *
 * Response: upstream streams `data: {"response": {...Gemini-shaped chunk...}}`
 * SSE events — the inner `response` object has the exact same shape as a
 * Gemini `streamGenerateContent` chunk (candidates/parts/usageMetadata), so
 * GeminiProvider's parsers are reused verbatim on the unwrapped object.
 *
 * Headers: Bearer access token + Antigravity short UA (requestUserAgent) —
 * identical fingerprint to the upstream Go client. generationConfig
 * .maxOutputTokens is REMOVED for Gemini targets (upstream deletes it), and
 * a stable sessionId (derived from the first user message, like upstream's
 * generateStableSessionID) keeps multi-turn context sticky.
 */
class AntigravityProvider(
    private val accessToken: String,
    override var model: LLMModel = AntigravityOAuth.FALLBACK_MODELS.first(),
    private val basePath: String = AntigravityOAuth.DAILY_API_ENDPOINT,
    private val projectId: String? = null,
    /**
     * Re-discover the project id at send time (loadCodeAssist → onboardUser).
     * Mirrors upstream PrepareRequestAuth: a credential without a project id
     * must NOT ship (buildRequest refuses), so we lazily fetch/repair it
     * before the first request and once more on a 403.
     */
    private val projectIdRefresher: (suspend () -> String?)? = null,
    /**
     * [fix-antigravity-401-refresh-retry] Refreshes the access token via the
     * stored refresh_token and returns the NEW access token (null on
     * failure). Wired by ProviderFactory through
     * AntigravityCredentialStore.validAccessToken so a 401 mid-conversation
     * transparently rotates the token and retries once — mirroring the
     * upstream executor instead of surfacing "登录已过期".
     */
    private val accessTokenRefresher: (suspend () -> String?)? = null,
) : LLMProvider {

    override val name = "Antigravity"
    override var instanceContext: com.openminis.app.data.model.ProviderInstance? = null
    // Gemini contents: a final role="model" content part is a valid prefill —
    // continueContent / partial model turn is natively supported.
    override val supportsPrefill: Boolean get() = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .dns(com.openminis.app.network.NetworkMonitor.buildDns())
        .addInterceptor(okhttp3.brotli.BrotliInterceptor)
        .build()

    private fun isClaudeModel(): Boolean = model.id.lowercase().contains("claude")

    /** Runtime project id — constructor value first, lazily refreshed. */
    @Volatile
    private var resolvedProjectId: String? = projectId

    /** True after the one-shot project-id repair retry has been consumed. */
    @Volatile
    private var projectRepairTried = false

    /**
     * [fix-antigravity-401-refresh-retry] Upstream parity
     * (antigravity_executor_auth.go): on a 401 the executor transparently
     * rotates the access token via the refresh_token and retries once. The
     * port used to surface the 401 directly as "Invalid API key: 登录已过期"
     * even though a perfectly valid refresh token was on file.
     */
    private fun tokenRefresher(): (suspend () -> String?)? = accessTokenRefresher

    /** True after the one-shot 401 refresh-retry has been consumed. */
    @Volatile
    private var authRetryTried = false

    /**
     * Upstream requires `project` in the envelope (buildRequest errors out
     * without one — see TestAntigravityBuildRequest missing-project case).
     * Fetch via loadCodeAssist/onboardUser on first use or after loss.
     */
    private suspend fun ensureProjectId(force: Boolean = false) {
        if (!force && !resolvedProjectId.isNullOrBlank()) return
        val refresher = projectIdRefresher ?: return
        val id = try { refresher() } catch (_: Exception) { null }
        if (!id.isNullOrBlank()) resolvedProjectId = id
    }

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        ensureProjectId()
        val inner = buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        val body = envelope(inner)
        val url = "$basePath/v1internal:generateContent"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", AntigravityOAuth.requestUserAgent())
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            // [fix-antigravity-401-refresh-retry] One guarded retry: refresh
            // the access token and replay the request once (upstream
            // executor parity). Guarded by authRetryTried so it can't loop.
            if (!authRetryTried && response.code == 401 && tokenRefresher() != null) {
                authRetryTried = true
                response.close()
                val fresh = try { tokenRefresher()?.invoke() } catch (_: Exception) { null }
                if (!fresh.isNullOrBlank()) {
                    val retryRequest = request.newBuilder()
                        .header("Authorization", "Bearer $fresh")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val retryResponse = client.newCall(retryRequest).execute()
                    if (retryResponse.isSuccessful) {
                        val retryText = retryResponse.body?.string() ?: ""
                        val retryUnwrapped = try { JSONObject(retryText).optJSONObject("response") } catch (_: Exception) { null }
                            ?: JSONObject()
                        return@withContext LLMResponse(
                            extractText(retryUnwrapped),
                            extractFinishReason(retryUnwrapped) ?: "end_turn",
                            extractUsage(retryUnwrapped),
                            extractInlineMedia(retryUnwrapped),
                        )
                    }
                    val retryErrBody = retryResponse.body?.string() ?: ""
                    val retryAfterMs = parseRetryAfterMs(retryResponse.headers["Retry-After"], System.currentTimeMillis())
                    val retryCode = retryResponse.code
                    retryResponse.close()
                    throw mapHttpError(retryCode, retryErrBody, retryAfterMs)
                }
                // Refresh failed → fall through to the honest error below.
            }
            // One repair round: a missing/stale project id surfaces as a 4xx
            // with PROJECT/VERSION-style bodies upstream. Guarded by
            // projectRepairTried so a retry can never recurse.
            val projectish = responseBody.contains("project", ignoreCase = true) ||
                responseBody.contains("VERSION_CHECK_FAILED", ignoreCase = true)
            if (!projectRepairTried && projectish && projectIdRefresher != null &&
                (response.code == 400 || response.code == 403)) {
                projectRepairTried = true
                response.close()
                ensureProjectId(force = true)
                return@withContext sendMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
            }
            throw mapHttpError(
                response.code,
                responseBody,
                parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis()),
            )
        }

        // Non-stream: {"response": {...gemini-shaped...}} → unwrap.
        val unwrapped = try { JSONObject(responseBody).optJSONObject("response") } catch (_: Exception) { null }
            ?: JSONObject()
        val text = extractText(unwrapped)
        val finishReason = extractFinishReason(unwrapped)
        val usage = extractUsage(unwrapped)
        val mediaAttachments = extractInlineMedia(unwrapped)
        LLMResponse(text, finishReason ?: "end_turn", usage, mediaAttachments)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
    ).failOnSilentEmptyCompletion(name)

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        ensureProjectId()
        val inner = buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        val body = envelope(inner)
        val url = "$basePath/v1internal:streamGenerateContent?alt=sse"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", AntigravityOAuth.requestUserAgent())
            .header("Content-Type", "application/json")
            .build()

        var response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            // [fix-antigravity-401-refresh-retry] Same one-shot refresh+retry
            // as the non-streaming path.
            if (!authRetryTried && response.code == 401 && tokenRefresher() != null) {
                authRetryTried = true
                val staleBody = response.body?.string() ?: ""
                response.close()
                val fresh = try { tokenRefresher()?.invoke() } catch (_: Exception) { null }
                if (!fresh.isNullOrBlank()) {
                    val retryBody = envelope(buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel))
                    val retryRequest = request.newBuilder()
                        .header("Authorization", "Bearer $fresh")
                        .post(retryBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    response = client.newCall(retryRequest).execute()
                    if (response.isSuccessful) {
                        // fall through to stream consumption below
                    } else {
                        val retryErrorBody = response.body?.string() ?: ""
                        val retryAfterMs = parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis())
                        response.close()
                        throw mapHttpError(response.code, retryErrorBody, retryAfterMs)
                    }
                } else {
                    // Refresh failed → surface the stale-body error honestly.
                    val retryAfterMs = parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis())
                    throw mapHttpError(401, staleBody, retryAfterMs)
                }
            } else {
                val errorBody = response.body?.string() ?: ""
                // One repair round, same shape as the non-streaming path above.
                val projectish = errorBody.contains("project", ignoreCase = true) ||
                    errorBody.contains("VERSION_CHECK_FAILED", ignoreCase = true)
                if (!projectRepairTried && projectish && projectIdRefresher != null &&
                    (response.code == 400 || response.code == 403)) {
                    projectRepairTried = true
                    response.close()
                    ensureProjectId(force = true)
                    val retryBody = envelope(buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel))
                    val retryRequest = request.newBuilder()
                        .post(retryBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    response = client.newCall(retryRequest).execute()
                    if (response.isSuccessful) {
                        // fall through to stream consumption below
                    } else {
                        val retryErrorBody = response.body?.string() ?: ""
                        val retryAfterMs = parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis())
                        response.close()
                        throw mapHttpError(response.code, retryErrorBody, retryAfterMs)
                    }
                } else {
                    val retryAfterMs = parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis())
                    response.close()
                    throw mapHttpError(response.code, errorBody, retryAfterMs)
                }
            }
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        try {
            var started = false
            var lastFinishReason: String? = null
            var contentChars = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val payload = l.removePrefix("data: ").trim()
                if (payload.isEmpty() || payload == "[]") continue

                val json = try { JSONObject(payload) } catch (_: Exception) { continue }

                // SSE chunks carry {"response": {...gemini-shaped...}} — unwrap
                // before parsing (ConvertAntigravityResponseToGemini parity).
                val chunk = json.optJSONObject("response") ?: json

                if (!started) {
                    send(LLMStreamChunk.Started)
                    started = true
                }

                val (text, thinking) = extractTextAndThinking(chunk)
                if (thinking.isNotEmpty()) {
                    contentChars += thinking.length
                    send(LLMStreamChunk.ThinkingDelta(thinking))
                }
                if (text.isNotEmpty()) {
                    contentChars += text.length
                    send(LLMStreamChunk.Text(text))
                }

                val functionCalls = extractFunctionCalls(chunk)
                for ((fcName, fcArgs, fcSignature) in functionCalls) {
                    val toolId = "antigravity_${System.nanoTime()}"
                    send(LLMStreamChunk.ToolUseStart(toolId, fcName))
                    send(LLMStreamChunk.ToolCallComplete(toolId, fcName, fcArgs, fcSignature))
                }

                extractUsage(chunk)?.let { usage ->
                    send(LLMStreamChunk.Usage(usage))
                }

                extractFinishReason(chunk)?.let { reason ->
                    lastFinishReason = reason
                }
            }
            if (lastFinishReason == null && contentChars > 0) {
                send(LLMStreamChunk.Finished(null, truncated = true))
            } else {
                send(LLMStreamChunk.Finished(lastFinishReason ?: "end_turn"))
            }
        } catch (e: Exception) {
            close(mapError(e))
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        awaitClose { }
    }

    // ── Envelope (geminiToAntigravity, antigravity_executor_request.go:459) ──

    /**
     * Wraps the Gemini-format payload into the Antigravity envelope:
     * model / userAgent / requestType / project / requestId / request.sessionId,
     * drops request.safetySettings, deletes generationConfig.maxOutputTokens
     * for Gemini targets (upstream: sjson.DeleteBytes on the same path).
     */
    private fun envelope(inner: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("model", model.id)
        out.put("userAgent", "antigravity")
        out.put("requestType", "agent")
        val project = resolvedProjectId
        if (!project.isNullOrBlank()) {
            out.put("project", project)
        }
        out.put("requestId", "agent-" + UUID.randomUUID().toString())

        // maxOutputTokens: upstream deletes it for Gemini targets before send.
        if (!isClaudeModel()) {
            inner.optJSONObject("generationConfig")?.remove("maxOutputTokens")
        } else if (inner.has("tools")) {
            // Claude targets additionally pin function-calling mode (upstream:
            // sjson.Set request.toolConfig.functionCallingConfig.mode=VALIDATED).
            val toolConfig = inner.optJSONObject("toolConfig") ?: JSONObject()
            val fcc = toolConfig.optJSONObject("functionCallingConfig") ?: JSONObject()
            fcc.put("mode", "VALIDATED")
            toolConfig.put("functionCallingConfig", fcc)
            inner.put("toolConfig", toolConfig)
        }

        // Stable per-conversation sessionId: hash of the first user message's
        // first text (generateStableSessionID parity) — keeps multi-turn
        // context sticky upstream without leaking message content.
        inner.put("sessionId", stableSessionId(inner))

        out.put("request", inner)
        return out
    }

    /** "-" + 63-bit signed-ish value from SHA-256 of the first user text (upstream parity). */
    private fun stableSessionId(inner: JSONObject): String {
        try {
            val contents = inner.optJSONArray("contents") ?: return "-" + randomNegative()
            for (i in 0 until contents.length()) {
                val content = contents.optJSONObject(i) ?: continue
                if (content.optString("role") != "user") continue
                val parts = content.optJSONArray("parts") ?: continue
                val first = parts.optJSONObject(0) ?: continue
                val text = first.optString("text")
                if (text.isEmpty()) continue
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.toByteArray(Charsets.UTF_8))
                var value = 0L
                for (b in 0 until 8) {
                    value = (value shl 8) or (digest[b].toLong() and 0xFF)
                }
                value = value and Long.MAX_VALUE
                return "-" + value.toString()
            }
        } catch (_: Exception) {
        }
        return "-" + randomNegative()
    }

    private fun randomNegative(): Long {
        val n = java.security.SecureRandom().nextLong()
        return java.lang.Math.abs(n)
    }

    // ── Gemini-format payload builder (same wire shape as GeminiProvider) ──

    // ── Test seams (internal; production code never calls these) ──

    internal fun extractFunctionCallsForTest(json: JSONObject) = extractFunctionCalls(json)

    internal fun buildRequestForTest(
        messages: List<LLMMessage>,
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject = buildRequestBody(
        messages = messages,
        systemPrompt = null,
        maxTokens = 1024,
        temperature = null,
        imageParts = emptyList(),
        tools = emptyList(),
        thinkingLevel = thinkingLevel,
    )

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        val body = JSONObject()

        val sanitizedMessages = sanitizeToolPairing(messages) { detail ->
            android.util.Log.i("AntigravityProvider", detail)
        }

        val contents = JSONArray()
        val lastUserIndex = sanitizedMessages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in sanitizedMessages.withIndex()) {
            val role = if (msg.role == LLMMessage.Role.USER) "user" else "model"
            val content = JSONObject()
            content.put("role", role)

            val parts = JSONArray()

            if (msg.contentParts.isNotEmpty()) {
                for (part in msg.contentParts) {
                    when (part) {
                        is AgentContentPart.Text -> {
                            if (part.text.isNotEmpty()) {
                                parts.put(JSONObject().put("text", part.text))
                            }
                        }
                        is AgentContentPart.ToolUse -> {
                            parts.put(JSONObject().apply {
                                val functionCall = JSONObject().apply {
                                    put("name", part.name)
                                    put("args", part.input)
                                }
                                // [fix-antigravity-thought-signature] Echo the
                                // signature verbatim on the same part — Google
                                // validates it against the original response.
                                part.thoughtSignature?.takeIf { it.isNotEmpty() }?.let {
                                    functionCall.put("thoughtSignature", it)
                                }
                                put("functionCall", functionCall)
                            })
                        }
                        is AgentContentPart.ToolResult -> {
                            val responseObj = JSONObject()
                            responseObj.put("name", part.name)
                            val responseContent = JSONObject()
                            val safeContent = part.content.ifEmpty { " " }
                            responseContent.put("result", safeContent)
                            if (part.isError) responseContent.put("error", true)
                            responseObj.put("response", responseContent)
                            parts.put(JSONObject().put("functionResponse", responseObj))
                        }
                        is AgentContentPart.ImageData -> {
                            parts.put(JSONObject().put("inlineData", JSONObject().apply {
                                put("mimeType", part.mimeType)
                                put("data", Base64.encodeToString(part.data, Base64.NO_WRAP))
                            }))
                        }
                    }
                }
            } else {
                if (index == lastUserIndex && imageParts.isNotEmpty()) {
                    for (part in imageParts) {
                        val inlineData = JSONObject()
                        inlineData.put("mimeType", part.mimeType)
                        inlineData.put("data", Base64.encodeToString(part.data, Base64.NO_WRAP))
                        parts.put(JSONObject().put("inlineData", inlineData))
                    }
                }
                val legacyText = msg.content.ifEmpty { " " }
                parts.put(JSONObject().put("text", legacyText))
            }
            if (parts.length() == 0) {
                parts.put(JSONObject().put("text", "(empty)"))
            }
            content.put("parts", parts)
            contents.put(content)
        }
        body.put("contents", contents)

        if (systemPrompt != null) {
            body.put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", systemPrompt)),
            ))
        }

        if (tools.isNotEmpty()) {
            val funcDecls = JSONArray()
            for (tool in tools) {
                funcDecls.put(tool.toGeminiJson())
            }
            body.put("tools", JSONArray().put(JSONObject().put("function_declarations", funcDecls)))
        }

        val config = JSONObject()
        if (isClaudeModel()) {
            // Claude targets keep a budget-style ceiling (upstream caps it to
            // the model's max_completion_tokens); Gemini targets drop it.
            config.put("maxOutputTokens", clampOutboundMaxTokens(maxTokens, effectiveMaxOutputTokens(model)))
        }
        if (temperature != null) {
            config.put("temperature", clampOutboundTemperature(temperature))
        }

        buildThinkingConfig(thinkingLevel)?.let { thinkingConfig ->
            config.put("thinkingConfig", thinkingConfig)
        }

        body.put("generationConfig", config)
        return body
    }

    /**
     * Antigravity thinking config — request.generationConfig.thinkingConfig
     * path with thinkingLevel strings (Antigravity Applier parity).
     *
     * [fix-antigravity-minimal-400] OFF → NO thinkingConfig at all (upstream
     * ModeNone parity: "With the amount fully disabled … delete
     * thinkingConfig"). The previous mapping sent `thinkingLevel: "minimal"`
     * for flash targets, but per the upstream registry several Antigravity
     * models (gemini-3.7-flash-high, gemini-3.8-flash-high, gemini-pro-agent,
     * gemini-3.1-pro-low) support only low/medium/high and Google rejects
     * minimal with 400 INVALID_ARGUMENT "Thinking level MINIMAL is not
     * supported for this model". When the field is absent Google applies the
     * model's default thinking behaviour.
     *
     * Per-model clamp: gemini-3.1-flash-image supports only minimal/high, so
     * LOW/MEDIUM clamp to minimal there (registry parity).
     */
    internal fun buildThinkingConfig(level: ThinkingLevel): JSONObject? {
        if (level == ThinkingLevel.OFF) return null
        val imageOnly = model.id.lowercase().contains("flash-image")
        return JSONObject().apply {
            put("thinkingLevel", when (level) {
                ThinkingLevel.LOW, ThinkingLevel.MEDIUM ->
                    if (imageOnly) "minimal" else level.name.lowercase()
                // HIGH / XHIGH / MAX / ULTRA all collapse to the top level.
                else -> "high"
            })
            put("includeThoughts", true)
        }
    }

    // ── Parsers (identical to GeminiProvider — same response shape) ──

    private fun extractText(json: JSONObject): String = extractTextAndThinking(json).first

    private fun extractTextAndThinking(json: JSONObject): Pair<String, String> {
        val candidates = json.optJSONArray("candidates") ?: return "" to ""
        val first = candidates.optJSONObject(0) ?: return "" to ""
        val content = first.optJSONObject("content") ?: return "" to ""
        val parts = content.optJSONArray("parts") ?: return "" to ""

        val textBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val text = part.safeOptString("text", "")
            if (text.isEmpty()) continue
            if (part.optBoolean("thought", false)) {
                thinkingBuilder.append(text)
            } else {
                textBuilder.append(text)
            }
        }
        return textBuilder.toString() to thinkingBuilder.toString()
    }

    private fun extractInlineMedia(json: JSONObject): List<LLMMediaAttachment> {
        val candidates = json.optJSONArray("candidates") ?: return emptyList()
        val first = candidates.optJSONObject(0) ?: return emptyList()
        val content = first.optJSONObject("content") ?: return emptyList()
        val parts = content.optJSONArray("parts") ?: return emptyList()

        val out = mutableListOf<LLMMediaAttachment>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inline = part.optJSONObject("inlineData") ?: continue
            val mime = inline.safeOptString("mimeType", "")
            val b64 = inline.safeOptString("data", "")
            if (mime.isEmpty() || b64.isEmpty()) continue
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: Throwable) {
                continue
            }
            val type = when {
                mime.startsWith("image/") -> LLMMediaAttachment.MediaType.IMAGE
                mime.startsWith("audio/") -> LLMMediaAttachment.MediaType.AUDIO
                mime.startsWith("video/") -> LLMMediaAttachment.MediaType.VIDEO
                else -> LLMMediaAttachment.MediaType.IMAGE
            }
            out.add(LLMMediaAttachment(type, mime, bytes))
        }
        return out
    }

    /**
     * [fix-antigravity-thought-signature] functionCall parts carry a part-level
     * `thoughtSignature` (camelCase; legacy snake_case tolerated like upstream's
     * normalizePart). It MUST round-trip: the next request's history re-emits
     * it on the same functionCall part, or Google 400s with "Function call is
     * missing a thought_signature in functionCall parts".
     */
    private fun extractFunctionCalls(json: JSONObject): List<Triple<String, JSONObject, String?>> {
        val candidates = json.optJSONArray("candidates") ?: return emptyList()
        val first = candidates.optJSONObject(0) ?: return emptyList()
        val content = first.optJSONObject("content") ?: return emptyList()
        val parts = content.optJSONArray("parts") ?: return emptyList()

        val calls = mutableListOf<Triple<String, JSONObject, String?>>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val fc = part.optJSONObject("functionCall") ?: continue
            val name = fc.safeOptString("name", "")
            val args = fc.optJSONObject("args") ?: JSONObject()
            if (name.isNotEmpty()) {
                calls.add(
                    Triple(
                        name,
                        args,
                        part.safeOptString("thoughtSignature", "")
                            .ifEmpty { part.safeOptString("thought_signature", "") }
                            .ifEmpty { null },
                    ),
                )
            }
        }
        return calls
    }

    private fun extractFinishReason(json: JSONObject): String? {
        val candidates = json.optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val reason = first.safeOptString("finishReason", "").ifEmpty { return null }
        return when (reason) {
            "STOP" -> "end_turn"
            "MAX_TOKENS" -> "max_tokens"
            else -> reason.lowercase()
        }
    }

    private fun extractUsage(json: JSONObject): LLMUsage? {
        val usage = json.optJSONObject("usageMetadata") ?: return null
        val totalInput = usage.optInt("promptTokenCount", 0)
        val cacheRead = usage.optInt("cachedContentTokenCount").takeIf { it > 0 }
        val freshInput = (totalInput - (cacheRead ?: 0)).coerceAtLeast(0)
        return LLMUsage(
            inputTokens = freshInput,
            outputTokens = usage.optInt("candidatesTokenCount", 0),
            cacheCreationInputTokens = null,
            cacheReadInputTokens = cacheRead,
            latestContextTokens = totalInput,
        )
    }

    private fun mapHttpError(statusCode: Int, body: String, retryAfterMs: Long? = null): LLMError {
        if (statusCode == 401) {
            // [fix-antigravity-401-refresh-retry] Reachable only after the
            // refresh+retry round failed, i.e. the refresh token itself is
            // dead (revoked / password change / >6h Google rotation grace).
            // "重新登录" is then genuinely the only remedy.
            return LLMError.InvalidApiKey("Antigravity 登录已过期，请在 Provider 连接页重新登录")
        }
        if (statusCode == 403) {
            // 403 frequently means a missing/invalid project id rather than
            // an expired credential — surface the upstream detail either way.
            return LLMError.InvalidApiKey("Antigravity 访问被拒：${body.take(300)}")
        }
        if (statusCode == 429) return LLMError.RateLimited(retryAfterMs = retryAfterMs)
        val message = "Antigravity API error $statusCode: ${body.take(200)}"
        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) return LLMError.TransientError(message, retryAfterMs)
        return LLMError.ProviderError(message)
    }

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
}
