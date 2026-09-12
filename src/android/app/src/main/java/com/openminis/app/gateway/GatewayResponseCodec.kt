package com.openminis.app.gateway

import org.json.JSONArray
import org.json.JSONObject


/**
 * [T-local-llm-gateway] Non-streaming outbound encoding: a normalised
 * [GatewayResult] back into the dialect the client spoke.
 *
 * Shapes below are what the stock SDKs actually parse — openai-python's
 * `ChatCompletion`, `@anthropic-ai/sdk`'s `Message`, google-generativeai's
 * `GenerateContentResponse`, ollama-python's `ChatResponse`, and the OpenAI
 * Responses `Response` object. Field names are not negotiable: a wrong key
 * silently drops content on the client (e.g. `finish_reason` vs `stop_reason`),
 * so each is written the way its SDK's model declares it.
 */
internal object GatewayResponseCodec {

    /**
     * [req.isGenerateApi] is honoured on top of the protocol because Ollama's
     * `/api/generate` shares the dialect but answers in `response` instead of
     * `message` — a chat-shaped reply there parses as an empty string.
     */
    fun completion(req: GatewayChatRequest, r: GatewayResult): String = when {
        req.protocol == GatewayProtocol.ollama && req.isGenerateApi -> ollamaGenerate(req, r)
        else -> when (req.protocol) {
        GatewayProtocol.openai -> openaiChat(req, r)
        GatewayProtocol.openaiCompletions -> openaiTextCompletion(req, r)
        GatewayProtocol.anthropic -> anthropicMessage(req, r)
        GatewayProtocol.gemini -> gemini(req, r)
        GatewayProtocol.ollama -> ollamaChat(req, r)
        GatewayProtocol.openaiResponses -> openaiResponses(req, r)
        }
    }

    fun usageObject(r: GatewayResult, protocol: GatewayProtocol): JSONObject = when (protocol) {
        GatewayProtocol.anthropic -> JSONObject()
            .put("input_tokens", r.inputTokens)
            .put("output_tokens", r.outputTokens)
        GatewayProtocol.gemini -> JSONObject()
            .put("promptTokenCount", r.inputTokens)
            .put("candidatesTokenCount", r.outputTokens)
            .put("totalTokenCount", r.inputTokens + r.outputTokens)
        GatewayProtocol.ollama -> JSONObject()
            .put("prompt_eval_count", r.inputTokens)
            .put("eval_count", r.outputTokens)
        else -> JSONObject()
            .put("prompt_tokens", r.inputTokens)
            .put("completion_tokens", r.outputTokens)
            .put("total_tokens", r.inputTokens + r.outputTokens)
    }

    // ── OpenAI Chat Completions ─────────────────────────────────────────

