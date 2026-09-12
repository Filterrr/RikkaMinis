package com.openminis.app.gateway

/**
 * [T-local-llm-gateway] Shared value types for the local LLM gateway.
 *
 * The gateway turns the phone into an OpenAI / Anthropic / Gemini / Ollama
 * compatible HTTP endpoint whose "backend" is Minis' own configured provider
 * catalogue. Every request is translated into the app's provider-agnostic
 * message model, executed through [com.openminis.app.sandbox.offload.ProviderExecutionGateway]
 * (so the actual upstream call still happens in the `:modelservice` worker —
 * the gateway never touches a provider socket itself), and translated back
 * into the dialect the client spoke.
 */

/** Which wire dialect a request arrived in. Determines the response encoder. */
enum class GatewayProtocol {
    /** OpenAI Chat Completions (`/v1/chat/completions`, `/v1/models`). */
    openai,

    /** Anthropic Messages (`/v1/messages`, SSE `message_start`/`content_block_*`). */
    anthropic,

    /** Google Generative Language (`/v1beta/models/{m}:generateContent[?alt=sse]`). */
    gemini,

    /** Ollama native (`/api/chat`, `/api/generate`, `/api/tags`, `/api/version`). */
    ollama,

    /** OpenAI Responses API (`/v1/responses`) — SSE `response.output_text.delta`. */
    openaiResponses,

    /** OpenAI legacy text completions (`/v1/completions`). */
    openaiCompletions,
}

/** A chat completion request normalised out of ANY inbound dialect. */
data class GatewayChatRequest(
    /** Model id as the client asked for it (already de-namespaced). */
    val model: String,
    /** Conversation turns, provider-agnostic. System prompt is folded into [systemPrompt]. */
    val messages: List<GatewayMessage>,
    val systemPrompt: String?,
    val maxTokens: Int,
    val temperature: Double?,
    val topP: Double?,
    /** Raw JSON-Schema tool declarations (OpenAI function shape is the lingua franca here). */
    val tools: List<GatewayTool>,
    /** "auto" | "none" | "required" | a concrete tool name (OpenAI) / any (Anthropic). */
    val toolChoice: String?,
    val stream: Boolean,
    val stop: List<String>,
    /**
     * True when the client asked for the thinking/reasoning channel to be
     * surfaced (Anthropic `thinking`, Gemini `thinkingConfig`, Ollama `thinking`).
     */
    val thinkingRequested: Boolean,
    /** Client-supplied identifier echoed back in the response (`id` field prefix). */
    val requestId: String,
    /** Protocol the request was parsed from — the response uses the same one. */
    val protocol: GatewayProtocol,
    /**
     * True for Ollama's `/api/generate` (single-prompt completion API): the
     * answer lives in `response`, not `message`, and the SSE/NDJSON frames are
     * shaped differently. Same protocol enum, different body contract.
     */
    val isGenerateApi: Boolean = false,
)

/** One normalised conversation turn. */
data class GatewayMessage(
    val role: String,                  // "user" | "assistant" | "tool" | "system"
    val text: String,
    /** Base64 image payloads attached to this turn (`data:` URIs already unwrapped). */
    val images: List<GatewayImage> = emptyList(),
    /** Assistant turn emits these; tool turns answer them. */
    val toolCalls: List<GatewayToolCall> = emptyList(),
    /** Present on `tool`/`tool_result` turns. */
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolIsError: Boolean = false,
    /** Accumulated chain-of-thought to echo back on the next turn (reasoning_content parity). */
    val reasoning: String? = null,
)

data class GatewayImage(val base64: String, val mimeType: String)

data class GatewayToolCall(val id: String, val name: String, val argsJson: String)

data class GatewayTool(val name: String, val description: String, val schemaJson: String)

/** Normalised completion result before it is re-encoded into a dialect. */
data class GatewayResult(
    val text: String,
    val reasoning: String,
    val toolCalls: List<GatewayToolCall>,
    /** Canonical reason: "end_turn" | "tool_use" | "max_tokens" | "stop". */
    val stopReason: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val model: String,
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

/** Error surfaced to the client, carrying an HTTP status + provider error shape. */
class GatewayException(
    val status: Int,
    val type: String,
    message: String,
    val code: String? = null,
) : Exception(message)
