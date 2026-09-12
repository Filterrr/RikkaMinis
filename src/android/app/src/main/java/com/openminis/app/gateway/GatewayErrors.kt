package com.openminis.app.gateway

import org.json.JSONArray
import org.json.JSONObject


/**
 * [T-local-llm-gateway] Error bodies in each dialect's shape.
 *
 * Clients branch on these: openai-python raises `AuthenticationError` on 401,
 * `RateLimitError` on 429, `NotFoundError` on 404 and reads `error.code`;
 * Anthropic's SDK reads `error.type`. A flat `{"error": "..."}` string — the
 * shape an ad-hoc server usually returns — makes both SDKs fall through to a
 * generic `APIError` with no message, so the per-dialect envelope matters.
 */
internal object GatewayErrors {

    fun body(ex: GatewayException, requestId: String? = null): String = body(ex, requestId, null)

    /**
     * Ollama's error contract is a bare `{"error": "..."}` (no nesting), while
     * OpenAI and Anthropic both read `error.{message,type}` — hand-rolled
     * clients key off the latter, and the Ollama CLI off the former.
     */
    fun body(ex: GatewayException, requestId: String?, protocol: GatewayProtocol?): String {
        if (protocol == GatewayProtocol.ollama) {
            return JSONObject().put("error", ex.message).toString()
        }
        val message = JSONObject().apply {
            put("type", ex.type)
            put("message", ex.message.orEmpty())
            if (ex.code != null) put("code", ex.code)
            if (requestId != null) put("request_id", requestId)
        }
        return JSONObject().put("error", message).toString()
    }

    /**
     * Map an exception thrown out of [com.openminis.app.sandbox.offload.ProviderExecutionGateway]
     * onto a client-visible status.
     *
     * This is not cosmetic: openai-python branches its whole retry policy on the
     * status (401 → AuthenticationError, no retry; 429 → RateLimitError, retry
     * with backoff; 5xx → APIConnectionError). Collapsing an upstream 401 into a
     * generic 502 makes a client hammer a key that will never work.
     */
    fun fromThrowable(t: Throwable): GatewayException = when (t) {
        is com.openminis.app.data.model.LLMError.InvalidApiKey ->
            GatewayException(401, "authentication_error", t.message ?: "invalid API key", "invalid_api_key")
        is com.openminis.app.data.model.LLMError.RateLimited ->
            GatewayException(429, "rate_limit_error", t.message ?: "rate limited", "rate_limit_exceeded")
        is com.openminis.app.data.model.LLMError.TransientError ->
            GatewayException(503, "api_error", t.message ?: "temporarily unavailable", "upstream_unavailable")
        is com.openminis.app.data.model.LLMError.NetworkError ->
            GatewayException(502, "api_error", t.message ?: "upstream connection failed", "upstream_connection_error")
        is com.openminis.app.data.model.LLMError.DecodingError ->
            GatewayException(502, "api_error", t.message ?: "unexpected upstream response", "upstream_bad_response")
        is com.openminis.app.data.model.LLMError.Cancelled ->
            GatewayException(499, "cancelled", t.message ?: "request cancelled", "cancelled")
        is com.openminis.app.data.model.LLMError ->
            GatewayException(502, "api_error", t.message ?: "provider error", "upstream_error")
        is com.openminis.app.sandbox.offload.ModelExecutionStreamException ->
            // hadChunks==false means nothing was delivered, so a client may retry;
            // a died-worker case is a gateway-side fault, not the caller's.
            if (t.hadChunks) GatewayException(502, "api_error", t.message ?: "stream aborted", "stream_aborted")
            else GatewayException(503, "api_error", t.message ?: "model worker unavailable", "worker_unavailable")
        else -> GatewayException(502, "api_error", t.message ?: "gateway generation failed", "upstream_error")
    }

    /** Map a worker/provider failure code onto (HTTP status, dialect error type). */
    fun fromWorkerCode(code: String, message: String): GatewayException {
        val normalized = code.lowercase()
        return when {
            normalized.contains("api_key") || normalized.contains("auth") ||
                normalized.contains("credential") || normalized.contains("token") ->
                GatewayException(401, "authentication_error", message, "invalid_api_key")
            normalized.contains("rate") || normalized.contains("429") ||
                normalized.contains("quota") ->
                GatewayException(429, "rate_limit_error", message, "rate_limit_exceeded")
            normalized.contains("not_found") || normalized.contains("model_not_found") ->
                GatewayException(404, "not_found_error", message, "model_not_found")
            normalized.contains("timeout") || normalized.contains("unavailable") ||
                normalized.contains("dispatch") ->
                GatewayException(504, "api_error", message, "upstream_timeout")
            normalized.contains("unsupported") || normalized.contains("invalid") ->
                GatewayException(400, "invalid_request_error", message, "invalid_request")
            else -> GatewayException(502, "api_error", message, "upstream_error")
        }
    }

