package com.openminis.app.provider.antigravity

import android.content.Context
import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.parseRetryAfterMs
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.clampOutboundMaxTokens
import com.openminis.app.provider.clampOutboundTemperature
import com.openminis.app.provider.sanitizeToolPairing
import com.openminis.app.provider.safeOptString
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * [T-antigravity-provider] LLMProvider for Antigravity (Cloud Code Assist).
 *
 * Routes chat through `{baseURL}/v1internal:generateContent` /
 * `:streamGenerateContent?alt=sse` with the Antigravity envelope:
 *
 * ```
 * {
 *   "model": "claude-sonnet-4-6",
 *   "userAgent": "antigravity",
 *   "requestType": "agent",
 *   "project": "<gcp project id>",
 *   "requestId": "agent-<uuid>",
 *   "request": { <Gemini-format body: contents / systemInstruction /
 *                generationConfig / tools> }
 * }
 * ```
 *
 * Responses (and SSE chunks) arrive wrapped as `{ "response": { candidates… } }`
 * — unwrapped before parsing. Ported from iOS `AntigravityProvider.swift`
 * (upstream OpenMinis) with the request shape cross-checked against
 * CLIProxyAPI's `antigravity_executor.go`.
 *
 * Credential handling: [storedCredential] is the raw `apiKey` slot content —
 * either an [AntigravityOAuthStore] token-bundle JSON (normal OAuth login
 * path) or a user-pasted static token (manual bearer). Access tokens are
 * refreshed transparently via [AntigravityOAuthStore.ensureFreshToken].
 */
