package com.openminis.app.gateway

import org.json.JSONArray
import org.json.JSONObject


/**
 * [T-local-llm-gateway] Request-side token check.
 *
 * `current()` reads the persisted [GatewaySettings] rather than a captured
 * value, so flipping the token in Settings takes effect on the next request
 * without restarting the listener.
 *
 * Accepts the three spellings real clients use:
 *   Authorization: Bearer <token>   (OpenAI, Ollama `OLLAMA_API_KEY`, Gemini)
 *   x-api-key: <token>              (Anthropic SDKs)
 *   x-goog-api-key: <token> / ?key= (Google SDKs)
 */
internal class GatewayAuth(private val expectedToken: String) {

    fun check(req: GatewayHttp.Request): GatewayException? {
        if (expectedToken.isBlank()) return null
        val provided = req.header("authorization")?.removePrefix("Bearer ")?.trim()
            ?: req.header("x-api-key")?.trim()
            ?: req.header("x-goog-api-key")?.trim()
            ?: req.query["key"]?.trim()
        if (provided == expectedToken) return null
        return GatewayException(
            status = 401,
            type = "authentication_error",
            message = "Missing or invalid gateway token. Send it as Authorization: Bearer <token>.",
            code = "invalid_api_key",
        )
    }

    companion object {
        @Volatile private var cached: GatewayAuth? = null
        @Volatile private var cachedToken: String? = null

        fun current(): GatewayAuth {
            val token = GatewaySettings.currentToken()
            if (cached != null && cachedToken == token) return cached!!
            return GatewayAuth(token).also { cached = it; cachedToken = token }
        }
    }
}

/**
 * Per-dialect connection snippets for the Settings UI.
 *
 * These strings are the whole point of the feature — a user copy-pastes one
 * into their client — so each is rendered with the live host:port and, for
 * OpenAI-compatible SDKs, the `/v1` suffix those clients append themselves.
 */
internal object GatewayClientRecipes {

    fun render(host: String, port: Int, token: String, model: String): String {
        val base = "http://$host:$port"
        val auth = if (token.isBlank()) "" else "\n  \"api_key\": \"$token\","
        return JSONObject().apply {
            put("openai", JSONObject().apply {
                put("base_url", "$base/v1")
                put("model", model)
                put("snippet", "from openai import OpenAI\n" +
                    "client = OpenAI(base_url=\"$base/v1\", api_key=\"${token.ifBlank { "minis" }}\")\n" +
                    "r = client.chat.completions.create(model=\"$model\", messages=[{\"role\":\"user\",\"content\":\"hi\"}])\n" +
                    "print(r.choices[0].message.content)")
            })
            put("anthropic", JSONObject().apply {
                put("base_url", base)
                put("snippet", "from anthropic import Anthropic\n" +
                    "client = Anthropic(base_url=\"$base\", api_key=\"${token.ifBlank { "minis" }}\")\n" +
                    "m = client.messages.create(model=\"$model\", max_tokens=1024, messages=[{\"role\":\"user\",\"content\":\"hi\"}])\n" +
                    "print(m.content[0].text)")
            })
            put("gemini", JSONObject().apply {
                put("base_url", "$base/v1beta")
                put("snippet", "import google.generativeai as genai\n" +
                    "genai.configure(api_key=\"${token.ifBlank { "minis" }}\", transport=\"rest\")\n" +
                    "genai._BASE_URL_PATH = \"$base/v1beta\"\n" +
                    "print(genai.GenerativeModel('$model').generate_content('hi').text)")
            })
            put("ollama", JSONObject().apply {
                put("base_url", base)
                put("snippet", "OLLAMA_HOST=$base ollama run $model\n" +
                    "# or: curl $base/api/chat -d '{\"model\":\"$model\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'")
            })
            put("env", JSONObject().apply {
                put("OPENAI_BASE_URL", "$base/v1")
                put("OPENAI_API_KEY", token.ifBlank { "minis" })
                put("ANTHROPIC_BASE_URL", base)
                put("OLLAMA_HOST", base)
                put("GEMINI_BASE_URL", "$base/v1beta")
            })
            put("endpoints", JSONArray().apply {
                put("$base/health"); put("$base/v1/models"); put("$base/v1/chat/completions")
                put("$base/v1/responses"); put("$base/v1/messages"); put("$base/v1beta/models")
                put("$base/api/chat"); put("$base/api/tags")
            })
        }.toString(2).let { it + authHint(token) }
    }

    private fun authHint(token: String): String = if (token.isBlank()) {
        "\n# No token set: any client value is accepted. Set one before enabling LAN access."
    } else {
        "\n# Send the token as Authorization: Bearer $token (or x-api-key / x-goog-api-key)."
    }

    /** Plain-text one-liner list for the "copy config" button. */
    fun envBlock(host: String, port: Int, token: String): String {
        val base = "http://$host:$port"
        val key = token.ifBlank { "minis" }
        return buildString {
            appendLine("OPENAI_BASE_URL=$base/v1")
            appendLine("OPENAI_API_KEY=$key")
            appendLine("ANTHROPIC_BASE_URL=$base")
            appendLine("ANTHROPIC_API_KEY=$key")
            appendLine("OLLAMA_HOST=$base")
            appendLine("GEMINI_API_KEY=$key")
            appendLine("MINIS_GATEWAY_MODEL=${GatewaySettings.defaultModel.orEmpty()}")
        }
    }

}