    /** Translate a canonical stop reason into [protocol]'s spelling. */
    fun stopReason(canonical: String, protocol: GatewayProtocol): String = when (protocol) {
        GatewayProtocol.openai, GatewayProtocol.openaiCompletions -> when (canonical) {
            "tool_use" -> "tool_calls"
            "max_tokens" -> "length"
            else -> "stop"
        }
        GatewayProtocol.openaiResponses -> when (canonical) {
            "tool_use" -> "requires_action"
            "max_tokens" -> "incomplete"
            else -> "completed"
        }
        GatewayProtocol.anthropic -> when (canonical) {
            "max_tokens" -> "max_tokens"
            "tool_use" -> "tool_use"
            "stop" -> "stop_sequence"
            else -> "end_turn"
        }
        GatewayProtocol.gemini -> when (canonical) {
            "max_tokens" -> "MAX_TOKENS"
            "tool_use" -> "STOP"      // Gemini reports function calls with finishReason STOP
            "stop" -> "STOP"
            else -> "STOP"
        }
        GatewayProtocol.ollama -> canonical
    }

    /** Inverse: a provider's native finish reason → canonical. */
    fun canonicalStop(raw: String?, hasToolCalls: Boolean): String {
        if (hasToolCalls) return "tool_use"
        return when (raw?.lowercase().orEmpty()) {
            "length", "max_tokens", "max_output_tokens", "incomplete" -> "max_tokens"
            "tool_calls", "tool_use", "function_call" -> "tool_use"
            // "" and null mean the stream hit EOF without a terminal event: the
            // turn ended, it did not hit a wall — reporting max_tokens there
            // would make callers re-send a "continue" prefill on a finished reply.
            "stop", "stop_sequence", "", "null" -> "stop"
            // "STOP" is Gemini's spelling, "end_turn" Anthropic's.
            "content_filter", "cancelled" -> "end_turn"
            else -> "end_turn"
        }
    }
}

/**
 * JSON helpers shared by every codec. org.json is already the app's parser of
 * choice (no Gson/Moshi in the dependency set), and these wrappers exist so the
 * codecs stay readable: they never throw on a missing/mistyped field.
 */
internal object GatewayJson {

    fun obj(raw: ByteArray): JSONObject? {
        if (raw.isEmpty()) return null
        return try {
            val text = String(raw, Charsets.UTF_8).trim()
            if (!text.startsWith("{")) null else JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    fun str(o: JSONObject, key: String, default: String = ""): String =
        o.optString(key, default)

    /** A content field that may be a string, an array of parts, or absent. */
    fun textOf(o: JSONObject, key: String): String {
        val v = o.opt(key) ?: return ""
        return when (v) {
            is String -> v
            is JSONArray -> partsText(v)
            is JSONObject -> v.optString("text", "")
            else -> v.toString()
        }
    }

    /** Flatten an OpenAI/Anthropic/Gemini content-part array into plain text. */
    fun partsText(arr: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val p = arr.opt(i)
            when (p) {
                is String -> sb.append(p)
                is JSONObject -> {
                    // OpenAI: {type:"text", text:".."} · Anthropic: {type:"text", text:".."}
                    // Gemini parts use inline_data/text and are handled by their codec.
                    val t = p.optString("text", p.optString("content", ""))
                    if (t.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(t)
                    }
                }
                else -> Unit
            }
        }
        return sb.toString()
    }

    fun arr(o: JSONObject, key: String): JSONArray = o.optJSONArray(key) ?: JSONArray()

    fun dbl(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    fun int(o: JSONObject, key: String, default: Int): Int {
        if (!o.has(key) || o.isNull(key)) return default
        return when (val v = o.opt(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    fun bool(o: JSONObject, key: String, default: Boolean = false): Boolean =
        if (!o.has(key) || o.isNull(key)) default else o.optBoolean(key, default)

    fun id(prefix: String): String = "$prefix-${(1e15 * Math.random()).toLong().toString(36)}"
}