class AntigravityProvider(
    storedCredential: String,
    override var model: LLMModel = LLMModel("claude-sonnet-4-6", "Claude Sonnet 4.6", "Antigravity"),
    /** App context for meta prefs (project id / pinned base URL) — nullable for JVM tests. */
    private val appContext: Context? = null,
    private val instanceId: String = "",
) : LLMProvider {

    override val name = "Antigravity"
    override var instanceContext: com.openminis.app.data.model.ProviderInstance? = null

    /**
     * Gemini-format contents accept a trailing model turn (prefill), same as
     * the GeminiProvider surface this protocol descends from.
     */
    override val supportsPrefill: Boolean get() = true

    /** Stable per-provider session id for the envelope (matches iOS). */
    private val sessionId = UUID.randomUUID().toString()

    /** Resolved credential state — re-read after each refresh. */
    private var bundle: AntigravityOAuthStore.TokenBundle? = AntigravityOAuthStore.parse(storedCredential)
    private val isTokenJson = AntigravityOAuthStore.isTokenJson(storedCredential)
    private val manualToken: String? = if (isTokenJson) null else storedCredential

    /** Project id for the envelope; discovered lazily on first request. */
    @Volatile private var projectID: String? =
        AntigravityOAuthStore.loadProject(appContext, instanceId)

    /** Chat base URL pinned at project discovery (falls back to daily). */
    @Volatile private var activeBaseURL: String =
        AntigravityOAuthStore.loadBaseURL(appContext, instanceId)
            ?: AntigravityOAuthStore.DEFAULT_BASE_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .build()

    /** Resolve a bearer token: refresh path for OAuth, pass-through for manual. */
    private suspend fun accessToken(): String {
        manualToken?.let { return it }
        val current = bundle ?: throw LLMError.InvalidApiKey()
        val token = AntigravityOAuthStore.ensureFreshToken(current) { renewed ->
            bundle = renewed
            persistCredential(renewed)
        }
        return token ?: throw LLMError.InvalidApiKey()
    }

    /** Write the renewed token bundle back into the apiKey slot. */
    private fun persistCredential(renewed: AntigravityOAuthStore.TokenBundle) {
        val context = appContext ?: return
        if (instanceId.isEmpty()) return
        try {
            val prefs = com.openminis.app.util.EncryptedPrefsFactory
                .safeCreate(context, "provider_secrets")
            prefs.edit().putString("apikey_$instanceId", AntigravityOAuthStore.serialize(renewed)).apply()
        } catch (e: Exception) {
            android.util.Log.w("AntigravityProvider", "token write-back failed: ${e.message}")
        }
    }

    /** Project id with one-time discovery (persisted for later providers). */
    private suspend fun resolveProject(): String {
        projectID?.let { return it }
        val token = accessToken()
        val discovered = AntigravityOAuthStore.discoverProject(token)
        if (discovered != null) {
            projectID = discovered.first
            activeBaseURL = discovered.second
            val context = appContext
            if (context != null && instanceId.isNotEmpty()) {
                AntigravityOAuthStore.saveMeta(context, instanceId, discovered.first, discovered.second, null)
            }
            return discovered.first
        }
        // Discovery failed — empty project still works for some accounts.
        return ""
    }

    // ── Non-streaming ───────────────────────────────────────────────────

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val project = resolveProject()
        val envelope = wrapEnvelope(
            buildInnerBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel),
        )
        val request = buildRequest("generateContent", envelope, thinkingLevel.isEnabled, accessToken())
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mapHttpError(
                    response.code,
                    body,
                    parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis()),
                )
            }
            val json = JSONObject(body)
            val (text, thinking) = extractTextAndThinking(json)
            val finishReason = extractFinishReason(json)
            LLMResponse(
                text = text,
                stopReason = finishReason ?: "end_turn",
                usage = extractUsage(json),
            )
        }
    }

    // ── Streaming ───────────────────────────────────────────────────────

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStream(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        .failOnSilentEmptyCompletion(name)

    private fun rawStream(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val request = withContext(Dispatchers.IO) {
            val project = resolveProject()
            val envelope = wrapEnvelope(
                buildInnerBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel),
            )
            buildRequest("streamGenerateContent?alt=sse", envelope, thinkingLevel.isEnabled, accessToken())
        }
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string().orEmpty()
            val retryAfterMs = parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis())
            response.close()
            throw mapHttpError(response.code, errorBody, retryAfterMs)
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
                if (payload.isEmpty() || payload == "[DONE]") continue
                val json = try { JSONObject(payload) } catch (_: Exception) { continue }

                if (!started) {
                    trySend(LLMStreamChunk.Started)
                    started = true
                }

                val (text, thinking) = extractTextAndThinking(json)
                if (thinking.isNotEmpty()) {
                    contentChars += thinking.length
                    trySend(LLMStreamChunk.ThinkingDelta(thinking))
                }
                if (text.isNotEmpty()) {
                    contentChars += text.length
                    trySend(LLMStreamChunk.Text(text))
                }

                for ((fcName, fcArgs) in extractFunctionCalls(json)) {
                    val toolId = "antigravity_${System.nanoTime()}"
                    trySend(LLMStreamChunk.ToolUseStart(toolId, fcName))
                    trySend(LLMStreamChunk.ToolCallComplete(toolId, fcName, fcArgs))
                }

                extractUsage(json)?.let { trySend(LLMStreamChunk.Usage(it)) }
                extractFinishReason(json)?.let { lastFinishReason = it }
            }
            // Same truncated-stream semantics as GeminiProvider: EOF with
            // content but no finish reason = server cut the reply mid-stream.
            if (lastFinishReason == null && contentChars > 0) {
                trySend(LLMStreamChunk.Finished(null, truncated = true))
            } else {
                trySend(LLMStreamChunk.Finished(lastFinishReason ?: "end_turn"))
            }
        } catch (e: Exception) {
            close(mapError(e))
            return@callbackFlow
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        awaitClose()
    }

    // ── Request building ────────────────────────────────────────────────

    private fun buildInnerBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
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
                                put("functionCall", JSONObject().apply {
                                    put("name", part.name)
                                    put("args", part.input)
                                })
                            })
                        }
                        is AgentContentPart.ToolResult -> {
                            val responseObj = JSONObject()
                            responseObj.put("name", part.name)
                            val responseContent = JSONObject()
                            responseContent.put("result", part.content.ifEmpty { " " })
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
                        parts.put(JSONObject().put("inlineData", JSONObject().apply {
                            put("mimeType", part.mimeType)
                            put("data", Base64.encodeToString(part.data, Base64.NO_WRAP))
                        }))
                    }
                }
                parts.put(JSONObject().put("text", msg.content.ifEmpty { " " }))
            }
            if (parts.length() == 0) {
                parts.put(JSONObject().put("text", "(empty)"))
            }
            content.put("parts", parts)
            contents.put(content)
        }
        body.put("contents", contents)

        if (!systemPrompt.isNullOrEmpty()) {
            body.put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", systemPrompt)),
            ))
        }

        if (tools.isNotEmpty()) {
            val funcDecls = JSONArray()
            for (tool in tools) funcDecls.put(tool.toGeminiJson())
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", funcDecls)))
            body.put("toolConfig", JSONObject().put(
                "functionCallingConfig", JSONObject().put("mode", "AUTO"),
            ))
        }

        val config = JSONObject()
        config.put("maxOutputTokens", clampOutboundMaxTokens(maxTokens, effectiveMaxOutputTokens(model)))
        if (temperature != null) {
            config.put("temperature", clampOutboundTemperature(temperature))
        }
        thinkingConfig(thinkingLevel)?.let { config.put("thinkingConfig", it) }
        body.put("generationConfig", config)
        return body
    }

    /**
     * Thinking config — ported from iOS AntigravityProvider. Claude models
     * take NO thinkingConfig (Cloud Code routes them to Anthropic-native
     * handling); Gemini 3.x uses thinkingLevel strings; 2.5 uses budgets.
     */
    private fun thinkingConfig(level: ThinkingLevel): JSONObject? {
        val id = model.id.lowercase()
        if (id.contains("claude")) return null
        if (id.contains("gemini-3")) {
            return JSONObject().apply {
                if (!level.isEnabled) {
                    put("thinkingLevel", if (id.contains("flash")) "minimal" else "low")
                } else {
                    put("thinkingLevel", when (level) {
                        ThinkingLevel.LOW -> "low"
                        ThinkingLevel.MEDIUM -> "medium"
                        ThinkingLevel.HIGH, ThinkingLevel.XHIGH,
                        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "high"
                        else -> "minimal"
                    })
                    put("includeThoughts", true)
                }
            }
        }
        if (id.contains("2.5-pro")) {
            return JSONObject().apply {
                put("thinkingBudget", when (level) {
                    ThinkingLevel.OFF -> 128
                    ThinkingLevel.LOW -> 2048
                    ThinkingLevel.MEDIUM -> 8192
                    ThinkingLevel.HIGH -> 16384
                    ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 32768
                })
                if (level.isEnabled) put("includeThoughts", true)
            }
        }
        if (id.contains("2.5-flash") && !id.contains("lite")) {
            return JSONObject().apply {
                put("thinkingBudget", when (level) {
                    ThinkingLevel.OFF -> 0
                    ThinkingLevel.LOW -> 1024
                    ThinkingLevel.MEDIUM -> 4096
                    ThinkingLevel.HIGH -> 8192
                    ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 16384
                })
                if (level.isEnabled) put("includeThoughts", true)
            }
        }
        if (id.contains("2.5-flash-lite")) return null
        return JSONObject().put("thinkingBudget", 0)
    }

    /** Antigravity Cloud Code envelope around the Gemini-format inner body. */
    private fun wrapEnvelope(inner: JSONObject): JSONObject {
        inner.put("sessionId", sessionId)
        return JSONObject().apply {
            put("model", model.id)
            put("userAgent", "antigravity")
            put("requestType", "agent")
            put("project", projectID ?: "")
            put("requestId", "agent-${UUID.randomUUID()}")
            put("request", inner)
        }
    }

    private fun buildRequest(path: String, body: JSONObject, thinkingEnabled: Boolean, token: String): Request {
        val builder = Request.Builder()
            .url("$activeBaseURL/v1internal:$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", AntigravityOAuthStore.USER_AGENT)
            .header("X-Client-Name", "antigravity")
            .header("X-Client-Version", "1.107.0")
            .header("x-goog-api-client", "gl-node/18.18.2 fire/0.8.6 grpc/1.10.x")

        // Streaming endpoints expect an SSE accept; Claude thinking models
        // need the interleaved-thinking beta header (matches iOS + Go core).
        if (path.contains("stream")) {
            builder.header("Accept", "text/event-stream")
        } else {
            builder.header("Accept", "application/json")
        }
        val lid = model.id.lowercase()
        if (lid.contains("claude") && (lid.contains("thinking") || thinkingEnabled)) {
            builder.header("anthropic-beta", "interleaved-thinking-2025-05-14")
        }
        return builder.build()
    }

    // ── Response parsing ────────────────────────────────────────────────

    /** Unwrap `{ "response": { … } }` → inner Gemini-format object. */
    private fun unwrap(json: JSONObject): JSONObject =
        json.optJSONObject("response") ?: json

    private fun extractTextAndThinking(json: JSONObject): Pair<String, String> {
        val effective = unwrap(json)
        val candidates = effective.optJSONArray("candidates") ?: return "" to ""
        val first = candidates.optJSONObject(0) ?: return "" to ""
        val content = first.optJSONObject("content") ?: return "" to ""
        val parts = content.optJSONArray("parts") ?: return "" to ""

        val text = StringBuilder()
        val thinking = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val t = part.safeOptString("text", "")
            if (t.isEmpty()) continue
            if (part.optBoolean("thought", false)) thinking.append(t) else text.append(t)
        }
        return text.toString() to thinking.toString()
    }

    private fun extractFunctionCalls(json: JSONObject): List<Pair<String, JSONObject>> {
        val effective = unwrap(json)
        val candidates = effective.optJSONArray("candidates") ?: return emptyList()
        val first = candidates.optJSONObject(0) ?: return emptyList()
        val content = first.optJSONObject("content") ?: return emptyList()
        val parts = content.optJSONArray("parts") ?: return emptyList()

        val calls = mutableListOf<Pair<String, JSONObject>>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val fc = part.optJSONObject("functionCall") ?: continue
            val name = fc.safeOptString("name", "")
            if (name.isNotEmpty()) calls.add(name to (fc.optJSONObject("args") ?: JSONObject()))
        }
        return calls
    }

    private fun extractFinishReason(json: JSONObject): String? {
        val effective = unwrap(json)
        val candidates = effective.optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val reason = first.safeOptString("finishReason", "").ifEmpty { return null }
        return when (reason) {
            "STOP" -> "end_turn"
            "MAX_TOKENS" -> "max_tokens"
            else -> reason.lowercase()
        }
    }

    private fun extractUsage(json: JSONObject): LLMUsage? {
        val usage = unwrap(json).optJSONObject("usageMetadata") ?: return null
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

    private fun mapHttpError(statusCode: Int, body: String, retryAfterMs: Long?): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
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
