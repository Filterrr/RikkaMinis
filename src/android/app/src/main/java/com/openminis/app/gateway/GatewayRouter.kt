package com.openminis.app.gateway

import android.content.Context
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.repository.ProviderRepository
import java.io.OutputStream
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject


/**
 * [T-local-llm-gateway] Router: URL → dialect → engine → response.
 *
 * Endpoints (each vendor's own prefix is answered, so a client is repointed by
 * changing nothing but `base_url`):
 *
 *   OpenAI     POST /v1/chat/completions · POST /v1/responses · POST /v1/completions
 *              GET  /v1/models · GET /v1/models/{id}
 *   Anthropic  POST /v1/messages · POST /v1/messages/count_tokens · GET /v1/models
 *   Gemini     POST /v1beta/models/{model}:generateContent[:streamGenerateContent]
 *              GET  /v1beta/models
 *   Ollama     POST /api/chat · POST /api/generate · GET /api/tags · GET /api/version
 *   misc       GET /health · GET /
 *
 * Concurrency: the accept loop already hands each socket its own thread, and
 * every request runs its own coroutine + [GatewayStreamWriter], so two clients
 * can stream at once without sharing state.
 */
internal class GatewayRouter(
    private val context: Context,
    private val repository: ProviderRepository,
) {

    private val engine = GatewayEngine(context, repository)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun shutdown() = scope.cancel()

    /**
     * Handle one parsed request. Returns true when a streaming body was opened
     * — the accept loop then closes the socket, because a chunked stream ends
     * at EOF and a keep-alive would make SSE clients wait forever for the
     * terminal frame.
     */
    fun dispatch(req: GatewayHttp.Request, socket: Socket): Boolean {
        val out = socket.getOutputStream()
        if (req.method == "OPTIONS") {
            GatewayHttp.writeCorsPreflight(out)
            return false
        }
        val authError = GatewayAuth.current().check(req)
        if (authError != null) {
            GatewayHttp.writeJsonError(out, authError)
            return false
        }
        return when (req.method) {
            "GET" -> handleGet(req, out)
            "POST" -> handlePost(req, out)
            else -> {
                GatewayHttp.writeJsonError(
                    out,
                    GatewayException(405, "invalid_request_error", "Method ${req.method} not allowed on ${req.path}"),
                )
                false
            }
        }
    }

    // ── GET: discovery + liveness ───────────────────────────────────────

    private fun handleGet(req: GatewayHttp.Request, out: java.io.OutputStream): Boolean {
        val path = req.path
        when {
            path == "/health" -> writeJson(out, 200, JSONObject().apply {
                put("ok", true)
                put("service", "minis-gateway")
                put("protocols", JSONArray().apply { put("openai"); put("anthropic"); put("gemini"); put("ollama") })
                put("models", engine.catalogue().size)
            }.toString())
            path == "/" -> writeJson(out, 200, JSONObject().apply {
                put("name", "Minis local LLM gateway")
                put("endpoints", JSONArray().apply {
                    put("/v1/chat/completions"); put("/v1/messages"); put("/v1/responses")
                    put("/v1beta/models/*:generateContent"); put("/api/chat"); put("/api/tags"); put("/health")
                })
            }.toString())
            path == "/v1/models" || path == "/models" -> writeJson(out, 200, openAiModelList())
            path.startsWith("/v1/models/") -> {
                val id = path.removePrefix("/v1/models/").substringBefore('/')
                val entry = engine.catalogue().firstOrNull { it.modelId == id || it.qualifiedId == id }
                if (entry == null) writeJson(out, 404, GatewayErrors.body(
                    GatewayException(404, "not_found_error", "model '$id' not found", "model_not_found")))
                else writeJson(out, 200, JSONObject().apply {
                    put("id", entry.modelId)
                    put("type", "model")
                    put("display_name", entry.displayName)
                    put("created_at", "2026-01-01T00:00:00Z")
                }.toString())
            }
            path == "/v1beta/models" || path == "/v1/models/list" -> writeJson(out, 200, geminiModelList())
            path == "/api/tags" || path == "/api/models" -> writeJson(out, 200, ollamaTags())
            path == "/api/version" -> writeJson(out, 200, JSONObject()
                .put("version", appVersion()).put("commit", "minis").toString())
            else -> GatewayHttp.writeJsonError(
                out, GatewayException(404, "not_found_error", "Unknown gateway route: $path", "route_not_found"),
            )
        }
        return false
    }

    // ── POST: generation ────────────────────────────────────────────────

    private fun handlePost(req: GatewayHttp.Request, out: java.io.OutputStream): Boolean {
        val path = req.path
        val body = GatewayJson.obj(req.body)
        if (body == null) {
            GatewayHttp.writeJsonError(
                out, GatewayException(400, "invalid_request_error", "Request body must be a JSON object"),
            )
            return false
        }
        val parsed: GatewayChatRequest = try {
            when {
                path == "/v1/chat/completions" || path == "/chat/completions" ->
                    GatewayRequestCodec.parseOpenAIChat(body)
                path == "/v1/responses" || path == "/responses" ->
                    GatewayRequestCodec.parseOpenAIResponses(body)
                path == "/v1/completions" || path == "/completions" ->
                    GatewayRequestCodec.parseOpenAICompletions(body)
                path == "/v1/messages/count_tokens" ->
                    return countTokens(out, GatewayRequestCodec.parseAnthropic(body))
                path == "/v1/messages" || path == "/messages" ->
                    GatewayRequestCodec.parseAnthropic(body)
                path == "/api/chat" -> GatewayRequestCodec.parseOllamaChat(body)
                path == "/api/generate" -> GatewayRequestCodec.parseOllamaGenerate(body)
                path.contains(":generateContent") || path.contains(":streamGenerateContent") ->
                    parseGeminiRoute(path, body) ?: return notFound(out, path)
                else -> return notFound(out, path)
            }
        } catch (ex: GatewayException) {
            GatewayHttp.writeJsonError(out, ex)
            return false
        } catch (t: Throwable) {
            GatewayHttp.writeJsonError(
                out,
                GatewayException(400, "invalid_request_error", "Unparsable request: ${t.message?.take(200)}"),
            )
            return false
        }

        if (parsed.messages.isEmpty() && parsed.systemPrompt == null) {
            GatewayHttp.writeJsonError(
                out, GatewayException(400, "invalid_request_error", "No messages supplied"),
            )
            return false
        }

        return if (parsed.stream) {
            runStream(parsed, out)
            true
        } else {
            runBuffered(parsed, out)
            false
        }
    }

    /** `:streamGenerateContent` / the verb decide streaming — Gemini has no body flag. */
    private fun parseGeminiRoute(path: String, body: JSONObject): GatewayChatRequest? {
        val segment = path.substringAfter("/models/", "").substringBefore('?')
        val colon = segment.indexOf(':')
        if (colon <= 0) return null
        val model = segment.substring(0, colon).trim()
        val verb = segment.substring(colon + 1)
        if (model.isEmpty() || !verb.startsWith("generateContent") && !verb.startsWith("streamGenerateContent")) {
            return null
        }
        // `?alt=sse` on streamGenerateContent is the Google client's spelling;
        // the bare verb already implies streaming either way.
        return GatewayRequestCodec.parseGemini(body, model, verb.startsWith("streamGenerateContent"))
    }

    // ── execution paths ─────────────────────────────────────────────────

    private fun runBuffered(req: GatewayChatRequest, out: java.io.OutputStream) {
        scope.launch {
            try {
                val result = engine.execute(req)
                writeJson(out, 200, GatewayResponseCodec.completion(req, result))
            } catch (ex: GatewayException) {
                GatewayHttp.writeJsonError(out, ex, req.requestId, req.protocol)
            } catch (t: Throwable) {
                GatewayEngine.logFailure(req, t)
                GatewayHttp.writeJsonError(
                    out, GatewayErrors.fromThrowable(t), req.requestId, req.protocol,
                )
            }
        }
    }

    private fun runStream(req: GatewayChatRequest, out: java.io.OutputStream) {
        val writer = GatewayStreamWriter(req, req.model)
        // Ollama's NDJSON and the SSE dialects differ only in framing, so the
        // headers must match or strict clients refuse to parse the body.
        GatewayHttp.beginStream(out, if (req.protocol == GatewayProtocol.ollama) "application/x-ndjson" else "text/event-stream; charset=utf-8")
        scope.launch {
            var emitted = false
            try {
                for (frame in writer.open()) {
                    writeFrame(out, req, frame)
                    emitted = true
                }
                // Terminal state is taken from the aggregated result, so the
                // close frames carry the same stop reason / usage the client
                // would have seen in a buffered response.
                val result = engine.execute(req) { chunk ->
                    for (frame in framesFor(chunk, writer)) writeFrame(out, req, frame)
                    emitted = true
                }
                for (frame in writer.close(result.stopReason, truncated = false)) writeFrame(out, req, frame)
                writer.doneSentinel()?.let { writeFrame(out, req, it) }
                GatewayHttp.endStream(out)
            } catch (ex: GatewayException) {
                if (!emitted) {
                    GatewayHttp.endStream(out)
                    GatewayHttp.writeJsonError(out, ex, req.requestId, req.protocol)
                } else {
                    // Headers are already gone: the only legal signal left is an
                    // in-band error frame followed by EOF.
                    runCatching { writeErrorFrame(out, req, ex) }
                    runCatching { GatewayHttp.endStream(out) }
                }
            } catch (t: Throwable) {
                GatewayEngine.logFailure(req, t)
                val ex = GatewayErrors.fromThrowable(t)
                if (!emitted) {
                    runCatching { GatewayHttp.endStream(out) }
                    runCatching { GatewayHttp.writeJsonError(out, ex, req.requestId, req.protocol) }
                } else {
                    runCatching { writeErrorFrame(out, req, ex) }
                    runCatching { GatewayHttp.endStream(out) }
                }
            }
        }
    }

    private fun framesFor(
        chunk: LLMStreamChunk,
        writer: GatewayStreamWriter,
    ): List<GatewayStreamWriter.SseFrame> = when (chunk) {
        is LLMStreamChunk.Text -> writer.onText(chunk.text)
        is LLMStreamChunk.ThinkingDelta -> writer.onReasoning(chunk.text)
        is LLMStreamChunk.ReasoningContent -> writer.onReasoningIfAbsent(chunk.content)
        is LLMStreamChunk.ToolUseStart -> writer.onToolStart(chunk.id, chunk.name)
        is LLMStreamChunk.ToolInputDelta -> writer.onToolArgs(chunk.id, chunk.accumulated)
        is LLMStreamChunk.ToolCallComplete -> writer.onToolComplete(chunk.id, chunk.name, chunk.args)
        is LLMStreamChunk.Usage -> {
            writer.onUsage(chunk.usage.inputTokens, chunk.usage.outputTokens)
            emptyList()
        }
        is LLMStreamChunk.Finished -> emptyList()
        // A generated image cannot ride a text stream; surface it as text so the
        // client at least learns the model returned media.
        is LLMStreamChunk.MediaAttachment -> writer.onText("[attachment ${chunk.attachment.mimeType}]")
        LLMStreamChunk.Started -> emptyList()
    }

    private fun writeFrame(out: java.io.OutputStream, req: GatewayChatRequest, frame: GatewayStreamWriter.SseFrame) {
        if (req.protocol == GatewayProtocol.ollama) {
            GatewayHttp.writeChunk(out, frame.data + "\n")
        } else {
            GatewayHttp.writeSseData(out, frame.data, frame.event)
        }
    }

    private fun writeErrorFrame(out: java.io.OutputStream, req: GatewayChatRequest, ex: GatewayException) {
        val payload = JSONObject().apply {
            put("error", JSONObject().apply {
                put("type", ex.type)
                put("message", ex.message)
                put("code", ex.code ?: JSONObject.NULL)
            })
        }.toString()
        when (req.protocol) {
            GatewayProtocol.ollama -> GatewayHttp.writeChunk(out, JSONObject().put("error", ex.message).toString() + "\n")
            GatewayProtocol.anthropic -> GatewayHttp.writeSseData(
                out,
                JSONObject().apply {
                    put("type", "error")
                    put("error", JSONObject().put("type", ex.type).put("message", ex.message))
                }.toString(),
                "error",
            )
            else -> GatewayHttp.writeSseData(out, payload, if (req.protocol == GatewayProtocol.openaiResponses) "error" else null)
        }
        if (req.protocol == GatewayProtocol.openai || req.protocol == GatewayProtocol.openaiCompletions) {
            GatewayHttp.writeSseData(out, "[DONE]")
        }
    }

    // ── payloads ────────────────────────────────────────────────────────

    private fun openAiModelList(): String {
        val data = JSONArray()
        engine.catalogue().forEach { e ->
            data.put(JSONObject().apply {
                put("id", e.modelId)
                put("object", "model")
                put("created", System.currentTimeMillis() / 1000)
                put("owned_by", "minis:${e.instanceLabel}")
                put("display_name", e.displayName)
                put("context_length", e.contextWindow ?: JSONObject.NULL)
                put("alternative_ids", JSONArray().put(e.qualifiedId))
            })
        }
        return JSONObject().put("object", "list").put("data", data).toString()
    }

    private fun geminiModelList(): String {
        val models = JSONArray()
        engine.catalogue().forEach { e ->
            models.put(JSONObject().apply {
                put("name", "models/${e.modelId}")
                put("displayName", e.displayName)
                put("inputTokenLimit", e.contextWindow ?: 128_000)
                put("outputTokenLimit", e.maxOutputTokens ?: 8_192)
                put("supportedGenerationMethods", JSONArray().apply {
                    put("generateContent"); put("streamGenerateContent")
                })
            })
        }
        return JSONObject().put("models", models).toString()
    }

    private fun ollamaTags(): String {
        val models = JSONArray()
        engine.catalogue().forEach { e ->
            models.put(JSONObject().apply {
                put("name", e.modelId)
                put("model", e.modelId)
                put("modified_at", java.time.Instant.now().toString())
                put("size", 0L)
                put("digest", "")
                put("details", JSONObject().apply {
                    put("parent_model", e.instanceLabel)
                    put("family", e.provider.lowercase().ifBlank { "minis" })
                    put("parameter_size", "")
                    put("quantization_level", "minis")
                })
            })
        }
        return JSONObject().put("models", models).toString()
    }

    /** Anthropic's token counter is a dry-run: no model call, just an estimate. */
    private fun countTokens(out: java.io.OutputStream, req: GatewayChatRequest): Boolean {
        var chars = req.systemPrompt?.length ?: 0
        for (m in req.messages) {
            chars += m.text.length
            for (c in m.toolCalls) chars += c.argsJson.length
        }
        writeJson(out, 200, JSONObject().put("input_tokens", (chars / 4).coerceAtLeast(1)).toString())
        return false
    }

    private fun notFound(out: java.io.OutputStream, path: String): Boolean {
        GatewayHttp.writeJsonError(
            out, GatewayException(404, "not_found_error", "Unknown gateway route: $path", "route_not_found"),
        )
        return false
    }

    private fun writeJson(out: java.io.OutputStream, status: Int, json: String) {
        GatewayHttp.writeResponse(out, status, json.toByteArray(Charsets.UTF_8))
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) {
        "0.0.0"
    }
}
