package com.openminis.app.gateway

import org.json.JSONArray
import org.json.JSONObject


/**
 * [T-local-llm-gateway] Inbound request decoding — five client dialects →
 * one [GatewayChatRequest].
 *
 * Rules every dialect decoder follows, learned from the wire formats rather
 * than invented:
 *  - a `system`/developer turn never becomes an LLMMessage with role USER;
 *    it is hoisted into [GatewayChatRequest.systemPrompt] (joined with
 *    newlines when a client sends several), because the provider layer takes
 *    systemPrompt separately and Anthropic-style cache breakpoints depend on
 *    that split;
 *  - assistant `tool_calls` and `tool` results keep their ids verbatim so
 *    the pairing sanitizer upstream can still match them;
 *  - multimodal parts that carry no bytes (pure-text parts, `file` refs) are
 *    flattened to text rather than dropped;
 *  - anything the app cannot honour (`n`, `seed`, `logprobs`, `response_format`)
 *    is ignored silently — a local gateway that hard-errors on unsupported
 *    knobs is unusable with real SDK clients, which send defaults for all of them.
 */
internal object GatewayRequestCodec {

    /** Anthropic `max_tokens` is mandatory; others default to a 4096 token reply. */
    private const val DEFAULT_MAX_TOKENS = 4096
    private const val MAX_TOKENS_CEILING = 128_000

    // ── OpenAI Chat Completions ─────────────────────────────────────────

    fun parseOpenAIChat(body: JSONObject): GatewayChatRequest {
        val messages = mutableListOf<GatewayMessage>()
        val systemParts = mutableListOf<String>()
        val arr = GatewayJson.arr(body, "messages")
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", "user").lowercase()
            val content = GatewayJson.textOf(m, "content")
            val images = mutableListOf<GatewayImage>()
            extractOpenAIImages(contentPartsOf(m), images)
            when (role) {
                "system", "developer" -> if (content.isNotBlank()) systemParts += content
                "tool", "function" -> messages += GatewayMessage(
                    role = "tool",
                    text = content,
                    toolCallId = m.optString("tool_call_id", m.optString("name", "")).ifBlank { null },
                    toolName = m.optString("name").ifBlank { null },
                )
                else -> {
                    val calls = mutableListOf<GatewayToolCall>()
                    val toolCalls = GatewayJson.arr(m, "tool_calls")
                    for (t in 0 until toolCalls.length()) {
                        val tc = toolCalls.optJSONObject(t) ?: continue
                        val fn = tc.optJSONObject("function") ?: tc
                        calls += GatewayToolCall(
                            id = tc.optString("id", GatewayJson.id("call")),
                            name = fn.optString("name", ""),
                            argsJson = fn.optString("arguments", "{}").ifBlank { "{}" },
                        )
                    }
                    messages += GatewayMessage(
                        role = if (role == "assistant") "assistant" else "user",
                        text = content,
                        images = images,
                        toolCalls = calls,
                        reasoning = m.optString("reasoning_content", m.optString("reasoning")).ifBlank { null },
                    )
                }
            }
        }

