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

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
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

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
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
                for ((fcName, fcArgs) in functionCalls) {
                    val toolId = "antigravity_${System.nanoTime()}"
                    send(LLMStreamChunk.ToolUseStart(toolId, fcName))
                    send(LLMStreamChunk.ToolCallComplete(toolId, fcName, fcArgs))
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
        if (!projectId.isNullOrBlank()) {
            out.put("project", projectId)
        }
        out.put("requestId", "agent-" + UUID.randomUUID().toString())

        // maxOutputTokens: upstream deletes it for Gemini targets before send.
        if (!isClaudeModel()) {
            inner.optJSONObject("generationConfig")?.remove("maxOutputTokens")
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
     * path with thinkingLevel strings (Antigravity Applier parity, simplified
     * to the level format the Antigravity models accept).
     */
    private fun buildThinkingConfig(level: ThinkingLevel): JSONObject? {
        val modelId = model.id
        val isFlash = modelId.contains("flash")
        return when {
            level == ThinkingLevel.OFF -> JSONObject().apply {
                put("thinkingLevel", if (isFlash) "minimal" else "low")
            }
            else -> JSONObject().apply {
                put("thinkingLevel", when (level) {
                    ThinkingLevel.LOW -> "low"
                    ThinkingLevel.MEDIUM -> "medium"
                    ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "high"
                    else -> "low"
                })
                put("includeThoughts", true)
            }
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

    private fun extractFunctionCalls(json: JSONObject): List<Pair<String, JSONObject>> {
        val candidates = json.optJSONArray("candidates") ?: return emptyList()
        val first = candidates.optJSONObject(0) ?: return emptyList()
        val content = first.optJSONObject("content") ?: return emptyList()
        val parts = content.optJSONArray("parts") ?: return emptyList()

        val calls = mutableListOf<Pair<String, JSONObject>>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val fc = part.optJSONObject("functionCall") ?: continue
            val name = fc.safeOptString("name", "")
            val args = fc.optJSONObject("args") ?: JSONObject()
            if (name.isNotEmpty()) calls.add(name to args)
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
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey("Antigravity 登录已过期，请在 Provider 连接页重新登录")
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