    fun openaiChat(req: GatewayChatRequest, r: GatewayResult): String {
        val message = JSONObject().apply {
            put("role", "assistant")
            // A pure tool-call turn reports content:null, not "" — clients
            // branch on null to decide whether to show text at all.
            put(
                "content",
                if (r.text.isEmpty() && r.hasToolCalls) JSONObject.NULL else r.text,
            )
            if (r.reasoning.isNotEmpty()) put("reasoning_content", r.reasoning)
            if (r.hasToolCalls) {
                put("tool_calls", JSONArray().apply {
                    r.toolCalls.forEachIndexed { i, c ->
                        put(JSONObject().apply {
                            put("id", c.id.ifBlank { "call_$i" })
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", c.name)
                                // `arguments` is a JSON *string* — never an object.
                                put("arguments", sanitizeArgs(c.argsJson))
                            })
                        })
                    }
                })
            }
        }
        return JSONObject().apply {
            put("id", req.requestId)
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", r.model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", message)
                put("logprobs", JSONObject.NULL)
                put("finish_reason", GatewayErrors.stopReason(r.stopReason, GatewayProtocol.openai))
            }))
            put("usage", usageObject(r, GatewayProtocol.openai))
        }.toString()
    }

    /** `/v1/completions` legacy shape — text in `choices[0].text`, no message object. */
    private fun openaiTextCompletion(req: GatewayChatRequest, r: GatewayResult): String =
        JSONObject().apply {
            put("id", req.requestId)
            put("object", "text_completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", r.model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("text", r.text)
                put("finish_reason", GatewayErrors.stopReason(r.stopReason, GatewayProtocol.openaiCompletions))
            }))
            put("usage", usageObject(r, GatewayProtocol.openaiCompletions))
        }.toString()

    // ── Anthropic Messages ──────────────────────────────────────────────

    fun anthropicMessage(req: GatewayChatRequest, r: GatewayResult): String {
        val blocks = JSONArray()
        if (r.reasoning.isNotEmpty()) {
            blocks.put(JSONObject().apply {
                put("type", "thinking")
                put("thinking", r.reasoning)
                // A real Anthropic signature is an HMAC the client validates on
                // replay; we have no key, so ship an empty one — SDKs accept it
                // and the gateway never replays blocks back to Anthropic.
                put("signature", "")
            })
        }
        if (r.text.isNotEmpty()) {
            blocks.put(JSONObject().put("type", "text").put("text", r.text))
        }
        for (c in r.toolCalls) {
            blocks.put(JSONObject().apply {
                put("type", "tool_use")
                put("id", c.id.ifBlank { GatewayJson.id("toolu") })
                put("name", c.name)
                put("input", try { JSONObject(sanitizeArgs(c.argsJson)) } catch (_: Exception) { JSONObject() })
            })
        }
        if (blocks.length() == 0) blocks.put(JSONObject().put("type", "text").put("text", ""))
        return JSONObject().apply {
            put("id", req.requestId)
            put("type", "message")
            put("role", "assistant")
            put("content", blocks)
            put("model", r.model)
            put("stop_reason", GatewayErrors.stopReason(r.stopReason, GatewayProtocol.anthropic))
            put("stop_sequence", JSONObject.NULL)
            put("usage", usageObject(r, GatewayProtocol.anthropic))
        }.toString()
    }

    // ── Gemini ──────────────────────────────────────────────────────────

    fun gemini(req: GatewayChatRequest, r: GatewayResult): String {
        val parts = JSONArray()
        if (r.reasoning.isNotEmpty()) {
            parts.put(JSONObject().apply {
                put("text", r.reasoning)
                put("thought", true)
            })
        }
        if (r.text.isNotEmpty()) parts.put(JSONObject().put("text", r.text))
        for (c in r.toolCalls) {
            parts.put(JSONObject().apply {
                put("functionCall", JSONObject().apply {
                    put("name", c.name)
                    put("args", try { JSONObject(sanitizeArgs(c.argsJson)) } catch (_: Exception) { JSONObject() })
                })
            })
        }
        if (parts.length() == 0) parts.put(JSONObject().put("text", ""))
        val candidate = JSONObject().apply {
            put("content", JSONObject().put("role", "model").put("parts", parts))
            put("finishReason", GatewayErrors.stopReason(r.stopReason, GatewayProtocol.gemini))
            put("index", 0)
        }
        return JSONObject().apply {
            put("candidates", JSONArray().put(candidate))
            put("usageMetadata", usageObject(r, GatewayProtocol.gemini))
            put("modelVersion", r.model)
            put("responseId", req.requestId)
        }.toString()
    }

    // ── Ollama ──────────────────────────────────────────────────────────

    fun ollamaChat(req: GatewayChatRequest, r: GatewayResult): String {
        val message = JSONObject().apply {
            put("role", "assistant")
            put("content", r.text)
            if (r.hasToolCalls) {
                put("tool_calls", JSONArray().apply {
                    r.toolCalls.forEach { c ->
                        put(JSONObject().apply {
                            put("function", JSONObject().apply {
                                put("name", c.name)
                                // Ollama's `arguments` is an OBJECT, unlike OpenAI's string.
                                put(
                                    "arguments",
                                    try { JSONObject(sanitizeArgs(c.argsJson)) } catch (_: Exception) { JSONObject() },
                                )
                            })
                        })
                    }
                })
            }
        }
        return JSONObject().apply {
            put("model", r.model)
            put("created_at", ollamaTimestamp())
            put("message", message)
            // Ollama surfaces chain-of-thought on its own top-level `thinking`
            // field; dropping it here would silently swallow the reasoning
            // channel from thinking models (deepseek-r1, qwq).
            if (r.reasoning.isNotEmpty()) put("thinking", r.reasoning)
            put("done", true)
            put("done_reason", if (r.stopReason == "max_tokens") "length" else "stop")
            put("total_duration", 0L)
            put("load_duration", 0L)
            put("prompt_eval_count", r.inputTokens)
            put("prompt_eval_duration", 0L)
            put("eval_count", r.outputTokens)
            put("eval_duration", 0L)
        }.toString()
    }

    /** `/api/generate` answers in `response`, not `message`. */
    fun ollamaGenerate(req: GatewayChatRequest, r: GatewayResult): String = JSONObject().apply {
        put("model", r.model)
        put("created_at", ollamaTimestamp())
        put("response", r.text)
        put("thinking", r.reasoning)
        put("done", true)
        put("done_reason", if (r.stopReason == "max_tokens") "length" else "stop")
        put("prompt_eval_count", r.inputTokens)
        put("eval_count", r.outputTokens)
    }.toString()

    /** Ollama stamps RFC3339Nano UTC — a plain epoch is rejected by some clients. */
    private fun ollamaTimestamp(): String =
        java.time.Instant.now().toString().replace(Regex("\\.\\d+Z$"), ".000000000Z")

    // ── OpenAI Responses API ────────────────────────────────────────────

    fun openaiResponses(req: GatewayChatRequest, r: GatewayResult): String {
        val output = JSONArray()
        if (r.text.isNotEmpty() || !r.hasToolCalls) {
            output.put(JSONObject().apply {
                put("id", "msg_" + req.requestId.substringAfter('-').ifBlank { "0" })
                put("type", "message")
                put("role", "assistant")
                put("status", "completed")
                put(
                    "content",
                    JSONArray().put(
                        JSONObject().put("type", "output_text").put("text", r.text).put("annotations", JSONArray()),
                    ),
                )
            })
        }
        r.toolCalls.forEachIndexed { i, c ->
            output.put(JSONObject().apply {
                put("type", "function_call")
                put("call_id", c.id.ifBlank { "call_$i" })
                put("name", c.name)
                put("arguments", sanitizeArgs(c.argsJson))
                put("status", "completed")
            })
        }
        val status = GatewayErrors.stopReason(r.stopReason, GatewayProtocol.openaiResponses)
        return JSONObject().apply {
            put("id", req.requestId)
            put("object", "response")
            put("created_at", System.currentTimeMillis() / 1000)
            put("status", status)
            put("error", JSONObject.NULL)
            put("incomplete_details", JSONObject.NULL)
            put("instructions", JSONObject.NULL)
            put("model", r.model)
            put("output", output)
            put("output_text", r.text)
            put(
                "usage",
                JSONObject()
                    .put("input_tokens", r.inputTokens)
                    .put("output_tokens", r.outputTokens)
                    .put("total_tokens", r.inputTokens + r.outputTokens),
            )
        }.toString()
    }

    /** Models emit truncated/malformed tool JSON; never forward unparsable args. */
    fun sanitizeArgs(raw: String): String {
        val trimmed = raw.trim().ifEmpty { return "{}" }
        return try {
            JSONObject(trimmed).toString()
        } catch (_: Exception) {
            try {
                JSONArray(trimmed).toString()
            } catch (_: Exception) {
                "{}"
            }
        }
    }
}