        val tools = mutableListOf<GatewayTool>()
        for (t in 0 until GatewayJson.arr(body, "tools").length()) {
            val item = GatewayJson.arr(body, "tools").optJSONObject(t) ?: continue
            val fn = item.optJSONObject("function") ?: item
            tools += GatewayTool(
                name = fn.optString("name", ""),
                description = fn.optString("description", ""),
                schemaJson = (fn.optJSONObject("parameters") ?: JSONObject()).toString(),
            )
        }
        val toolChoice = body.opt("tool_choice")?.let {
            if (it is JSONObject) "auto" else it.toString()
        }
        val stream = GatewayJson.bool(body, "stream")
        return GatewayChatRequest(
            model = body.optString("model", "minis").ifBlank { "minis" },
            messages = messages,
            systemPrompt = systemParts.joinToString("\n\n").ifBlank { null },
            maxTokens = GatewayJson.int(body, "max_completion_tokens",
                GatewayJson.int(body, "max_tokens", DEFAULT_MAX_TOKENS)).coerceIn(1, MAX_TOKENS_CEILING),
            temperature = GatewayJson.dbl(body, "temperature"),
            topP = GatewayJson.dbl(body, "top_p"),
            tools = tools,
            toolChoice = toolChoice,
            stream = stream,
            stop = stringOrArray(body.opt("stop")),
            thinkingRequested = thinkingEnabled(body.optJSONObject("thinking")) ||
                thinkingEnabled(body.optJSONObject("reasoning")),
            requestId = GatewayJson.id("chatcmpl"),
            protocol = GatewayProtocol.openai,
        )
    }

    /** OpenAI message content may be a string or a parts array; return the array form. */
    private fun contentPartsOf(m: JSONObject): JSONArray {
        val out = JSONArray()
        val v = m.opt("content")
        if (v is JSONArray) for (i in 0 until v.length()) {
            val part = v.optJSONObject(i) ?: continue
            if (part.optString("type") == "image_url") out.put(part)
        }
        return out
    }

    private fun extractOpenAIImages(parts: JSONArray, into: MutableList<GatewayImage>) {
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            when (p.optString("type")) {
                "image_url" -> {
                    val url = (p.opt("image_url") as? JSONObject)?.optString("url")
                        ?: p.optString("url")
                    parseDataUrl(url)?.let { into += it }
                }
                "image" -> {
                    // {type:"image", source:{type:"base64", media_type, data}}
                    val src = p.optJSONObject("source") ?: continue
                    if (src.optString("data").isNotBlank()) {
                        into += GatewayImage(src.getString("data"), src.optString("media_type", "image/png"))
                    }
                }
                "input_audio" -> { /* audio in is provider-gated downstream; keep text path */ }
                else -> Unit
            }
        }
    }

    fun parseDataUrl(url: String?): GatewayImage? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        if (!u.startsWith("data:")) return null // remote URLs are not fetched by a local gateway
        val comma = u.indexOf(',')
        if (comma < 0) return null
        val meta = u.substring(5, comma)
        val data = u.substring(comma + 1)
        val mime = meta.substringBefore(';').ifBlank { "image/png" }
        return GatewayImage(data, mime)
    }

    // ── Anthropic Messages ──────────────────────────────────────────────

    fun parseAnthropic(body: JSONObject): GatewayChatRequest {
        val messages = mutableListOf<GatewayMessage>()
        val systemParts = mutableListOf<String>()
        body.opt("system")?.let { s ->
            when (s) {
                is String -> if (s.isNotBlank()) systemParts += s
                is JSONArray -> for (i in 0 until s.length()) {
                    val t = s.optJSONObject(i)?.optString("text").orEmpty()
                    if (t.isNotBlank()) systemParts += t
                }
                else -> Unit
            }
        }
        val arr = GatewayJson.arr(body, "messages")
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", "user").lowercase()
            val content = m.opt("content")
            val text = StringBuilder()
            val images = mutableListOf<GatewayImage>()
            val calls = mutableListOf<GatewayToolCall>()
            val toolResults = mutableListOf<Pair<String, String>>()
            var isError = false
            fun handleBlock(b: JSONObject) {
                when (b.optString("type")) {
                    "text" -> text.append(b.optString("text", ""))
                    "image" -> {
                        val src = b.optJSONObject("source") ?: return
                        if (src.optString("type") == "base64" && src.optString("data").isNotBlank()) {
                            images += GatewayImage(src.getString("data"), src.optString("media_type", "image/png"))
                        }
                    }
                    "tool_use" -> calls += GatewayToolCall(
                        id = b.optString("id", GatewayJson.id("toolu")),
                        name = b.optString("name", ""),
                        argsJson = (b.optJSONObject("input") ?: JSONObject()).toString(),
                    )
                    "tool_result" -> {
                        toolResults += b.optString("tool_use_id", "") to blockText(b.opt("content"))
                        isError = isError || b.optBoolean("is_error", false)
                    }
                    "thinking" -> { /* echoed on the next turn; not re-sent to non-Anthropic backends */ }
                    else -> Unit
                }
            }
            when (content) {
                is String -> text.append(content)
                is JSONArray -> for (j in 0 until content.length()) {
                    content.optJSONObject(j)?.let(::handleBlock)
                }
                else -> Unit
            }
            if (role == "assistant" && calls.isEmpty() && toolResults.isEmpty() &&
                text.isBlank() && images.isEmpty()
            ) continue
            // Anthropic packs several tool_results into one user turn; flatten to
            // individual tool messages — the provider layer's sanitizer expects
            // one result per message id.
            if (toolResults.isNotEmpty()) {
                for ((id, rtext) in toolResults) {
                    messages += GatewayMessage(
                        role = "tool",
                        text = rtext,
                        toolCallId = id.ifBlank { null },
                        toolIsError = isError,
                    )
                }
                if (text.isNotBlank()) {
                    messages += GatewayMessage(role = "user", text = text.toString())
                }
                continue
            }
            messages += GatewayMessage(
                role = if (role == "assistant") "assistant" else "user",
                text = text.toString(),
                images = images,
                toolCalls = calls,
            )
        }

        val tools = mutableListOf<GatewayTool>()
        for (t in 0 until GatewayJson.arr(body, "tools").length()) {
            val item = GatewayJson.arr(body, "tools").optJSONObject(t) ?: continue
            tools += GatewayTool(
                name = item.optString("name", ""),
                description = item.optString("description", ""),
                schemaJson = (item.optJSONObject("input_schema") ?: JSONObject()).toString(),
            )
        }
        return GatewayChatRequest(
            model = body.optString("model", "minis").ifBlank { "minis" },
            messages = messages,
            systemPrompt = systemParts.joinToString("\n\n").ifBlank { null },
            maxTokens = GatewayJson.int(body, "max_tokens", DEFAULT_MAX_TOKENS).coerceIn(1, MAX_TOKENS_CEILING),
            temperature = GatewayJson.dbl(body, "temperature"),
            topP = GatewayJson.dbl(body, "top_p"),
            tools = tools,
            toolChoice = body.optJSONObject("tool_choice")?.optString("type")?.let {
                when (it) { "any" -> "required"; "tool" -> "auto"; else -> "auto" }
            },
            stream = GatewayJson.bool(body, "stream"),
            stop = stringOrArray(body.opt("stop_sequences")),
            thinkingRequested = thinkingEnabled(body.optJSONObject("thinking")),
            requestId = GatewayJson.id("msg"),
            protocol = GatewayProtocol.anthropic,
        )
    }

    private fun blockText(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until v.length()) {
                val b = v.optJSONObject(i) ?: continue
                val t = b.optString("text")
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(t)
                }
            }
            sb.toString()
        }
        is JSONObject -> v.optString("text", v.toString())
        else -> v.toString()
    }

    // ── Gemini generateContent ──────────────────────────────────────────

    /**
     * `POST /v1beta/models/{model}:generateContent[?alt=sse]`.
     * Model comes from the path, not the body — the caller hands it over.
     */
    fun parseGemini(body: JSONObject, pathModel: String?, stream: Boolean): GatewayChatRequest {
        val messages = mutableListOf<GatewayMessage>()
        val systemParts = mutableListOf<String>()
        (body.opt("systemInstruction") as? JSONObject)?.let { si ->
            val t = geminiPartsText(GatewayJson.arr(si, "parts"))
            if (t.isNotBlank()) systemParts += t
        }
        for (i in 0 until GatewayJson.arr(body, "contents").length()) {
            val c = GatewayJson.arr(body, "contents").optJSONObject(i) ?: continue
            val role = c.optString("role", "user").lowercase()
            val parts = GatewayJson.arr(c, "parts")
            val text = geminiPartsText(parts)
            val images = mutableListOf<GatewayImage>()
            val calls = mutableListOf<GatewayToolCall>()
            val responses = mutableListOf<Pair<String, JSONObject>>()
            for (j in 0 until parts.length()) {
                val p = parts.optJSONObject(j) ?: continue
                p.optJSONObject("inlineData")?.let { inline ->
                    val mime = inline.optString("mimeType", "image/png")
                    val data = inline.optString("data")
                    if (data.isNotBlank() && mime.startsWith("image/")) {
                        images += GatewayImage(data, mime)
                    }
                }
                p.optJSONObject("inline_data")?.let { inline ->
                    val mime = inline.optString("mime_type", "image/png")
                    val data = inline.optString("data")
                    if (data.isNotBlank() && mime.startsWith("image/")) {
                        images += GatewayImage(data, mime)
                    }
                }
                p.optJSONObject("functionCall")?.let { fc ->
                    calls += GatewayToolCall(
                        id = fc.optString("id", "call_${fc.optString("name", "fn")}"),
                        name = fc.optString("name", ""),
                        argsJson = (fc.optJSONObject("args") ?: JSONObject()).toString(),
                    )
                }
                p.optJSONObject("functionResponse")?.let { fr ->
                    responses += fr.optString("name", "") to (fr.optJSONObject("response") ?: JSONObject())
                }
            }
            if (responses.isNotEmpty()) {
                for ((name, payload) in responses) {
                    messages += GatewayMessage(
                        role = "tool",
                        text = payloadToString(payload),
                        toolName = name.ifBlank { null },
                        toolCallId = "call_$name",
                    )
                }
                continue
            }
            messages += GatewayMessage(
                role = if (role == "model") "assistant" else "user",
                text = text,
                images = images,
                toolCalls = calls,
            )
        }
        val tools = mutableListOf<GatewayTool>()
        for (t in 0 until GatewayJson.arr(body, "tools").length()) {
            val group = GatewayJson.arr(body, "tools").optJSONObject(t) ?: continue
            val decls = GatewayJson.arr(group, "functionDeclarations")
            for (d in 0 until decls.length()) {
                val fd = decls.optJSONObject(d) ?: continue
                tools += GatewayTool(
                    name = fd.optString("name", ""),
                    description = fd.optString("description", ""),
                    schemaJson = (fd.optJSONObject("parameters") ?: JSONObject()).toString(),
                )
            }
        }
        val gen = body.optJSONObject("generationConfig")
        val thinking = body.optJSONObject("thinkingConfig") ?: gen?.optJSONObject("thinkingConfig")
        return GatewayChatRequest(
            model = pathModel?.takeIf { it.isNotBlank() } ?: "minis",
            messages = messages,
            systemPrompt = systemParts.joinToString("\n\n").ifBlank { null },
            maxTokens = GatewayJson.int(gen ?: JSONObject(), "maxOutputTokens", DEFAULT_MAX_TOKENS)
                .coerceIn(1, MAX_TOKENS_CEILING),
            temperature = gen?.let { GatewayJson.dbl(it, "temperature") },
            topP = gen?.let { GatewayJson.dbl(it, "topP") },
            tools = tools,
            toolChoice = body.optJSONObject("toolConfig")?.optString("functionCallingConfig")
                ?.let { "auto" },
            stream = stream,
            stop = GatewayJson.arr(gen ?: JSONObject(), "stopSequences").let { sa ->
                (0 until sa.length()).map { sa.getString(it) }
            },
            thinkingRequested = thinking?.optBoolean("includeThoughts", false) ?: false,
            requestId = GatewayJson.id("gemini"),
            protocol = GatewayProtocol.gemini,
        )
    }

    private fun geminiPartsText(parts: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val t = parts.optJSONObject(i)?.optString("text").orEmpty()
            if (t.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(t)
            }
        }
        return sb.toString()
    }

    private fun payloadToString(o: JSONObject): String {
        if (o.length() == 1 && o.has("result")) return o.opt("result").toString()
        return o.toString()
    }

    // ── Ollama /api/chat, /api/generate ─────────────────────────────────

    fun parseOllamaChat(body: JSONObject): GatewayChatRequest {
        val messages = mutableListOf<GatewayMessage>()
        val systemParts = mutableListOf<String>()
        for (i in 0 until GatewayJson.arr(body, "messages").length()) {
            val m = GatewayJson.arr(body, "messages").optJSONObject(i) ?: continue
            val role = m.optString("role", "user").lowercase()
            val images = mutableListOf<GatewayImage>()
            GatewayJson.arr(m, "images").let { arr ->
                for (j in 0 until arr.length()) {
                    val data = arr.optString(j)
                    if (data.isNotBlank()) images += GatewayImage(data, "image/png")
                }
            }
            val calls = mutableListOf<GatewayToolCall>()
            GatewayJson.arr(m, "tool_calls").let { arr ->
                for (j in 0 until arr.length()) {
                    val tc = arr.optJSONObject(j) ?: continue
                    val fn = tc.optJSONObject("function") ?: continue
                    calls += GatewayToolCall(
                        id = "call_${messages.size}_$j",
                        name = fn.optString("name", ""),
                        argsJson = (fn.opt("arguments") ?: JSONObject()).toString(),
                    )
                }
            }
            if (role == "tool") {
                messages += GatewayMessage(
                    role = "tool",
                    text = m.optString("content", ""),
                    toolName = m.optString("tool_name").ifBlank { null },
                )
                continue
            }
            when (role) {
                "system" -> {
                    val t = m.optString("content", "")
                    if (t.isNotBlank()) systemParts += t
                }
                else -> messages += GatewayMessage(
                    role = if (role == "assistant") "assistant" else "user",
                    text = m.optString("content", ""),
                    images = images,
                    toolCalls = calls,
                )
            }
        }
        val tools = mutableListOf<GatewayTool>()
        for (t in 0 until GatewayJson.arr(body, "tools").length()) {
            val item = GatewayJson.arr(body, "tools").optJSONObject(t) ?: continue
            val fn = item.optJSONObject("function") ?: continue
            tools += GatewayTool(
                name = fn.optString("name", ""),
                description = fn.optString("description", ""),
                schemaJson = (fn.optJSONObject("parameters") ?: JSONObject()).toString(),
            )
        }
        val options = body.optJSONObject("options") ?: JSONObject()
        return GatewayChatRequest(
            model = body.optString("model", "minis").ifBlank { "minis" },
            messages = messages,
            systemPrompt = systemParts.joinToString("\n\n").ifBlank { null },
            maxTokens = GatewayJson.int(options, "num_predict", DEFAULT_MAX_TOKENS)
                .let { if (it <= 0) DEFAULT_MAX_TOKENS else it }.coerceIn(1, MAX_TOKENS_CEILING),
            temperature = GatewayJson.dbl(options, "temperature"),
            topP = GatewayJson.dbl(options, "top_p"),
            tools = tools,
            toolChoice = null,
            stream = body.optBoolean("stream", true),
            stop = GatewayJson.arr(options, "stop").let { sa -> (0 until sa.length()).map { sa.getString(it) } },
            thinkingRequested = body.optString("think").let { it == "true" || it == "enabled" } ||
                GatewayJson.bool(body, "thinking"),
            requestId = GatewayJson.id("ollama"),
            protocol = GatewayProtocol.ollama,
        )
    }

    /** `/api/generate` is a single-prompt completion API with optional history. */
    fun parseOllamaGenerate(body: JSONObject): GatewayChatRequest {
        val prompt = body.optString("prompt", "")
        val messages = mutableListOf<GatewayMessage>()
        GatewayJson.arr(body, "messages").let { hist ->
            for (i in 0 until hist.length()) {
                val m = hist.optJSONObject(i) ?: continue
                messages += GatewayMessage(
                    role = if (m.optString("role", "user") == "assistant") "assistant" else "user",
                    text = m.optString("content", ""),
                )
            }
        }
        messages += GatewayMessage(role = "user", text = prompt)
        val systemParts = mutableListOf<String>()
        body.optString("system").takeIf { it.isNotBlank() }?.let { systemParts += it }
        val options = body.optJSONObject("options") ?: JSONObject()
        return GatewayChatRequest(
            model = body.optString("model", "minis").ifBlank { "minis" },
            messages = messages,
            systemPrompt = systemParts.joinToString("\n\n").ifBlank { null },
            maxTokens = GatewayJson.int(options, "num_predict", DEFAULT_MAX_TOKENS)
                .let { if (it <= 0) DEFAULT_MAX_TOKENS else it }.coerceIn(1, MAX_TOKENS_CEILING),
            temperature = GatewayJson.dbl(options, "temperature"),
            topP = GatewayJson.dbl(options, "top_p"),
            tools = emptyList(),
            toolChoice = null,
            stream = body.optBoolean("stream", true),
            stop = emptyList(),
            thinkingRequested = body.optString("think").let { it == "true" || it == "enabled" },
            requestId = GatewayJson.id("ollama"),
            protocol = GatewayProtocol.ollama,
            isGenerateApi = true,
        )
    }

    // ── OpenAI Responses API + legacy completions ───────────────────────

    /**
     * `/v1/responses`. Input is either a plain string or an item array whose
     * entries carry `type:"message"` + role + content parts, plus
     * `function_call` / `function_call_output` items for tool traffic.
     */
    fun parseOpenAIResponses(body: JSONObject): GatewayChatRequest {
        val messages = mutableListOf<GatewayMessage>()
        val systemParts = mutableListOf<String>()
        body.optString("instructions").takeIf { it.isNotBlank() }?.let { systemParts += it }
        when (val input = body.opt("input")) {
            is String -> if (input.isNotBlank()) messages += GatewayMessage(role = "user", text = input)
            is JSONArray -> for (i in 0 until input.length()) {
                val item = input.optJSONObject(i) ?: continue
                when (item.optString("type", "message")) {
                    "function_call" -> messages += GatewayMessage(
                        role = "assistant",
                        text = "",
                        toolCalls = listOf(GatewayToolCall(
                            id = item.optString("call_id", GatewayJson.id("call")),
                            name = item.optString("name", ""),
                            argsJson = item.optString("arguments", "{}").ifBlank { "{}" },
                        )),
                    )
                    "function_call_output" -> messages += GatewayMessage(
                        role = "tool",
                        text = blockText(item.opt("output")),
                        toolCallId = item.optString("call_id").ifBlank { null },
                    )
                    else -> {
                        val role = item.optString("role", "user").lowercase()
                        val content = item.opt("content")
                        val text = when (content) {
                            is String -> content
                            is JSONArray -> partsTextWithImages(content, null)
                            else -> ""
                        }
                        when (role) {
                            "system", "developer" -> if (text.isNotBlank()) systemParts += text
                            else -> messages += GatewayMessage(
                                role = if (role == "assistant") "assistant" else "user",
                                text = text,
                            )
                        }
                    }
                }
            }
            else -> Unit
        }
        val tools = mutableListOf<GatewayTool>()
        for (t in 0 until GatewayJson.arr(body, "tools").length()) {
            val item = GatewayJson.arr(body, "tools").optJSONObject(t) ?: continue
            if (item.optString("type") != "function") continue
            tools += GatewayTool(
                name = item.optString("name", ""),
                description = item.optString("description", ""),
                schemaJson = (item.optJSONObject("parameters") ?: JSONObject()).toString(),
            )
        }
        val maxTok = GatewayJson.int(body, "max_output_tokens", DEFAULT_MAX_TOKENS).coerceIn(1, MAX_TOKENS_CEILING)
        return GatewayChatRequest(
            model = body.optString("model", "minis").ifBlank { "minis" },
            messages = messages,
            systemPrompt = systemParts.joinToString("\n\n").ifBlank { null },
            maxTokens = maxTok,
            temperature = GatewayJson.dbl(body, "temperature"),
            topP = GatewayJson.dbl(body, "top_p"),
            tools = tools,
            toolChoice = body.opt("tool_choice")?.toString(),
            stream = GatewayJson.bool(body, "stream"),
            stop = stringOrArray(body.opt("stop")),
            thinkingRequested = thinkingEnabled(body.optJSONObject("reasoning")),
            requestId = GatewayJson.id("resp"),
            protocol = GatewayProtocol.openaiResponses,
        )
    }

    private fun partsTextWithImages(parts: JSONArray, sink: MutableList<GatewayImage>?): String {
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            when (p.optString("type")) {
                "input_text", "output_text", "text" -> sb.append(p.optString("text", ""))
                "input_image", "image_url" -> if (sink != null) extractOpenAIImages(parts, sink)
                else -> Unit
            }
        }
        return sb.toString()
    }

    /** `/v1/completions` — prompt is a string or string array, no chat roles. */
    fun parseOpenAICompletions(body: JSONObject): GatewayChatRequest {
        val prompt = when (val p = body.opt("prompt")) {
            is JSONArray -> (0 until p.length()).joinToString("\n") { p.optString(it) }
            else -> body.optString("prompt", "")
        }
        return GatewayChatRequest(
            model = body.optString("model", "minis").ifBlank { "minis" },
            messages = listOf(GatewayMessage(role = "user", text = prompt)),
            systemPrompt = null,
            maxTokens = GatewayJson.int(body, "max_tokens", DEFAULT_MAX_TOKENS).coerceIn(1, MAX_TOKENS_CEILING),
            temperature = GatewayJson.dbl(body, "temperature"),
            topP = GatewayJson.dbl(body, "top_p"),
            tools = emptyList(),
            toolChoice = null,
            stream = GatewayJson.bool(body, "stream"),
            stop = stringOrArray(body.opt("stop")),
            thinkingRequested = false,
            requestId = GatewayJson.id("cmpl"),
            protocol = GatewayProtocol.openaiCompletions,
        )
    }

    // ── shared bits ─────────────────────────────────────────────────────

    private fun partsText(arr: JSONArray): String = GatewayJson.partsText(arr)

    private fun stringOrArray(v: Any?): List<String> = when (v) {
        is String -> if (v.isBlank()) emptyList() else listOf(v)
        is JSONArray -> (0 until v.length()).map { v.optString(it) }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    /** OpenAI: {reasoning:{enabled:true}} · Anthropic: {thinking:{type:"enabled"}} · effort levels. */
    private fun thinkingEnabled(o: JSONObject?): Boolean {
        o ?: return false
        if (o.has("enabled") && o.optBoolean("enabled")) return true
        val type = o.optString("type")
        if (type == "enabled" || type == "adaptive") return true
        val effort = o.optString("effort")
        return effort.isNotBlank() && effort != "minimal"
    }
}
